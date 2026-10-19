package com.jingyu233.bluetoothhook.data.bridge

import android.content.Context
import android.content.SharedPreferences
import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.utils.Logger
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 配置桥接器：在 UI 进程与注入进程（`com.android.bluetooth`）之间同步配置。
 *
 * 迁移说明（legacy → libxposed API 102）：
 *
 * 旧实现依赖 legacy 的 `MODE_WORLD_READABLE` + `XSharedPreferences` 扩展，
 * 让蓝牙进程直接读模块 App 私有目录里的 XML。API 102 起：
 * - libxposed 模块**禁止**调用 legacy API（框架会抛异常）；
 * - LSPosed 的 New XSharedPreferences 扩展自 v2.1.0 起已被移除；
 * - 官方给的正道是 **Remote Preferences**：配置存在框架侧数据库
 *   （`/data/adb/lspd/...`），两个进程通过 binder 读写，天然支持变更监听。
 *
 * 因此这里改成：
 * 1. UI 进程启动时通过 [XposedServiceHelper] 与框架建立连接，拿到 [XposedService]；
 * 2. 用 `service.getRemotePreferences("hook_config")` 得到 [SharedPreferences]，
 *    写入后调用 `apply()/commit()`，框架会把变更推送给已注入的蓝牙进程；
 * 3. 框架未连接（例如未启用模块）时降级为本地私有 SharedPreferences，
 *    仅保证 UI 不崩，此时蓝牙进程读不到配置 —— 这是预期行为，并在
 *    [getHookStatus] 里如实反映为 `Inactive`。
 */
class ConfigBridge(context: Context) {

    companion object {
        private val TAG = Logger.Tags.DATA_BRIDGE

        /** 远程偏好组名，必须与 ModuleEntry.PREF_GROUP 一致 */
        const val PREF_GROUP = "hook_config"

        /** 本地降级用的私有偏好文件名 */
        private const val LOCAL_PREF_NAME = "hook_config_local"

        // 键名（与 ModuleEntry.KEY_* 保持一致）
        private const val KEY_DEVICES = "devices"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_LAST_UPDATED = "last_updated"

        /** 框架连接状态：null 表示尚未连接或连接失败 */
        @Volatile
        private var service: XposedService? = null

        @Volatile
        private var listenerRegistered = false

        /** 是否已连上框架（供 UI 显示状态） */
        fun isFrameworkConnected(): Boolean = service != null
    }

    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    init {
        ensureServiceListener()
    }

    // ------------------------------------------------------------ 框架连接

