package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * SAA1064 — 4-digit LED driver (full driver).
 *
 * Extends [SAA1064Minimal] with mode, digit-pair blanking, segment current,
 * segment test, segment mapping, per-digit decimal points and the status byte.
 */
class SAA1064Full @Throws(IOException::class) constructor(connection: Connection) : SAA1064Minimal(connection) {

    @Throws(IOException::class)
    private fun writeControlByte() {
        connection.write(byteArrayOf(SUB_CONTROL.toByte(), control.toByte()))
    }

    @Throws(IOException::class)
    private fun setBit(mask: Int, on: Boolean) {
        control = if (on) control or mask else control and mask.inv() and 0x7F
        writeControlByte()
    }

    /** Select dynamic (all four digits) or static (digits 1 and 2) mode. */
    @Throws(IOException::class)
    fun setMode(dynamic: Boolean) = setBit(C_DYNAMIC, dynamic)

    /**
     * Blank digit pairs.
     *
     * @param digits13 true to blank digits 1 and 3
     * @param digits24 true to blank digits 2 and 4
     */
    @Throws(IOException::class)
    fun blank(digits13: Boolean, digits24: Boolean) {
        control = if (digits13) control and C_UNBLANK_13.inv() else control or C_UNBLANK_13
        control = if (digits24) control and C_UNBLANK_24.inv() else control or C_UNBLANK_24
        control = control and 0x7F
        writeControlByte()
    }

    /**
     * Set the segment output current.
     *
     * @param ma 0, 3, 6, 9, 12, 15, 18 or 21 (mA)
     */
    @Throws(IOException::class)
    fun setCurrent(ma: Int) {
        require(ma in 0..21 && ma % 3 == 0) { "ma must be one of 0, 3, 6, 9, 12, 15, 18, 21" }
        control = (control and C_CURRENT.inv() and 0x7F) or ((ma / 3) shl 4)
        writeControlByte()
    }

    /** Switch all segment outputs on (segment test). */
    @Throws(IOException::class)
    fun segmentTest(on: Boolean) = setBit(C_TEST, on)

    /**
     * Remap the helper's segment bits to the module wiring.
     *
     * @param mapping bit numbers 0-7 for the keys "a" to "g" and "dp"
     */
    fun setSegmentMap(mapping: Map<String, Int>) {
        val keys = arrayOf("a", "b", "c", "d", "e", "f", "g", "dp")
        val m = IntArray(8) {
            val v = requireNotNull(mapping[keys[it]]) { "mapping needs keys a-g and dp" }
            require(v in 0..7) { "bit numbers must be in 0..7" }
            v
        }
        m.copyInto(segMap)
        rebuildGlyphs()
    }

    /**
     * Show one digit with an optional decimal point.
     *
     * @param position digit 1-4
     * @param value 0-9 or [BLANK]
     * @param dp true to light the decimal point
     */
    @Throws(IOException::class)
    fun showDigit(position: Int, value: Int, dp: Boolean = false) {
        require(position in 1..DIGITS) { "position must be in 1..4" }
        var p = pattern(value)
        if (dp) p = p or (1 shl segMap[7])
        patterns[position - 1] = p
        connection.write(byteArrayOf(position.toByte(), p.toByte()))
    }

    /**
     * Switch a decimal point, keeping the cached segments.
     *
     * @param position digit 1-4
     * @param on true to light the decimal point
     */
    @Throws(IOException::class)
    fun setDecimalPoint(position: Int, on: Boolean) {
        require(position in 1..DIGITS) { "position must be in 1..4" }
        val bit = 1 shl segMap[7]
        patterns[position - 1] = if (on) patterns[position - 1] or bit else patterns[position - 1] and bit.inv() and 0xFF
        connection.write(byteArrayOf(position.toByte(), patterns[position - 1].toByte()))
    }

    /** Read the status byte (bit 7 = PR power-reset flag, cleared by the read). */
    @Throws(IOException::class)
    fun readStatus(): Int = connection.read(1)[0].toInt() and 0xFF

    /** Read the status byte and return its PR flag. */
    @Throws(IOException::class)
    fun powerResetFlag(): Boolean = (readStatus() and 0x80) != 0

    /**
     * Write a raw control byte (bit 7 forced to 0).
     *
     * @param value control byte 0-127
     */
    @Throws(IOException::class)
    fun writeControl(value: Int) {
        control = value and 0x7F
        writeControlByte()
    }
}
