package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException

/**
 * ST7036 character LCD controller — full driver.
 *
 * Extends [St7036Minimal] with the DOGM081 / DOGM162 / DOGM163 modules at 3.3 V or 5 V,
 * display control (on/off, cursor, blink, entry mode, shift), contrast, booster, follower
 * and bias, custom characters (CGRAM), double height, ICON RAM and raw instructions.
 *
 * @param connection display connection (no BUSY pin)
 * @param lines 1 (DOGM081, 1 x 8), 2 (DOGM162, 2 x 16) or 3 (DOGM163, 3 x 16)
 * @param columns characters per line, or 0 for the module default (8 or 16)
 * @param supply [SUPPLY_3V3] (booster on) or [SUPPLY_5V]
 * @param contrast contrast C5..C0 0..63, or -1 for the data sheet preset of the module
 * @throws IOException if the bus fails
 * @throws IllegalArgumentException if [lines] is not 1, 2 or 3
 */
open class St7036Full(
    connection: DisplayConn,
    lines: Int = 2,
    columns: Int = 0,
    supply: Int = SUPPLY_3V3,
    contrast: Int = -1,
) : St7036Minimal(connection, preset(lines, columns, supply, contrast)) {

    companion object {
        /** Supply voltage of the module: 3.3 V (booster on) or 5 V (booster off). */
        const val SUPPLY_3V3 = 0
        const val SUPPLY_5V = 1

        // func, bias, power, follower, contrast low per module (1, 2, 3 lines) and supply, data sheet page 7
        private val PRESETS = arrayOf(
            arrayOf(intArrayOf(0x31, 0x14, 0x55, 0x6D, 0x7C), intArrayOf(0x31, 0x1C, 0x51, 0x6A, 0x74)),   // DOGM081
            arrayOf(intArrayOf(0x39, 0x14, 0x55, 0x6D, 0x78), intArrayOf(0x39, 0x1C, 0x52, 0x69, 0x74)),   // DOGM162
            arrayOf(intArrayOf(0x39, 0x15, 0x55, 0x6E, 0x72), intArrayOf(0x39, 0x1D, 0x50, 0x6C, 0x7C)),   // DOGM163
        )

        /** Module configuration array for the [St7036Minimal] constructor. */
        private fun preset(lines: Int, columns: Int, supply: Int, contrast: Int): IntArray {
            require(lines in 1..3) { "lines must be 1, 2 or 3" }
            val p = PRESETS[lines - 1][if (supply == SUPPLY_5V) 1 else 0]
            val func = p[0]
            val bias = p[1]
            var power = p[2]
            val follower = p[3]
            var low = p[4]
            var c = ((power and 3) shl 4) or (low and 0x0F)
            if (contrast >= 0) {
                c = contrast and 0x3F
                power = (power and 3.inv()) or ((c shr 4) and 3)
                low = CMD_CONTRAST or (c and 0x0F)
            }
            val cols = if (columns > 0) columns else if (lines == 1) 8 else 16
            return intArrayOf(lines, cols, (func shr 3) and 1, (power shr 2) and 1, c, follower and 7,
                (bias shr 3) and 1, bias and 1, func, bias, power, follower, low)
        }
    }

    // ---- display control ---------------------------------------------------

    private fun setDisplayBit(mask: Int, on: Boolean) {
        display = if (on) display or mask else display and mask.inv()
        display = (display and 0x07) or CMD_DISPLAY
        instr(display)
    }

    /** Turn the display on or off (the DDRAM content is kept). */
    fun display(on: Boolean) = setDisplayBit(0x04, on)

    /** Show or hide the cursor ([on]) and its blinking block ([blink]). */
    fun cursor(on: Boolean, blink: Boolean = false) {
        setDisplayBit(0x02, on)
        setDisplayBit(0x01, blink)
    }

    /** Set the entry mode: [increment] = cursor moves right (address + 1); [shift] = shift the display on every write. */
    fun setEntryMode(increment: Boolean, shift: Boolean = false) {
        entry = CMD_ENTRY or (if (increment) 2 else 0) or (if (shift) 1 else 0)
        instr(entry)
    }

    /** Shift the cursor one position. */
    fun shiftCursor(right: Boolean) = instr(CMD_SHIFT or (if (right) 0x04 else 0))

    /** Shift the whole display one position (the cursor follows). */
    fun shiftDisplay(right: Boolean) = instr(CMD_SHIFT or 0x08 or (if (right) 0x04 else 0))

    /** Return the cursor and a shifted display to the home position (Return Home, 2 ms). */
    fun returnHome() = home()

    /** Set a raw DDRAM address (for use with display shift). */
    fun setDdramAddress(address: Int) = setDdram(address)

    // ---- contrast and supply -----------------------------------------------

    private fun extBegin() = instr(function(1))

    private fun extEnd() = instr(function(0))

    /** Set the contrast C5..C0 (0..63). */
    fun setContrast(value: Int) {
        contrastBits = value and 0x3F
        extBegin()
        instr(powerByte())
        instr(CMD_CONTRAST or (contrastBits and 0x0F))
        extEnd()
    }

    /** Switch the voltage booster on or off (needed at 3.3 V). */
    fun setBooster(on: Boolean) {
        bon = if (on) 1 else 0
        extBegin()
        instr(powerByte())
        extEnd()
    }

    /** Voltage follower on/off and V0 amplification [ratio] (Rab2..Rab0 0..7: 1 + Rb/Ra = 1, 1.25, 1.5, 1.8, 2, 2.5, 3, 3.75). */
    fun setFollower(on: Boolean, ratio: Int) {
        fon = if (on) 1 else 0
        rab = ratio and 7
        extBegin()
        instr(CMD_FOLLOWER or (fon shl 3) or rab)
        extEnd()
    }

    /** LCD bias: 1/5 (default) or 1/4 ([quarter]); FX follows the line count. */
    fun setBias(quarter: Boolean) {
        this.quarter = quarter
        extBegin()
        instr(CMD_BIAS or (if (quarter) 8 else 0) or fx)
        extEnd()
    }

    // ---- custom characters and special modes -------------------------------

    /** Define a user character (CGRAM) [index] 0..7 from 8 row patterns of 5 bits (bit 4 = leftmost pixel) and restore the DDRAM address. */
    fun createChar(index: Int, rows: IntArray) {
        val saved = addr
        instr(CMD_CGRAM or ((index and 7) shl 3))
        for (i in 0 until 8) data(rows[i] and 0x1F)
        setDdram(saved)
    }

    /**
     * Double-height font (1-line module: 5 x 16 font; 3-line module: top ([upper]) or bottom
     * pair of lines). Ignored on the 2-line module (the combination is forbidden by the data sheet).
     */
    fun setDoubleHeight(on: Boolean, upper: Boolean = true) {
        if (lines == 2) return
        dh = if (on) 1 else 0
        instr(function(if (lines == 1) 0 else 2))
        if (lines == 3) instr(CMD_DHPOS or (if (upper) 8 else 0))
        instr(function(0))
    }

    /** Turn the ICON display on or off (no icons are wired on the DOG modules). */
    fun icon(on: Boolean) {
        ion = if (on) 1 else 0
        extBegin()
        instr(powerByte())
        extEnd()
    }

    /** Write one ICON RAM byte ([address] 0..15). */
    fun writeIcon(address: Int, value: Int) {
        val saved = addr
        extBegin()
        instr(CMD_ICONADDR or (address and 0x0F))
        data(value)
        extEnd()
        setDdram(saved)
    }

    /** Send any instruction byte (RS low) and wait 30 us. */
    fun sendCommand(instruction: Int) = instr(instruction and 0xFF)

    /** Send any data byte (RS high) and wait 30 us. */
    fun sendData(value: Int) = writeChar(value)
}
