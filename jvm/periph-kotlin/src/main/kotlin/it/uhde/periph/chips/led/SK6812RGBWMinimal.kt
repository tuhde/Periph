package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of [n] SK6812RGBW pixels over a NeoPixel connection.
 * Maintains an internal GRBW buffer; [fill] writes all pixels and
 * transmits immediately. Each pixel has four channels: red, green,
 * blue, and white (dedicated white LED element).
 *
 * Use [SK6812RGBWFull] for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 *
 * Extends the shared Java [NeoPixelRGBWMinimal] base, fixing GRBW wire
 * order and this chip's 24-byte (~80 µs) extended reset.
 *
 * @param connection configured NeoPixel connection
 * @param n number of pixels in the strip (≥1)
 */
class SK6812RGBWMinimal(connection: Connection, n: Int) :
    NeoPixelRGBWMinimal(connection, n, CHANNEL_ORDER, RESET_BYTES) {

    companion object {
        private val CHANNEL_ORDER = intArrayOf(1, 0, 2, 3) // GRBW: wire[0]=G, wire[1]=R, wire[2]=B, wire[3]=W
        private const val RESET_BYTES = 24                  // ~80us extended reset
    }
}
