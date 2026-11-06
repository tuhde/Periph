package it.uhde.periph.chips.display

import it.uhde.periph.connection.Connection

/**
 * PCF8578 — row/column LCD dot-matrix driver (minimal driver).
 *
 * Drives a stand-alone dot-matrix LCD (mixed mode: the chip drives both rows and columns) out of
 * the box. With `rows` rows the panel is `40 - rows` columns wide (8x32, 16x24, 24x16 or 32x8).
 * The chip has no register map; this driver streams command bytes and display data with the plain
 * [Connection] `write` / `writeRead` calls. The I2C address is 0x3C (SA0 = VSS) or 0x3D
 * (SA0 = VDD); the chip supports 100 kHz at most.
 *
 * The constructor waits for the internal reset, clears the whole display RAM, then switches the
 * display on (normal video, start bank 0). A shadow copy of the 4 x 40 byte RAM is kept so
 * single-pixel updates need only one byte on the bus. One RAM byte is 8 vertical pixels of one
 * column; bit 0 is the top row of the bank.
 *
 * @param connection I2C connection bound to the PCF8578 address (100 kHz maximum)
 * @param rows display rows: 8, 16 (default), 24 or 32
 */
open class PCF8578Minimal(protected val connection: Connection, rows: Int = 16) {

    companion object {
        const val ADDR_SA0_LOW  = 0x3C
        const val ADDR_SA0_HIGH = 0x3D

        const val CMD_SET_MODE       = 0x40
        const val CMD_DEVICE_SELECT  = 0x60
        const val CMD_RAM_ACCESS     = 0x70
        const val CMD_SET_START_BANK = 0x7C
        const val CONT               = 0x80

        const val STATUS_BLANK   = 0
        const val STATUS_NORMAL  = 1
        const val STATUS_ALL_ON  = 2
        const val STATUS_INVERSE = 3

        const val ACCESS_CHARACTER    = 0
        const val ACCESS_HALF_GRAPHIC = 1
        const val ACCESS_FULL_GRAPHIC = 2

        const val RAM_COLUMNS = 40
        const val RAM_BANKS   = 4
        val PER_COL = intArrayOf(1, 2, 4)

        fun muxCode(rows: Int): Int = when (rows) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 0
            else -> throw IllegalArgumentException("rows must be 8, 16, 24 or 32")
        }
    }

    protected var rows: Int = rows
    protected var mixed: Boolean = true
    protected var status: Int = STATUS_NORMAL
    protected var topBank: Int = 0
    protected val shadow: Array<ByteArray> = Array(RAM_BANKS) { ByteArray(RAM_COLUMNS) }

    init {
        muxCode(rows)
        try {
            Thread.sleep(2) // >= 1 ms for the internal power-on reset
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        doInit()
    }

    /** The set-mode command byte for the given display status. */
    protected fun modeByte(status: Int, cont: Boolean): Int =
        (if (cont) CONT else 0) or CMD_SET_MODE or ((if (mixed) 1 else 0) shl 4) or
            (status shl 2) or muxCode(rows)

    protected fun ramAccessByte(access: Int, bank: Int): Int =
        CONT or CMD_RAM_ACCESS or (access shl 2) or bank

    /** Clear the RAM with one full-graphic write, then switch the display on. */
    protected fun doInit() {
        mixed = true
        topBank = 0
        val buf = ByteArray(5 + RAM_COLUMNS * RAM_BANKS)
        buf[0] = (CONT or CMD_DEVICE_SELECT).toByte()
        buf[1] = modeByte(STATUS_BLANK, true).toByte()
        buf[2] = (CONT or CMD_SET_START_BANK).toByte()
        buf[3] = ramAccessByte(ACCESS_FULL_GRAPHIC, 0).toByte()
        buf[4] = 0x00 // load-X 0 (C = 0); the 160 data bytes stay zero
        connection.write(buf)
        zeroShadow()
        status = STATUS_NORMAL
        connection.write(byteArrayOf(modeByte(STATUS_NORMAL, false).toByte()))
    }

    private fun zeroShadow() {
        for (bank in shadow) bank.fill(0)
    }

    protected fun requireMixed() {
        check(mixed) { "display RAM is only accessible in mixed mode" }
    }

    /** Write data at RAM column [x] / bank [bank] and mirror it in the shadow. */
    protected fun ramWrite(x: Int, bank: Int, data: ByteArray, access: Int) {
        requireMixed()
        val buf = ByteArray(3 + data.size)
        buf[0] = (CONT or CMD_DEVICE_SELECT).toByte()
        buf[1] = ramAccessByte(access, bank).toByte()
        buf[2] = (x and 0x3F).toByte()
        data.copyInto(buf, 3)
        connection.write(buf)
        val perCol = PER_COL[access]
        for (i in data.indices) {
            shadow[bank + (i % perCol)][x + i / perCol] = data[i]
        }
    }

    /** Zero the whole display RAM (4 banks x 40 columns) and the shadow. */
    fun clear() {
        requireMixed()
        val buf = ByteArray(3 + RAM_COLUMNS * RAM_BANKS)
        buf[0] = (CONT or CMD_DEVICE_SELECT).toByte()
        buf[1] = ramAccessByte(ACCESS_FULL_GRAPHIC, 0).toByte()
        buf[2] = 0x00
        connection.write(buf)
        zeroShadow()
    }

    /**
     * Set or clear one pixel. Out-of-range coordinates are ignored. Writes exactly one RAM byte.
     *
     * @param x display column, 0 .. (40 - rows - 1)
     * @param y display row, 0 .. rows - 1
     * @param on `true` lights the pixel, `false` clears it
     */
    fun setPixel(x: Int, y: Int, on: Boolean = true) {
        requireMixed()
        if (x < 0 || x >= RAM_COLUMNS - rows || y < 0 || y >= rows) return
        val ramX = rows + x
        val bank = y / 8
        val bit = 1 shl (y % 8)
        var v = shadow[bank][ramX].toInt() and 0xFF
        v = if (on) (v or bit) else (v and bit.inv() and 0xFF)
        ramWrite(ramX, bank, byteArrayOf(v.toByte()), ACCESS_CHARACTER)
    }

    /**
     * Write consecutive column bytes in character access mode.
     *
     * @param x first display column, 0 .. (40 - rows - 1)
     * @param bank display bank (8-row band), 0 .. rows/8 - 1
     * @param data column bytes; bit 0 is the top row of the bank. Truncated at the last display column
     */
    fun writeColumns(x: Int, bank: Int, data: ByteArray) {
        require(bank >= 0 && bank < rows / 8) { "bank out of range for the configured rows" }
        val columns = RAM_COLUMNS - rows
        if (x < 0 || x >= columns) return
        val n = minOf(data.size, columns - x)
        if (n == 0) return
        ramWrite(rows + x, bank, data.copyOf(n), ACCESS_CHARACTER)
    }
}
