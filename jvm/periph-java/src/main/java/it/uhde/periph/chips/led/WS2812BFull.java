package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

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
public class WS2812BFull extends NeoPixelRGBFull {

    private static final int[] CHANNEL_ORDER = {1, 0, 2}; // GRB: wire[0]=G, wire[1]=R, wire[2]=B
    private static final int RESET_BYTES = 16;             // ~53us, WS2812B's default minimum

    /**
     * Construct the full driver.
     *
     * @param connection configured NeoPixel connection
     * @param n         number of pixels in the strip (≥1)
     */
    public WS2812BFull(Connection connection, int n) {
        super(connection, n, CHANNEL_ORDER, RESET_BYTES);
    }
}
