package com.example.yodascale

/**
 * Yoda0 / Chipsea OKOK 1.1 广播协议解析。
 * 秤只广播不连接，体重写在 Manufacturer Specific Data 里。
 * 数据布局（13 字节示例 06 B6 13 88 00 00 29 4C 6C A1 62 3A 1F）：
 *   [0..1] 体重原始值，大端 uint16
 *   [2..3] 电阻值（此款恒为 5000，体脂不可信）
 *   [6]    单位 + 锁定状态：bit3-5 单位码，bit0 锁定标志
 *   [7..12] MAC 地址（反序）
 */
data class ScaleReading(
    val rawWeight: Int,
    val resistance: Int,
    val unitCode: Int,
    val locked: Boolean,
    val weightKg: Double,
    val displayValue: Double,
    val displayUnit: String,
    val mac: String,
)

object ScaleParser {
    const val COMPANY_ID = 0x10C0
    private const val LB_TO_KG = 0.45359237

    /** 从 manufacturer data 解析；数据不合法返回 null。 */
    fun parse(data: ByteArray): ScaleReading? {
        if (data.size < 7) return null

        val raw = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        // 体脂称重范围 1kg~300kg；原始值换算前先做粗筛，过滤广播噪声
        if (raw <= 0 || raw > 60000) return null

        val resistance = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val props = data[6].toInt() and 0xFF
        val unitCode = (props shr 3) and 0x07
        val locked = (props and 0x01) == 1

        val (display, unit, kg) = when (unitCode) {
            4 -> Triple(raw / 100.0, "kg", raw / 100.0)          // 0x24/0x25
            5 -> Triple(raw / 10.0, "斤", raw / 20.0)             // 0x28/0x29
            6 -> Triple(raw / 10.0, "lb", raw / 10.0 * LB_TO_KG)  // 0x30/0x31
            7 -> {                                                // 0x38/0x39, st:lb
                val stones = raw / 256
                val pounds = (raw % 256) / 10.0
                val totalLb = stones * 14.0 + pounds
                Triple(totalLb, "lb", totalLb * LB_TO_KG)
            }
            else -> Triple(raw / 100.0, "?", raw / 100.0)
        }

        if (kg < 1.0 || kg > 400.0) return null

        val mac = if (data.size >= 13) {
            (7..12).joinToString(":") { "%02X".format(data[it].toInt() and 0xFF) }
        } else {
            ""
        }

        return ScaleReading(raw, resistance, unitCode, locked, kg, display, unit, mac)
    }

    /** 外部（Activity）从 ScanRecord 的 manufacturer data 里挑出秤的数据再调用 parse。 */
    fun looksLikeScale(data: ByteArray): Boolean {
        if (data.size < 7) return false
        val props = data[6].toInt() and 0xFF
        val unitCode = (props shr 3) and 0x07
        return unitCode in 4..7
    }
}
