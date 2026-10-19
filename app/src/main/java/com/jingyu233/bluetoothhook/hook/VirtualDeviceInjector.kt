package com.jingyu233.bluetoothhook.hook

import android.content.SharedPreferences
import com.jingyu233.bluetoothhook.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 虚拟设备注入器：把配置里的虚拟设备投递给当前所有活跃的扫描客户端。
 *
 * Android 17 起 `com.android.bluetooth.le_scan` 被重写为 Kotlin：
 *
 * ```
 * ScanClient.mScannerId  ->  ScanClient.scannerId   （并新增 ScanClient.app）
 * ScannerApp.mCallback   ->  ScannerApp.callback
 * ```
 *
 * 旧版代码硬编码 `mScannerId` / `mCallback`，在 Android 17 上会抛
 * `com.android.bluetooth.le_scan.ScanClient#mScannerId`，导致完全无法投递。
 * 这里改成多候选探测（[ReflectCompat]），任何一个命名命中都能工作。
 *
 * 迁移说明（legacy → libxposed API 102）：
 * 旧实现用 `XSharedPreferences` 读模块 App 写在自身私有目录里的配置，
 * 那是 legacy 扩展；API 102 禁止 libxposed 模块调用 legacy API。
 * 现在改为框架提供的 remote preferences（`SharedPreferences` 接口），
 * 每次注入前 `reload()` 以拿到 UI 进程刚写入的最新值。
 */
