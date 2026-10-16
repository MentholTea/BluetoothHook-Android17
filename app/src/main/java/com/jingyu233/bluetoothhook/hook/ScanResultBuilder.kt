package com.jingyu233.bluetoothhook.hook

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import com.jingyu233.bluetoothhook.utils.Logger
import de.robv.android.xposed.XposedHelpers

/**
 * ScanResult构造器
 * 根据逆向代码分析（ScanController.java line 431）构造符合系统要求的ScanResult对象
 */
class ScanResultBuilder(private val classLoader: ClassLoader) {

    companion object {
        private val TAG = Logger.Tags.HOOK_BUILDER
    }

    /**
     * 构造ScanResult对象
     *
     * @param macAddress MAC地址 (格式: AA:BB:CC:DD:EE:FF)
     * @param rssi 信号强度 (-100 to 0)
     * @param advDataHex 广播数据十六进制字符串
     * @param scanResponseHex 扫描响应数据十六进制字符串（可选）
     * @param useExtendedAdvertising 是否使用扩展广播
     * @param deviceName 设备名称（可选，会添加到广播数据中）
     * @return ScanResult对象，失败返回null
     */
    fun buildScanResult(
        macAddress: String,
        rssi: Int,
        advDataHex: String,
        scanResponseHex: String = "",
        useExtendedAdvertising: Boolean = false,
        deviceName: String? = null
    ): Any? {
        return try {
            // 1. 获取BluetoothAdapter并创建BluetoothDevice
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val device = adapter.getRemoteDevice(macAddress)

            // 2. 合并广播数据和扫描响应数据
            val combinedDataHex = if (scanResponseHex.isNotEmpty()) {
                advDataHex + scanResponseHex
            } else {
                advDataHex
            }
            // 2b. 设备名称兜底：广播数据里若没有 Local Name（AD 类型 0x08/0x09），
            //     把 device.name 追加进去。上游把 deviceName 传进来却从未使用，
            //     导致只填了「设备名称」而没点「生成标准广播数据」时，扫描方看到空名字。
            val namedDataHex = ensureLocalName(
                combinedDataHex,
                deviceName,
                extended = useExtendedAdvertising
            )
            val advBytes = hexStringToByteArray(namedDataHex)

            // 3. 使用ScanRecord.parseFromBytes解析广播数据
            val scanRecordClass = XposedHelpers.findClass(
                "android.bluetooth.le.ScanRecord",
                classLoader
            )
            val scanRecord = XposedHelpers.callStaticMethod(
                scanRecordClass,
                "parseFromBytes",
                advBytes
            )

            // 4. 构造ScanResult对象
            // 根据逆向代码line 431的构造器签名:
            // new ScanResult(device, eventType, primaryPhy, secondaryPhy,
            //               advertisingSid, txPower, rssi, periodicAdvInt,
            //               scanRecord, timestamp)
            val scanResultClass = XposedHelpers.findClass(
                "android.bluetooth.le.ScanResult",
                classLoader
            )

            val timestampNanos = SystemClock.elapsedRealtimeNanos()

            // 确定事件类型
            val eventType = if (useExtendedAdvertising) {
                0x00  // EXTENDED_ADVERTISING
            } else {
                0x10  // LEGACY_ADVERTISING
            }

            // 尝试使用完整参数的构造器
            try {
                XposedHelpers.newInstance(
                    scanResultClass,
                    device,                  // BluetoothDevice
                    eventType,               // eventType: 0x10 = LEGACY, 0x00 = EXTENDED
                    1,                       // primaryPhy: 1 = LE 1M
                    if (useExtendedAdvertising) 1 else 0,  // secondaryPhy: 1 = LE 1M, 0 = None
                    255,                     // advertisingSid: 255 = Not periodic
                    0,                       // txPower: 0 = Unknown
                    rssi,                    // rssi
                    0,                       // periodicAdvInt: 0 = None
                    scanRecord,              // ScanRecord
                    timestampNanos           // timestamp
                )
            } catch (e: Exception) {
                // 降级：使用简化构造器（兼容旧版本Android）
                XposedHelpers.newInstance(
                    scanResultClass,
                    device,
                    scanRecord,
                    rssi,
                    timestampNanos
                )
            }

        } catch (e: Throwable) {
            Logger.Hook.e(TAG, "Failed to build ScanResult for MAC=$macAddress", e)
            null
        }
    }

