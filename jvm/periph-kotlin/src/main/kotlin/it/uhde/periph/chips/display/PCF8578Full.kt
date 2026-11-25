package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.util.BitSet

/**
 * PCF8578 — row/column LCD dot-matrix driver (full driver).
 *
 * Extends [PCF8578Minimal] with pixel read-back from the shadow, fill, line / rectangle / bitmap
 * drawing, display status (blank / normal / all-on / inverse), multiplex rate and row mode,
 * hardware vertical scrolling via the start bank, raw RAM write / read in all three access modes
 * and the device-select command for PCF8579 cascades.
 *
 * @param connection I2C connection bound to the PCF8578 address (100 kHz maximum)
 * @param rows display rows: 8, 16 (default), 24 or 32
 */
class PCF8578Full(connection: Connection, rows: Int = 16) : PCF8578Minimal(connection, rows) {

    companion object {
        const val DISPLAY_BLANK   = PCF8578Minimal.STATUS_BLANK
        const val DISPLAY_NORMAL  = PCF8578Minimal.STATUS_NORMAL
        const val DISPLAY_ALL_ON  = PCF8578Minimal.STATUS_ALL_ON
        const val DISPLAY_INVERSE = PCF8578Minimal.STATUS_INVERSE

        const val ACCESS_CHARACTER    = PCF8578Minimal.ACCESS_CHARACTER
        const val ACCESS_HALF_GRAPHIC = PCF8578Minimal.ACCESS_HALF_GRAPHIC
        const val ACCESS_FULL_GRAPHIC = PCF8578Minimal.ACCESS_FULL_GRAPHIC
    }

    private fun plot(x: Int, y: Int, on: Boolean, dirty: BitSet) {
        if (x < 0 || x >= RAM_COLUMNS - rows || y < 0 || y >= rows) return
        val ramX = rows + x
        val bank = y / 8
        val bit = 1 shl (y % 8)
        val v = shadow[bank][ramX].toInt() and 0xFF
        shadow[bank][ramX] = (if (on) (v or bit) else (v and bit.inv())).toByte()
        dirty.set(bank * RAM_COLUMNS + ramX)
    }

    /** One transaction per run of consecutive changed columns in a bank. */
    private fun flush(dirty: BitSet) {
        for (bank in 0 until RAM_BANKS) {
            var x = 0
            while (x < RAM_COLUMNS) {
                if (!dirty.get(bank * RAM_COLUMNS + x)) {
                    x++
                    continue
                }
                val start = x
                while (x < RAM_COLUMNS && dirty.get(bank * RAM_COLUMNS + x)) x++
                ramWrite(start, bank, shadow[bank].copyOfRange(start, x), ACCESS_CHARACTER)
            }
        }
    }

    /**
     * Return the state of one pixel from the shadow (no bus traffic).
     *
     * @return `true` if the pixel is set; `false` if clear or out of range
     */
    fun getPixel(x: Int, y: Int): Boolean {
        if (x < 0 || x >= RAM_COLUMNS - rows || y < 0 || y >= rows) return false
        return (shadow[y / 8][rows + x].toInt() and (1 shl (y % 8))) != 0
    }

    /**
     * Set every displayed pixel on or off.
     *
     * @param on `true` lights all pixels, `false` clears them
     */
    fun fill(on: Boolean = true) {
        val col = ByteArray(RAM_COLUMNS - rows) { if (on) 0xFF.toByte() else 0 }
        for (bank in 0 until rows / 8) {
            ramWrite(rows, bank, col, ACCESS_CHARACTER)
        }
    }

