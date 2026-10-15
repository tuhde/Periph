package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;
import java.io.IOException;
import java.util.Arrays;

/**
 * IL0373 black/white e-paper controller — full driver.
 *
 * <p>Extends {@link Il0373Minimal} with drawing helpers, partial-window
 * updates, scan direction, power and VCOM settings, frame rate, user LUTs and
 * the read commands (status, temperature, revision, VCOM, low power, OTP).
 *
 * <p>The {@code read*} methods need a half-duplex wire mode; with a SIMPLEX
 * connection they throw {@link UnsupportedOperationException}.
 */
public class Il0373Full extends Il0373Minimal {

    /** Frame rate in Hz for PLL M (1..7) and N (1..7), panel datasheet. */
    private static final int[][] PLL_HZ = {
        {29, 14, 10, 7, 6, 5, 4},
        {57, 29, 19, 14, 11, 10, 8},
        {86, 43, 29, 21, 17, 14, 12},
        {114, 57, 38, 29, 23, 19, 16},
        {150, 72, 48, 36, 29, 24, 20},
        {171, 86, 57, 43, 34, 29, 24},
        {200, 100, 67, 50, 40, 33, 29},
    };

    /**
     * Construct a 104 x 212 driver with a 30 s BUSY timeout.
     *
     * @param connection display connection
     * @throws IOException if the bus fails or BUSY never goes high
     */
    public Il0373Full(DisplayConn connection) throws IOException {
        super(connection);
    }

    /**
     * Construct the driver; see {@link Il0373Minimal#Il0373Minimal(DisplayConn, int, int, long)}.
     *
     * @param connection    display connection
     * @param width         pixels per row, a multiple of 8, 8..160
     * @param height        rows, 1..296
     * @param busyTimeoutMs limit for every BUSY wait, {@code <= 0} for no limit
     * @throws IOException if the bus fails or BUSY never goes high
     */
    public Il0373Full(DisplayConn connection, int width, int height, long busyTimeoutMs)
            throws IOException {
        super(connection, width, height, busyTimeoutMs);
    }

    // ---- framebuffer -------------------------------------------------------------------