    /**
     * 十六进制字符串转字节数组
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val cleanHex = hexString.replace(" ", "").replace(":", "")
        val len = cleanHex.length
        val data = ByteArray(len / 2)

        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                    Character.digit(cleanHex[i + 1], 16)).toByte()
            i += 2
        }

        return data
    }

    /**
     * 若广播数据里已经带有 Local Name（AD 类型 0x08 Shortened / 0x09 Complete），
     * 原样返回；否则把设备名作为 Complete Local Name (0x09) 追加进去。
     *
     * 为什么需要：扫描方 App 是通过 `ScanRecord.getDeviceName()` /
     * `ScanResult.getDevice().getName()` 取名字的，而这两个值都由系统从广播数据
     * 的 0x08/0x09 段解析得出。上游虽然把 `device.name` 传进了 buildScanResult，
     * 却从未使用，所以只填「设备名称」而不点「生成标准广播数据」时，扫描方看到的
     * 是空名字。
     */
    private fun ensureLocalName(
        advDataHex: String,
        deviceName: String?,
        extended: Boolean
    ): String {
        if (deviceName.isNullOrBlank()) return advDataHex
        val clean = advDataHex.replace(" ", "").replace(":", "")
        if (hasLocalName(clean)) return advDataHex

        val currentBytes = clean.length / 2
        val maxBytes = if (extended) 254 else 31
        // AD 结构额外开销：1 字节 Length + 1 字节 Type
        val available = maxBytes - currentBytes - 2
        if (available <= 0) {
            Logger.Hook.w(
                TAG,
                "广播数据已满（当前 $currentBytes/$maxBytes 字节），设备名未写入"
            )
            return advDataHex
        }

        val allNameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val shortened = allNameBytes.size > available
        val nameBytes = if (shortened) {
            allNameBytes.copyOfRange(0, utf8SafeLength(allNameBytes, available))
        } else {
            allNameBytes
        }
        if (nameBytes.isEmpty()) return advDataHex
        if (shortened) {
            Logger.Hook.w(TAG, "广播数据空间不足，设备名已截断为 ${nameBytes.size} 字节")
        }

        val sb = StringBuilder(clean)
        // Length 字段 = 1 字节 Type + N 字节名字
        sb.append(String.format("%02X", nameBytes.size + 1))
        // 0x08 = Shortened Local Name（截断时），0x09 = Complete Local Name
        sb.append(if (shortened) "08" else "09")
        nameBytes.forEach { sb.append(String.format("%02X", it.toInt() and 0xFF)) }
        return sb.toString()
    }

    /**
     * 返回不超过 [limit] 的最大长度，且保证不把一个 UTF-8 多字节字符切成两半。
     */
    private fun utf8SafeLength(bytes: ByteArray, limit: Int): Int {
        var end = limit.coerceAtMost(bytes.size)
        while (end > 0) {
            var start = end - 1
            while (start >= 0 && (bytes[start].toInt() and 0xC0) == 0x80) start--
            if (start < 0) return end
            val expected = when (bytes[start].toInt() and 0xFF) {
                in 0x00..0x7F -> 1
                in 0xC0..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF7 -> 4
                else -> 1
            }
            if (start + expected <= end) return end
            end = start
        }
        return 0
    }

    /** 判断广播数据里是否已含 Local Name AD 段（0x08 Shortened / 0x09 Complete）。 */
    private fun hasLocalName(cleanHex: String): Boolean {
        var i = 0
        while (i + 3 < cleanHex.length) {
            val length = Character.digit(cleanHex[i], 16) * 16 +
                Character.digit(cleanHex[i + 1], 16)
            if (length <= 0) break                       // 0x00 = 后续为填充，结束
            val type = Character.digit(cleanHex[i + 2], 16) * 16 +
                Character.digit(cleanHex[i + 3], 16)
            if (type == 0x08 || type == 0x09) return true
            i += (length + 1) * 2                        // Length 字节 + length 个内容字节
        }
        return false
    }

    /**
     * 生成标准BLE广播数据
     * 包含Flags和Complete Local Name
     */
    fun generateStandardAdvData(deviceName: String): String {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size

        // Flags AD结构 (3 bytes): 02 01 06
        // - Length: 0x02
        // - Type: 0x01 (Flags)
        // - Data: 0x06 (LE General Discoverable Mode, BR/EDR Not Supported)
        val flags = "020106"

        // Complete Local Name AD结构
        // - Length: nameLength + 1
        // - Type: 0x09 (Complete Local Name)
        // - Data: name bytes
        val nameHex = StringBuilder()
        nameHex.append(String.format("%02X", nameLength + 1)) // Length
        nameHex.append("09")                                   // Type
        nameBytes.forEach { byte ->
            nameHex.append(String.format("%02X", byte))
        }

        return flags + nameHex.toString()
    }

    /**
     * 验证MAC地址格式
     */
    fun isValidMacAddress(mac: String): Boolean {
        return mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }

    /**
     * 验证RSSI范围
     */
    fun isValidRssi(rssi: Int): Boolean {
        return rssi in -100..0
    }

    /**
     * 验证广播数据长度（Legacy BLE最多31字节，Extended最多254字节）
     */
    fun isValidAdvDataLength(hexString: String, extended: Boolean = false): Boolean {
        val byteCount = hexString.replace(" ", "").replace(":", "").length / 2
        return if (extended) {
            byteCount <= 254
        } else {
            byteCount <= 31
        }
    }
}
