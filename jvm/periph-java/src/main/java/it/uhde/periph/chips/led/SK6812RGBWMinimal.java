package it.uhde.periph.chips.led;

import it.uhde.periph.connection.Connection;

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} SK6812RGBW pixels over a NeoPixel connection.
 * Maintains an internal GRBW buffer; {@link #fill} writes all pixels and
 * transmits immediately. Each pixel has four channels: red, green, blue,
 * and white (dedicated white LED element).
 *
 * <p>Use {@link SK6812RGBWFull} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 */
public class SK6812RGBWMinimal extends NeoPixelRGBWMinimal {

    private static final int[] CHANNEL_ORDER = {1, 0, 2, 3}; // GRBW: wire[0]=G, wire[1]=R, wire[2]=B, wire[3]=W
    private static final int RESET_BYTES = 24;                // ~80us extended reset

    /**
     * Construct the driver.
     *
     * @param connection configured NeoPixel connection
     * @param n         number of pixels in the strip (≥1)
     */
    public SK6812RGBWMinimal(Connection connection, int n) {
        super(connection, n, CHANNEL_ORDER, RESET_BYTES);
    }
}
