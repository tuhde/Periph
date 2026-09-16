package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * WS2814 addressable RGBW LED strip — minimal interface.
 *
 * <p>Drives a chain of {@code n} WS2814 pixels over a NeoPixel connection.
 * Maintains an internal RGBW buffer (identity wire order — no reorder
 * needed); {@link #fill} writes all pixels and transmits immediately.
 * Each pixel has four channels: red, green, blue, and white.
 *
 * <p>Use {@link WS2814Full} for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 *
 * <p>Extends the shared Java {@link NeoPixelRGBWMinimal} base, fixing
 * identity RGBW wire order (no reorder) and this chip's 90-byte
 * (~300 µs) extended reset.
 */
@CompileStatic
class WS2814Minimal extends NeoPixelRGBWMinimal {

    private static final int[] CHANNEL_ORDER = [0, 1, 2, 3] as int[] // RGBW: identity, no reorder
    private static final int RESET_BYTES = 90                        // ~300us extended reset (>=280us required)

    /**
     * Construct the driver.
     *
     * @param connection configured NeoPixel connection
     * @param n number of pixels in the strip (≥1)
     */
    WS2814Minimal(Connection connection, int n) {
        super(connection, n, CHANNEL_ORDER, RESET_BYTES)
    }
}
