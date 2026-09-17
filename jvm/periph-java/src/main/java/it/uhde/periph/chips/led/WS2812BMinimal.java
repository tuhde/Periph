package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

/**
 * WS2812B addressable RGB LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} WS2812B pixels over a NeoPixel connection.
 * Maintains an internal GRB buffer; {@link #fill} writes all pixels and
 * transmits immediately. No per-pixel addressing or brightness control.
 *
 * <p>Use {@link WS2812BFull} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 */
public class WS2812BMinimal extends NeoPixelRGBMinimal {

    private static final int[] CHANNEL_ORDER = {1, 0, 2}; // GRB: wire[0]=G, wire[1]=R, wire[2]=B
    private static final int RESET_BYTES = 16;             // ~53us, WS2812B's default minimum

    /**
     * Construct the driver.
     *
     * @param connection configured NeoPixel connection
     * @param n         number of pixels in the strip (≥1)
     */
    public WS2812BMinimal(Connection connection, int n) {
        super(connection, n, CHANNEL_ORDER, RESET_BYTES);
    }
}
