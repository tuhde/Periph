package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException

/**
 * ST7036 character LCD controller — minimal driver (EA DOGM162W-A, 2 x 16, 3.3 V).
 *
 * Drives the module over 4-wire serial: SI, SCL, CSB, RS (C/D) and RESET. The
 * HD44780-compatible instruction set has an extension table (selected by Function
 * Set IS[2:1]) for bias, booster, follower and contrast; the DOG presets from the
 * data sheet (page 7) are sent at initialisation.
 *
 * The connection is a [DisplayConn] with no BUSY pin (see `specs/transport_display.md`).
 * The serial mode is write-only and has no busy flag, so every instruction is followed
 * by a fixed wait (30 us, 2 ms after Clear / Return Home). Every instruction is a single
 * byte sent with RS (D/C#) low; every data byte gets its own CS window, followed by the
 * 30 us gap (`delayUs`). SPI mode 0 at about 1 MHz or less (SCL cycle >= 200 ns at <= 4.5 V).
 *
 * The constructor runs the DOGM162 3.3 V initialisation sequence: hardware reset, 40 ms,
 * Function Set `0x39`, Bias `0x14`, Contrast `0x78`, Power `0x55` (booster on), Follower
 * `0x6D`, 200 ms, Function Set `0x38`, Display on (`0x0C`), Clear, Entry Mode `0x06`.
 *
 * @param connection display connection (no BUSY pin)
 * @throws IOException if the bus fails
 */
open class St7036Minimal protected constructor(protected val connection: DisplayConn, cfg: IntArray) {

    constructor(connection: DisplayConn) : this(connection, DOGM162_3V3)

    companion object {
        const val COLUMNS = 16
        const val ROWS = 2

        const val CMD_CLEAR = 0x01
        const val CMD_HOME = 0x02
        const val CMD_ENTRY = 0x04
        const val CMD_DISPLAY = 0x08
        const val CMD_SHIFT = 0x10
        const val CMD_FUNCTION = 0x20
        const val CMD_CGRAM = 0x40
        const val CMD_DDRAM = 0x80
        const val CMD_BIAS = 0x14       // table 1: 0x14 | BS << 3 | FX
        const val CMD_ICONADDR = 0x40   // table 1
        const val CMD_POWER = 0x50      // table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
        const val CMD_FOLLOWER = 0x60   // table 1: | Fon << 3 | Rab2..0
        const val CMD_CONTRAST = 0x70   // table 1: | C3..C0
        const val CMD_DHPOS = 0x10      // table 2: | UD << 3

        /** Module configuration: lines, columns, n, bon, contrast, rab, quarter, fx, func, bias, power, follower, low. */
        private val DOGM162_3V3 = intArrayOf(ROWS, COLUMNS, 1, 1, 0x18, 5, 0, 0, 0x39, 0x14, 0x55, 0x6D, 0x78)
    }

    protected var lines = cfg[0]
    protected var columns = cfg[1]
    protected var n = cfg[2]            // Function Set N
    protected var dh = 0                // Function Set DH
    protected var bon = cfg[3]          // booster on
    protected var ion = 0               // ICON display on
    protected var contrastBits = cfg[4]     // C5..C0
    protected var fon = 1               // follower on
    protected var rab = cfg[5]          // Rab2..0
    protected var quarter = cfg[6] == 1 // bias 1/4
    protected var fx = cfg[7]           // bias FX bit
    protected var display = 0x0C        // Display ON/OFF byte
    protected var entry = 0x06          // Entry Mode byte
    protected var addr = 0              // cached DDRAM address

    init {
        initSequence(cfg[8], cfg[9], cfg[10], cfg[11], cfg[12])
    }

    /** Characters per line. */
    fun columns(): Int = columns

    /** Number of lines. */
    fun rows(): Int = lines

    // ---- internals ---------------------------------------------------------

    /** Send one instruction byte (RS low) and wait [waitUs] microseconds. */
    protected fun instr(b: Int, waitUs: Int = 30) {
        connection.sendCommand(b, ByteArray(0))
        if (waitUs >= 1000) connection.delay(((waitUs + 999) / 1000).toLong())
        else connection.delayUs(waitUs.toLong())
    }

    /** Send one data byte (RS high) in its own CS window, then wait 30 us. */
    protected fun data(b: Int) {
        connection.sendData(byteArrayOf(b.toByte()))
        connection.delayUs(30)
    }

    protected fun rowStart(row: Int): Int = if (lines == 3) row * 0x10 else row * 0x40

    protected fun setDdram(a: Int) {
        addr = a and 0x7F
        instr(CMD_DDRAM or addr)
    }

    /** Function Set byte for the cached N / DH and instruction table [table] (0..2). */
    protected fun function(table: Int): Int = CMD_FUNCTION or 0x10 or (n shl 3) or (dh shl 2) or (table and 3)

    /** Power/ICON/Contrast high byte from the cached Ion, Bon and contrast. */
    protected fun powerByte(): Int = CMD_POWER or (ion shl 3) or (bon shl 2) or ((contrastBits shr 4) and 3)

    /** Hardware reset, power-up wait and the instruction sequence of a preset. */
    private fun initSequence(func: Int, bias: Int, power: Int, follower: Int, low: Int) {
        connection.reset()
        connection.delay(40)
        instr(func)
        instr(bias)
        instr(low)
        instr(power)
        instr(follower)
        connection.delay(200)               // booster / V0 settle
        instr(func and 0x01.inv())          // back to instruction table 0
        instr(display)
        instr(CMD_CLEAR, 2000)
        instr(entry)
        addr = 0
    }

    // ---- public API --------------------------------------------------------

    /** Clear the display (Clear Display, 2 ms); the cursor goes to the top left. */
    fun clear() {
        instr(CMD_CLEAR, 2000)
        addr = 0
    }

    /** Return the cursor (and a shifted display) to the home position (Return Home, 2 ms). */
    fun home() {
        instr(CMD_HOME, 2000)
        addr = 0
    }

    /** Move the cursor to ([col], [row]). Out-of-range positions are ignored. */
    fun setCursor(col: Int, row: Int) {
        if (col < 0 || row < 0 || col >= columns || row >= lines) return
        setDdram(rowStart(row) + col)
    }

    /**
     * Write text at the cursor, one data byte per transfer with a 30 us gap. ASCII `0x20 .. 0x7D`
     * is written as is; any other character becomes `?`. The cursor does not wrap to the next line.
     */
    fun write(text: String?) {
        if (text == null) return
        for (ch in text) {
            val c = ch.code
            writeChar(if (c in 0x20..0x7D) c else '?'.code)
        }
    }

    /** Write one raw character code (including the CGRAM codes 0..7) at the cursor. */
    fun writeChar(code: Int) {
        data(code)
        addr = (addr + 1) and 0x7F
    }

    /** Move the cursor to ([col], [row]) and write [text]. */
    fun printAt(col: Int, row: Int, text: String) {
        setCursor(col, row)
        write(text)
    }
}
