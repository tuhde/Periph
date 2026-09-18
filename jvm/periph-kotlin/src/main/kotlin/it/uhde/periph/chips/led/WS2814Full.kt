package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * WS2814 full interface — extends the shared [NeoPixelRGBWFull] Java base
 * with identity RGBW wire order (no reorder) and this chip's 90-byte
 * (~300 µs) extended reset.
 *
 * Adds individual pixel addressing, explicit `show`, global brightness
 * scaling (0–255), pixel buffer rotation, and HSV fill. Call `setPixel`
 * or `setPixels` to update the buffer, then `show` to transmit.
 * The inherited `fill` remains available as the fast path for
 * all-same-colour updates (fills and transmits immediately).
 *
 * @param connection configured NeoPixel connection
 * @param n number of pixels in the strip (≥1)
 */
class WS2814Full(connection: Connection, n: Int) :
    NeoPixelRGBWFull(connection, n, CHANNEL_ORDER, RESET_BYTES) {

    companion object {
        private val CHANNEL_ORDER = intArrayOf(0, 1, 2, 3) // RGBW: identity, no reorder
        private const val RESET_BYTES = 90                  // ~300us extended reset (>=280us required)
    }
}
