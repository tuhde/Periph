package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * PCF8578 — row/column LCD dot-matrix driver (minimal driver).
 *
 * <p>Drives a stand-alone dot-matrix LCD (mixed mode: the chip drives both rows and
 * columns) out of the box. With {@code rows} rows the panel is {@code 40 - rows} columns
 * wide (8x32, 16x24, 24x16 or 32x8). The chip has no register map; this driver streams
 * command bytes and display data with the plain {@link Connection} {@code write} /
 * {@code writeRead} calls. The I2C address is 0x3C (SA0 = VSS) or 0x3D (SA0 = VDD); the chip
 * supports 100 kHz at most.
 *
 * <p>The constructor waits for the internal reset, clears the whole display RAM, then
 * switches the display on (normal video, start bank 0). A shadow copy of the 4 x 40 byte RAM
 * is kept so single-pixel updates need only one byte on the bus. One RAM byte is 8 vertical
 * pixels of one column; bit 0 is the top row of the bank.
 */
@CompileStatic
class PCF8578Minimal {

    static final int ADDR_SA0_LOW  = 0x3C
    static final int ADDR_SA0_HIGH = 0x3D

    static final int CMD_SET_MODE       = 0x40
    static final int CMD_DEVICE_SELECT  = 0x60
    static final int CMD_RAM_ACCESS     = 0x70
    static final int CMD_SET_START_BANK = 0x7C
    static final int CONT               = 0x80

    static final int STATUS_BLANK   = 0
    static final int STATUS_NORMAL  = 1
    static final int STATUS_ALL_ON  = 2
    static final int STATUS_INVERSE = 3

    static final int ACCESS_CHARACTER    = 0
    static final int ACCESS_HALF_GRAPHIC = 1
    static final int ACCESS_FULL_GRAPHIC = 2

    static final int RAM_COLUMNS = 40
    static final int RAM_BANKS   = 4
    static final int[] PER_COL   = [1, 2, 4] as int[]

    protected final Connection connection
    protected int rowCount
    protected boolean mixedMode = true
    protected int displayStatus = STATUS_NORMAL
    protected int topBank = 0
    protected final byte[][] shadow = new byte[RAM_BANKS][RAM_COLUMNS]

    /**
     * Construct the driver with 16 rows (16 x 24 panel) and initialise the chip.
     *
     * @param connection I2C connection bound to the PCF8578 address (100 kHz maximum)
     */
    PCF8578Minimal(Connection connection) {
        this(connection, 16)
    }

    /**
     * Construct the driver and initialise the chip: wait for the power-on reset, clear the RAM,
     * switch the display on.
     *
     * @param connection I2C connection bound to the PCF8578 address (100 kHz maximum)
     * @param rows       display rows: 8, 16, 24 or 32
     */
    PCF8578Minimal(Connection connection, int rows) {
        muxCode(rows)
        this.connection = connection
        this.rowCount = rows
        try {
            Thread.sleep(2L) // >= 1 ms for the internal power-on reset
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
        doInit()
    }

    protected static int muxCode(int rows) {
        switch (rows) {
            case 8:  return 1
            case 16: return 2
            case 24: return 3
            case 32: return 0
            default: throw new IllegalArgumentException('rows must be 8, 16, 24 or 32')
        }
    }

    /** @return the set-mode command byte for the given display status */
    protected int modeByte(int status, boolean cont) {
        (cont ? CONT : 0) | CMD_SET_MODE | ((mixedMode ? 1 : 0) << 4) | (status << 2) | muxCode(rowCount)
    }

    protected static int ramAccessByte(int access, int bank) {
        CONT | CMD_RAM_ACCESS | (access << 2) | bank
    }

    /** Clear the RAM with one full-graphic write, then switch the display on. */
    protected void doInit() {
        mixedMode = true
        topBank = 0
        byte[] buf = new byte[5 + RAM_COLUMNS * RAM_BANKS]
        buf[0] = (byte) (CONT | CMD_DEVICE_SELECT)
        buf[1] = (byte) modeByte(STATUS_BLANK, true)
        buf[2] = (byte) (CONT | CMD_SET_START_BANK)
        buf[3] = (byte) ramAccessByte(ACCESS_FULL_GRAPHIC, 0)
        buf[4] = (byte) 0x00 // load-X 0 (C = 0); the 160 data bytes stay zero
        connection.write(buf)
        zeroShadow()
        displayStatus = STATUS_NORMAL
        connection.write([(byte) modeByte(STATUS_NORMAL, false)] as byte[])
    }

    private void zeroShadow() {
        for (byte[] bank : shadow) {
            Arrays.fill(bank, (byte) 0)
        }
    }

    protected void requireMixed() {
        if (!mixedMode) {
            throw new IllegalStateException('display RAM is only accessible in mixed mode')
        }
    }

    /** Write data at RAM column {@code x} / bank {@code bank} and mirror it in the shadow. */
    protected void ramWrite(int x, int bank, byte[] data, int access) {
        requireMixed()
        byte[] buf = new byte[3 + data.length]
        buf[0] = (byte) (CONT | CMD_DEVICE_SELECT)
        buf[1] = (byte) ramAccessByte(access, bank)
        buf[2] = (byte) (x & 0x3F)
        System.arraycopy(data, 0, buf, 3, data.length)
        connection.write(buf)
        int perCol = PER_COL[access]
        for (int i = 0; i < data.length; i++) {
            shadow[bank + (i % perCol)][x + ((int) (i / perCol))] = data[i]
        }
    }

    /** Zero the whole display RAM (4 banks x 40 columns) and the shadow. */
    void clear() {
        requireMixed()
        byte[] buf = new byte[3 + RAM_COLUMNS * RAM_BANKS]
        buf[0] = (byte) (CONT | CMD_DEVICE_SELECT)
        buf[1] = (byte) ramAccessByte(ACCESS_FULL_GRAPHIC, 0)
        buf[2] = (byte) 0x00
        connection.write(buf)
        zeroShadow()
    }

    /**
     * Set or clear one pixel. Out-of-range coordinates are ignored. Writes exactly one RAM byte.
     *
     * @param x  display column, 0 .. (40 - rows - 1)
     * @param y  display row, 0 .. rows - 1
     * @param on {@code true} lights the pixel, {@code false} clears it
     */
    void setPixel(int x, int y, boolean on) {
        requireMixed()
        if (x < 0 || x >= RAM_COLUMNS - rowCount || y < 0 || y >= rowCount) return
        int ramX = rowCount + x
        int bank = (int) (y / 8)
        int bit = 1 << (y % 8)
        int v = shadow[bank][ramX] & 0xFF
        v = on ? (v | bit) : (v & ~bit & 0xFF)
        ramWrite(ramX, bank, [(byte) v] as byte[], ACCESS_CHARACTER)
    }

    /**
     * Write consecutive column bytes in character access mode.
     *
     * @param x    first display column, 0 .. (40 - rows - 1)
     * @param bank display bank (8-row band), 0 .. rows/8 - 1
     * @param data column bytes; bit 0 is the top row of the bank. Truncated at the last display column
     */
    void writeColumns(int x, int bank, byte[] data) {
        if (bank < 0 || bank >= (int) (rowCount / 8)) {
            throw new IllegalArgumentException('bank out of range for the configured rows')
        }
        int columns = RAM_COLUMNS - rowCount
        if (x < 0 || x >= columns) return
        int n = Math.min(data.length, columns - x)
        if (n == 0) return
        ramWrite(rowCount + x, bank, Arrays.copyOf(data, n), ACCESS_CHARACTER)
    }
}