class VirtualDeviceInjector(
    private val scanResultBuilder: ScanResultBuilder,
    private val prefs: SharedPreferences?
) {
    companion object {
        private val TAG = Logger.Tags.HOOK_INJECTOR

        /** Android 10~16: `ScannerApp.mCallback` / Android 17+: `ScannerApp.callback` */
        private val CALLBACK_NAMES = arrayOf("callback", "mCallback")

        /** Android 17 新增：`ScanClient.app` 直接指向 ScannerApp */
        private val APP_NAMES = arrayOf("app", "mApp", "scannerApp", "mScannerApp")

        /**
         * scanner id 的候选名。
         * 故意不把 `id` / `mId` 放进来——ScanClient 上可能有别的 id 字段，
         * 认错会把结果投递给错误的 scanner。
         */
        private val ID_NAMES = arrayOf("scannerId", "mScannerId")

        /** 失败 dump 只打一次，避免每次扫描回调都刷屏 */
        private val DUMPED = AtomicBoolean(false)

        /** 成功日志限流（毫秒） */
        private const val SUCCESS_LOG_INTERVAL_MS = 10_000L
    }

    /** 每个设备的上次注入时间戳，用于实现「广播间隔」节流 */
    private val lastInjectTime = ConcurrentHashMap<String, Long>()

    private val lastSuccessLogAt = AtomicLong(0L)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    /** 全局开关（remote preferences 里的 `global_enabled`，默认开启）。 */
    fun isEnabled(): Boolean {
        val p = prefs ?: return true
        return try {
            p.getBoolean(ModuleEntry.KEY_GLOBAL_ENABLED, true)
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * 注入虚拟设备到扫描结果。
     *
     * @param scanControllerInstance ScanController 实例（仅用于诊断 dump）
     * @param scanManager            ScanManager 实例，可为 null
     * @param scannerMap             ScannerMap 实例，可为 null（Android 17 那条路径不需要它）
     * @param scanQueue              当前活跃的扫描客户端队列
     */
    fun injectDevices(
        scanControllerInstance: Any,
        scanManager: Any?,
        scannerMap: Any?,
        scanQueue: Collection<*>
    ) {
        try {
            val p = prefs ?: return

            if (!isEnabled()) {
                return
            }

            val devicesJson = try {
                p.getString(ModuleEntry.KEY_DEVICES, "[]") ?: "[]"
            } catch (t: Throwable) {
                Logger.Hook.e(TAG, "读取 devices 配置失败", t)
                return
            }
            if (devicesJson.isBlank() || devicesJson == "[]") {
                return
            }

            val devices = try {
                json.decodeFromString<List<VirtualDeviceData>>(devicesJson)
            } catch (e: Exception) {
                Logger.Hook.e(TAG, "JSON parse error: ${e.message}\nJSON: $devicesJson", e)
                return
            }

            val enabledDevices = devices.filter { it.enabled }
            if (enabledDevices.isEmpty()) {
                return
            }

            for (device in enabledDevices) {
                try {
                    injectSingleDevice(device, scanQueue, scannerMap)
                } catch (e: Throwable) {
                    Logger.Hook.e(TAG, "Failed to inject device ${device.name}", e)
                }
            }
        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Error in injectDevices", e)
        }
    }

    /** 注入单个虚拟设备（受该设备自己的「广播间隔」节流）。 */
    private fun injectSingleDevice(
        device: VirtualDeviceData,
        scanQueue: Collection<*>,
        scannerMap: Any?
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastInjectTime[device.id] ?: 0L
        if (now - lastTime < device.intervalMs) {
            return
        }
        lastInjectTime[device.id] = now

        if (!scanResultBuilder.isValidMacAddress(device.mac)) {
            Logger.Hook.w(TAG, "Invalid MAC address for device ${device.name}: ${device.mac}")
            return
        }

        if (!scanResultBuilder.isValidRssi(device.rssi)) {
            Logger.Hook.w(TAG, "Invalid RSSI for device ${device.name}: ${device.rssi}")
            return
        }

        val scanResult = scanResultBuilder.buildScanResult(
            macAddress = device.mac,
            rssi = device.rssi,
            advDataHex = device.advDataHex,
            scanResponseHex = device.scanResponseHex,
            useExtendedAdvertising = device.useExtendedAdvertising,
            deviceName = device.name
        ) ?: return

        var delivered = 0
        var firstClient: Any? = null
        for (scanClient in scanQueue) {
            if (scanClient == null) continue
            if (firstClient == null) firstClient = scanClient
            if (deliverToClient(scanClient, scannerMap, scanResult)) {
                delivered++
            }
        }

        if (delivered > 0) {
            logSuccessThrottled(device, delivered)
        } else if (firstClient != null) {
            dumpOnce(firstClient, scannerMap)
        }
    }

    /**
     * 把 ScanResult 投递给单个扫描客户端。
     *
     * @return 是否真的调用到了 `IScannerCallback.onScanResult`
     */
    private fun deliverToClient(scanClient: Any, scannerMap: Any?, scanResult: Any): Boolean {
        val callback = resolveCallback(scanClient, scannerMap) ?: return false
        return try {
            ReflectCompat.call(callback, "onScanResult", scanResult)
            true
        } catch (t: Throwable) {
            Logger.Hook.e(
                TAG,
                "调用 IScannerCallback.onScanResult 失败: ${t.javaClass.simpleName}: ${t.message}"
            )
            false
        }
    }

    /**
     * 解析出扫描客户端的 `IScannerCallback`，覆盖 Android 10~17 的各种命名。
     *
     * 策略按命中率从高到低排列：
     * 1. Android 17 (Kotlin)：`ScanClient.app` → `ScannerApp.callback`
     * 2. Android 10~16 (Java)：`ScanClient.mScannerId` → `ScannerMap.getById(id)` → `ScannerApp.mCallback`
     * 3. 少数 ROM 把回调直接挂在 `ScanClient` 上
     */
    private fun resolveCallback(scanClient: Any, scannerMap: Any?): Any? {
        // 1) Android 17：ScanClient.app 直接指向 ScannerApp
        val app = ReflectCompat.property(scanClient, *APP_NAMES)
        if (app != null) {
            ReflectCompat.property(app, *CALLBACK_NAMES)?.let { return it }
        }

        // 2) Android 10~16：scannerId -> ScannerMap.getById() -> ScannerApp.mCallback
        if (scannerMap != null) {
            val id = ReflectCompat.intProperty(scanClient, *ID_NAMES)
            if (id != null) {
                val appFromMap = ReflectCompat.callOrNull(scannerMap, "getById", id)
                if (appFromMap != null) {
                    ReflectCompat.property(appFromMap, *CALLBACK_NAMES)?.let { return it }
                }
            }
        }

        // 3) 回调直接挂在 ScanClient 上
        return ReflectCompat.property(scanClient, *CALLBACK_NAMES)
    }

    private fun logSuccessThrottled(device: VirtualDeviceData, delivered: Int) {
        val now = System.currentTimeMillis()
        val prev = lastSuccessLogAt.get()
        if (now - prev >= SUCCESS_LOG_INTERVAL_MS && lastSuccessLogAt.compareAndSet(prev, now)) {
            Logger.Hook.i(
                TAG,
                "Injected virtual device: ${device.name} (${device.mac}) to $delivered clients"
            )
        }
    }

    /**
     * 投递彻底失败时把真实字段表打出来供定位。
     * 只打一次，并附带探测到的中间结果，方便判断卡在哪一步。
     */
    private fun dumpOnce(scanClient: Any, scannerMap: Any?) {
        if (!DUMPED.compareAndSet(false, true)) return
        try {
            Logger.Hook.e(
                TAG,
                "无法解析扫描客户端的 IScannerCallback —— 请把这段日志反馈给开发者：\n" +
                        ReflectCompat.dump(scanClient.javaClass)
            )

            val app = ReflectCompat.property(scanClient, *APP_NAMES)
            if (app != null) {
                Logger.Hook.e(TAG, ReflectCompat.dump(app.javaClass))
            } else {
                Logger.Hook.e(TAG, "ScanClient 上没有 app 字段，候选=${APP_NAMES.joinToString()}")
            }

            val id = ReflectCompat.intProperty(scanClient, *ID_NAMES)
            Logger.Hook.e(
                TAG,
                "scannerId 探测结果=$id, " +
                        "ScanClient 候选回调=${CALLBACK_NAMES.joinToString()}, " +
                        "scannerMap=${scannerMap?.javaClass?.name ?: "null"}"
            )
        } catch (t: Throwable) {
            Logger.Hook.e(TAG, "dump 反射结构失败", t)
        }
    }
}

/**
 * 虚拟设备数据模型（用于从 SharedPreferences 反序列化）
 * 必须与 [com.jingyu233.bluetoothhook.data.model.VirtualDevice] 的 JSON 序列化格式匹配
 */
@Serializable
data class VirtualDeviceData(
    val id: String,
    val name: String,
    val mac: String,
    val rssi: Int,
    val advDataHex: String,
    val intervalMs: Long,
    val enabled: Boolean,
    val scanResponseHex: String = "",             // 扫描响应数据
    val useExtendedAdvertising: Boolean = false,  // 是否使用扩展广播
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)