package it.uhde.periph.chips.led

import it.uhde.periph.transport.Transport

/**
 * SK6812RGBW full interface — extends [SK6812RGBWMinimal] with per-pixel control.
 *
 * Adds individual pixel addressing, explicit [show], global brightness
 * scaling (0–255), pixel buffer rotation, and HSV fill. Call [setPixel]
 * or [setPixels] to update the buffer, then [show] to transmit.
 * The inherited [fill] remains available as the fast path for
 * all-same-colour updates (fills and transmits immediately).
 *
 * Brightness is stored separately and applied non-destructively at [show]
 * time: `sent = stored × brightness / 255`.
 *
 * @param transport configured NeoPixel transport
 * @param n number of pixels in the strip (≥1)
 */
class SK6812RGBWFull(transport: Transport, n: Int) : SK6812RGBWMinimal(transport, n) {

    /**
     * Global brightness scalar applied at [show] time (0–255).
     * Stored values are not modified; scaling is: sent = stored × brightness / 255.
     */
    var brightness: Int = 255
        set(value) { field = value.coerceIn(0, 255) }

    /**
     * Write one pixel into the buffer without transmitting.
     *
     * Index is clamped to [0, n−1]; each channel is clamped to [0, 255].
     * Call [show] to transmit. The white channel defaults to 0.
     *
     * @param index zero-based pixel index
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param w white channel (0–255)
     */
    fun setPixel(index: Int, r: Int, g: Int, b: Int, w: Int = 0) {
        val i = index.coerceIn(0, n - 1)
        buf[i * 4]     = g.coerceIn(0, 255).toByte()
        buf[i * 4 + 1] = r.coerceIn(0, 255).toByte()
        buf[i * 4 + 2] = b.coerceIn(0, 255).toByte()
        buf[i * 4 + 3] = w.coerceIn(0, 255).toByte()
    }

    /**
     * Write a sequence of (r, g, b, w) values into the buffer starting at pixel 0.
     *
     * Each element may be size 3 ([r, g, b], w=0) or 4 ([r, g, b, w]).
     * Extra entries beyond the strip length are ignored. Call [show] to transmit.
     *
     * @param colors list of IntArray quadruples [r, g, b, w] (0–255 each)
     */
    fun setPixels(colors: List<IntArray>) {
        for (i in 0 until minOf(colors.size, n)) {
            val wc = if (colors[i].size >= 4) colors[i][3] else 0
            buf[i * 4]     = colors[i][1].coerceIn(0, 255).toByte() // g
            buf[i * 4 + 1] = colors[i][0].coerceIn(0, 255).toByte() // r
            buf[i * 4 + 2] = colors[i][2].coerceIn(0, 255).toByte() // b
            buf[i * 4 + 3] = wc.coerceIn(0, 255).toByte()           // w
        }
    }

    /**
     * Transmit the current buffer to the strip, applying brightness scaling.
     *
     * Each channel: sent = stored × brightness / 255.
     */
    fun show() {
        if (brightness == 255) {
            transport.write(buf)
        } else {
            val scaled = ByteArray(buf.size) { i ->
                ((buf[i].toInt() and 0xFF) * brightness / 255).toByte()
            }
            transport.write(scaled)
        }
    }

    /**
     * Shift the pixel buffer left by [steps] positions (wraps around).
     *
     * Does not transmit — call [show] afterwards.
     *
     * @param steps number of pixel positions to shift left (default 1)
     */
    fun rotate(steps: Int = 1) {
        val s = ((steps % n) + n) % n
        if (s == 0) return
        val s4 = s * 4
        val n4 = n * 4
        val tmp = ByteArray(n4)
        buf.copyInto(tmp, 0, s4, n4)
        buf.copyInto(tmp, n4 - s4, 0, s4)
        tmp.copyInto(buf)
    }

    /**
     * Fill every pixel with one HSV colour and transmit immediately.
     *
     * Converts HSV to RGB (w=0), then calls [fill].
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     */
    fun fillHsv(h: Double, s: Double, v: Double) {
        val (r, g, b) = hsvToRgb(h, s, v)
        fill(r, g, b, 0)
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Triple<Int, Int, Int> {
        if (s == 0.0) {
            val c = (v * 255).toInt()
            return Triple(c, c, c)
        }
        val i  = (h * 6.0).toInt()
        val f  = h * 6.0 - i
        val p  = (v * (1.0 - s) * 255).toInt()
        val q  = (v * (1.0 - s * f) * 255).toInt()
        val t  = (v * (1.0 - s * (1.0 - f)) * 255).toInt()
        val vv = (v * 255).toInt()
        return when (i % 6) {
            0    -> Triple(vv, t, p)
            1    -> Triple(q, vv, p)
            2    -> Triple(p, vv, t)
            3    -> Triple(p, q, vv)
            4    -> Triple(t, p, vv)
            else -> Triple(vv, p, q)
        }
    }
}
