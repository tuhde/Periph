package it.uhde.periph.chips.display;

import it.uhde.periph.connection.DisplayConn;
import java.io.IOException;

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
public class Uc8159Full extends Uc8159Minimal {

    /**
     * Construct the driver with a 60 s BUSY timeout.
     *
     * @param connection display connection
     * @throws IOException if the bus fails or BUSY never goes high
     */
    public Uc8159Full(DisplayConn connection) throws IOException {
        super(connection);
    }

    /**
     * Construct the driver; see {@link Uc8159Minimal#Uc8159Minimal(DisplayConn, long)}.
     *
     * @param connection    display connection
     * @param busyTimeoutMs limit for every BUSY wait, {@code <= 0} for no limit
     * @throws IOException if the bus fails or BUSY never goes high
     */
    public Uc8159Full(DisplayConn connection, long busyTimeoutMs) throws IOException {
        super(connection, busyTimeoutMs);
    }

    /**
     * Colour code of one framebuffer pixel.
     *
     * @param x column
     * @param y row
     * @return colour code 0..7 ({@code BLACK} when out of range)
     */
    public int getPixel(int x, int y) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return BLACK;
        int b = fb[(y * WIDTH + x) >> 1] & 0xFF;
        return (x & 1) == 1 ? (b & 7) : ((b >> 4) & 7);
    }

    /**
     * Bresenham line into the framebuffer.
     *
     * @param x0    start column
     * @param y0    start row
     * @param x1    end column
     * @param y1    end row
     * @param color colour code 0..7
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
     * @param color colour code 0..7
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
     * @param color colour code 0..7
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

    /**
     * Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept.
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
        connection.sendCommand(CMD_PSR, new byte[] {(byte) psr, 0x08});
    }

    /**
     * Frame rate ({@code PLL} raw code): {@code 0x3C} = 50 Hz (default),
     * {@code 0x3A} = 100 Hz, {@code 0x39} = 200 Hz, {@code 0x00..0x0F} = 12.5 .. 200 Hz.
     *
     * @param code PLL code
     * @throws IOException on a bus error
     */
    public void setFrameRate(int code) throws IOException {
        connection.sendCommand(CMD_PLL, new byte[] {(byte) code});
    }

    /**
     * VCOM and data interval ({@code CDI}).
     *
     * @param cdi      interval, 0..15 (0b0111 = 10 hsync)
     * @param border   border colour code 0..7
     * @param polarity DDX, 1 = default (the colour codes assume 1)
     * @throws IOException on a bus error
     */
    public void setInterval(int cdi, int border, int polarity) throws IOException {
        this.cdi = ((border & 7) << 5) | ((polarity & 1) << 4) | (cdi & 0x0F);
        connection.sendCommand(CMD_CDI, new byte[] {(byte) this.cdi});
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
     * VCOM_DC ({@code VDCS}): -0.10 .. -4.0 V in 0.05 V steps.
     *
     * @param volts VCOM voltage
     * @throws IOException on a bus error
     */
    public void setVcomDc(double volts) throws IOException {
        int c = (int) ((-0.10 - volts) / 0.05 + 2.5);
        c = Math.max(2, Math.min(0x50, c));
        connection.sendCommand(CMD_VDCS, new byte[] {(byte) c});
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

    private byte[] read(int cmd, int length) throws IOException {
        connection.sendCommand(cmd, new byte[0]);
        return connection.readData(length);
    }

    /**
     * Read {@code FLG}: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N.
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
     * Read low-power detection ({@code LPD}).
     *
     * @return {@code true} when VDD is below 2.5 V
     * @throws IOException on a bus error
     */
    public boolean readLowPower() throws IOException {
        return (read(CMD_LPD, 1)[0] & 1) == 0;
    }

    /**
     * Read the measured VCOM ({@code VV}).
     *
     * @return volts, -0.10 V - 0.05 V x (VV - 2)
     * @throws IOException on a bus error
     */
    public double readVcom() throws IOException {
        int c = read(CMD_VV, 1)[0] & 0x7F;
        return -0.10 - 0.05 * (c > 2 ? c - 2 : 0);
    }
}
