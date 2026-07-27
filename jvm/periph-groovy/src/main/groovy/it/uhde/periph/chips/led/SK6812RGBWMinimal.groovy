package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} SK6812RGBW pixels over a NeoPixel connection.
 * Maintains an internal GRBW buffer; {@link #fill} writes all pixels and
 * transmits immediately. Each pixel has four channels: red, green, blue,
 * and white (dedicated white LED element).
 *
 * <p>Use {@link SK6812RGBWFull} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 */
@CompileStatic
class SK6812RGBWMinimal {

    protected final Connection connection
    protected final int n
    /** Internal pixel buffer in GRBW wire order (G, R, B, W per pixel). */
    protected byte[] buf

    /**
     * Construct the driver.
     *
     * @param connection configured NeoPixel connection
     * @param n number of pixels in the strip (≥1)
     */
    SK6812RGBWMinimal(Connection connection, int n) {
        this.connection = connection
        this.n = n
        this.buf = new byte[n * 4]
    }

    /**
     * Fill every pixel with one colour and transmit immediately.
     *
     * <p>Each channel is clamped to [0, 255]. Stores values in GRBW wire order.
     * The white channel defaults to 0 for RGB-only usage.
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param w white channel (0–255)
     */
    void fill(int r, int g, int b, int w = 0) {
        r = Math.max(0, Math.min(255, r))
        g = Math.max(0, Math.min(255, g))
        b = Math.max(0, Math.min(255, b))
        w = Math.max(0, Math.min(255, w))
        for (int i = 0; i < n; i++) {
            buf[i * 4]     = (byte) g
            buf[i * 4 + 1] = (byte) r
            buf[i * 4 + 2] = (byte) b
            buf[i * 4 + 3] = (byte) w
        }
        connection.write(buf)
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0, 0)}).
     */
    void off() {
        fill(0, 0, 0, 0)
    }
}
