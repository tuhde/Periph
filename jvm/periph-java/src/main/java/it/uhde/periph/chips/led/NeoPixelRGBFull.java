package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * Shared full-tier logic for 3-channel (RGB) NeoPixel-protocol LED drivers.
 *
 * <p>Adds individual pixel addressing, explicit {@link #show()}, global
 * brightness scaling, buffer rotation, and HSV fill on top of
 * {@link NeoPixelRGBMinimal}. Concrete chip drivers (e.g. {@code WS2812BFull})
 * extend this class, fixing {@code channelOrder} and {@code resetBytes} for
 * their specific chip. Kotlin and Groovy chip drivers extend this same Java
 * class directly.
 */
public class NeoPixelRGBFull extends NeoPixelRGBMinimal {

    private int brightness = 255;

    /**
     * Construct the base with a connection, pixel count, and chip-specific values.
     *
     * @param connection   configured NeoPixel connection
     * @param n            number of pixels in the strip (≥1)
     * @param channelOrder {@code [iR, iG, iB]} wire channel order
     * @param resetBytes   total trailing zero bytes for this chip's reset pulse
     */
    protected NeoPixelRGBFull(Connection connection, int n, int[] channelOrder, int resetBytes) {
        super(connection, n, channelOrder, resetBytes);
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
        if (n == 0) return;
        index = Math.max(0, Math.min(n - 1, index));
        int[] vals = {
            Math.max(0, Math.min(255, r)),
            Math.max(0, Math.min(255, g)),
            Math.max(0, Math.min(255, b)),
        };
        buf[index * 3]     = (byte) vals[channelOrder[0]];
        buf[index * 3 + 1] = (byte) vals[channelOrder[1]];
        buf[index * 3 + 2] = (byte) vals[channelOrder[2]];
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
            int[] vals = {
                Math.max(0, Math.min(255, colors[i][0])),
                Math.max(0, Math.min(255, colors[i][1])),
                Math.max(0, Math.min(255, colors[i][2])),
            };
            buf[i * 3]     = (byte) vals[channelOrder[0]];
            buf[i * 3 + 1] = (byte) vals[channelOrder[1]];
            buf[i * 3 + 2] = (byte) vals[channelOrder[2]];
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * <p>Each channel is scaled: {@code sent = stored × brightness / 255}.
     *
     * @throws IOException on connection error
     */
    public void show() throws IOException {
        if (brightness == 255) {
            transmit(buf);
        } else {
            byte[] scaled = new byte[buf.length];
            for (int i = 0; i < buf.length; i++) {
                scaled[i] = (byte) ((buf[i] & 0xFF) * brightness / 255);
            }
            transmit(scaled);
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
        if (n == 0) return;
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
     * @throws IOException on connection error
     */
    public void fillHsv(double h, double s, double v) throws IOException {
        int[] rgb = NeoPixelColor.hsvToRgb(h, s, v);
        fill(rgb[0], rgb[1], rgb[2]);
    }
}
