package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * WS2812B full interface — extends the shared [NeoPixelRGBFull] Java base
 * with GRB wire order and WS2812B's default reset length.
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
class WS2812BFull(connection: Connection, n: Int) :
    NeoPixelRGBFull(connection, n, CHANNEL_ORDER, RESET_BYTES) {

    companion object {
        private val CHANNEL_ORDER = intArrayOf(1, 0, 2) // GRB: wire[0]=G, wire[1]=R, wire[2]=B
        private const val RESET_BYTES = 16               // ~53us, WS2812B's default minimum
    }
}
