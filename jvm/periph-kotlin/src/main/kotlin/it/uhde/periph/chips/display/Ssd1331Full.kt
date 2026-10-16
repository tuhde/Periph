package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException
import kotlin.math.abs

/**
 * SSD1331 96 x 64 RGB OLED controller — full driver.
 *
 * Extends [Ssd1331Minimal] with software drawing into the framebuffer, partial
 * transfers, the controller's graphic acceleration commands (line, rectangle
 * with fill, copy, dim and clear window, scrolling — these act on the display
 * RAM only, not on the host framebuffer), and the electrical settings (contrast,
 * master current, flip, display mode, power save, dim mode, gray table).
 *
 * @param connection display connection (no BUSY pin)
 * @throws IOException if the bus fails
 */
open class Ssd1331Full(connection: DisplayConn) : Ssd1331Minimal(connection) {

    companion object {
        const val MODE_NORMAL = 0xA4
        const val MODE_ALL_ON = 0xA5
        const val MODE_ALL_OFF = 0xA6
        const val MODE_INVERSE = 0xA7
    }

    // ---- framebuffer ------------------------------------------------------

    /** RGB565 colour of one framebuffer pixel (0 when out of range). */
    fun getPixel(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return 0
        val i = (y * WIDTH + x) * 2
        return ((fb[i].toInt() and 0xFF) shl 8) or (fb[i + 1].toInt() and 0xFF)
    }

