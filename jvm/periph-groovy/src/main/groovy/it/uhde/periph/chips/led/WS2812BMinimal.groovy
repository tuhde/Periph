package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * WS2812B addressable RGB LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} WS2812B pixels over a NeoPixel transport.
 * Maintains an internal GRB buffer; {@link #fill} writes all pixels and
 * transmits immediately. No per-pixel addressing or brightness control.
 *
 * <p>Use {@link WS2812BFull} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 */
@CompileStatic
class WS2812BMinimal {

    protected final Transport transport
    protected final int n
    /** Internal pixel buffer in GRB wire order (G, R, B per pixel). */
    protected byte[] buf

    /**
     * Construct the driver.
     *
     * @param transport configured NeoPixel transport
     * @param n number of pixels in the strip (≥1)
     */
    WS2812BMinimal(Transport transport, int n) {
        this.transport = transport
        this.n = n
        this.buf = new byte[n * 3]
    }

    /**
     * Fill every pixel with one colour and transmit immediately.
     *
     * <p>Each channel is clamped to [0, 255]. Stores values in GRB wire order
     * (WS2812B expects G, R, B on the data line).
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     */
    void fill(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r))
        g = Math.max(0, Math.min(255, g))
        b = Math.max(0, Math.min(255, b))
        for (int i = 0; i < n; i++) {
            buf[i * 3]     = (byte) g
            buf[i * 3 + 1] = (byte) r
            buf[i * 3 + 2] = (byte) b
        }
        transport.write(buf)
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0)}).
     */
    void off() {
        fill(0, 0, 0)
    }
}