    /**
     * 注册框架服务监听（只注册一次）。
     *
     * 框架会在模块 App 启动后主动把服务 binder 送进来；这里注册监听后
     * 可能立即触发 `onServiceBind`（binder 早于监听注册到达的情况）。
     */
    private fun ensureServiceListener() {
        if (listenerRegistered) return
        synchronized(Companion::class.java) {
            if (listenerRegistered) return
            listenerRegistered = true
            try {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(svc: XposedService) {
                        service = svc
                        Logger.App.i(
                            TAG,
                            "已连接 Xposed 框架: ${svc.frameworkName} ${svc.frameworkVersion} " +
                                    "(API ${svc.apiVersion})"
                        )
                    }

                    override fun onServiceDied(svc: XposedService) {
                        if (service === svc) service = null
                        Logger.App.w(TAG, "Xposed 框架服务已断开")
                    }
                })
                Logger.App.d(TAG, "XposedServiceHelper listener registered")
            } catch (t: Throwable) {
                Logger.App.e(TAG, "注册 XposedServiceHelper 监听失败", t)
            }
        }
    }

    /**
     * 取配置用的 [SharedPreferences]。
     *
     * 优先返回框架的 remote preferences；框架不可用时返回本地私有偏好，
     * 保证 UI 读写不抛异常（只是蓝牙进程读不到）。
     */
    private fun prefs(): SharedPreferences {
        val svc = service
        if (svc != null) {
            try {
                if (svc.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L) {
                    return svc.getRemotePreferences(PREF_GROUP)
                } else {
                    Logger.App.w(TAG, "框架不支持 remote preferences，降级为本地存储")
                }
            } catch (t: Throwable) {
                Logger.App.e(TAG, "getRemotePreferences 失败，降级为本地存储", t)
            }
        }
        return appContext.getSharedPreferences(LOCAL_PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 当前是否在用远程偏好（false = 本地降级，注入侧读不到） */
    fun isUsingRemotePreferences(): Boolean = service != null

    // ------------------------------------------------------------ 设备配置

    /**
     * 把 Room 里的设备列表同步给注入进程。
     *
     * 用 `commit()` 而不是 `apply()`：remote preferences 的 `apply()` 是异步
     * 投递到单线程 executor，紧接着就让用户去扫描可能读到旧值；这里数据量很小，
     * 同步写入的代价可以接受。
     */
    fun writeDeviceConfig(devices: List<VirtualDevice>) {
        try {
            val p = prefs()
            val devicesJson = json.encodeToString(devices)

            val ok = p.edit()
                .putString(KEY_DEVICES, devicesJson)
                .putBoolean(KEY_GLOBAL_ENABLED, getGlobalEnabled())
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()

            Logger.App.d(
                TAG,
                "Wrote ${devices.size} devices (commit=$ok, remote=${isUsingRemotePreferences()})"
            )
        } catch (t: Throwable) {
            Logger.App.e(TAG, "Failed to write device config", t)
        }
    }

    /** 设置全局开关 */
    fun setGlobalEnabled(enabled: Boolean) {
        try {
            val p = prefs()
            p.edit()
                .putBoolean(KEY_GLOBAL_ENABLED, enabled)
                .putLong(KEY_LAST_UPDATED, System.currentTimeMillis())
                .commit()
            Logger.App.d(TAG, "Set global_enabled = $enabled")
        } catch (t: Throwable) {
            Logger.App.e(TAG, "Failed to set global enabled state", t)
        }
    }

    /** 读取全局开关 */
    fun getGlobalEnabled(): Boolean = try {
        prefs().getBoolean(KEY_GLOBAL_ENABLED, true)
    } catch (t: Throwable) {
        Logger.App.e(TAG, "Failed to get global enabled state", t)
        true
    }

    /** 配置最后更新时间 */
    fun getLastUpdatedTime(): Long = try {
        prefs().getLong(KEY_LAST_UPDATED, 0L)
    } catch (t: Throwable) {
        Logger.App.e(TAG, "Failed to get last updated time", t)
        0L
    }

    // ------------------------------------------------------------ 状态

    fun setHookStatus(status: String) {
        try {
            appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("hook_status", status)
                .putLong("hook_status_updated", System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            Logger.App.e(TAG, "Failed to write hook status", t)
        }
    }

    /**
     * 查询模块运行状态。
     *
     * modern API 下模块 App 自身**不再被注入**，所以旧版「hook Application 写
     * `xposed_active` 标记」的探测方式彻底失效。改为直接问框架：
     * - 服务都没连上 → `Inactive`
     * - 连上了但从没成功写过配置 → `Active`（框架认可模块，只是还没扫描过）
     *
     * 返回值保持旧接口的字符串形式，避免改动调用方。
     */
    fun getHookStatus(): String {
        val svc = service ?: return "Inactive"
        return try {
            val scope = svc.scope
            if (scope.isEmpty()) {
                "Inactive"
            } else {
                "Active"
            }
        } catch (t: Throwable) {
            Logger.App.e(TAG, "Failed to read hook status", t)
            "Unknown"
        }
    }

    /** 清空所有配置 */
    fun clearAll() {
        try {
            prefs().edit().clear().commit()
            appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .edit().clear().apply()
            Logger.App.w(TAG, "Cleared all configuration")
        } catch (t: Throwable) {
            Logger.App.e(TAG, "Failed to clear config", t)
        }
    }
}
