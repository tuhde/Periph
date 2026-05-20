package it.uhde.periph.chips.led

import it.uhde.periph.transport.Transport

/**
 * WS2812B full interface — extends [WS2812BMinimal] with per-pixel control.
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
class WS2812BFull(transport: Transport, n: Int) : WS2812BMinimal(transport, n) {

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
     * Call [show] to transmit.
     *
     * @param index zero-based pixel index
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     */
    fun setPixel(index: Int, r: Int, g: Int, b: Int) {
        val i = index.coerceIn(0, n - 1)
        buf[i * 3]     = g.coerceIn(0, 255).toByte()
        buf[i * 3 + 1] = r.coerceIn(0, 255).toByte()
        buf[i * 3 + 2] = b.coerceIn(0, 255).toByte()
    }

    /**
     * Write a sequence of (r, g, b) values into the buffer starting at pixel 0.
     *
     * Extra entries beyond the strip length are ignored. Call [show] to transmit.
     *
     * @param colors list of IntArray triples [r, g, b] (0–255 each)
     */
    fun setPixels(colors: List<IntArray>) {
        for (i in 0 until minOf(colors.size, n)) {
            buf[i * 3]     = colors[i][1].coerceIn(0, 255).toByte() // g
            buf[i * 3 + 1] = colors[i][0].coerceIn(0, 255).toByte() // r
            buf[i * 3 + 2] = colors[i][2].coerceIn(0, 255).toByte() // b
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
        val s3 = s * 3
        val n3 = n * 3
        val tmp = ByteArray(n3)
        buf.copyInto(tmp, 0, s3, n3)
        buf.copyInto(tmp, n3 - s3, 0, s3)
        tmp.copyInto(buf)
    }

    /**
     * Fill every pixel with one HSV colour and transmit immediately.
     *
     * Converts HSV to RGB, then calls [fill].
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     */
    fun fillHsv(h: Double, s: Double, v: Double) {
        val (r, g, b) = hsvToRgb(h, s, v)
        fill(r, g, b)
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
