package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * ST7036 character LCD controller — minimal driver (EA DOGM162W-A, 2 x 16, 3.3 V).
 *
 * <p>Drives the module over 4-wire serial: SI, SCL, CSB, RS (C/D) and RESET. The
 * HD44780-compatible instruction set has an extension table (selected by Function
 * Set IS[2:1]) for bias, booster, follower and contrast; the DOG presets from the
 * data sheet (page 7) are sent at initialisation.
 *
 * <p>The connection is a {@link DisplayConn} with no BUSY pin (see {@code
 * specs/transport_display.md}). The serial mode is write-only and has no busy flag,
 * so every instruction is followed by a fixed wait (30 us, 2 ms after Clear /
 * Return Home). Every instruction is a single byte sent with RS (D/C#) low; every
 * data byte gets its own CS window, followed by the 30 us gap ({@code delayUs}).
 * SPI mode 0 at about 1 MHz or less (SCL cycle &ge; 200 ns at &le; 4.5 V).
 */
@CompileStatic
class St7036Minimal {

    static final int COLUMNS = 16
    static final int ROWS = 2

    protected static final int CMD_CLEAR = 0x01
    protected static final int CMD_HOME = 0x02
    protected static final int CMD_ENTRY = 0x04
    protected static final int CMD_DISPLAY = 0x08
    protected static final int CMD_SHIFT = 0x10
    protected static final int CMD_FUNCTION = 0x20
    protected static final int CMD_CGRAM = 0x40
    protected static final int CMD_DDRAM = 0x80
    protected static final int CMD_BIAS = 0x14       // table 1: 0x14 | BS << 3 | FX
    protected static final int CMD_ICONADDR = 0x40   // table 1
    protected static final int CMD_POWER = 0x50      // table 1: | Ion << 3 | Bon << 2 | C5 << 1 | C4
    protected static final int CMD_FOLLOWER = 0x60   // table 1: | Fon << 3 | Rab2..0
    protected static final int CMD_CONTRAST = 0x70   // table 1: | C3..C0
    protected static final int CMD_DHPOS = 0x10      // table 2: | UD << 3

    /** Module configuration: lines, columns, n, bon, contrast, rab, quarter, fx, func, bias, power, follower, low. */
    protected static final int[] DOGM162_3V3 = [ROWS, COLUMNS, 1, 1, 0x18, 5, 0, 0, 0x39, 0x14, 0x55, 0x6D, 0x78] as int[]

    protected final DisplayConn connection
    protected int lines
    protected int columns
    protected int n           // Function Set N
    protected int dh          // Function Set DH
    protected int bon         // booster on
    protected int ion         // ICON display on
    protected int contrast    // C5..C0
    protected int fon = 1     // follower on
    protected int rab         // Rab2..0
    protected boolean quarter // bias 1/4
    protected int fx          // bias FX bit
    protected int display = 0x0C  // Display ON/OFF byte
    protected int entry = 0x06    // Entry Mode byte
    protected int addr        // cached DDRAM address

    /**
     * Construct the driver and run the DOGM162 3.3 V initialisation sequence:
     * hardware reset, 40 ms, Function Set {@code 0x39}, Bias {@code 0x14}, Contrast
     * {@code 0x78}, Power {@code 0x55} (booster on), Follower {@code 0x6D}, 200 ms,
     * Function Set {@code 0x38}, Display on ({@code 0x0C}), Clear, Entry Mode
     * {@code 0x06}. The display is cleared and the cursor is at the top left.
     *
     * @param connection display connection (no BUSY pin)
     */
    St7036Minimal(DisplayConn connection) {
        this(connection, DOGM162_3V3)
    }

    /** Constructor for the full driver: runs the initialisation of a module configuration. */
    protected St7036Minimal(DisplayConn connection, int[] cfg) {
        this.connection = connection
        this.lines = cfg[0]
        this.columns = cfg[1]
        this.n = cfg[2]
        this.bon = cfg[3]
        this.contrast = cfg[4]
        this.rab = cfg[5]
        this.quarter = cfg[6] == 1
        this.fx = cfg[7]
        initSequence(cfg[8], cfg[9], cfg[10], cfg[11], cfg[12])
    }

    /** @return characters per line */
    int columns() { columns }

    /** @return number of lines */
    int rows() { lines }

    // ---- internals ---------------------------------------------------------

    /** Send one instruction byte (RS low) and wait {@code waitUs} microseconds. */
    protected void instr(int b, int waitUs) {
        connection.sendCommand(b, new byte[0])
        if (waitUs >= 1000) connection.delay((long) ((waitUs + 999) / 1000))
        else connection.delayUs((long) waitUs)
    }

    protected void instr(int b) { instr(b, 30) }

    /** Send one data byte (RS high) in its own CS window, then wait 30 us. */
    protected void data(int b) {
        connection.sendData([(byte) b] as byte[])
        connection.delayUs(30L)
    }

    protected int rowStart(int row) { lines == 3 ? row * 0x10 : row * 0x40 }

    protected void setDdram(int a) {
        addr = a & 0x7F
        instr(CMD_DDRAM | addr)
    }

    /** Function Set byte for the cached N / DH and instruction table {@code table} (0..2). */
    protected int function(int table) {
        CMD_FUNCTION | 0x10 | (n << 3) | (dh << 2) | (table & 3)
    }

    /** Power/ICON/Contrast high byte from the cached Ion, Bon and contrast. */
    protected int powerByte() {
        CMD_POWER | (ion << 3) | (bon << 2) | ((contrast >> 4) & 3)
    }

    /** Hardware reset, power-up wait and the instruction sequence of a preset. */
    private void initSequence(int func, int bias, int power, int follower, int low) {
        connection.reset()
        connection.delay(40L)
        instr(func)
        instr(bias)
        instr(low)
        instr(power)
        instr(follower)
        connection.delay(200L)              // booster / V0 settle
        instr(func & ~0x01)                 // back to instruction table 0
        instr(display)
        instr(CMD_CLEAR, 2000)
        instr(entry)
        addr = 0
    }

    // ---- public API --------------------------------------------------------

    /** Clear the display (Clear Display, 2 ms); the cursor goes to the top left. */
    void clear() {
        instr(CMD_CLEAR, 2000)
        addr = 0
    }

    /** Return the cursor (and a shifted display) to the home position (Return Home, 2 ms). */
    void home() {
        instr(CMD_HOME, 2000)
        addr = 0
    }

    /**
     * Move the cursor to (col, row). Out-of-range positions are ignored.
     *
     * @param col column, 0 .. columns - 1
     * @param row row, 0 .. rows - 1
     */
    void setCursor(int col, int row) {
        if (col < 0 || row < 0 || col >= columns || row >= lines) return
        setDdram(rowStart(row) + col)
    }

    /**
     * Write text at the cursor, one data byte per transfer with a 30 us gap. ASCII
     * {@code 0x20 .. 0x7D} is written as is; any other character becomes {@code ?}.
     * The cursor does not wrap to the next line.
     *
     * @param text the text; {@code null} is ignored
     */
    void write(String text) {
        if (text == null) return
        for (int i = 0; i < text.length(); i++) {
            int c = (int) text.charAt(i)
            writeChar(c >= 0x20 && c <= 0x7D ? c : (int) ('?' as char))
        }
    }

    /**
     * Write one raw character code (including the CGRAM codes 0..7) at the cursor.
     *
     * @param code character code 0..255
     */
    void writeChar(int code) {
        data(code)
        addr = (addr + 1) & 0x7F
    }

    /**
     * Move the cursor to (col, row) and write text.
     *
     * @param col  column
     * @param row  row
     * @param text the text
     */
    void printAt(int col, int row, String text) {
        setCursor(col, row)
        write(text)
    }
}
