package it.uhde.periph.chips.led

import it.uhde.periph.transport.Transport

/**
 * WS2812B addressable RGB LED strip — minimal interface.
 *
 * Drives a chain of [n] WS2812B pixels over a NeoPixel transport.
 * Maintains an internal GRB buffer; [fill] writes all pixels and
 * transmits immediately. No per-pixel addressing or brightness control.
 *
 * Use [WS2812BFull] for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 *
 * @param transport configured NeoPixel transport
 * @param n number of pixels in the strip (≥1)
 */
open class WS2812BMinimal(
    protected val transport: Transport,
    protected val n: Int
) {
    /** Internal pixel buffer in GRB wire order (G, R, B per pixel). */
    protected val buf: ByteArray = ByteArray(n * 3)

    /**
     * Fill every pixel with one colour and transmit immediately.
     *
     * Each channel is clamped to [0, 255]. Stores values in GRB wire order
     * (WS2812B expects G, R, B on the data line).
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     */
    fun fill(r: Int, g: Int, b: Int) {
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        for (i in 0 until n) {
            buf[i * 3]     = gc.toByte()
            buf[i * 3 + 1] = rc.toByte()
            buf[i * 3 + 2] = bc.toByte()
        }
        transport.write(buf)
    }

    /**
     * Turn off all pixels (equivalent to [fill](0, 0, 0)).
     */
    fun off() = fill(0, 0, 0)
}
