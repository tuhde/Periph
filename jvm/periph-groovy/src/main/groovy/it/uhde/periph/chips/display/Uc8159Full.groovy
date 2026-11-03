package it.uhde.periph.chips.display

import groovy.transform.CompileStatic
import it.uhde.periph.connection.DisplayConn

/**
 * UC8159 seven-colour e-paper controller — full driver.
 *
 * <p>Extends {@link Uc8159Minimal} with drawing helpers, scan direction, frame
 * rate, border/data interval, temperature-sensor source, VCOM_DC and the read
 * commands (status, temperature, low power, VCOM).
 *
 * <p>The {@code read*} methods need a half-duplex wire mode; with a SIMPLEX
 * connection they throw {@link UnsupportedOperationException}.
 */
@CompileStatic
class Uc8159Full extends Uc8159Minimal {

    /**
     * Construct the driver with a 60 s BUSY timeout.
     *
     * @param connection display connection
     */
    Uc8159Full(DisplayConn connection) {
        super(connection)
    }

    /**
     * Construct the driver; see {@link Uc8159Minimal}.
     *
     * @param connection    display connection
     * @param busyTimeoutMs limit for every BUSY wait, {@code <= 0} for no limit
     */
    Uc8159Full(DisplayConn connection, long busyTimeoutMs) {
        super(connection, busyTimeoutMs)
    }

    /**
     * Colour code of one framebuffer pixel.
     *
     * @param x column
     * @param y row
     * @return colour code 0..7 ({@code BLACK} when out of range)
     */
    int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return BLACK
        int b = fb[(y * WIDTH + x) >> 1] & 0xFF
        (x & 1) == 1 ? (b & 7) : ((b >> 4) & 7)
    }

    /** Bresenham line into the framebuffer. */
    void drawLine(int x0, int y0, int x1, int y1, int color = BLACK) {
        int cx = x0
        int cy = y0
        int dx = Math.abs(x1 - x0)
        int dy = -Math.abs(y1 - y0)
        int sx = x0 < x1 ? 1 : -1
        int sy = y0 < y1 ? 1 : -1
        int err = dx + dy
        while (true) {
            setPixel(cx, cy, color)
            if (cx == x1 && cy == y1) break
            int e2 = 2 * err
            if (e2 >= dy) { err += dy; cx += sx }
            if (e2 <= dx) { err += dx; cy += sy }
        }
    }

    /** Rectangle outline or, with {@code fill}, a filled rectangle into the framebuffer. */
    void drawRect(int x, int y, int w, int h, int color = BLACK, boolean fill = false) {
        if (w <= 0 || h <= 0) return
        if (fill) {
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) setPixel(x + i, y + j, color)
            }
            return
        }
        drawLine(x, y, x + w - 1, y, color)
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, color)
        drawLine(x, y, x, y + h - 1, color)
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, color)
    }

    /**
     * Copy a 1-bit-per-pixel bitmap (row-major, MSB-first, rows padded to
     * whole bytes) into the framebuffer. Set bits are drawn in {@code color},
     * clear bits leave the framebuffer unchanged.
     */
    void blit(int x, int y, byte[] data, int w, int h, int color = BLACK) {
        if (w <= 0 || h <= 0) return
        int rb = (w + 7).intdiv(8)
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if ((data[j * rb + (i >> 3)] & (0x80 >> (i & 7))) != 0) setPixel(x + i, y + j, color)
            }
        }
    }

    /** Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept. */
    @Override
    void wake() {
        super.wake()
    }

    /**
     * Wait for BUSY to go high.
     *
     * @param timeoutMs limit in milliseconds, {@code <= 0} for no limit
     * @return {@code true} when ready, {@code false} on timeout
     */
    boolean waitBusy(long timeoutMs) {
        connection.waitBusy(false, timeoutMs)
    }

    /** Mirror the picture: {@code horizontal} clears SHL, {@code vertical} clears UD. */
    void setFlip(boolean horizontal, boolean vertical) {
        psr = horizontal ? (psr & ~0x04) : (psr | 0x04)
        psr = vertical ? (psr & ~0x08) : (psr | 0x08)
        connection.sendCommand(CMD_PSR, [(byte) psr, (byte) 0x08] as byte[])
    }

    /** Frame rate ({@code PLL} raw code): {@code 0x3C} = 50 Hz (default), {@code 0x3A} = 100 Hz, {@code 0x39} = 200 Hz. */
    void setFrameRate(int code) {
        connection.sendCommand(CMD_PLL, [(byte) code] as byte[])
    }

    /**
     * VCOM and data interval ({@code CDI}).
     *
     * @param cdi      interval, 0..15 (0b0111 = 10 hsync)
     * @param border   border colour code 0..7
     * @param polarity DDX, 1 = default (the colour codes assume 1)
     */
    void setInterval(int cdi, int border = WHITE, int polarity = 1) {
        this.cdi = ((border & 7) << 5) | ((polarity & 1) << 4) | (cdi & 0x0F)
        connection.sendCommand(CMD_CDI, [(byte) this.cdi] as byte[])
    }

    /** Temperature sensor source ({@code TSE}): internal (default) or external LM75, offset -8..+7 degrees Celsius. */
    void setTemperatureSensor(boolean external, int offset = 0) {
        int v = (external ? 0x80 : 0x00) | (offset & 0x0F)
        connection.sendCommand(CMD_TSE, [(byte) v] as byte[])
    }

    /** VCOM_DC ({@code VDCS}): -0.10 .. -4.0 V in 0.05 V steps. */
    void setVcomDc(double volts) {
        int c = (int) ((-0.10d - volts) / 0.05d + 2.5d)
        c = Math.max(2, Math.min(0x50, c))
        connection.sendCommand(CMD_VDCS, [(byte) c] as byte[])
    }

    /** Send a raw command and parameters (escape hatch). */
    void sendCommand(int command, byte[] data = new byte[0]) {
        connection.sendCommand(command, data)
    }

    private byte[] read(int cmd, int length) {
        connection.sendCommand(cmd, new byte[0])
        connection.readData(length)
    }

    /** Read {@code FLG}: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N. */
    int readStatus() {
        read(CMD_FLG, 1)[0] & 0xFF
    }

    /** Read the internal temperature sensor ({@code TSC}) in signed degrees Celsius. */
    int readTemperature() {
        (int) read(CMD_TSC, 2)[0]
    }

    /** Read low-power detection ({@code LPD}): {@code true} when VDD is below 2.5 V. */
    boolean readLowPower() {
        (read(CMD_LPD, 1)[0] & 1) == 0
    }

    /** Read the measured VCOM ({@code VV}) in volts: -0.10 V - 0.05 V x (VV - 2). */
    double readVcom() {
        int c = read(CMD_VV, 1)[0] & 0x7F
        -0.10d - 0.05d * (c > 2 ? c - 2 : 0)
    }
}
