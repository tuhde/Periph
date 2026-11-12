package it.uhde.periph.chips.display

/**
 * Page-major monochrome framebuffer (bit 0 of a byte = top pixel of its page) with drawing primitives, shared by
 * the SSD130x style OLED drivers.
 *
 * @param width columns
 * @param height rows, a multiple of 8
 */
class MonoFramebuffer(val width: Int, val height: Int) {

    /** Number of 8-pixel pages. */
    val pages: Int = height / 8

    /** The page-major bytes (live view, `width * pages` long). */
    val data: ByteArray = ByteArray(width * pages)

    /** The framebuffer column-major (for vertical addressing). */
    fun transposed(): ByteArray {
        val out = ByteArray(data.size)
        var n = 0
        for (x in 0 until width) for (p in 0 until pages) out[n++] = data[p * width + x]
        return out
    }

    /** Zero the framebuffer. */
    fun clear() = data.fill(0)

    /** Set (or clear) every pixel. */
    fun fill(on: Boolean) = data.fill(if (on) 0xFF.toByte() else 0)

    /** Set or clear one pixel; out-of-range coordinates are ignored. */
    fun setPixel(x: Int, y: Int, on: Boolean) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val i = (y shr 3) * width + x
        val mask = 1 shl (y and 7)
        data[i] = if (on) (data[i].toInt() or mask).toByte() else (data[i].toInt() and mask.inv()).toByte()
    }

    /** Return a pixel; out-of-range coordinates read as false. */
    fun getPixel(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        return (data[(y shr 3) * width + x].toInt() and (1 shl (y and 7))) != 0
    }

    /** Bresenham line. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, on: Boolean) {
        var x = x0
        var y = y0
        val dx = Math.abs(x1 - x)
        val dy = -Math.abs(y1 - y)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx + dy
        while (true) {
            setPixel(x, y, on)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Rectangle outline or filled rectangle. */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, on: Boolean, fill: Boolean) {
        if (w <= 0 || h <= 0) return
        if (fill) {
            for (yy in y until y + h) for (xx in x until x + w) setPixel(xx, yy, on)
            return
        }
        drawLine(x, y, x + w - 1, y, on)
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, on)
        drawLine(x, y, x, y + h - 1, on)
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, on)
    }

    /**
     * Copy a column-major bitmap (8 pixels per byte, bit 0 = top); clipped. [columns] holds [heightPages] groups of
     * columns, page by page.
     *
     * @throws IllegalArgumentException if [y] is not a multiple of 8
     */
    fun blit(x: Int, y: Int, columns: ByteArray, heightPages: Int) {
        require(y % 8 == 0) { "y must be a multiple of 8" }
        val n = columns.size / heightPages
        for (p in 0 until heightPages) {
            val page = y / 8 + p
            if (page < 0 || page >= pages) continue
            for (c in 0 until n) {
                val xx = x + c
                if (xx in 0 until width) data[page * width + xx] = columns[p * n + c]
            }
        }
    }
}
