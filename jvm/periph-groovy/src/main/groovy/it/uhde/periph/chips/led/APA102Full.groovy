package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * APA102 full interface — extends {@link APA102Minimal} with per-pixel control.
 *
 * <p>Adds individual pixel addressing with per-pixel hardware brightness,
 * explicit {@link #show()}, global software brightness scaling (0–255),
 * buffer rotation, and HSV fill. Call {@link #setPixel} or
 * {@link #setPixels} to update the buffer, then {@link #show()} to
 * transmit. The inherited {@link #fill} remains available as the fast
 * path for all-same-colour updates (fills and transmits immediately).
 *
 * <p>The per-pixel hardware brightness field (0–31) is stored in the
 * buffer and is NOT affected by the global software brightness scalar.
 * Software brightness scaling is applied at {@link #show()} time:
 * {@code sent = stored × brightness / 255}.
 */
@CompileStatic
class APA102Full extends APA102Minimal {

    private int brightness = 255

    /**
     * Construct the full driver.
     *
     * @param connection configured SPI connection (Mode 0, MSB first)
     * @param n number of pixels in the strip (≥1)
     */
    APA102Full(Connection connection, int n) {
        super(connection, n)
    }

    /**
     * Get the global software brightness scalar.
     *
     * @return current brightness (0–255)
     */
    int getBrightness() {
        return brightness
    }

    /**
     * Set the global software brightness scalar applied at {@link #show()} time.
     *
     * <p>Stored RGB channel values are not modified; scaling is applied on
     * transmission: {@code sent = stored × brightness / 255}.
     * The per-pixel hardware brightness byte is NOT scaled.
     *
     * @param value brightness (0–255, clamped)
     */
    void setBrightness(int value) {
        this.brightness = Math.max(0, Math.min(255, value))
    }

    /**
     * Write one pixel into the buffer without transmitting.
     *
     * <p>Index is clamped to [0, n−1]; each RGB channel is clamped to
     * [0, 255]; pixelBrightness is clamped to [0, 31]. Call
     * {@link #show()} to transmit.
     *
     * @param index zero-based pixel index
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param pixelBrightness per-pixel hardware brightness 0–31 (default 31)
     */
    void setPixel(int index, int r, int g, int b, int pixelBrightness = 31) {
        if (n == 0) return
        index = Math.max(0, Math.min(n - 1, index))
        r = Math.max(0, Math.min(255, r))
        g = Math.max(0, Math.min(255, g))
        b = Math.max(0, Math.min(255, b))
        pixelBrightness = Math.max(0, Math.min(31, pixelBrightness))
        buf[index * 4]     = (byte) (0xE0 | pixelBrightness)
        buf[index * 4 + 1] = (byte) b
        buf[index * 4 + 2] = (byte) g
        buf[index * 4 + 3] = (byte) r
    }

    /**
     * Write multiple pixels from a 2D array into the buffer starting at pixel 0.
     *
     * <p>Each element is {@code {r, g, b}} or {@code {r, g, b, pixelBrightness}}.
     * Missing brightness defaults to 31. Extra entries beyond the strip
     * length are ignored. Call {@link #show()} to transmit.
     *
     * @param colors array of {@code {r, g, b}} or {@code {r, g, b, pixelBrightness}} arrays
     */
    void setPixels(int[][] colors) {
        int count = Math.min(colors.length, n)
        for (int i = 0; i < count; i++) {
            int[] c = colors[i]
            int r = Math.max(0, Math.min(255, c[0]))
            int g = Math.max(0, Math.min(255, c[1]))
            int b = Math.max(0, Math.min(255, c[2]))
            int pixelBrightness = 31
            if (c.length > 3) {
                pixelBrightness = Math.max(0, Math.min(31, c[3]))
            }
            buf[i * 4]     = (byte) (0xE0 | pixelBrightness)
            buf[i * 4 + 1] = (byte) b
            buf[i * 4 + 2] = (byte) g
            buf[i * 4 + 3] = (byte) r
        }
    }

    /**
     * Transmit the current buffer to the strip, applying software brightness scaling.
     *
     * <p>Each RGB channel value is scaled: {@code sent = stored × brightness / 255}.
     * The per-pixel hardware brightness byte is NOT scaled.
     */
    void show() {
        int endBytes = Math.max(4, (n + 15) / 16)
        int pixelDataLen = n * 4
        int totalLen = 4 + pixelDataLen + endBytes

        byte[] frame = new byte[totalLen]
        frame[0] = frame[1] = frame[2] = frame[3] = 0x00

        if (brightness == 255) {
            System.arraycopy(buf, 0, frame, 4, pixelDataLen)
        } else {
            for (int i = 0; i < n; i++) {
                int base = i * 4
                frame[4 + base]     = buf[base]
                frame[4 + base + 1] = (byte) ((buf[base + 1] & 0xFF) * brightness / 255)
                frame[4 + base + 2] = (byte) ((buf[base + 2] & 0xFF) * brightness / 255)
                frame[4 + base + 3] = (byte) ((buf[base + 3] & 0xFF) * brightness / 255)
            }
        }

        for (int i = 0; i < endBytes; i++) {
            frame[4 + pixelDataLen + i] = (byte) 0xFF
        }

        connection.write(frame)
    }

    /**
     * Shift the pixel buffer left by {@code steps} whole-pixel positions (wraps around).
     *
     * <p>Each step shifts 4 bytes (one BGR+brightness pixel). Does not
     * transmit — call {@link #show()} afterwards.
     *
     * @param steps number of pixel positions to shift left
     */
    void rotate(int steps) {
        if (n == 0) return
        steps = ((steps % n) + n) % n
        if (steps == 0) return
        int s4 = steps * 4
        int n4 = n * 4
        byte[] tmp = new byte[n4]
        System.arraycopy(buf, s4, tmp, 0, n4 - s4)
        System.arraycopy(buf, 0, tmp, n4 - s4, s4)
        System.arraycopy(tmp, 0, buf, 0, n4)
    }

    /**
     * Fill every pixel with one HSV colour and transmit immediately.
     *
     * <p>Converts HSV to RGB, then calls {@link #fill(int, int, int)}
     * at hardware brightness 31.
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     */
    void fillHsv(double h, double s, double v) {
        int[] rgb = hsvToRgb(h, s, v)
        fill(rgb[0], rgb[1], rgb[2])
    }

    private static int[] hsvToRgb(double h, double s, double v) {
        return NeoPixelColor.hsvToRgb(h, s, v)
    }
}