    /**
     * Colour of one framebuffer pixel.
     *
     * @param x column
     * @param y row
     * @return {@link #BLACK} or {@link #WHITE} ({@code BLACK} when out of range)
     */
    public int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return BLACK;
        int index = y * rowBytes + (x >> 3);
        int mask = 0x80 >> (x & 7);
        return (fb[index] & mask) != 0 ? WHITE : BLACK;
    }

    /**
     * Bresenham line into the framebuffer.
     *
     * @param x0    start column
     * @param y0    start row
     * @param x1    end column
     * @param y1    end row
     * @param color {@link #BLACK} or {@link #WHITE}
     */
    public void drawLine(int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            setPixel(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /**
     * Rectangle outline or filled rectangle into the framebuffer.
     *
     * @param x     left column
     * @param y     top row
     * @param w     width in pixels
     * @param h     height in pixels
     * @param color {@link #BLACK} or {@link #WHITE}
     * @param fill  {@code true} fills the rectangle
     */
    public void drawRect(int x, int y, int w, int h, int color, boolean fill) {
        if (w <= 0 || h <= 0) return;
        if (fill) {
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) setPixel(x + i, y + j, color);
            }
            return;
        }
        drawLine(x, y, x + w - 1, y, color);
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, color);
        drawLine(x, y, x, y + h - 1, color);
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, color);
    }

    /**
     * Copy a 1-bit-per-pixel bitmap (row-major, MSB-first, rows padded to
     * whole bytes) into the framebuffer. Set bits are drawn in {@code color},
     * clear bits leave the framebuffer unchanged.
     *
     * @param x     left column
     * @param y     top row
     * @param data  bitmap bytes
     * @param w     bitmap width in pixels
     * @param h     bitmap height in pixels
     * @param color {@link #BLACK} or {@link #WHITE}
     */
    public void blit(int x, int y, byte[] data, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        int rb = (w + 7) / 8;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if ((data[j * rb + (i >> 3)] & (0x80 >> (i & 7))) != 0) setPixel(x + i, y + j, color);
            }
        }
    }

    // ---- updates -----------------------------------------------------------------------

    /**
     * Update only a window: PTIN, PTL (window widened to whole 8-pixel
     * columns), the window's bytes of both planes, DRF, power off, PTOUT. Saves
     * SPI time; the waveform is the OTP full waveform, so the panel still
     * flashes.
     *
     * @param x left column
     * @param y top row
     * @param w width in pixels
     * @param h height in pixels
     * @throws IllegalArgumentException if the window is empty or outside the panel
     * @throws IOException on a bus error or BUSY timeout
     */
    public void showRegion(int x, int y, int w, int h) throws IOException {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("empty window");
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        if (x >= width || y >= height || w <= 0 || h <= 0) {
            throw new IllegalArgumentException("window outside the panel");
        }
        int x0 = x & ~7;
        int x1 = Math.min((x + w - 1) | 7, width - 1);
        int y0 = y;
        int y1 = Math.min(y + h - 1, height - 1);
        if (asleep) wake();
        ensurePower();
        connection.sendCommand(CMD_PTIN, new byte[0]);
        connection.sendCommand(CMD_PTL, new byte[] {
            (byte) (x0 & 0xF8), (byte) ((x1 & 0xF8) | 0x07),
            (byte) ((y0 >> 8) & 1), (byte) (y0 & 0xFF),
            (byte) ((y1 >> 8) & 1), (byte) (y1 & 0xFF),
            0x00}); // PT_SCAN = 0: scan only inside the window
        int winBytes = (x1 - x0 + 1) / 8;
        for (int plane = 0; plane < 2; plane++) {
            connection.sendCommand(plane == 0 ? CMD_DTM1 : CMD_DTM2, new byte[0]);
            for (int row = y0; row <= y1; row++) {
                int start = row * rowBytes + (x0 >> 3);
                connection.sendData(plane == 0
                    ? inverted(fb, start, start + winBytes)
                    : Arrays.copyOfRange(fb, start, start + winBytes));
            }
        }
        try {
            refreshPowerOff();
        } finally {
            connection.sendCommand(CMD_PTOUT, new byte[0]);
        }
    }

    /**
     * Hardware reset and initialisation (exit from deep sleep). Framebuffers are kept.
     *
     * @throws IOException on a bus error or BUSY timeout
     */
    @Override
    public void wake() throws IOException {
        super.wake();
    }

    /**
     * Wait for BUSY to go high.
     *
     * @param timeoutMs limit in milliseconds, {@code <= 0} for no limit
     * @return {@code true} when ready, {@code false} on timeout
     * @throws IOException on a bus error
     */
    public boolean waitBusy(long timeoutMs) throws IOException {
        return connection.waitBusy(false, timeoutMs);
    }

    // ---- settings ----------------------------------------------------------------------

    private void writePsr() throws IOException {
        connection.sendCommand(CMD_PSR, new byte[] {(byte) psr});
    }

    private static int code(double value, double base, double step, int maxCode) {
        int c = (int) ((value - base) / step + 0.5);
        return Math.max(0, Math.min(maxCode, c));
    }

    /**
     * Mirror the picture: {@code horizontal} clears SHL, {@code vertical}
     * clears UD. The framebuffer is unchanged.
     *
     * @param horizontal mirror left/right
     * @param vertical   mirror top/bottom
     * @throws IOException on a bus error
     */
    public void setFlip(boolean horizontal, boolean vertical) throws IOException {
        if (horizontal) psr &= ~0x04; else psr |= 0x04;
        if (vertical) psr &= ~0x08; else psr |= 0x08;
        writePsr();
    }

    /**
     * Source/gate power ({@code PWR}, internal DC-DC).
     *
     * <p>Advanced: the values must match the panel vendor's; a wrong set can
     * drive the panel outside its rating. The driver does not write PWR by
     * default.
     *
     * @param vdh positive source voltage, 6.4..15.0 V (0.2 V steps)
     * @param vdl negative source voltage magnitude, 6.4..15.0 V
     * @param vgh gate voltage magnitude: 20, 19, 18 or 17 V (VGL = -VGH)
     * @throws IOException on a bus error
     */
    public void setPower(double vdh, double vdl, double vgh) throws IOException {
        int lv = Math.max(0, Math.min(3, (int) (20.0 - vgh + 0.5)));
        connection.sendCommand(CMD_PWR, new byte[] {
            0x03, // VDS_EN | VDG_EN
            (byte) lv,
            (byte) code(vdh, 6.4, 0.2, 0x2B),
            (byte) code(Math.abs(vdl), 6.4, 0.2, 0x2B),
            0x03}); // VDHR (red): unused in KW mode
    }

    /**
     * VCOM_DC ({@code VDCS}): -0.10 .. -3.00 V in 0.05 V steps.
     *
     * @param volts VCOM voltage, e.g. the value printed on the panel label
     * @throws IOException on a bus error
     */
    public void setVcomDc(double volts) throws IOException {
        connection.sendCommand(CMD_VDCS, new byte[] {(byte) code(-0.10 - volts, 0.0, 0.05, 0x3A)});
    }

    /**
     * Frame rate ({@code PLL}, M/N layout): the nearest rate of the table.
     *
     * @param hz requested rate, 4..200 Hz
     * @throws IOException on a bus error
     */
    public void setFrameRate(int hz) throws IOException {
        int best = 0x3C; // M = 7, N = 4 (50 Hz)
        int bestDiff = Integer.MAX_VALUE;
        for (int m = 1; m <= 7; m++) {
            for (int n = 1; n <= 7; n++) {
                int diff = Math.abs(PLL_HZ[m - 1][n - 1] - hz);
                if (diff < bestDiff) { bestDiff = diff; best = (m << 3) | n; }
            }
        }
        connection.sendCommand(CMD_PLL, new byte[] {(byte) best});
    }

    /**
     * Booster soft start ({@code BTST}).
     *
     * @param a phase A byte
     * @param b phase B byte
     * @param c phase C byte
     * @throws IOException on a bus error
     */
    public void setSoftStart(int a, int b, int c) throws IOException {
        connection.sendCommand(CMD_BTST, new byte[] {(byte) a, (byte) b, (byte) c});
    }

    /**
     * VCOM and data interval ({@code CDI}).
     *
     * @param cdi      interval, 0..15 (0b0111 = 10 hsync)
     * @param border   border data selection VBD, 0..3 (2 as at initialisation)
     * @param polarity data polarity DDX, 0..3; the framebuffer mapping assumes
     *                 1, changing it is the caller's responsibility
     * @throws IOException on a bus error
     */
    public void setInterval(int cdi, int border, int polarity) throws IOException {
        connection.sendCommand(CMD_CDI,
            new byte[] {(byte) (((border & 3) << 6) | ((polarity & 3) << 4) | (cdi & 0x0F))});
    }

    /**
     * Temperature sensor source ({@code TSE}): internal (default) or external LM75.
     *
     * @param external {@code true} selects the external sensor
     * @param offset   temperature offset, -8..+7 degrees Celsius
     * @throws IOException on a bus error
     */
    public void setTemperatureSensor(boolean external, int offset) throws IOException {
        connection.sendCommand(CMD_TSE,
            new byte[] {(byte) ((external ? 0x80 : 0x00) | (offset & 0x0F))});
    }

    /**
     * Load one user waveform table and select register LUTs
     * ({@code PSR.REG = 1}). The chip datasheet gives 44 (VCOM) / 42 bytes per
     * table, the panel datasheet 60 / 36 / 60 / 36 / 36; the bytes are sent as
     * given.
     *
     * @param table 0 = VCOM, 1 = W-W, 2 = B-W, 3 = W-B, 4 = B-B (commands 0x20..0x24)
     * @param data  table bytes
     * @throws IllegalArgumentException if table is not 0..4
     * @throws IOException on a bus error
     */
    public void writeLut(int table, byte[] data) throws IOException {
        if (table < 0 || table > 4) throw new IllegalArgumentException("table must be 0..4");
        connection.sendCommand(CMD_LUTC + table, data);
        psr |= 0x20;
        writePsr();
    }

    /**
     * Send a raw command and parameters (escape hatch).
     *
     * @param command command byte
     * @param data    parameter bytes
     * @throws IOException on a bus error
     */
    public void sendCommand(int command, byte[] data) throws IOException {
        connection.sendCommand(command, data);
    }

    // ---- reads (half-duplex wiring only) -----------------------------------------------

    private byte[] read(int cmd, int length) throws IOException {
        connection.sendCommand(cmd, new byte[0]);
        return connection.readData(length);
    }

    /**
     * Read {@code FLG}: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag,
     * 2 PON, 1 POF, 0 BUSY.
     *
     * @return the status byte (0..255)
     * @throws IOException on a bus error
     */
    public int readStatus() throws IOException {
        return read(CMD_FLG, 1)[0] & 0xFF;
    }

    /**
     * Read the internal temperature sensor ({@code TSC}).
     *
     * @return signed degrees Celsius
     * @throws IOException on a bus error
     */
    public int readTemperature() throws IOException {
        return read(CMD_TSC, 2)[0]; // signed byte
    }

    /**
     * Read {@code REV} (LUT_REV, one byte).
     *
     * @return the LUT revision (0..255)
     * @throws IOException on a bus error
     */
    public int readRevision() throws IOException {
        return read(CMD_REV, 1)[0] & 0xFF;
    }

    /**
     * Read the measured VCOM ({@code VV}).
     *
     * @return volts, -0.10 V - 0.05 V x VV[5:0]
     * @throws IOException on a bus error
     */
    public double readVcom() throws IOException {
        return -0.10 - 0.05 * (read(CMD_VV, 1)[0] & 0x3F);
    }

    /**
     * Read low-power detection ({@code LPD}).
     *
     * @return {@code true} when the supply is low
     * @throws IOException on a bus error
     */
    public boolean readLowPower() throws IOException {
        return (read(CMD_LPD, 1)[0] & 1) == 0;
    }

    /**
     * Read OTP bytes from address 0 ({@code ROTP}; the dummy byte is dropped).
     *
     * @param length number of bytes
     * @return the OTP contents
     * @throws IOException on a bus error
     */
    public byte[] readOtp(int length) throws IOException {
        connection.sendCommand(CMD_ROTP, new byte[0]);
        connection.readData(1);
        return connection.readData(length);
    }
}
