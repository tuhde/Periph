package it.uhde.periph.chips.led;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * WS2812B full interface — extends {@link WS2812BMinimal} with per-pixel control.
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
public class WS2812BFull extends WS2812BMinimal {

    private int brightness = 255;

    /**
     * Construct the full driver.
     *
     * @param transport configured NeoPixel transport
     * @param n         number of pixels in the strip (≥1)
     */
    public WS2812BFull(Transport transport, int n) {
        super(transport, n);
    }

    /**
     * Get the global brightness scalar.
     *
     * @return current brightness (0–255)
     */
    public int getBrightness() {
        return brightness;
    }

    /**
     * Set the global brightness scalar applied at {@link #show()} time.
     *
     * <p>Stored channel values are not modified; scaling is applied on
     * transmission: {@code sent = stored × brightness / 255}.
     *
     * @param value brightness (0–255, clamped)
     */
    public void setBrightness(int value) {
        this.brightness = Math.max(0, Math.min(255, value));
    }

    /**
     * Write one pixel into the buffer without transmitting.
     *
     * <p>Index is clamped to [0, n−1]; each channel is clamped to [0, 255].
     * Call {@link #show()} to transmit.
     *
     * @param index zero-based pixel index
     * @param r     red channel (0–255)
     * @param g     green channel (0–255)
     * @param b     blue channel (0–255)
     */
    public void setPixel(int index, int r, int g, int b) {
        index = Math.max(0, Math.min(n - 1, index));
        buf[index * 3]     = (byte) Math.max(0, Math.min(255, g));
        buf[index * 3 + 1] = (byte) Math.max(0, Math.min(255, r));
        buf[index * 3 + 2] = (byte) Math.max(0, Math.min(255, b));
    }

    /**
     * Write a sequence of (r, g, b) values into the buffer starting at pixel 0.
     *
     * <p>Extra entries beyond the strip length are ignored. Call {@link #show()} to transmit.
     *
     * @param colors array of {@code {r, g, b}} triples (0–255 each)
     */
    public void setPixels(int[][] colors) {
        int count = Math.min(colors.length, n);
        for (int i = 0; i < count; i++) {
            buf[i * 3]     = (byte) Math.max(0, Math.min(255, colors[i][1])); // g
            buf[i * 3 + 1] = (byte) Math.max(0, Math.min(255, colors[i][0])); // r
            buf[i * 3 + 2] = (byte) Math.max(0, Math.min(255, colors[i][2])); // b
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * <p>Each channel is scaled: {@code sent = stored × brightness / 255}.
     *
     * @throws IOException on transport error
     */
    public void show() throws IOException {
        if (brightness == 255) {
            transport.write(buf);
        } else {
            byte[] scaled = new byte[buf.length];
            for (int i = 0; i < buf.length; i++) {
                scaled[i] = (byte) ((buf[i] & 0xFF) * brightness / 255);
            }
            transport.write(scaled);
        }
    }

    /**
     * Shift the pixel buffer left by {@code steps} positions (wraps around).
     *
     * <p>Does not transmit — call {@link #show()} afterwards.
     *
     * @param steps number of pixel positions to shift left
     */
    public void rotate(int steps) {
        steps = ((steps % n) + n) % n;
        if (steps == 0) return;
        int s3 = steps * 3;
        int n3 = n * 3;
        byte[] tmp = new byte[n3];
        System.arraycopy(buf, s3, tmp, 0, n3 - s3);
        System.arraycopy(buf, 0, tmp, n3 - s3, s3);
        System.arraycopy(tmp, 0, buf, 0, n3);
    }

    /**
     * Fill every pixel with one HSV colour and transmit immediately.
     *
     * <p>Converts HSV to RGB, then calls {@link #fill(int, int, int)}.
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     * @throws IOException on transport error
     */
    public void fillHsv(double h, double s, double v) throws IOException {
        int[] rgb = hsvToRgb(h, s, v);
        fill(rgb[0], rgb[1], rgb[2]);
    }

    private static int[] hsvToRgb(double h, double s, double v) {
        return NeoPixelColor.hsvToRgb(h, s, v);
    }
}
