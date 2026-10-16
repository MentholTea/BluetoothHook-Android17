package com.jingyu233.bluetoothhook.hook

import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed模块入口点
 * 负责初始化Hook并拦截com.android.bluetooth进程
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private val TAG = Logger.Tags.CORE_ENTRY
        private const val PACKAGE_BLUETOOTH = "com.android.bluetooth"
        private const val PREF_PACKAGE = "com.jingyu233.bluetoothhook"
        private const val PREF_NAME = "hook_config" // Must match ConfigBridge.PREF_NAME
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook自身Application以检测模块激活状态
        if (lpparam.packageName == PREF_PACKAGE) {
            hookModuleApplication(lpparam)
            return
        }

        // 只Hook蓝牙系统进程
        if (lpparam.packageName != PACKAGE_BLUETOOTH) {
            return
        }

        try {
            Logger.Hook.i(TAG, "Initializing BluetoothHook in ${lpparam.packageName}")

            // 初始化SharedPreferences以读取模块配置
            // 使用XSharedPreferences可以跨进程读取（系统进程读取模块进程的配置）
            val prefs = XSharedPreferences(PREF_PACKAGE, PREF_NAME)

            // 设置文件权限为world-readable（在模块进程中设置，这里只是尝试）
            try {
                prefs.makeWorldReadable()
            } catch (e: Exception) {
                Logger.Hook.w(TAG, "Failed to make preferences world-readable: ${e.message}")
            }

            // 检查模块是否启用
            prefs.reload()
            val enabled = prefs.getBoolean("global_enabled", true)

            if (!enabled) {
                Logger.Hook.i(TAG, "Module is disabled by user")
                return
            }

            // 初始化蓝牙扫描Hook
            val bluetoothScanHook = BluetoothScanHook(lpparam.classLoader, prefs)
            bluetoothScanHook.init()

            // 写入Hook状态标记（供UI进程读取）
            writeHookStatus()

            Logger.Hook.i(TAG, "BluetoothHook initialized successfully")

        } catch (e: Throwable) {
            // 捕获所有异常，防止蓝牙服务崩溃
            Logger.Hook.e(TAG, "Fatal error during initialization", e)
        }
    }

    /**
     * Hook模块自身的Application.onCreate
     * 写入激活标记供UI读取
     */
    private fun hookModuleApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.thisObject as android.content.Context
                            val prefs = context.getSharedPreferences(
                                "module_status",
                                android.content.Context.MODE_PRIVATE
                            )
                            prefs.edit()
                                .putBoolean("xposed_active", true)
                                .putLong("last_hook_time", System.currentTimeMillis())
                                .apply()

                            Logger.Hook.i(TAG, "Module activation marker written successfully")
                        } catch (e: Exception) {
                            Logger.Hook.e(TAG, "Failed to write activation marker", e)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to hook module application", e)
        }
    }

    /**
     * 尽力写一份 Hook 状态文件（纯排障用，不是功能依赖）。
     *
     * 注意：Android 17 上蓝牙进程跑在 `bluetooth` uid 下，`/data/system` 属于
     * `system_data_file`，直接写会抛 `EACCES: Permission denied`
     * —— 这就是旧版日志里那条 `Failed to write hook status file` 的来源，
     * 它是**无害**的，但会让人误以为模块没生效。
     *
     * 并且这个文件其实没有任何代码读取：UI 侧的状态读的是
     * `module_status` SharedPreferences（见 `ConfigBridge.getHookStatus`）。
     * 所以这里改成逐个候选路径尽力而为，全失败就静默降级，不再打 ERROR。
     */
    private fun writeHookStatus() {
        val data = "Active|${System.currentTimeMillis()}"
        val candidates = arrayOf(
            "/data/local/tmp/bluetooth_hook_status.txt",
            "/data/system/bluetooth_hook_status.txt"
        )

        for (path in candidates) {
            try {
                java.io.File(path).writeText(data)
                Logger.Hook.d(TAG, "Hook status written to $path")
                return
            } catch (_: Throwable) {
                // 权限不足是预期情况，继续试下一个路径
            }
        }

        Logger.Hook.d(
            TAG,
            "Hook status file 不可写（Android 17 上是预期行为）；" +
                    "模块是否生效请以 BTHook: 开头的日志为准"
        )
    }
}
