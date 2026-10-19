package com.jingyu233.bluetoothhook.utils

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 统一的日志工具类
 * 使用层级化标签格式: BTHook:Layer:Component
 *
 * 使用方法:
 * - Hook层: Logger.Hook.d(TAG, message)
 * - App层: Logger.App.d(TAG, message)
 *
 * 迁移说明（legacy → libxposed API 102）：
 * 旧实现直接调用 `de.robv.android.xposed.XposedBridge.log()`，那是 legacy API。
 * API 102 起 libxposed 模块 **禁止** 调用 legacy API，改为：
 * 1. 进入最终进程前（入口类被框架实例化之前），Hook 日志退回 `android.util.Log`；
 * 2. 框架回调 `onModuleLoaded()` 里拿到 `XposedInterface` 后，调用
 *    [bind] 把后端切到 `XposedInterface.log()`，日志会进入 LSPosed/Vector 的模块日志页。
 */
object Logger {

    /**
     * 日志后端。默认走 android.util.Log，bind() 之后改走框架日志。
     * volatile：bind 发生在框架回调线程，读发生在任意 Hook 线程。
     */
    @Volatile
    private var backend: XposedInterface? = null

    /** 由 ModuleEntry 在 onModuleLoaded 里调用，把日志接到框架。 */
    fun bind(xposed: XposedInterface?) {
        backend = xposed
    }

    fun isBoundToFramework(): Boolean = backend != null

    /**
     * Hook层日志（运行在系统蓝牙进程）
     * 已 bind 时进框架日志（LSPosed/Vector 模块日志页），否则退回 logcat。
     */
    object Hook {
        private fun write(priority: Int, tag: String, message: String, tr: Throwable? = null) {
            val line = "[$tag] $message"
            val x = backend
            if (x != null) {
                try {
                    if (tr != null) x.log(priority, tag, line, tr) else x.log(priority, tag, line)
                    return
                } catch (_: Throwable) {
                    // 框架日志失败时退回 logcat，绝不因日志导致 Hook 失效
                }
            }
            if (tr != null) Log.println(priority, tag, "$line\n${Log.getStackTraceString(tr)}")
            else Log.println(priority, tag, line)
        }

        fun v(tag: String, message: String) = write(Log.VERBOSE, tag, "VERBOSE: $message")

        fun d(tag: String, message: String) = write(Log.DEBUG, tag, "DEBUG: $message")

        fun i(tag: String, message: String) = write(Log.INFO, tag, message)

        fun w(tag: String, message: String) = write(Log.WARN, tag, "WARN: $message")

        fun e(tag: String, message: String, throwable: Throwable? = null) =
            write(Log.ERROR, tag, "ERROR: $message", throwable)
    }

    /**
     * App层日志（运行在模块UI进程）
     * 使用 Android Log.*
     */
    object App {
        fun v(tag: String, message: String) {
            Log.v(tag, message)
        }

        fun d(tag: String, message: String) {
            Log.d(tag, message)
        }

        fun i(tag: String, message: String) {
            Log.i(tag, message)
        }

        fun w(tag: String, message: String) {
            Log.w(tag, message)
        }

        fun e(tag: String, message: String, throwable: Throwable? = null) {
            Log.e(tag, message, throwable)
        }
    }

    /**
     * 预定义的标签常量
     */
    object Tags {
        // Core
        const val CORE_ENTRY = "BTHook:Core:Entry"

        // Hook层
        const val HOOK_SCANNER = "BTHook:Hook:Scanner"
        const val HOOK_INJECTOR = "BTHook:Hook:Injector"
        const val HOOK_BUILDER = "BTHook:Hook:Builder"

        // Engine
        const val ENGINE_DYNAMIC = "BTHook:Engine:DynamicData"

        // Data层
        const val DATA_BRIDGE = "BTHook:Data:Bridge"
        const val DATA_REPOSITORY = "BTHook:Data:Repository"
        const val DATA_DATABASE = "BTHook:Data:Database"

        // UI ViewModel
        const val UI_VM_DEVICE_LIST = "BTHook:UI:VM:DeviceList"
        const val UI_VM_DEVICE_EDITOR = "BTHook:UI:VM:DeviceEditor"
        const val UI_VM_SETTINGS = "BTHook:UI:VM:Settings"

        // Sync
        const val SYNC = "BTHook:Sync"

        // UI Screen
        const val UI_SCREEN_DEVICE_LIST = "BTHook:UI:Screen:DeviceList"
        const val UI_SCREEN_DEVICE_EDITOR = "BTHook:UI:Screen:DeviceEditor"
        const val UI_SCREEN_SETTINGS = "BTHook:UI:Screen:Settings"
    }
}
