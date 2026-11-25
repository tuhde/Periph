package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * PCF8579 — LCD column driver (full driver).
 *
 * <p>Extends {@link PCF8579Minimal} with pixel read-back from the shadow, fill, line /
 * rectangle / bitmap drawing, display status (blank / normal / all-on / inverse), multiplex
 * rate and PCF8578 mode bit, hardware vertical scrolling via the start bank, and raw RAM write /
 * read in all three access modes including transfers that run on into the following cascaded
 * devices.
 */
@CompileStatic
class PCF8579Full extends PCF8579Minimal {

    static final int DISPLAY_BLANK   = STATUS_BLANK
    static final int DISPLAY_NORMAL  = STATUS_NORMAL
    static final int DISPLAY_ALL_ON  = STATUS_ALL_ON
    static final int DISPLAY_INVERSE = STATUS_INVERSE

    /**
     * Construct the driver with subaddress 0, 16 rows and the PCF8578 in row mode.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     */
    PCF8579Full(Connection connection) {
        super(connection)
    }

    /**
     * Construct the driver with 16 rows and the PCF8578 in row mode.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
     */
    PCF8579Full(Connection connection, int subaddress) {
        super(connection, subaddress)
    }

    /**
     * Construct the driver with the PCF8578 in row mode.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
     * @param rows       display rows: 8, 16, 24 or 32
     */
    PCF8579Full(Connection connection, int subaddress, int rows) {
        super(connection, subaddress, rows)
    }

    /**
     * Construct the driver and initialise the chip.
     *
     * @param connection I2C connection bound to the PCF8579 address (100 kHz maximum)
     * @param subaddress hardware subaddress set on pins A3:A0, 0 .. 15
     * @param rows       display rows: 8, 16, 24 or 32
     * @param mixed      {@code true} if the PCF8578 in the system runs in mixed mode
     */
    PCF8579Full(Connection connection, int subaddress, int rows, boolean mixed) {
        super(connection, subaddress, rows, mixed)
    }

