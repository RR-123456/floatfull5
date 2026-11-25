package com.pragon.mobile

/**
 * v21: pure-Kotlin IR code builder for Carrier (Midea-made, remote R14A/CE) air conditioners.
 * No Android classes, so it can be tested on a plain JVM. Profiles: "midea" (default) and "coolix" (fallback).
 * Timings and frame layouts follow IRremoteESP8266 (ir_Midea / ir_Coolix), checked against its test vectors.
 */
object IrCodes {
    const val FREQ = 38000
    private val MODES = listOf("cool", "dry", "auto", "heat", "fan")
    private val MIDEA_FAN = mapOf("auto" to 0, "low" to 1, "med" to 2, "high" to 3)

    private fun rev8(b: Int): Int { var r = 0; for (i in 0..7) if (b and (1 shl i) != 0) r = r or (1 shl (7 - i)); return r }

    fun mideaChecksum(state: Long): Int {
        var s = 0; var t = state
        for (i in 0 until 5) { t = t shr 8; s = (s + rev8((t and 0xFF).toInt())) and 0xFF }
        return rev8((256 - s) and 0xFF)
    }

    fun mideaState(power: Boolean, mode: String, fan: String, tempC: Int, sleep: Boolean): Long {
        val m = MODES.indexOf(mode).let { if (it < 0) 2 else it }
        val f = MIDEA_FAN[fan] ?: 0
        val t = tempC.coerceIn(17, 30)
        val b4 = m or (f shl 3) or ((if (sleep) 1 else 0) shl 6) or ((if (power) 1 else 0) shl 7)
        val b3 = 0x40 or (t - 17)
        val st = (0xA1L shl 40) or (b4.toLong() shl 32) or (b3.toLong() shl 24) or (0xFFL shl 16) or (0xFFL shl 8)
        return st or mideaChecksum(st).toLong()
    }

    private val TEMPMAP = intArrayOf(0, 1, 3, 2, 6, 7, 5, 4, 12, 13, 9, 8, 10, 11)
    private val CX_FAN = mapOf("auto" to 5, "low" to 4, "med" to 2, "high" to 1)

    fun coolixState(mode: String, fan: String, tempC: Int): Int {
        val md = MODES.indexOf(mode).let { if (it < 0) 2 else it }
        var f = CX_FAN[fan] ?: 5
        if (md == 2 || md == 1) f = if (f == 5) 0 else f
        val m: Int; val t: Int
        if (md == 4) { m = 1; t = 14 } else { m = md; t = TEMPMAP[tempC.coerceIn(17, 30) - 17] }
        val b0 = (m shl 2) or (t shl 4)
        val b1 = 0x1F or (f shl 5)
        return (0xB2 shl 16) or (b1 shl 8) or b0
    }

    private val MIDEA_BTN = mapOf(
        "swing" to 0xA201FFFFFF7CL, "econo" to 0xA202FFFFFF7EL, "light" to 0xA208FFFFFF75L,
        "turbo" to 0xA209FFFFFF74L, "clean" to 0xA20DFFFFFF70L
    )
    private val COOLIX_BTN = mapOf(
        "off" to 0xB27BE0L, "swing" to 0xB26BE0L, "sleep" to 0xB2E003L,
        "turbo" to 0xB5F5A2L, "light" to 0xB5F5A5L, "clean" to 0xB5F5AAL
    )

    /** Code for a one-shot button (swing, turbo, light, clean, econo, sleep), or null if this profile has none. */
    fun button(profile: String, name: String): Long? =
        if (profile == "coolix") COOLIX_BTN[name] else MIDEA_BTN[name]

    /** The full state code for the current panel settings, or null for an unknown profile. */
    fun state(profile: String, power: Boolean, mode: String, fan: String, tempC: Int, sleep: Boolean): Long? = when (profile) {
        "midea" -> mideaState(power, mode, fan, tempC, sleep)
        "coolix" -> if (!power) COOLIX_BTN["off"] else coolixState(mode, fan, tempC).toLong()
        else -> null
    }

    private fun addByte(out: MutableList<Int>, v: Int, mark: Int, one: Int, zero: Int) {
        for (i in 7 downTo 0) { out.add(mark); out.add(if ((v shr i) and 1 == 1) one else zero) }
    }

    /** Midea: header, 6 bytes, footer; then the whole frame again with every bit inverted. */
    fun mideaPattern(state: Long): IntArray {
        val out = ArrayList<Int>()
        var d = state
        for (phase in 0..1) {
            out.add(4480); out.add(4480)
            for (i in 5 downTo 0) addByte(out, ((d shr (i * 8)) and 0xFF).toInt(), 560, 1680, 560)
            out.add(560); out.add(5600)
            d = d.inv()
        }
        return out.toIntArray()
    }

    /** Coolix: header, 3 bytes each followed by its inverse, footer; sent twice. */
    fun coolixPattern(state: Int): IntArray {
        val out = ArrayList<Int>()
        for (rep in 0..1) {
            out.add(4692); out.add(4416)
            for (i in 2 downTo 0) {
                val b = (state shr (i * 8)) and 0xFF
                addByte(out, b, 552, 1656, 552); addByte(out, b xor 0xFF, 552, 1656, 552)
            }
            out.add(552); out.add(5244)
        }
        return out.toIntArray()
    }

    fun pattern(profile: String, code: Long): IntArray =
        if (profile == "coolix") coolixPattern(code.toInt()) else mideaPattern(code)
}
