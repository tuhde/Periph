package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.ResetExtender

/**
 * WS2814 addressable RGBW LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} WS2814 pixels over a NeoPixel connection.
 * Maintains an internal RGBW buffer (identity wire order — no reorder
 * needed); {@link #fill} writes all pixels and transmits immediately.
 * Each pixel has four channels: red, green, blue, and white.
 *
 * <p>Use {@link WS2814Full} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 */
@CompileStatic
class WS2814Minimal {

    // Reset-pulse length (trailing zero bytes, post bit-encoding) requested via
    // ResetExtender to guarantee the WS2814's ≥280 µs reset pulse - longer than
    // both WS2812B's ≈53 µs default and SK6812RGBW's 24-byte (~80 µs) extension.
    // Padding the *pre-encoded* pixel buffer with extra zero bytes instead would
    // not achieve this: those bytes get bit-encoded as more zero-value data bits
    // (periodic low-with-brief-highs), not a continuous low - see
    // ResetExtender's doc comment.
    private static final int RESET_BYTES = 90

    protected final Connection connection
    protected final int n
    /** Internal pixel buffer in RGBW wire order (identity — R, G, B, W per pixel). */
    protected byte[] buf

    /**
     * Send {@code data} with the extended reset pulse WS2814 needs, if the
     * connection supports requesting one ({@link ResetExtender}); falls back
     * to a plain write otherwise.
     */
    protected void transmit(byte[] data) {
        if (connection instanceof ResetExtender) {
            ((ResetExtender) connection).writeExt(data, RESET_BYTES)
        } else {
            connection.write(data)
        }
    }

    /**
     * Construct the driver.
     *
     * @param connection configured NeoPixel connection
     * @param n number of pixels in the strip (≥1)
     */
    WS2814Minimal(Connection connection, int n) {
        this.connection = connection
        this.n = n
        this.buf = new byte[n * 4]
    }

    /**
     * Fill every pixel with one colour and transmit immediately.
     *
     * <p>Each channel is clamped to [0, 255]. Stores values in RGBW wire
     * order (identity — no reorder). The white channel defaults to 0 for
     * RGB-only usage.
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
            buf[i * 4]     = (byte) r
            buf[i * 4 + 1] = (byte) g
            buf[i * 4 + 2] = (byte) b
            buf[i * 4 + 3] = (byte) w
        }
        transmit(buf)
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0, 0)}).
     */
    void off() {
        fill(0, 0, 0, 0)
    }
}
