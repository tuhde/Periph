package it.uhde.periph.chips.led

import it.uhde.periph.transport.Transport

/**
 * SK6812RGBW addressable RGBW LED strip — minimal interface.
 *
 * Drives a chain of [n] SK6812RGBW pixels over a NeoPixel transport.
 * Maintains an internal GRBW buffer; [fill] writes all pixels and
 * transmits immediately. Each pixel has four channels: red, green,
 * blue, and white (dedicated white LED element).
 *
 * Use [SK6812RGBWFull] for per-pixel addressing, explicit frame control,
 * brightness scaling, and HSV fill.
 *
 * @param transport configured NeoPixel transport
 * @param n number of pixels in the strip (≥1)
 */
open class SK6812RGBWMinimal(
    protected val transport: Transport,
    protected val n: Int
) {
    /** Internal pixel buffer in GRBW wire order (G, R, B, W per pixel). */
    protected val buf: ByteArray = ByteArray(n * 4)

    /**
     * Fill every pixel with one colour and transmit immediately.
     *
     * Each channel is clamped to [0, 255]. Stores values in GRBW wire order.
     * The white channel defaults to 0 for RGB-only usage.
     *
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param w white channel (0–255)
     */
    fun fill(r: Int, g: Int, b: Int, w: Int = 0) {
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        val wc = w.coerceIn(0, 255)
        for (i in 0 until n) {
            buf[i * 4]     = gc.toByte()
            buf[i * 4 + 1] = rc.toByte()
            buf[i * 4 + 2] = bc.toByte()
            buf[i * 4 + 3] = wc.toByte()
        }
        transport.write(buf)
    }

    /**
     * Turn off all pixels (equivalent to [fill](0, 0, 0, 0)).
     */
    fun off() = fill(0, 0, 0, 0)
}