    /**
     * Draw a Bresenham line, written as one batch of changed bytes.
     *
     * @param on `true` draws, `false` erases
     */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, on: Boolean = true) {
        requireMixed()
        val dirty = BitSet()
        var cx = x0
        var cy = y0
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(cx, cy, on, dirty)
            if (cx == x1 && cy == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; cx += sx }
            if (e2 <= dx) { err += dx; cy += sy }
        }
        flush(dirty)
    }

    /**
     * Draw a rectangle outline or a filled rectangle.
     *
     * @param x left column
     * @param y top row
     * @param w width in pixels
     * @param h height in pixels
     * @param on `true` draws, `false` erases
     * @param fill `true` fills the interior
     */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, on: Boolean = true, fill: Boolean = false) {
        requireMixed()
        if (w <= 0 || h <= 0) return
        val dirty = BitSet()
        for (yy in y until y + h) {
            for (xx in x until x + w) {
                if (fill || yy == y || yy == y + h - 1 || xx == x || xx == x + w - 1) {
                    plot(xx, yy, on, dirty)
                }
            }
        }
        flush(dirty)
    }

    /**
     * Blit a column-major bitmap aligned to a bank boundary.
     *
     * @param x left display column
     * @param y top display row; must be a multiple of 8
     * @param bands one array per 8-pixel-high band from the top, each holding one byte per column
     *   (bit 0 = top row). Content outside the display is clipped
     */
    fun showBitmap(x: Int, y: Int, bands: Array<ByteArray>) {
        require(y >= 0 && y % 8 == 0) { "y must be a non-negative multiple of 8" }
        for (i in bands.indices) {
            val bank = y / 8 + i
            if (bank >= rows / 8) break
            writeColumns(x, bank, bands[i])
        }
    }

    /**
     * Set the display status; RAM contents are preserved.
     *
     * @param status [DISPLAY_BLANK], [DISPLAY_NORMAL], [DISPLAY_ALL_ON] or [DISPLAY_INVERSE]
     */
    fun setDisplay(status: Int) {
        require(status in 0..3) { "status must be in 0..3" }
        this.status = status
        connection.write(byteArrayOf(modeByte(status, false).toByte()))
    }

    /**
     * Change the multiplex rate and the row/mixed mode. In row mode (`mixed = false`) the RAM
     * cannot be accessed; pixel and RAM calls throw [IllegalStateException] until mixed mode is
     * restored.
     *
     * @param rows 8, 16, 24 or 32
     * @param mixed `true` for mixed mode, `false` for row mode
     */
    fun setMode(rows: Int, mixed: Boolean = true) {
        muxCode(rows)
        this.rows = rows
        this.mixed = mixed
        connection.write(byteArrayOf(modeByte(status, false).toByte()))
    }

    /**
     * Select the RAM bank shown at the top of the display (hardware vertical scroll).
     *
     * @param bank 0 .. 3
     */
    fun setStartBank(bank: Int) {
        require(bank in 0..3) { "bank must be in 0..3" }
        topBank = bank
        connection.write(byteArrayOf((CMD_SET_START_BANK or bank).toByte()))
    }

    /**
     * Add [banks] (may be negative) to the start bank, modulo the number of banks in use.
     */
    fun scroll(banks: Int) {
        setStartBank(Math.floorMod(topBank + banks, rows / 8))
    }

    private fun checkRamArgs(x: Int, bank: Int, count: Int, access: Int) {
        require(access in 0..2 && x in 0..39 && bank in 0..3) {
            "x must be in 0..39, bank in 0..3, access in 0..2"
        }
        require(!((access == ACCESS_HALF_GRAPHIC && bank % 2 != 0) || (access == ACCESS_FULL_GRAPHIC && bank != 0))) {
            "half-graphic must start at an even bank, full-graphic at bank 0"
        }
        val perCol = PER_COL[access]
        require(x + (count + perCol - 1) / perCol <= RAM_COLUMNS) { "write would pass column 39" }
    }

    /**
     * Raw RAM write in character, half-graphic or full-graphic order.
     *
     * @param x RAM column, 0 .. 39
     * @param bank RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic)
     * @param data bytes to write
     * @param mode [ACCESS_CHARACTER], [ACCESS_HALF_GRAPHIC] or [ACCESS_FULL_GRAPHIC]
     */
    fun writeRam(x: Int, bank: Int, data: ByteArray, mode: Int = ACCESS_CHARACTER) {
        checkRamArgs(x, bank, data.size, mode)
        if (data.isEmpty()) return
        ramWrite(x, bank, data, mode)
    }

    /**
     * Raw RAM read: address-set commands, repeated start, then [n] bytes.
     *
     * @param x RAM column, 0 .. 39
     * @param bank RAM bank, 0 .. 3
     * @param n number of bytes to read
     * @param mode access mode as for [writeRam]
     * @return the bytes read
     */
    fun readRam(x: Int, bank: Int, n: Int, mode: Int = ACCESS_CHARACTER): ByteArray {
        requireMixed()
        checkRamArgs(x, bank, n, mode)
        val cmds = byteArrayOf(
            (CONT or CMD_DEVICE_SELECT).toByte(),
            ramAccessByte(mode, bank).toByte(),
            (x and 0x3F).toByte(),
        )
        return connection.writeRead(cmds, n)
    }

    /**
     * Load the subaddress counter (for PCF8579 cascades). Subaddress 0 addresses the PCF8578
     * itself; pixel and RAM calls restore it before their own accesses.
     *
     * @param subaddress 0 .. 15
     */
    fun deviceSelect(subaddress: Int) {
        require(subaddress in 0..15) { "subaddress must be in 0..15" }
        connection.write(byteArrayOf((CMD_DEVICE_SELECT or subaddress).toByte()))
    }

    /** Re-run initialisation at the current row count (clears RAM, mixed mode, normal video). */
    fun reset() {
        doInit()
    }
}
