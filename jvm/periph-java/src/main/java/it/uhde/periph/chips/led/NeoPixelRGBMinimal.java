package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.ResetExtender;

import java.io.IOException;

/**
 * Shared minimal-tier logic for 3-channel (RGB) NeoPixel-protocol LED drivers.
 *
 * <p>Drives a chain of {@code n} pixels over a NeoPixel connection. Maintains
 * an internal buffer in wire order; {@link #fill} writes all pixels and
 * transmits immediately. Concrete chip drivers (e.g. {@code WS2812BMinimal})
 * extend this class, fixing {@code channelOrder} and {@code resetBytes} for
 * their specific chip. Kotlin and Groovy chip drivers extend this same Java
 * class directly.
 */
public class NeoPixelRGBMinimal {

    protected final Connection connection;
    protected final int n;
    /** Internal pixel buffer in this chip's wire order. */
    protected final byte[] buf;
    /** Wire byte positions for (r, g, b); shared with {@link NeoPixelRGBFull}. */
    protected final int[] channelOrder;
    private final int resetBytes;

    /**
     * Construct the base with a connection, pixel count, and chip-specific values.
     *
     * @param connection   configured NeoPixel connection
     * @param n            number of pixels in the strip (≥1)
     * @param channelOrder {@code [iR, iG, iB]}; {@code wire[k] = [r,g,b][channelOrder[k]]}
     * @param resetBytes   total trailing zero bytes for this chip's reset pulse
     */
    protected NeoPixelRGBMinimal(Connection connection, int n, int[] channelOrder, int resetBytes) {
        this.connection = connection;
        this.n = n;
        this.buf = new byte[n * 3];
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
     * <p>Each channel is clamped to [0, 255]. Stores the three channels in
     * the internal buffer using this chip's wire channel order.
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @throws IOException on connection error
     */
    public void fill(int r, int g, int b) throws IOException {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        int[] vals = {r, g, b};
        byte w0 = (byte) vals[channelOrder[0]];
        byte w1 = (byte) vals[channelOrder[1]];
        byte w2 = (byte) vals[channelOrder[2]];
        for (int i = 0; i < n; i++) {
            buf[i * 3]     = w0;
            buf[i * 3 + 1] = w1;
            buf[i * 3 + 2] = w2;
        }
        transmit(buf);
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0)}).
     *
     * @throws IOException on connection error
     */
    public void off() throws IOException {
        fill(0, 0, 0);
    }
}
