package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * Shared full-tier logic for 4-channel (RGBW) NeoPixel-protocol LED drivers.
 *
 * <p>Adds individual pixel addressing, explicit {@link #show()}, global
 * brightness scaling, buffer rotation, and HSV fill on top of
 * {@link NeoPixelRGBWMinimal}. Concrete chip drivers (e.g. {@code SK6812RGBWFull})
 * extend this class, fixing {@code channelOrder} and {@code resetBytes} for
 * their specific chip. Kotlin and Groovy chip drivers extend this same Java
 * class directly.
 */
public class NeoPixelRGBWFull extends NeoPixelRGBWMinimal {

    private int brightness = 255;

    /**
     * Construct the base with a connection, pixel count, and chip-specific values.
     *
     * @param connection   configured NeoPixel connection
     * @param n            number of pixels in the strip (≥1)
     * @param channelOrder {@code [iR, iG, iB, iW]} wire channel order
     * @param resetBytes   total trailing zero bytes for this chip's reset pulse
     */
    protected NeoPixelRGBWFull(Connection connection, int n, int[] channelOrder, int resetBytes) {
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
     * Call {@link #show()} to transmit. The white channel defaults to 0.
     *
     * @param index zero-based pixel index
     * @param r     red channel (0–255)
     * @param g     green channel (0–255)
     * @param b     blue channel (0–255)
     * @param w     white channel (0–255)
     */
    public void setPixel(int index, int r, int g, int b, int w) {
        if (n == 0) return;
        index = Math.max(0, Math.min(n - 1, index));
        int[] vals = {
            Math.max(0, Math.min(255, r)),
            Math.max(0, Math.min(255, g)),
            Math.max(0, Math.min(255, b)),
            Math.max(0, Math.min(255, w)),
        };
        buf[index * 4]     = (byte) vals[channelOrder[0]];
        buf[index * 4 + 1] = (byte) vals[channelOrder[1]];
        buf[index * 4 + 2] = (byte) vals[channelOrder[2]];
        buf[index * 4 + 3] = (byte) vals[channelOrder[3]];
    }

    /**
     * Write a sequence of (r, g, b, w) values into the buffer starting at pixel 0.
     *
     * <p>Each element may be length 3 ({r, g, b}, w=0) or 4 ({r, g, b, w}).
     * Extra entries beyond the strip length are ignored. Call {@link #show()} to transmit.
     *
     * @param colors array of {@code {r, g, b}} or {@code {r, g, b, w}} quadruples (0–255 each)
     */
    public void setPixels(int[][] colors) {
        int count = Math.min(colors.length, n);
        for (int i = 0; i < count; i++) {
            int w = colors[i].length >= 4 ? colors[i][3] : 0;
            int[] vals = {
                Math.max(0, Math.min(255, colors[i][0])),
                Math.max(0, Math.min(255, colors[i][1])),
                Math.max(0, Math.min(255, colors[i][2])),
                Math.max(0, Math.min(255, w)),
            };
            buf[i * 4]     = (byte) vals[channelOrder[0]];
            buf[i * 4 + 1] = (byte) vals[channelOrder[1]];
            buf[i * 4 + 2] = (byte) vals[channelOrder[2]];
            buf[i * 4 + 3] = (byte) vals[channelOrder[3]];
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
        int s4 = steps * 4;
        int n4 = n * 4;
        byte[] tmp = new byte[n4];
        System.arraycopy(buf, s4, tmp, 0, n4 - s4);
        System.arraycopy(buf, 0, tmp, n4 - s4, s4);
        System.arraycopy(tmp, 0, buf, 0, n4);
    }

    /**
     * Fill every pixel with one HSV colour and transmit immediately.
     *
     * <p>Converts HSV to RGB (w=0), then calls {@link #fill(int, int, int, int)}.
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     * @throws IOException on connection error
     */
    public void fillHsv(double h, double s, double v) throws IOException {
        int[] rgb = NeoPixelColor.hsvToRgb(h, s, v);
        fill(rgb[0], rgb[1], rgb[2], 0);
    }
}
