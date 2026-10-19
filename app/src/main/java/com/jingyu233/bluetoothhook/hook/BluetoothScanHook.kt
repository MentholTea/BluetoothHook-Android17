package com.jingyu233.bluetoothhook.hook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.jingyu233.bluetoothhook.utils.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蓝牙扫描 Hook 核心类（libxposed modern API 102）。
 *
 * 注入点：`com.android.bluetooth.le_scan.ScanController#onScanResultInternal(...)`
 * —— 系统把一条真实广播转成 `ScanResult` 并投递给各扫描客户端的地方。
 * 在它执行完之后追加一次虚拟设备投递即可，Android 10~17 都是同一个方法，
 * Android 17 的 Kotlin 重写**没有**改动这个方法名和签名。
 *
 * 迁移说明（legacy → API 102）：
 * - `XposedBridge.hookMethod(method, XC_MethodHook)` → `hook(method).intercept { chain -> ... }`
 * - `afterHookedMethod` 的语义等价于「先 `chain.proceed()` 拿到结果，再做注入」
 * - 不再有 `MethodHookParam.thisObject`，改从 `chain.thisObject` 取
 * - hook 找不到时抛 `HookFailedError`，这里统一 try/catch 保持旧行为（只记日志）
 */
class BluetoothScanHook(
    private val xposed: XposedInterface,
    private val classLoader: ClassLoader,
    private val prefs: SharedPreferences?
) {
    companion object {
        private val TAG = Logger.Tags.HOOK_SCANNER

        /** ScanController 的候选类名（AOSP 与主流 OEM 都沿用第一个） */
        private val SCAN_CONTROLLER_CLASSES = arrayOf(
            "com.android.bluetooth.le_scan.ScanController"
        )

        /** 注入点候选方法名（Android 17 仍叫 onScanResultInternal） */
        private val HOOK_METHOD_NAMES = arrayOf("onScanResultInternal")

        /** ScanManager 的候选字段名（Android 17 Kotlin 重写去掉 m 前缀） */
        private val SCAN_MANAGER_NAMES = arrayOf("mScanManager", "scanManager")

        /** ScannerMap 的候选字段名 */
        private val SCANNER_MAP_NAMES = arrayOf("mScannerMap", "scannerMap")

        /** 有些 ROM 把 ScannerMap 挂在 ScanHelper 上 */
        private val SCAN_HELPER_NAMES = arrayOf("mScanHelper", "scanHelper")

        /** 取「常规扫描」客户端的候选方法名 */
        private val SCAN_QUEUE_NAMES =
            arrayOf("getRegularScanQueue", "getScanQueue", "regularScanQueue")

        /**
         * 保活开关。
         *
         * 真实扫描回调只有在「附近恰好有别的 BLE 设备在广播」时才会触发；
         * 如果环境里一个 BLE 广播都没有，虚拟设备就永远不会出现。
         * 打开后会在**同一个 Looper** 上按 1 秒节拍主动触发一次注入
         * （仍然受每个设备自己的「广播间隔」节流），保证虚拟设备稳定可见。
         */
        private const val ENABLE_KEEPALIVE = true
        private const val KEEPALIVE_TICK_MS = 1000L
    }

    private lateinit var scanResultBuilder: ScanResultBuilder
    private lateinit var virtualDeviceInjector: VirtualDeviceInjector

    // ---- 保活注入用到的实例引用（弱引用，避免让蓝牙服务无法回收）----
    private val keepaliveStarted = AtomicBoolean(false)

    @Volatile
    private var controllerRef: WeakReference<Any>? = null

    @Volatile
    private var scanManagerRef: WeakReference<Any>? = null

    @Volatile
    private var scannerMapRef: WeakReference<Any>? = null

    fun init() {
        try {
            Logger.Hook.i(TAG, "Initializing BluetoothScanHook (libxposed API 102 build)")

            scanResultBuilder = ScanResultBuilder(classLoader)
            virtualDeviceInjector = VirtualDeviceInjector(scanResultBuilder, prefs)

            if (hookScanResultInternal()) {
                Logger.Hook.i(TAG, "Successfully hooked ScanController.onScanResultInternal")
            } else {
                Logger.Hook.e(
                    TAG,
                    "Failed to hook ScanController.onScanResultInternal —— " +
                            "候选类=${SCAN_CONTROLLER_CLASSES.joinToString()}, " +
                            "候选方法=${HOOK_METHOD_NAMES.joinToString()}",
                    null
                )
            }
        } catch (t: Throwable) {
            Logger.Hook.e(TAG, "Failed to initialize BluetoothScanHook", t)
        }
    }

    /**
     * Hook `ScanController.onScanResultInternal`。
     *
     * 先用精确签名找，找不到再退化为「同名 + 11 个参数 + 第 3 个是 String」，
     * 这样即使某个 ROM 微调了参数类型也还能挂上。
     */
    private fun hookScanResultInternal(): Boolean {
        var controllerClass: Class<*>? = null
        for (name in SCAN_CONTROLLER_CLASSES) {
            controllerClass = try {
                Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                null
            }
            if (controllerClass != null) {
                Logger.Hook.i(TAG, "Found scan controller class: ${controllerClass.name}")
                break
            }
        }
        if (controllerClass == null) {
            return false
        }

        var hooked = false
        for (methodName in HOOK_METHOD_NAMES) {
            val method = findScanResultMethod(controllerClass, methodName) ?: continue

            try {
                xposed.hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        // 先让原方法执行，保持真实扫描结果不受影响；
                        // 原始异常必须原样抛出，不能被注入逻辑吞掉。
                        val result = chain.proceed()
                        try {
                            injectVirtualDevices(chain.thisObject)
                        } catch (t: Throwable) {
                            // 捕获所有异常，绝不让虚拟设备注入影响真实扫描
                            Logger.Hook.e(TAG, "Error during virtual device injection", t)
                        }
                        result
                    }
            } catch (t: Throwable) {
                Logger.Hook.e(TAG, "hook() 失败: $methodName", t)
                continue
            }

            Logger.Hook.i(
                TAG,
                "Hooked ${method.declaringClass.name}#${method.name}(" +
                        method.parameterTypes.joinToString(", ") { it.simpleName } + ")"
            )
            hooked = true
        }
        return hooked
    }

    /** 精确签名优先，退化匹配兜底。 */
    private fun findScanResultMethod(clazz: Class<*>, name: String): Method? {
        try {
            // onScanResultInternal(int eventType, int addressType, String address,
            //                      int primaryPhy, int secondaryPhy, int advertisingSid,
            //                      int txPower, int rssi, int periodicAdvInt,
            //                      byte[] scanRecord, String originalAddress)
            return clazz.getDeclaredMethod(
                name,
                Int::class.javaPrimitiveType,    // eventType
                Int::class.javaPrimitiveType,    // addressType
                String::class.java,              // address
                Int::class.javaPrimitiveType,    // primaryPhy
                Int::class.javaPrimitiveType,    // secondaryPhy
                Int::class.javaPrimitiveType,    // advertisingSid
                Int::class.javaPrimitiveType,    // txPower
                Int::class.javaPrimitiveType,    // rssi
                Int::class.javaPrimitiveType,    // periodicAdvInt
                ByteArray::class.java,           // scanRecord
                String::class.java               // originalAddress
            )
        } catch (_: Throwable) {
            // 落到下面的退化匹配
        }

        // 退化：同名 + 11 个参数 + 第 3 个参数是 String（address）
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            try {
                for (m in cur.declaredMethods) {
                    if (m.name == name && m.parameterCount == 11 &&
                        m.parameterTypes[2] == String::class.java
                    ) {
                        return m
                    }
                }
            } catch (_: Throwable) {
            }
            cur = cur.superclass
        }
        return null
    }

    /**
     * 从真实的扫描回调里取出 ScanController 及其依赖，然后注入虚拟设备。
     *
     * @param controller `chain.thisObject`，即被 hook 的 ScanController 实例
     */
    private fun injectVirtualDevices(controller: Any?) {
        try {
            if (controller == null) return

            if (!virtualDeviceInjector.isEnabled()) {
                return
            }

            val scanManager = ReflectCompat.property(controller, *SCAN_MANAGER_NAMES)

            var scannerMap = ReflectCompat.property(controller, *SCANNER_MAP_NAMES)
            if (scannerMap == null) {
                val helper = ReflectCompat.property(controller, *SCAN_HELPER_NAMES)
                if (helper != null) {
                    scannerMap = ReflectCompat.property(helper, *SCANNER_MAP_NAMES)
                }
            }

            // 记住实例供保活复用
            controllerRef = WeakReference(controller)
            scanManagerRef = scanManager?.let { WeakReference(it) }
            scannerMapRef = scannerMap?.let { WeakReference(it) }

            val scanQueue = resolveScanQueue(scanManager)
            if (scanQueue.isNullOrEmpty()) {
                return // 当前没有活跃的扫描客户端，静默返回
            }

            virtualDeviceInjector.injectDevices(controller, scanManager, scannerMap, scanQueue)
            startKeepaliveIfNeeded()
        } catch (t: Throwable) {
            Logger.Hook.e(TAG, "Error in injectVirtualDevices", t)
        }
    }

    /**
     * 取当前活跃的常规扫描客户端。
     *
     * Android 17 Kotlin 重写后可能是方法 `getRegularScanQueue()`，
     * 也可能变成属性 `regularScanQueue`，这里两种都试。
     */
    private fun resolveScanQueue(scanManager: Any?): Collection<*>? {
        if (scanManager == null) return null

        for (name in SCAN_QUEUE_NAMES) {
            val v = ReflectCompat.callOrNull(scanManager, name)
            if (v is Collection<*>) return v
        }
        for (name in SCAN_QUEUE_NAMES) {
            val v = ReflectCompat.fieldValue(scanManager, name)
            if (v is Collection<*>) return v
        }
        return null
    }

    /**
     * 启动保活注入：在第一次成功拿到注入时机后，于**当前 Looper** 上起一个 1 秒节拍的任务。
     *
     * 必须用同一个 Looper，才能保证和系统原本调用 `onScanResultInternal` 的线程一致，
     * 不会引入并发问题；如果当前线程没有 Looper（Binder 线程），就静默放弃保活。
     */
    private fun startKeepaliveIfNeeded() {
        if (!ENABLE_KEEPALIVE) return
        if (!keepaliveStarted.compareAndSet(false, true)) return

        try {
            val looper = Looper.myLooper()
            if (looper == null) {
                Logger.Hook.w(TAG, "当前线程没有 Looper，保活注入未启动（仅回调驱动）")
                keepaliveStarted.set(false)
                return
            }

            val handler = Handler(looper)
            val tick = object : Runnable {
                override fun run() {
                    try {
                        val controller = controllerRef?.get()
                        if (controller == null) {
                            keepaliveStarted.set(false)
                            return
                        }
                        val scanManager = scanManagerRef?.get()
                        val scannerMap = scannerMapRef?.get()
                        val queue = resolveScanQueue(scanManager)
                        if (!queue.isNullOrEmpty()) {
                            virtualDeviceInjector.injectDevices(
                                controller, scanManager, scannerMap, queue
                            )
                        }
                    } catch (t: Throwable) {
                        Logger.Hook.e(TAG, "保活注入异常", t)
                    } finally {
                        handler.postDelayed(this, KEEPALIVE_TICK_MS)
                    }
                }
            }
            handler.postDelayed(tick, KEEPALIVE_TICK_MS)
            Logger.Hook.i(TAG, "保活注入已启动（${KEEPALIVE_TICK_MS}ms 节拍）")
        } catch (t: Throwable) {
            keepaliveStarted.set(false)
            Logger.Hook.e(TAG, "启动保活注入失败", t)
        }
    }
}
