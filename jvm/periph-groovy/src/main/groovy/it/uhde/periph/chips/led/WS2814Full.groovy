package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * WS2814 full interface — extends the shared {@link NeoPixelRGBWFull} Java
 * base with identity RGBW wire order (no reorder) and this chip's 90-byte
 * (~300 µs) extended reset.
 *
 * <p>Adds individual pixel addressing, explicit {@link #show()}, global brightness
 * scaling (0–255), pixel buffer rotation, and HSV fill. Call {@link #setPixel}
 * or {@link #setPixels} to update the buffer, then {@link #show()} to transmit.
 * The inherited {@link #fill} remains available as the fast path for
 * all-same-colour updates (fills and transmits immediately).
 */
@CompileStatic
class WS2814Full extends NeoPixelRGBWFull {

    private static final int[] CHANNEL_ORDER = [0, 1, 2, 3] as int[] // RGBW: identity, no reorder
    private static final int RESET_BYTES = 90                        // ~300us extended reset (>=280us required)

    /**
     * Construct the full driver.
     *
     * @param connection configured NeoPixel connection
     * @param n number of pixels in the strip (≥1)
     */
    WS2814Full(Connection connection, int n) {
        super(connection, n, CHANNEL_ORDER, RESET_BYTES)
    }
}
