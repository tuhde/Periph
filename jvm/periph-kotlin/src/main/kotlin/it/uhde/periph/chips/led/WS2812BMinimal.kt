package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * WS2812B addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of [n] WS2812B pixels over a NeoPixel connection.
 * Maintains an internal GRB buffer; [fill] writes all pixels and
 * transmits immediately. No per-pixel addressing or brightness control.
 *
 * Use [WS2812BFull] for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 *
 * Extends the shared Java [NeoPixelRGBMinimal] base, fixing GRB wire order
 * and WS2812B's default reset length.
 *
 * @param connection configured NeoPixel connection
 * @param n number of pixels in the strip (≥1)
 */
class WS2812BMinimal(connection: Connection, n: Int) :
    NeoPixelRGBMinimal(connection, n, CHANNEL_ORDER, RESET_BYTES) {

    companion object {
        private val CHANNEL_ORDER = intArrayOf(1, 0, 2) // GRB: wire[0]=G, wire[1]=R, wire[2]=B
        private const val RESET_BYTES = 16               // ~53us, WS2812B's default minimum
    }
}
