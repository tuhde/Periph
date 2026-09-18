package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * WS2814 addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of [n] WS2814 pixels over a NeoPixel connection.
 * Maintains an internal RGBW buffer (identity wire order — no reorder
 * needed); [fill] writes all pixels and transmits immediately. Each
 * pixel has four channels: red, green, blue, and white.
 *
 * Use [WS2814Full] for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 *
 * Extends the shared Java [NeoPixelRGBWMinimal] base, fixing identity
 * RGBW wire order (no reorder) and this chip's 90-byte (~300 µs)
 * extended reset.
 *
 * @param connection configured NeoPixel connection
 * @param n number of pixels in the strip (≥1)
 */
class WS2814Minimal(connection: Connection, n: Int) :
    NeoPixelRGBWMinimal(connection, n, CHANNEL_ORDER, RESET_BYTES) {

    companion object {
        private val CHANNEL_ORDER = intArrayOf(0, 1, 2, 3) // RGBW: identity, no reorder
        private const val RESET_BYTES = 90                  // ~300us extended reset (>=280us required)
    }
}
