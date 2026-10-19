package com.jingyu233.bluetoothhook.data.bridge

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.jingyu233.bluetoothhook.data.model.VirtualDevice
import com.jingyu233.bluetoothhook.utils.Logger
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

        /**
         * 框架连接状态的可观察版本。
         *
         * `null` = 尚未有结果（进程刚起来，框架 binder 还在路上）；
         * `false` = 明确连不上（框架未启用 / 模块未激活）；
         * `true`  = 已连上。
         *
         * UI 用它来区分「还在等」和「真的没激活」—— 旧实现在这两者之间都返回
         * `Inactive`，于是冷启动第一帧必然闪一下「Hook未激活」。
         */
        private val _connected = MutableStateFlow<Boolean?>(null)
        val connected: StateFlow<Boolean?> = _connected.asStateFlow()

        /**
         * 等待框架 binder 的最长时间。
         *
         * 模块 App 冷启动时，框架是在 `ContentProvider.onCreate` 之后才把 binder
         * 送进来的（`XposedProvider.call()` 走 binder 线程），而 ViewModel 的
         * `init` 往往更早跑完。等这么久还没等到，就认定框架确实没连上。
         */
        const val FRAMEWORK_BIND_TIMEOUT_MS = 1500L

        /** 是否已连上框架（供 UI 显示状态） */
        fun isFrameworkConnected(): Boolean = service != null

        /**
         * 框架是否**确定**连不上（等待期结束后仍未收到 binder）。
         * 等待期内返回 false，避免 UI 过早判定为未激活。
         */
        fun isFrameworkConfirmedMissing(): Boolean = _connected.value == false
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
     *
     * 注册完再挂一个 [FRAMEWORK_BIND_TIMEOUT_MS] 的超时检查：超时还没收到
     * binder，才把 [connected] 置为 `false`。这样 UI 不会在冷启动第一帧
     * 就误报「未激活」。
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
                        _connected.value = true
                        Logger.App.i(
                            TAG,
                            "已连接 Xposed 框架: ${svc.frameworkName} ${svc.frameworkVersion} " +
                                    "(API ${svc.apiVersion})"
                        )
                    }

                    override fun onServiceDied(svc: XposedService) {
                        if (service === svc) {
                            service = null
                            _connected.value = false
                        }
                        Logger.App.w(TAG, "Xposed 框架服务已断开")
                    }
                })
                Logger.App.d(TAG, "XposedServiceHelper listener registered")

                // registerListener 里如果缓存里已有 binder，会同步回调 onServiceBind，
                // 此时 _connected 已是 true，不必再挂超时。
                if (service == null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (service == null) {
                            _connected.value = false
                            Logger.App.w(
                                TAG,
                                "等待框架 binder 超时（${FRAMEWORK_BIND_TIMEOUT_MS}ms），判定为未连接"
                            )
                        }
                    }, FRAMEWORK_BIND_TIMEOUT_MS)
                }
            } catch (t: Throwable) {
                _connected.value = false
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
     * - 还在等框架 binder → `Unknown`（**不能报未激活**，否则冷启动必闪）
     * - 等待超时 / 服务断开 → `Inactive`
     * - 连上了但没有作用域 → `Inactive`
     * - 连上了且有作用域 → `Active`
     *
     * 返回值保持旧接口的字符串形式，避免改动调用方。
     */
    fun getHookStatus(): String {
        val svc = service
        if (svc == null) {
            // 等待期内（connected == null）如实说「未知」，不要谎报未激活
            return if (_connected.value == null) "Unknown" else "Inactive"
        }
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

    /**
     * 等框架 binder 落地后再查状态（冷启动用）。
     *
     * 收到 binder 或超时都会返回；最多等 [timeoutMs]，不会把 UI 卡住。
     */
    suspend fun awaitHookStatus(timeoutMs: Long = FRAMEWORK_BIND_TIMEOUT_MS + 800L): String {
        withTimeoutOrNull(timeoutMs) {
            connected.first { it != null }
        }
        return getHookStatus()
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
