package it.uhde.periph.chips.led;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} SK6812RGBW pixels over a NeoPixel transport.
 * Maintains an internal GRBW buffer; {@link #fill} writes all pixels and
 * transmits immediately. Each pixel has four channels: red, green, blue,
 * and white (dedicated white LED element).
 *
 * <p>Use {@link SK6812RGBWFull} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 */
public class SK6812RGBWMinimal {

    protected final Transport transport;
    protected final int n;
    /** Internal pixel buffer in GRBW wire order (G, R, B, W per pixel). */
    protected final byte[] buf;

    /**
     * Construct the driver.
     *
     * @param transport configured NeoPixel transport
     * @param n         number of pixels in the strip (≥1)
     */
    public SK6812RGBWMinimal(Transport transport, int n) {
        this.transport = transport;
        this.n = n;
        this.buf = new byte[n * 4];
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
     * @throws IOException on transport error
     */
    public void fill(int r, int g, int b, int w) throws IOException {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        w = Math.max(0, Math.min(255, w));
        for (int i = 0; i < n; i++) {
            buf[i * 4]     = (byte) g;
            buf[i * 4 + 1] = (byte) r;
            buf[i * 4 + 2] = (byte) b;
            buf[i * 4 + 3] = (byte) w;
        }
        transport.write(buf);
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0, 0)}).
     *
     * @throws IOException on transport error
     */
    public void off() throws IOException {
        fill(0, 0, 0, 0);
    }
}