    /** Bresenham line into the framebuffer. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var cx = x0
        var cy = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            setPixel(cx, cy, color)
            if (cx == x1 && cy == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; cx += sx }
            if (e2 <= dx) { err += dx; cy += sy }
        }
    }

    /** Rectangle outline or filled rectangle into the framebuffer. */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int, fill: Boolean) {
        if (w <= 0 || h <= 0) return
        for (j in 0 until h) {
            for (i in 0 until w) {
                if (fill || i == 0 || j == 0 || i == w - 1 || j == h - 1) setPixel(x + i, y + j, color)
            }
        }
    }

    /** Copy a row-major `w * h` RGB565 bitmap into the framebuffer. */
    fun blit(x: Int, y: Int, pixels: IntArray, w: Int, h: Int) {
        for (j in 0 until h) {
            for (i in 0 until w) {
                val k = j * w + i
                if (k < pixels.size) setPixel(x + i, y + j, pixels[k])
            }
        }
    }

    /**
     * Send only a rectangle of the framebuffer (one window, row by row). The
     * region is clipped to the panel; an empty region sends nothing.
     */
    @Throws(IOException::class)
    fun showRegion(x: Int, y: Int, w: Int, h: Int) {
        var cx = x
        var cy = y
        var cw = w
        var ch = h
        if (cx < 0) { cw += cx; cx = 0 }
        if (cy < 0) { ch += cy; cy = 0 }
        cw = minOf(cw, WIDTH - cx)
        ch = minOf(ch, HEIGHT - cy)
        if (cw <= 0 || ch <= 0) return
        window(cx, cy, cx + cw - 1, cy + ch - 1)
        for (j in 0 until ch) {
            val start = ((cy + j) * WIDTH + cx) * 2
            connection.sendData(fb.copyOfRange(start, start + cw * 2))
        }
    }

    // ---- graphic acceleration (display RAM only) --------------------------

    private fun colorOps(c: Int): IntArray =
        intArrayOf(((c shr 11) and 0x1F) shl 1, (c shr 5) and 0x3F, (c and 0x1F) shl 1)

    /** Hardware line (`0x21`) on the display RAM. */
    @Throws(IOException::class)
    fun hwLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val c = colorOps(color)
        cmd(CMD_LINE, x0, y0, x1, y1, c[0], c[1], c[2])
    }

    /**
     * Hardware rectangle (`0x26` fill enable + `0x22`) on the display RAM;
     * nothing is sent when [w] or [h] is below 1.
     */
    @Throws(IOException::class)
    fun hwRect(x: Int, y: Int, w: Int, h: Int, lineColor: Int, fillColor: Int, fill: Boolean) {
        if (w <= 0 || h <= 0) return
        cmd(CMD_FILL, if (fill) 1 else 0)
        val l = colorOps(lineColor)
        val f = colorOps(fillColor)
        cmd(CMD_RECT, x, y, x + w - 1, y + h - 1, l[0], l[1], l[2], f[0], f[1], f[2])
    }

    /** Clear a window of the display RAM (`0x25`). */
    @Throws(IOException::class)
    fun hwClear(x0: Int, y0: Int, x1: Int, y1: Int) = cmd(CMD_CLEAR, x0, y0, x1, y1)

    /** Dim a window of the display RAM (`0x24`). */
    @Throws(IOException::class)
    fun hwDim(x0: Int, y0: Int, x1: Int, y1: Int) = cmd(CMD_DIM, x0, y0, x1, y1)

    /** Copy a window of the display RAM to ([x], [y]) (`0x23`). */
    @Throws(IOException::class)
    fun hwCopy(x0: Int, y0: Int, x1: Int, y1: Int, x: Int, y: Int) = cmd(CMD_COPY, x0, y0, x1, y1, x, y)

    /**
     * Configure and start continuous scrolling (`0x27`, `0x2F`): [horizontal]
     * columns per step 0..95, [startRow], [rows], [vertical] rows per step 0..63,
     * [interval] 0 = 6, 1 = 10, 2 = 100, 3 = 200 frames per step.
     */
    @Throws(IOException::class)
    fun scroll(horizontal: Int, startRow: Int, rows: Int, vertical: Int, interval: Int) =
        cmd(CMD_SCROLL_SETUP, horizontal, startRow, rows, vertical, interval and 3, CMD_SCROLL_ON)

    /** Stop scrolling (`0x2E`); the display RAM must be rewritten afterwards. */
    @Throws(IOException::class)
    fun scrollStop() = cmd(CMD_SCROLL_OFF)

    // ---- settings ---------------------------------------------------------

    /** Contrast of colour channels A, B, C (`0x81..0x83`), 0..255. */
    @Throws(IOException::class)
    fun setContrast(a: Int, b: Int, c: Int) = cmd(CMD_CONTRAST_A, a, CMD_CONTRAST_B, b, CMD_CONTRAST_C, c)

    /** Master current (`0x87`), n/16 for n = 1..16 (clamped). */
    @Throws(IOException::class)
    fun setMasterCurrent(n: Int) = cmd(CMD_MASTER_CURRENT, n.coerceIn(1, 16) - 1)

    /** Display mode: [MODE_NORMAL], [MODE_ALL_ON], [MODE_ALL_OFF] or [MODE_INVERSE]. */
    @Throws(IOException::class)
    fun setDisplayMode(mode: Int) = cmd(mode)

    /** Raw remap and colour depth byte (`0xA0`). */
    @Throws(IOException::class)
    fun setRemap(flags: Int) {
        remapFlags = flags and 0xFF
        cmd(CMD_REMAP, remapFlags)
    }

    /** Mirror horizontally (column remap, bit 1) and/or vertically (COM scan, bit 4). */
    @Throws(IOException::class)
    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        var r = remapFlags and 0x12.inv()
        if (horizontal) r = r or 0x02
        if (vertical) r = r or 0x10
        setRemap(r)
    }

    /** Power save mode (`0xB0`): `true` = `0x1A` enable, `false` = `0x0B` disable. */
    @Throws(IOException::class)
    fun setPowerSave(on: Boolean) = cmd(CMD_POWER_SAVE, if (on) 0x1A else 0x0B)

    /** Dim mode (`0xAB` + `0xAC`): contrasts A/B/C and pre-charge voltage 0..31. */
    @Throws(IOException::class)
    fun dimMode(a: Int, b: Int, c: Int, precharge: Int) =
        cmd(CMD_DIM_SETTING, 0x00, a, b, c, precharge and 0x1F, CMD_DISPLAY_DIM)

    /** Restore the linear gray scale table (`0xB9`). */
    @Throws(IOException::class)
    fun grayLinear() = cmd(CMD_GRAY_LINEAR)

    /** Send any command with parameters (all with D/C# low). */
    @Throws(IOException::class)
    fun sendCommand(command: Int, vararg params: Int) {
        cmd(command)
        cmd(*params)
    }
}
