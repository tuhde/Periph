package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * SK6812RGBW full interface — extends the shared [NeoPixelRGBWFull] Java
 * base with GRBW wire order and this chip's 24-byte (~80 µs) extended
 * reset.
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
class SK6812RGBWFull(connection: Connection, n: Int) :
    NeoPixelRGBWFull(connection, n, CHANNEL_ORDER, RESET_BYTES) {

    companion object {
        private val CHANNEL_ORDER = intArrayOf(1, 0, 2, 3) // GRBW: wire[0]=G, wire[1]=R, wire[2]=B, wire[3]=W
        private const val RESET_BYTES = 24                  // ~80us extended reset
    }
}
