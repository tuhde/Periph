package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection
import java.util.BitSet

/**
 * PCF8579 — LCD column driver (full driver).
 *
 * Extends [PCF8579Minimal] with pixel read-back from the shadow, fill, line / rectangle / bitmap
 * drawing, display status (blank / normal / all-on / inverse), multiplex rate and PCF8578 mode
 * bit, hardware vertical scrolling via the start bank, and raw RAM write / read in all three
 * access modes including transfers that run on into the following cascaded devices.
 *
 * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
 * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
 * @param rows display rows: 8, 16 (default), 24 or 32
 * @param mixed `true` if the PCF8578 in the system runs in mixed mode
 */
class PCF8579Full(
    connection: Connection,
    subaddress: Int = 0,
    rows: Int = 16,
    mixed: Boolean = false,
) : PCF8579Minimal(connection, subaddress, rows, mixed) {

    companion object {
        const val DISPLAY_BLANK   = PCF8579Minimal.STATUS_BLANK
        const val DISPLAY_NORMAL  = PCF8579Minimal.STATUS_NORMAL
        const val DISPLAY_ALL_ON  = PCF8579Minimal.STATUS_ALL_ON
        const val DISPLAY_INVERSE = PCF8579Minimal.STATUS_INVERSE

        const val ACCESS_CHARACTER    = PCF8579Minimal.ACCESS_CHARACTER
        const val ACCESS_HALF_GRAPHIC = PCF8579Minimal.ACCESS_HALF_GRAPHIC
        const val ACCESS_FULL_GRAPHIC = PCF8579Minimal.ACCESS_FULL_GRAPHIC
    }

    private fun plot(x: Int, y: Int, on: Boolean, dirty: BitSet) {
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= rows) return
        val bank = y / 8
        val bit = 1 shl (y % 8)
        val v = shadow[bank][x].toInt() and 0xFF
        shadow[bank][x] = (if (on) (v or bit) else (v and bit.inv())).toByte()
        dirty.set(bank * RAM_COLUMNS + x)
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
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= rows) return false
        return (shadow[y / 8][x].toInt() and (1 shl (y % 8))) != 0
    }

    /**
     * Set every displayed pixel (rows x 40) on or off.
     *
     * @param on `true` lights all pixels, `false` clears them
     */
    fun fill(on: Boolean = true) {
        val col = ByteArray(RAM_COLUMNS) { if (on) 0xFF.toByte() else 0 }
        for (bank in 0 until rows / 8) {
            ramWrite(0, bank, col, ACCESS_CHARACTER)
        }
    }

    /**
     * Draw a Bresenham line, written as one batch of changed bytes.
     *
     * @param on `true` draws, `false` erases
     */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, on: Boolean = true) {
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
     * Blit 8-pixel-high column bytes aligned to a bank boundary.
     *
     * @param x left column
     * @param y top row; must be a non-negative multiple of 8
     * @param columns one byte per column (bit 0 = top row); clipped at column 39
     */
    fun showBitmap(x: Int, y: Int, columns: ByteArray) {
        require(y >= 0 && y % 8 == 0) { "y must be a non-negative multiple of 8" }
        writeColumns(x, y / 8, columns)
    }

    /**
     * Set the display status; RAM contents are preserved. Affects all devices on the address.
     *
     * @param status [DISPLAY_BLANK], [DISPLAY_NORMAL], [DISPLAY_ALL_ON] or [DISPLAY_INVERSE]
     */
    fun setDisplay(status: Int) {
        require(status in 0..3) { "status must be in 0..3" }
        this.status = status
        connection.write(byteArrayOf(modeByte(status, false).toByte()))
    }

    /**
     * Change the multiplex rate and the PCF8578 mode bit T (affects all devices on the address).
     *
     * @param rows 8, 16, 24 or 32
     * @param mixed `true` if the PCF8578 runs in mixed mode
     */
    fun setMode(rows: Int, mixed: Boolean = false) {
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

    private fun checkRamArgs(x: Int, bank: Int, count: Int, access: Int, cascade: Boolean = false) {
        require(access in 0..2 && x in 0..39 && bank in 0..3) {
            "x must be in 0..39, bank in 0..3, access in 0..2"
        }
        require(!((access == ACCESS_HALF_GRAPHIC && bank % 2 != 0) || (access == ACCESS_FULL_GRAPHIC && bank != 0))) {
            "half-graphic must start at an even bank, full-graphic at bank 0"
        }
        val perCol = PER_COL[access]
        require(cascade || x + (count + perCol - 1) / perCol <= RAM_COLUMNS) { "write would pass column 39" }
    }

    /**
     * Raw RAM write of this device in character, half-graphic or full-graphic order.
     *
     * @param x RAM column, 0 .. 39
     * @param bank RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic)
     * @param data bytes to write; must not pass column 39
     * @param mode [ACCESS_CHARACTER], [ACCESS_HALF_GRAPHIC] or [ACCESS_FULL_GRAPHIC]
     */
    fun writeRam(x: Int, bank: Int, data: ByteArray, mode: Int = ACCESS_CHARACTER) {
        checkRamArgs(x, bank, data.size, mode)
        if (data.isEmpty()) return
        ramWrite(x, bank, data, mode)
    }

    /**
     * Write from column 0, bank 0 of this device and run on into the following subaddresses (the
     * chip advances its subaddress counter after column 39). The shadow only tracks this device's
     * first 40 columns.
     *
     * @param data bytes to write
     * @param mode [ACCESS_CHARACTER], [ACCESS_HALF_GRAPHIC] or [ACCESS_FULL_GRAPHIC] (default)
     */
    fun writeRamCascade(data: ByteArray, mode: Int = ACCESS_FULL_GRAPHIC) {
        checkRamArgs(0, 0, data.size, mode, cascade = true)
        if (data.isEmpty()) return
        ramWrite(0, 0, data, mode)
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
        checkRamArgs(x, bank, n, mode)
        val cmds = byteArrayOf(
            selectByte().toByte(),
            ramAccessByte(mode, bank).toByte(),
            (x and 0x3F).toByte(),
        )
        return connection.writeRead(cmds, n)
    }

    /** Re-run initialisation (clears this device's RAM, normal video, start bank 0). */
    fun reset() {
        doInit()
    }
}
