package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.ResetExtender;

import java.io.IOException;

/**
 * Shared minimal-tier logic for 4-channel (RGBW) NeoPixel-protocol LED drivers.
 *
 * <p>Drives a chain of {@code n} pixels over a NeoPixel connection. Maintains
 * an internal buffer in wire order; {@link #fill} writes all pixels and
 * transmits immediately. Each pixel has four channels: red, green, blue, and
 * white. Concrete chip drivers (e.g. {@code SK6812RGBWMinimal}) extend this
 * class, fixing {@code channelOrder} and {@code resetBytes} for their
 * specific chip. Kotlin and Groovy chip drivers extend this same Java class
 * directly.
 */
public class NeoPixelRGBWMinimal {

    protected final Connection connection;
    protected final int n;
    /** Internal pixel buffer in this chip's wire order. */
    protected final byte[] buf;
    /** Wire byte positions for (r, g, b, w); shared with {@link NeoPixelRGBWFull}. */
    protected final int[] channelOrder;
    private final int resetBytes;

    /**
     * Construct the base with a connection, pixel count, and chip-specific values.
     *
     * @param connection   configured NeoPixel connection
     * @param n            number of pixels in the strip (≥1)
     * @param channelOrder {@code [iR, iG, iB, iW]}; {@code wire[k] = [r,g,b,w][channelOrder[k]]}
     * @param resetBytes   total trailing zero bytes for this chip's reset pulse
     */
    protected NeoPixelRGBWMinimal(Connection connection, int n, int[] channelOrder, int resetBytes) {
        this.connection = connection;
        this.n = n;
        this.buf = new byte[n * 4];
        this.channelOrder = channelOrder;
        this.resetBytes = resetBytes;
    }

    /**
     * Send {@code data} with this chip's reset pulse length, using
     * {@link ResetExtender} where the connection supports it; falls back to
     * a plain write otherwise.
     */
    protected void transmit(byte[] data) throws IOException {
        if (connection instanceof ResetExtender re) {
            re.writeExt(data, resetBytes);
        } else {
            connection.write(data);
        }
    }

    /**
     * Fill every pixel with one colour and transmit immediately.
     *
     * <p>Each channel is clamped to [0, 255]. Stores the four channels in the
     * internal buffer using this chip's wire channel order. The white
     * channel defaults to 0 for RGB-only usage.
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param w white channel (0–255)
     * @throws IOException on connection error
     */
    public void fill(int r, int g, int b, int w) throws IOException {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        w = Math.max(0, Math.min(255, w));
        int[] vals = {r, g, b, w};
        byte w0 = (byte) vals[channelOrder[0]];
        byte w1 = (byte) vals[channelOrder[1]];
        byte w2 = (byte) vals[channelOrder[2]];
        byte w3 = (byte) vals[channelOrder[3]];
        for (int i = 0; i < n; i++) {
            buf[i * 4]     = w0;
            buf[i * 4 + 1] = w1;
            buf[i * 4 + 2] = w2;
            buf[i * 4 + 3] = w3;
        }
        transmit(buf);
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0, 0)}).
     *
     * @throws IOException on connection error
     */
    public void off() throws IOException {
        fill(0, 0, 0, 0);
    }
}
