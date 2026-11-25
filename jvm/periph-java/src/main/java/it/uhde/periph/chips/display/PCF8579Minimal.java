package it.uhde.periph.chips.display;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * PCF8579 — LCD column driver (minimal driver).
 *
 * <p>The PCF8579 is the column-driver companion of the PCF8578 and cannot run alone: the clock,
 * frame sync and row waveforms come from a PCF8578 (or an equivalent source). This driver writes
 * the display RAM of one PCF8579 (4 banks x 40 columns, X = n drives output Cn). The chip has no
 * register map; this driver streams command bytes and display data with the plain
 * {@link Connection} {@code write} / {@code writeRead} calls. The I2C address is 0x3C
 * (SA0 = VSS) or 0x3D (SA0 = VDD); the chip supports 100 kHz at most.
 *
 * <p>The constructor waits for the internal reset, clears this device's RAM, then switches the
 * display on (normal video, start bank 0). If the subaddress pins A3:A0 do not match, the data
 * bytes are not acknowledged and the connection reports an {@link IOException}. A shadow copy of
 * the 4 x 40 byte RAM is kept so single-pixel updates need only one byte on the bus. One RAM byte
 * is 8 vertical pixels of one column; bit 0 is the top row of the bank.
 *
 * <p>{@code device-select}, {@code set-mode} and {@code set-start-bank} reach every device on the
 * I2C address, so all drivers on one bus must use the same {@code rows} / {@code mixed} settings.
 */
public class PCF8579Minimal {

    public static final int ADDR_SA0_LOW  = 0x3C;
    public static final int ADDR_SA0_HIGH = 0x3D;

    protected static final int CMD_SET_MODE       = 0x40;
    protected static final int CMD_DEVICE_SELECT  = 0x60;
    protected static final int CMD_RAM_ACCESS     = 0x70;
    protected static final int CMD_SET_START_BANK = 0x7C;
    protected static final int CONT               = 0x80;

    protected static final int STATUS_BLANK   = 0;
    protected static final int STATUS_NORMAL  = 1;
    protected static final int STATUS_ALL_ON  = 2;
    protected static final int STATUS_INVERSE = 3;

    public static final int ACCESS_CHARACTER    = 0;
    public static final int ACCESS_HALF_GRAPHIC = 1;
    public static final int ACCESS_FULL_GRAPHIC = 2;

    protected static final int RAM_COLUMNS = 40;
    protected static final int RAM_BANKS   = 4;
    protected static final int[] PER_COL   = {1, 2, 4};

    protected final Connection connection;
    protected final int subaddress;
    protected int rows;
    protected boolean mixed;
    protected int status = STATUS_NORMAL;
    protected int startBank = 0;
    protected final byte[][] shadow = new byte[RAM_BANKS][RAM_COLUMNS];

    /**
     * Construct the driver with subaddress 0, 16 rows and the PCF8578 in row mode.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @throws IOException on I2C error
     */
    public PCF8579Minimal(Connection connection) throws IOException {
        this(connection, 0, 16, false);
    }

    /**
     * Construct the driver with 16 rows and the PCF8578 in row mode.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
     * @throws IOException on I2C error
     */
    public PCF8579Minimal(Connection connection, int subaddress) throws IOException {
        this(connection, subaddress, 16, false);
    }

    /**
     * Construct the driver with the PCF8578 in row mode.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
     * @param rows       display rows: 8, 16, 24 or 32
     * @throws IOException on I2C error
     */
    public PCF8579Minimal(Connection connection, int subaddress, int rows) throws IOException {
        this(connection, subaddress, rows, false);
    }

    /**
     * Construct the driver and initialise the chip: wait for the power-on reset, clear the RAM,
     * switch the display on.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
     * @param rows       display rows: 8, 16, 24 or 32
     * @param mixed      {@code true} if the PCF8578 in the system runs in mixed mode (T bit)
     * @throws IOException              on I2C error
     * @throws IllegalArgumentException if rows or subaddress is out of range
     */
    public PCF8579Minimal(Connection connection, int subaddress, int rows, boolean mixed) throws IOException {
        muxCode(rows);
        if (subaddress < 0 || subaddress > 15) {
            throw new IllegalArgumentException("subaddress must be in 0..15");
        }
        this.connection = connection;
        this.subaddress = subaddress;
        this.rows = rows;
        this.mixed = mixed;
        init();
    }

