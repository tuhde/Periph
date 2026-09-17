package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection

/**
 * APA102 full interface — extends [APA102Minimal] with per-pixel control.
 *
 * Adds individual pixel addressing with per-pixel hardware brightness,
 * explicit [show], global software brightness scaling (0–255),
 * buffer rotation, and HSV fill. Call [setPixel] or
 * [setPixels] to update the buffer, then [show] to transmit.
 * The inherited [fill] remains available as the fast path for
 * all-same-colour updates (fills and transmits immediately).
 *
 * The per-pixel hardware brightness field (0–31) is stored in the
 * buffer and is NOT affected by the global software brightness scalar.
 * Software brightness scaling is applied at [show] time:
 * `sent = stored × brightness / 255`.
 *
 * @param connection configured SPI connection (Mode 0, MSB first)
 * @param n number of pixels in the strip (≥1)
 */
class APA102Full(connection: Connection, n: Int) : APA102Minimal(connection, n) {

    /**
     * Global software brightness scalar applied at [show] time (0–255).
     * Stored RGB values are not modified; scaling is: sent = stored × brightness / 255.
     * The per-pixel hardware brightness byte is NOT scaled.
     */
    var brightness: Int = 255
        set(value) { field = value.coerceIn(0, 255) }

    /**
     * Write one pixel into the buffer without transmitting.
     *
     * Index is clamped to [0, n−1]; each RGB channel is clamped to
     * [0, 255]; pixelBrightness is clamped to [0, 31]. Call
     * [show] to transmit.
     *
     * @param index zero-based pixel index
     * @param r red channel (0–255)
     * @param g green channel (0–255)
     * @param b blue channel (0–255)
     * @param pixelBrightness per-pixel hardware brightness 0–31 (default 31)
     */
    fun setPixel(index: Int, r: Int, g: Int, b: Int, pixelBrightness: Int = 31) {
        if (n == 0) return
        val i = index.coerceIn(0, n - 1)
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        val pb = pixelBrightness.coerceIn(0, 31)
        buf[i * 4]     = (0xE0 or pb).toByte()
        buf[i * 4 + 1] = bc.toByte()
        buf[i * 4 + 2] = gc.toByte()
        buf[i * 4 + 3] = rc.toByte()
    }

    /**
     * Write multiple pixels from a list into the buffer starting at pixel 0.
     *
     * Each element is an IntArray of [r, g, b] or [r, g, b, pixelBrightness].
     * Missing brightness defaults to 31. Extra entries beyond the strip
     * length are ignored. Call [show] to transmit.
     *
     * @param colors list of IntArray triples [r, g, b] or quadruples [r, g, b, pixelBrightness]
     */
    fun setPixels(colors: List<IntArray>) {
        for (i in 0 until minOf(colors.size, n)) {
            val c = colors[i]
            val rc = c[0].coerceIn(0, 255)
            val gc = c[1].coerceIn(0, 255)
            val bc = c[2].coerceIn(0, 255)
            val pb = if (c.size > 3) c[3].coerceIn(0, 31) else 31
            buf[i * 4]     = (0xE0 or pb).toByte()
            buf[i * 4 + 1] = bc.toByte()
            buf[i * 4 + 2] = gc.toByte()
            buf[i * 4 + 3] = rc.toByte()
        }
    }

    /**
     * Transmit the current buffer to the strip, applying software brightness scaling.
     *
     * Each RGB channel value is scaled: `sent = stored × brightness / 255`.
     * The per-pixel hardware brightness byte is NOT scaled.
     */
    override fun show() {
        val endBytes = maxOf(4, (n + 15) / 16)
        val pixelDataLen = n * 4
        val totalLen = 4 + pixelDataLen + endBytes

        val frame = ByteArray(totalLen)
        frame[0] = 0x00.toByte()
        frame[1] = 0x00.toByte()
        frame[2] = 0x00.toByte()
        frame[3] = 0x00.toByte()

        if (brightness == 255) {
            buf.copyInto(frame, 4, 0, pixelDataLen)
        } else {
            for (i in 0 until n) {
                val base = i * 4
                frame[4 + base]     = buf[base]
                frame[4 + base + 1] = ((buf[base + 1].toInt() and 0xFF) * brightness / 255).toByte()
                frame[4 + base + 2] = ((buf[base + 2].toInt() and 0xFF) * brightness / 255).toByte()
                frame[4 + base + 3] = ((buf[base + 3].toInt() and 0xFF) * brightness / 255).toByte()
            }
        }

        for (i in 0 until endBytes) {
            frame[4 + pixelDataLen + i] = 0xFF.toByte()
        }

        connection.write(frame)
    }

    /**
     * Shift the pixel buffer left by [steps] whole-pixel positions (wraps around).
     *
     * Each step shifts 4 bytes (one BGR+brightness pixel). Does not
     * transmit — call [show] afterwards.
     *
     * @param steps number of pixel positions to shift left (default 1)
     */
    override fun rotate(steps: Int = 1) {
        if (n == 0) return
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
     * Converts HSV to RGB, then calls [fill] at hardware brightness 31.
     *
     * @param h hue (0.0–1.0)
     * @param s saturation (0.0–1.0)
     * @param v value / brightness (0.0–1.0)
     */
    override fun fillHsv(h: Double, s: Double, v: Double) {
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