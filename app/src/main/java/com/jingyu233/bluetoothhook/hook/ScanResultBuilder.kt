package com.jingyu233.bluetoothhook.hook

import android.bluetooth.BluetoothAdapter
import android.os.SystemClock
import com.jingyu233.bluetoothhook.utils.Logger
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

/**
 * ScanResult 构造器。
 *
 * 迁移说明（legacy → libxposed API 102）：
 * 旧实现依赖 `de.robv.android.xposed.XposedHelpers` 的
 * `findClass` / `callStaticMethod` / `newInstance`。modern API **不再提供**
 * `XposedHelpers`（官方明确说明不会保留），因此这里改成纯 JDK 反射，
 * 行为等价且不依赖任何框架辅助类。
 *
 * 构造遵循系统 `ScanController` 里那条 10 参构造器：
 * `ScanResult(BluetoothDevice, int eventType, int primaryPhy, int secondaryPhy,
 *            int advertisingSid, int txPower, int rssi, int periodicAdvInt,
 *            ScanRecord, long timestamp)`
 */
class ScanResultBuilder(private val classLoader: ClassLoader) {

    companion object {
        private val TAG = Logger.Tags.HOOK_BUILDER

        private const val SCAN_RECORD_CLASS = "android.bluetooth.le.ScanRecord"
        private const val SCAN_RESULT_CLASS = "android.bluetooth.le.ScanResult"

        /** 精确类型标记：`int` 原始类型 */
        private val SPEC_INT: Class<*> = Int::class.javaPrimitiveType!!
        private val SPEC_LONG: Class<*> = Long::class.javaPrimitiveType!!
        private val SPEC_ANY: Class<*> = Any::class.java
    }

    /**
     * 构造 ScanResult 对象。
     *
     * @param macAddress MAC 地址（格式 AA:BB:CC:DD:EE:FF）
     * @param rssi 信号强度（-100..0）
     * @param advDataHex 广播数据十六进制串
     * @param scanResponseHex 扫描响应数据十六进制串（可选）
     * @param useExtendedAdvertising 是否使用扩展广播
     * @param deviceName 设备名称（会作为 Local Name 折进广播数据）
     * @return ScanResult 实例，失败返回 null
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
            // 1. BluetoothDevice
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
                Logger.Hook.e(TAG, "BluetoothAdapter 为 null，无法构造 ScanResult", null)
                return null
            }
            val device = adapter.getRemoteDevice(macAddress)

            // 2. 合并广播数据与扫描响应数据
            val combinedDataHex = if (scanResponseHex.isNotEmpty()) {
                advDataHex + scanResponseHex
            } else {
                advDataHex
            }

            // 2b. 设备名称兜底：广播数据里若没有 Local Name（AD 类型 0x08/0x09），
            //     把 deviceName 追加进去。扫描方 App 的名字来自系统对广播数据的解析，
            //     ScanResult 本身没有独立的名字字段。
            val namedDataHex = ensureLocalName(
                combinedDataHex,
                deviceName,
                extended = useExtendedAdvertising
            )
            val advBytes = hexStringToByteArray(namedDataHex)

            // 3. ScanRecord.parseFromBytes(byte[])
            val scanRecordClass = loadClass(SCAN_RECORD_CLASS)
            val parseFromBytes = scanRecordClass.getMethod("parseFromBytes", ByteArray::class.java)
            parseFromBytes.isAccessible = true
            val scanRecord = parseFromBytes.invoke(null, advBytes)

            val scanResultClass = loadClass(SCAN_RESULT_CLASS)
            val timestampNanos = SystemClock.elapsedRealtimeNanos()

            val eventType = if (useExtendedAdvertising) 0x00 else 0x10

            // 4. 优先用完整 10 参构造器：
            //    (BluetoothDevice, int eventType, int primaryPhy, int secondaryPhy,
            //     int advertisingSid, int txPower, int rssi, int periodicAdvInt,
            //     ScanRecord, long timestamp)
            val full = findConstructor(
                scanResultClass,
                SPEC_ANY, SPEC_INT, SPEC_INT, SPEC_INT, SPEC_INT,
                SPEC_INT, SPEC_INT, SPEC_INT, SPEC_ANY, SPEC_LONG
            )
            if (full != null) {
                try {
                    return newInstance(
                        full,
                        device,
                        eventType,
                        1,                              // primaryPhy: LE 1M
                        if (useExtendedAdvertising) 1 else 0,
                        255,                            // advertisingSid: not periodic
                        0,                              // txPower
                        rssi,
                        0,                              // periodicAdvInt
                        scanRecord,
                        timestampNanos
                    )
                } catch (t: Throwable) {
                    Logger.Hook.w(TAG, "10 参构造器失败，降级到 4 参构造器: ${t.message}")
                }
            }

            // 5. 降级：旧版 4 参构造器 ScanResult(BluetoothDevice, ScanRecord, int, long)
            val legacy = findConstructor(
                scanResultClass,
                SPEC_ANY, SPEC_ANY, SPEC_INT, SPEC_LONG
            ) ?: throw NoSuchMethodException("$SCAN_RESULT_CLASS 没有可用的构造器")
            newInstance(legacy, device, scanRecord, rssi, timestampNanos)
        } catch (t: Throwable) {
            Logger.Hook.e(TAG, "Failed to build ScanResult for MAC=$macAddress", t)
            null
        }
    }

    // ------------------------------------------------------------------ 反射 helper

    private fun loadClass(name: String): Class<*> = Class.forName(name, false, classLoader)

    /**
     * 按「参数个数 + 类型宽松匹配」找构造器。
     *
     * [spec] 里：原始类型（如 [SPEC_INT]）要求精确相等；
     * [SPEC_ANY] 表示「任意引用类型」占位，不参与严格校验。
     */
    private fun findConstructor(clazz: Class<*>, vararg spec: Class<*>): Constructor<*>? {
        for (ctor in clazz.declaredConstructors) {
            val types = ctor.parameterTypes
            if (types.size != spec.size) continue
            var ok = true
            for (i in spec.indices) {
                val want = spec[i]
                val actual = types[i]
                val matched = if (want.isPrimitive) {
                    actual == want
                } else {
                    // Any 占位放行，其余要求可赋值
                    want == Any::class.java || want.isAssignableFrom(actual)
                }
                if (!matched) {
                    ok = false
                    break
                }
            }
            if (ok) return ctor
        }
        return null
    }

