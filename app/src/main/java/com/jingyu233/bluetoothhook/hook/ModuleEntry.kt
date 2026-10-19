package com.jingyu233.bluetoothhook.hook

import android.content.SharedPreferences
import com.jingyu233.bluetoothhook.utils.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed modern API（API 101/102）模块入口。
 *
 * 与 legacy 的 `IXposedHookLoadPackage` 相比有三处根本差别：
 *
 * 1. **入口发现方式**：类名登记在 `META-INF/xposed/java_init.list`，
 *    由框架反射实例化，不再实现 `IXposedHookLoadPackage`。
 * 2. **生命周期**：框架自动注入 `XposedInterface`（`attachFramework`），
 *    模块必须在 `onModuleLoaded()` 之后才能做初始化。构造里什么都别做。
 * 3. **不再 hook 模块自身**：modern API 下模块 App 进程不被注入，
 *    所以旧版「hook Application.onCreate 写激活标记」的做法整体作废，
 *    UI 侧的框架状态改由 `ConfigBridge` 通过 XposedService 查询。
 *
 * 作用域由 `META-INF/xposed/scope.list` 声明（只有 `com.android.bluetooth`），
 * 框架只会把本模块注入该进程；`onPackageReady` 里仍再校验一次包名。
 */
class ModuleEntry : XposedModule() {

    companion object {
        private val TAG = Logger.Tags.CORE_ENTRY
        private const val PACKAGE_BLUETOOTH = "com.android.bluetooth"

        /** 与 ConfigBridge 保持一致的组名 / 键名 */
        const val PREF_GROUP = "hook_config"
        const val KEY_DEVICES = "devices"
        const val KEY_GLOBAL_ENABLED = "global_enabled"
    }

    /** 供 VirtualDeviceInjector 读取的远程偏好（框架维护，跨进程共享）。 */
    private var prefs: SharedPreferences? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        try {
            // 把 Logger 接到框架日志，之后所有 Hook 日志都会进 LSPosed/Vector 的模块日志页
            Logger.bind(this)
            Logger.Hook.i(
                TAG,
                "Initializing BluetoothHook (libxposed API ${apiVersion}) " +
                        "framework=${frameworkName} ${frameworkVersion} in ${param.processName}"
            )
        } catch (t: Throwable) {
            // 日志相关失败绝不能影响注入
            android.util.Log.e("BTHook", "onModuleLoaded logging failed", t)
        }
    }

    /**
     * 类加载器就绪、Application 尚未创建时回调。
     * 这里是最早能安全拿 classloader 的时机，用来挂 Hook。
     */
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        if (pkg != PACKAGE_BLUETOOTH) {
            // 理论上 scope.list 已限定，这里只是双保险；不是目标就停止接收后续回调
            Logger.Hook.d(TAG, "Skip non-target package: $pkg")
            return
        }

        try {
            val remotePrefs = obtainPreferences()
            if (remotePrefs == null) {
                Logger.Hook.e(TAG, "无法获取 remote preferences，配置读写将不可用", null)
            } else {
                prefs = remotePrefs
            }

            // 全局开关：UI 侧通过 remote prefs 写入，默认 true
            val enabled = try {
                remotePrefs?.getBoolean(KEY_GLOBAL_ENABLED, true) ?: true
            } catch (_: Throwable) {
                true
            }
            if (!enabled) {
                Logger.Hook.i(TAG, "Module is disabled by user")
                return
            }

            val scanHook = BluetoothScanHook(this, param.classLoader, remotePrefs)
            scanHook.init()

            Logger.Hook.i(TAG, "BluetoothHook initialized successfully")
        } catch (t: Throwable) {
            // 捕获所有异常，防止蓝牙服务崩溃
            Logger.Hook.e(TAG, "Fatal error during initialization", t)
        }
    }

    /**
     * 取框架提供的远程偏好。
     *
     * 框架能力不足（`PROP_CAP_REMOTE` 未置位）时抛出 `UnsupportedOperationException`，
     * 这里统一吞掉并在调用方降级为「无配置」，而不是让整个模块失效。
     */
    private fun obtainPreferences(): SharedPreferences? = try {
        if (frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) {
            Logger.Hook.w(TAG, "当前框架不支持 remote preferences，配置不可用")
            null
        } else {
            getRemotePreferences(PREF_GROUP)
        }
    } catch (t: Throwable) {
        Logger.Hook.w(TAG, "getRemotePreferences 失败: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // 默认实现会 unhook 旧钩子；这里没有需要额外迁移的状态，交给父类
        super.onHotReloaded(param)
        Logger.Hook.i(TAG, "Module hot-reloaded")
    }
}