    private void plot(int x, int y, boolean on, BitSet dirty) {
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= rowCount) return
        int bank = (int) (y / 8)
        int bit = 1 << (y % 8)
        int v = shadow[bank][x] & 0xFF
        shadow[bank][x] = (byte) (on ? (v | bit) : (v & ~bit))
        dirty.set(bank * RAM_COLUMNS + x)
    }

    /** One transaction per run of consecutive changed columns in a bank. */
    private void flush(BitSet dirty) {
        for (int bank = 0; bank < RAM_BANKS; bank++) {
            int x = 0
            while (x < RAM_COLUMNS) {
                if (!dirty.get(bank * RAM_COLUMNS + x)) {
                    x++
                    continue
                }
                int start = x
                while (x < RAM_COLUMNS && dirty.get(bank * RAM_COLUMNS + x)) x++
                ramWrite(start, bank, Arrays.copyOfRange(shadow[bank], start, x), ACCESS_CHARACTER)
            }
        }
    }

    /**
     * Return the state of one pixel from the shadow (no bus traffic).
     *
     * @param x column, 0 .. 39
     * @param y row
     * @return {@code true} if the pixel is set; {@code false} if clear or out of range
     */
    boolean getPixel(int x, int y) {
        if (x < 0 || x >= RAM_COLUMNS || y < 0 || y >= rowCount) return false
        (shadow[(int) (y / 8)][x] & (1 << (y % 8))) != 0
    }

    /**
     * Set every displayed pixel (rows x 40) on or off.
     *
     * @param on {@code true} lights all pixels, {@code false} clears them
     */
    void fill(boolean on) {
        byte[] col = new byte[RAM_COLUMNS]
        Arrays.fill(col, (byte) (on ? 0xFF : 0x00))
        for (int bank = 0; bank < (int) (rowCount / 8); bank++) {
            ramWrite(0, bank, col, ACCESS_CHARACTER)
        }
    }

    /**
     * Draw a Bresenham line, written as one batch of changed bytes.
     *
     * @param x0 start column
     * @param y0 start row
     * @param x1 end column
     * @param y1 end row
     * @param on {@code true} draws, {@code false} erases
     */
    void drawLine(int x0, int y0, int x1, int y1, boolean on) {
        BitSet dirty = new BitSet()
        int cx = x0
        int cy = y0
        int dx = Math.abs(x1 - x0)
        int dy = -Math.abs(y1 - y0)
        int sx = x0 < x1 ? 1 : -1
        int sy = y0 < y1 ? 1 : -1
        int err = dx + dy
        while (true) {
            plot(cx, cy, on, dirty)
            if (cx == x1 && cy == y1) break
            int e2 = 2 * err
            if (e2 >= dy) { err += dy; cx += sx }
            if (e2 <= dx) { err += dx; cy += sy }
        }
        flush(dirty)
    }

    /**
     * Draw a rectangle outline or a filled rectangle.
     *
     * @param x    left column
     * @param y    top row
     * @param w    width in pixels
     * @param h    height in pixels
     * @param on   {@code true} draws, {@code false} erases
     * @param fill {@code true} fills the interior
     */
    void drawRect(int x, int y, int w, int h, boolean on, boolean fill) {
        if (w <= 0 || h <= 0) return
        BitSet dirty = new BitSet()
        for (int yy = y; yy < y + h; yy++) {
            for (int xx = x; xx < x + w; xx++) {
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
     * @param x       left column
     * @param y       top row; must be a non-negative multiple of 8
     * @param columns one byte per column (bit 0 = top row); clipped at column 39
     */
    void showBitmap(int x, int y, byte[] columns) {
        if (y < 0 || y % 8 != 0) {
            throw new IllegalArgumentException('y must be a non-negative multiple of 8')
        }
        writeColumns(x, (int) (y / 8), columns)
    }

    /**
     * Set the display status; RAM contents are preserved. Affects all devices on the address.
     *
     * @param status {@link #DISPLAY_BLANK}, {@link #DISPLAY_NORMAL}, {@link #DISPLAY_ALL_ON} or
     *               {@link #DISPLAY_INVERSE}
     */
    void setDisplay(int status) {
        if (status < 0 || status > 3) throw new IllegalArgumentException('status must be in 0..3')
        this.displayStatus = status
        connection.write([(byte) modeByte(status, false)] as byte[])
    }

    /**
     * Change the multiplex rate and the PCF8578 mode bit T (affects all devices on the address).
     *
     * @param rows  8, 16, 24 or 32
     * @param mixed {@code true} if the PCF8578 runs in mixed mode
     */
    void setMode(int rows, boolean mixed) {
        muxCode(rows)
        this.rowCount = rows
        this.mixedMode = mixed
        connection.write([(byte) modeByte(displayStatus, false)] as byte[])
    }

    /**
     * Select the RAM bank shown at the top of the display (hardware vertical scroll).
     *
     * @param bank 0 .. 3
     */
    void setStartBank(int bank) {
        if (bank < 0 || bank > 3) throw new IllegalArgumentException('bank must be in 0..3')
        this.topBank = bank
        connection.write([(byte) (CMD_SET_START_BANK | bank)] as byte[])
    }

    /**
     * Add {@code banks} (may be negative) to the start bank, modulo the number of banks in use.
     *
     * @param banks number of 8-row steps
     */
    void scroll(int banks) {
        setStartBank(Math.floorMod(topBank + banks, (int) (rowCount / 8)))
    }

    private static void checkRamArgs(int x, int bank, int count, int access, boolean cascade) {
        if (access < 0 || access > 2 || x < 0 || x > 39 || bank < 0 || bank > 3) {
            throw new IllegalArgumentException('x must be in 0..39, bank in 0..3, access in 0..2')
        }
        if ((access == ACCESS_HALF_GRAPHIC && bank % 2 != 0) || (access == ACCESS_FULL_GRAPHIC && bank != 0)) {
            throw new IllegalArgumentException('half-graphic must start at an even bank, full-graphic at bank 0')
        }
        int perCol = PER_COL[access]
        if (!cascade && x + (int) ((count + perCol - 1) / perCol) > RAM_COLUMNS) {
            throw new IllegalArgumentException('write would pass column 39')
        }
    }

    /**
     * Raw RAM write of this device in character, half-graphic or full-graphic order.
     *
     * @param x    RAM column, 0 .. 39
     * @param bank RAM bank, 0 .. 3 (even for half-graphic, 0 for full-graphic)
     * @param data bytes to write; must not pass column 39
     * @param mode {@link #ACCESS_CHARACTER}, {@link #ACCESS_HALF_GRAPHIC} or {@link #ACCESS_FULL_GRAPHIC}
     */
    void writeRam(int x, int bank, byte[] data, int mode) {
        checkRamArgs(x, bank, data.length, mode, false)
        if (data.length == 0) return
        ramWrite(x, bank, data, mode)
    }

    /**
     * Write from column 0, bank 0 of this device and run on into the following subaddresses (the
     * chip advances its subaddress counter after column 39). The shadow only tracks this
     * device's first 40 columns.
     *
     * @param data bytes to write
     * @param mode {@link #ACCESS_CHARACTER}, {@link #ACCESS_HALF_GRAPHIC} or {@link #ACCESS_FULL_GRAPHIC}
     */
    void writeRamCascade(byte[] data, int mode) {
        checkRamArgs(0, 0, data.length, mode, true)
        if (data.length == 0) return
        ramWrite(0, 0, data, mode)
    }

    /**
     * Raw RAM read: address-set commands, repeated start, then {@code n} bytes.
     *
     * @param x    RAM column, 0 .. 39
     * @param bank RAM bank, 0 .. 3
     * @param n    number of bytes to read
     * @param mode access mode as for {@link #writeRam}
     * @return the bytes read
     */
    byte[] readRam(int x, int bank, int n, int mode) {
        checkRamArgs(x, bank, n, mode, false)
        byte[] cmds = new byte[3]
        cmds[0] = (byte) selectByte()
        cmds[1] = (byte) ramAccessByte(mode, bank)
        cmds[2] = (byte) (x & 0x3F)
        connection.writeRead(cmds, n)
    }

    /** Re-run initialisation (clears this device's RAM, normal video, start bank 0). */
    void reset() {
        doInit()
    }
}
