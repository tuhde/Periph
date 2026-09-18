package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * APA102 addressable RGB LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} APA102 pixels over an SPI connection
 * (Mode 0, MSB first). Maintains an internal BGR+brightness buffer;
 * {@link #fill} writes all pixels and transmits the full frame
 * (start + pixels + end) immediately. No per-pixel addressing or
 * brightness control.
 *
 * <p>The APA102 frame format is:
 * <ul>
 *   <li>start frame: 4 zero-bytes (0x00 × 4)</li>
 *   <li>pixel data:  n × 4 bytes [0xE0|brightness, B, G, R] (BGR wire order)</li>
 *   <li>end frame:   max(4, (n+15)//16) bytes of 0xFF</li>
 * </ul>
 *
 * <p>Use {@link APA102Full} for per-pixel addressing with per-pixel
 * hardware brightness, explicit frame control, global software brightness
 * scaling, buffer rotation, and HSV fill.
 */
public class APA102Minimal {

    protected final Connection connection;
    protected final int n;
    /** Internal pixel buffer in BGR+brightness wire order ([0xE0|brightness, B, G, R] per pixel). */
    protected final byte[] buf;

    /**
     * Construct the driver.
     *
     * @param connection configured SPI connection (Mode 0, MSB first)
     * @param n         number of pixels in the strip (≥1)
     */
    public APA102Minimal(Connection connection, int n) {
        this.connection = connection;
        this.n = n;
        this.buf = new byte[n * 4];
        // Initialize buffer with hardware brightness=31, all channels off
        for (int i = 0; i < n; i++) {
            buf[i * 4]     = (byte) (0xE0 | 31); // brightness byte (3 high bits = 1)
            buf[i * 4 + 1] = 0;                  // blue
            buf[i * 4 + 2] = 0;                  // green
            buf[i * 4 + 3] = 0;                  // red
        }
    }

    /**
     * Fill every pixel with one colour and transmit the full APA102 frame
     * immediately.
     *
     * <p>Each channel is clamped to [0, 255]. Stores brightness/B/G/R in
     * the internal buffer (BGR wire order with hardware brightness byte
     * first), then sends start frame + pixel data + end frame.
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
        for (int i = 0; i < n; i++) {
            buf[i * 4]     = (byte) (0xE0 | 31); // hardware brightness = 31 (max)
            buf[i * 4 + 1] = (byte) b;           // blue
            buf[i * 4 + 2] = (byte) g;           // green
            buf[i * 4 + 3] = (byte) r;           // red
        }
        sendFrame();
    }

    /**
     * Turn off all pixels (equivalent to {@code fill(0, 0, 0)}).
     *
     * @throws IOException on connection error
     */
    public void off() throws IOException {
        fill(0, 0, 0);
    }

    /**
     * Send the full APA102 frame (start + pixel buffer + end).
     */
    protected void sendFrame() throws IOException {
        int endBytes = Math.max(4, (n + 15) / 16);
        byte[] frame = new byte[4 + n * 4 + endBytes];
        frame[0] = frame[1] = frame[2] = frame[3] = 0x00; // start frame
        System.arraycopy(buf, 0, frame, 4, n * 4);        // pixel data
        for (int i = 0; i < endBytes; i++) {
            frame[4 + n * 4 + i] = (byte) 0xFF;           // end frame
        }
        connection.write(frame);
    }
}