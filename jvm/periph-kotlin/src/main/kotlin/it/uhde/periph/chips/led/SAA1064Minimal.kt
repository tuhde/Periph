package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SAA1064 — 4-digit LED driver (minimal driver).
 *
 * Drives four 7-segment digits (multiplexed in pairs, dynamic mode) with
 * decimal points. I2C address 0x38-0x3B, selected by the voltage on the ADR pin.
 *
 * Defaults baked in at construction: control byte 0x47 (dynamic mode, all
 * digits unblanked, 12 mA segment current), digits blank, default segment
 * mapping a = bit 0 ... g = bit 6, dp = bit 7.
 */
open class SAA1064Minimal @Throws(IOException::class) constructor(protected val connection: Connection) {

    companion object {
        const val DIGITS = 4
        /** Value that blanks a digit in [showDigits]. */
        const val BLANK = -1

        const val SUB_CONTROL  = 0x00
        const val C_DYNAMIC    = 0x01
        const val C_UNBLANK_13 = 0x02
        const val C_UNBLANK_24 = 0x04
        const val C_TEST       = 0x08
        const val C_CURRENT    = 0x70

        private val SEGMENTS = arrayOf(
            "abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"
        )
        private const val KEYS = "abcdefg"
    }

    protected var control: Int = 0x47
    protected val patterns = IntArray(4)
    protected val segMap = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private val glyphs = IntArray(10)

    init {
        rebuildGlyphs()
        connection.write(byteArrayOf(SUB_CONTROL.toByte(), control.toByte(), 0, 0, 0, 0))
    }

    protected fun rebuildGlyphs() {
        for (v in 0..9) {
            var p = 0
            for (s in SEGMENTS[v]) p = p or (1 shl segMap[KEYS.indexOf(s)])
            glyphs[v] = p
        }
    }

    protected fun pattern(value: Int): Int {
        if (value == BLANK) return 0
        require(value in 0..9) { "digit must be 0-9 or BLANK" }
        return glyphs[value]
    }

    @Throws(IOException::class)
    protected fun writeDigits(first: Int) {
        val n = DIGITS - first + 1
        val buf = ByteArray(1 + n)
        buf[0] = first.toByte()
        for (i in 0 until n) buf[1 + i] = patterns[first - 1 + i].toByte()
        connection.write(buf)
    }

    /**
     * Show four digits using the segment mapping.
     *
     * @param digits four values, each 0-9 or [BLANK]
     */
    @Throws(IOException::class)
    fun showDigits(digits: IntArray) {
        require(digits.size == DIGITS) { "digits must have 4 values" }
        val p = IntArray(4) { pattern(digits[it]) }
        p.copyInto(patterns)
        writeDigits(1)
    }

    /**
     * Show a right-aligned decimal number with leading zeros blanked.
     *
     * @param value 0-9999
     */
    @Throws(IOException::class)
    fun showNumber(value: Int) {
        require(value in 0..9999) { "value must be in 0..9999" }
        val digits = intArrayOf(BLANK, BLANK, BLANK, value % 10)
        var v = value / 10
        for (i in 2 downTo 0) {
            if (v > 0) { digits[i] = v % 10; v /= 10 }
        }
        showDigits(digits)
    }

    /** Blank all four digits. */
    @Throws(IOException::class)
    fun clear() {
        patterns.fill(0)
        writeDigits(1)
    }

    /**
     * Write raw segment bytes starting at a digit.
     *
     * @param raw 1-4 segment bytes (bit 0 = P1/P9 ... bit 7 = P8/P16)
     * @param firstDigit first digit to write, 1-4
     */
    @Throws(IOException::class)
    fun writeRaw(raw: IntArray, firstDigit: Int = 1) {
        require(firstDigit in 1..DIGITS) { "firstDigit must be in 1..4" }
        require(raw.isNotEmpty() && firstDigit - 1 + raw.size <= DIGITS) { "raw must fit within digits 1..4" }
        val buf = ByteArray(1 + raw.size)
        buf[0] = firstDigit.toByte()
        for (i in raw.indices) {
            require(raw[i] in 0..255) { "pattern must be 0..255" }
            patterns[firstDigit - 1 + i] = raw[i]
            buf[1 + i] = raw[i].toByte()
        }
        connection.write(buf)
    }
}
