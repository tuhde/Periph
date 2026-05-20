package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * SK6812RGBW full interface — extends {@link SK6812RGBWMinimal} with per-pixel control.
 *
 * <p>Adds individual pixel addressing, explicit {@link #show()}, global brightness
 * scaling (0–255), pixel buffer rotation, and HSV fill. Call {@link #setPixel}
 * or {@link #setPixels} to update the buffer, then {@link #show()} to transmit.
 * The inherited {@link #fill} remains available as the fast path for
 * all-same-colour updates (fills and transmits immediately).
 *
 * <p>Brightness is stored separately and applied non-destructively at
 * {@link #show()} time: {@code sent = stored × brightness / 255}.
 */
@CompileStatic
class SK6812RGBWFull extends SK6812RGBWMinimal {

    private int brightness = 255

    /**
     * Construct the full driver.
     *
     * @param transport configured NeoPixel transport
     * @param n number of pixels in the strip (≥1)
     */
    SK6812RGBWFull(Transport transport, int n) {
        super(transport, n)
    }

    /**
     * Get the global brightness scalar.
     *
     * @return current brightness (0–255)
     */
    int getBrightness() {
        brightness
    }

    /**
     * Set the global brightness scalar applied at {@link #show()} time.
     *
     * <p>Stored channel values are not modified; scaling is applied on
     * transmission: {@code sent = stored × brightness / 255}.
     *
     * @param value brightness (0–255, clamped)
     */
    void setBrightness(int value) {
        brightness = Math.max(0, Math.min(255, value))
    }

    /**
     * Write one pixel into the buffer without transmitting.
     *
     * <p>Index is clamped to [0, n−1]; each channel is clamped to [0, 255].
     * Call {@link #show()} to transmit. The white channel defaults to 0.
     *
     * @param index zero-based pixel index
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param w white channel (0–255)
     */
    void setPixel(int index, int r, int g, int b, int w = 0) {
        index = Math.max(0, Math.min(n - 1, index))
        buf[index * 4]     = (byte) Math.max(0, Math.min(255, g))
        buf[index * 4 + 1] = (byte) Math.max(0, Math.min(255, r))
        buf[index * 4 + 2] = (byte) Math.max(0, Math.min(255, b))
        buf[index * 4 + 3] = (byte) Math.max(0, Math.min(255, w))
    }

    /**
     * Write a sequence of (r, g, b, w) values into the buffer starting at pixel 0.
     *
     * <p>Each element may be length 3 ([r, g, b], w=0) or 4 ([r, g, b, w]).
     * Extra entries beyond the strip length are ignored. Call {@link #show()} to transmit.
     *
     * @param colors list of [r, g, b] or [r, g, b, w] int arrays (0–255 each)
     */
    void setPixels(List<int[]> colors) {
        int count = Math.min(colors.size(), n)
        for (int i = 0; i < count; i++) {
            int wc = colors[i].length >= 4 ? colors[i][3] : 0
            buf[i * 4]     = (byte) Math.max(0, Math.min(255, colors[i][1])) // g
            buf[i * 4 + 1] = (byte) Math.max(0, Math.min(255, colors[i][0])) // r
            buf[i * 4 + 2] = (byte) Math.max(0, Math.min(255, colors[i][2])) // b
            buf[i * 4 + 3] = (byte) Math.max(0, Math.min(255, wc))           // w
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * <p>Each channel is scaled: {@code sent = stored × brightness / 255}.
     */
    void show() {
        if (brightness == 255) {
            transport.write(buf)
        } else {
            byte[] scaled = new byte[buf.length]
            for (int i = 0; i < buf.length; i++) {
                scaled[i] = (byte) ((buf[i] & 0xFF) * brightness / 255)
            }
            transport.write(scaled)
        }
    }

    /**
     * Shift the pixel buffer left by {@code steps} positions (wraps around).
     *
     * <p>Does not transmit — call {@link #show()} afterwards.
     *
     * @param steps number of pixel positions to shift left
     */
    void rotate(int steps) {
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
     * <p>Converts HSV to RGB (w=0), then calls {@link #fill(int, int, int, int)}.
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     */
    void fillHsv(double h, double s, double v) {
        int[] rgb = hsvToRgb(h, s, v)
        fill(rgb[0], rgb[1], rgb[2], 0)
    }

    private static int[] hsvToRgb(double h, double s, double v) {
        if (s == 0.0) {
            int c = (int) (v * 255)
            return [c, c, c] as int[]
        }
        int i    = (int) (h * 6.0)
        double f = h * 6.0 - i
        int p    = (int) (v * (1.0 - s) * 255)
        int q    = (int) (v * (1.0 - s * f) * 255)
        int t    = (int) (v * (1.0 - s * (1.0 - f)) * 255)
        int vv   = (int) (v * 255)
        switch (i % 6) {
            case 0:  return [vv, t, p] as int[]
            case 1:  return [q, vv, p] as int[]
            case 2:  return [p, vv, t] as int[]
            case 3:  return [p, q, vv] as int[]
            case 4:  return [t, p, vv] as int[]
            default: return [vv, p, q] as int[]
        }
    }
}