    private static int muxCode(int rows) {
        switch (rows) {
            case 8:  return 1;
            case 16: return 2;
            case 24: return 3;
            case 32: return 0;
            default: throw new IllegalArgumentException("rows must be 8, 16, 24 or 32");
        }
    }

    /** @return the set-mode command byte for the given display status */
    protected int modeByte(int status, boolean cont) {
        return (cont ? CONT : 0) | CMD_SET_MODE | ((mixed ? 1 : 0) << 4) | (status << 2) | muxCode(rows);
    }

    protected int selectByte() {
        return CONT | CMD_DEVICE_SELECT | subaddress;
    }

    protected static int ramAccessByte(int access, int bank) {
        return CONT | CMD_RAM_ACCESS | (access << 2) | bank;
    }

    /** Wait for the power-on reset, clear the RAM with one full-graphic write, switch the display on. */
    protected void init() throws IOException {
        try {
            Thread.sleep(2); // >= 1 ms for the internal power-on reset
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        doInit();
    }

    protected void doInit() throws IOException {
        startBank = 0;
        byte[] buf = new byte[5 + RAM_COLUMNS * RAM_BANKS];
        buf[0] = (byte) selectByte();
        buf[1] = (byte) modeByte(STATUS_BLANK, true);
        buf[2] = (byte) (CONT | CMD_SET_START_BANK);
        buf[3] = (byte) ramAccessByte(ACCESS_FULL_GRAPHIC, 0);
        buf[4] = 0x00; // load-X 0 (C = 0); the 160 data bytes stay zero
        connection.write(buf);
        zeroShadow();
        status = STATUS_NORMAL;
        connection.write(new byte[]{(byte) modeByte(STATUS_NORMAL, false)});
    }

    private void zeroShadow() {
        for (byte[] bank : shadow) {
            java.util.Arrays.fill(bank, (byte) 0);
        }
    }

    /** Write data at RAM column {@code x} / bank {@code bank} and mirror it in the shadow. */
    protected void ramWrite(int x, int bank, byte[] data, int access) throws IOException {
        byte[] buf = new byte[3 + data.length];
        buf[0] = (byte) selectByte();
        buf[1] = (byte) ramAccessByte(access, bank);
        buf[2] = (byte) (x & 0x3F);
        System.arraycopy(data, 0, buf, 3, data.length);
        connection.write(buf);
        int perCol = PER_COL[access];
        for (int i = 0; i < data.length; i++) {
            int col = x + i / perCol;
            if (col < RAM_COLUMNS) shadow[bank + (i % perCol)][col] = data[i];
        }
    }

    /**
     * Zero this device's whole display RAM (4 banks x 40 columns) and the shadow.
     *
     * @throws IOException on I2C error
     */
    public void clear() throws IOException {
        byte[] buf = new byte[3 + RAM_COLUMNS * RAM_BANKS];
        buf[0] = (byte) selectByte();
        buf[1] = (byte) ramAccessByte(ACCESS_FULL_GRAPHIC, 0);
        buf[2] = 0x00;
        connection.write(buf);
        zeroShadow();
    }

    /**
     * Set or clear one pixel. Out-of-range coordinates are ignored. Writes exactly one RAM byte.
     *
     * @param x  column, 0 .. 39
     * @param y  row, 0 .. rows - 1
     * @param on {@code true} lights the pixel, {@code false} clears it
     * @throws IOException on I2C error
     */
    public void setPixel(int x, int y, boolean on) throws IOException {
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= rows) return;
        int bank = y / 8;
        int bit = 1 << (y % 8);
        int v = shadow[bank][x] & 0xFF;
        v = on ? (v | bit) : (v & ~bit & 0xFF);
        ramWrite(x, bank, new byte[]{(byte) v}, ACCESS_CHARACTER);
    }

    /**
     * Write consecutive column bytes in character access mode.
     *
     * @param x    first column, 0 .. 39
     * @param bank bank (8-row band), 0 .. rows/8 - 1
     * @param data column bytes; bit 0 is the top row of the bank. Truncated at column 39
     * @throws IOException              on I2C error
     * @throws IllegalArgumentException if bank is out of range
     */
    public void writeColumns(int x, int bank, byte[] data) throws IOException {
        if (bank < 0 || bank >= rows / 8) {
            throw new IllegalArgumentException("bank out of range for the configured rows");
        }
        if (x < 0 || x >= RAM_COLUMNS) return;
        int n = Math.min(data.length, RAM_COLUMNS - x);
        if (n == 0) return;
        ramWrite(x, bank, java.util.Arrays.copyOf(data, n), ACCESS_CHARACTER);
    }
}