    private fun newInstance(ctor: Constructor<*>, vararg args: Any?): Any {
        ctor.isAccessible = true
        return try {
            ctor.newInstance(*args)
        } catch (e: InvocationTargetException) {
            // 把构造器内部真正的异常抛出来，便于日志定位
            throw (e.targetException ?: e)
        }
    }

    /**
     * 十六进制字符串转字节数组。支持带空格或冒号分隔。
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val cleanHex = hexString.replace(" ", "").replace(":", "")
        val len = cleanHex.length
        val data = ByteArray(len / 2)

        var i = 0
        while (i + 1 < len) {
            val hi = Character.digit(cleanHex[i], 16)
            val lo = Character.digit(cleanHex[i + 1], 16)
            if (hi < 0 || lo < 0) {
                throw IllegalArgumentException("非法十六进制字符: ${cleanHex[i]}${cleanHex[i + 1]}")
            }
            data[i / 2] = ((hi shl 4) + lo).toByte()
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
     * 的 0x08/0x09 段解析得出。
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
     * 生成标准 BLE 广播数据：Flags + Complete Local Name。
     */
    fun generateStandardAdvData(deviceName: String): String {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameLength = nameBytes.size

        // Flags AD 结构 (3 bytes): 02 01 06
        val flags = "020106"

        // Complete Local Name AD 结构：Length(1) + Type(0x09) + name
        val nameHex = StringBuilder()
        nameHex.append(String.format("%02X", nameLength + 1))
        nameHex.append("09")
        nameBytes.forEach { byte ->
            nameHex.append(String.format("%02X", byte.toInt() and 0xFF))
        }

        return flags + nameHex.toString()
    }

    /** 验证 MAC 地址格式 */
    fun isValidMacAddress(mac: String): Boolean {
        return mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    }

    /** 验证 RSSI 范围 */
    fun isValidRssi(rssi: Int): Boolean {
        return rssi in -100..0
    }

    /** 验证广播数据长度（Legacy BLE 最多 31 字节，Extended 最多 254 字节） */
    fun isValidAdvDataLength(hexString: String, extended: Boolean = false): Boolean {
        val byteCount = hexString.replace(" ", "").replace(":", "").length / 2
        return if (extended) byteCount <= 254 else byteCount <= 31
    }
}
