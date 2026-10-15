package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import java.io.IOException
import kotlin.math.abs

/**
 * UC8276C black/white/red e-paper controller — full driver.
 *
 * Extends [Uc8276cMinimal] with drawing helpers, partial-window updates, the
 * auto sequence, pixel mode / scan direction, power and VCOM settings, frame
 * rate, user LUTs and the read commands (status, temperature, revision, VCOM,
 * low power, OTP).
 *
 * Register layouts follow the panel datasheet where it disagrees with the chip
 * datasheet (PWR, PLL, VCOM_DC); see `specs/display/uc8276c.md`. The `read*`
 * methods need a half-duplex wire mode; with a SIMPLEX connection they throw
 * [UnsupportedOperationException].
 *
 * @param connection display connection
 * @param width pixels per row, a multiple of 8, 8..400
 * @param height rows, 1..300
 * @param busyTimeoutMs limit for every BUSY_N wait, `<= 0` for no limit
 */
open class Uc8276cFull @JvmOverloads constructor(
    connection: DisplayConn,
    width: Int = MAX_WIDTH,
    height: Int = MAX_HEIGHT,
    busyTimeoutMs: Long = 30_000L,
) : Uc8276cMinimal(connection, width, height, busyTimeoutMs) {

    private companion object {
        /** Frame rate in Hz for PLL M (1..7) and N (1..7), panel datasheet p. 24. */
        val PLL_HZ = arrayOf(
            intArrayOf(29, 14, 10, 7, 6, 5, 4),
            intArrayOf(57, 29, 19, 14, 11, 10, 8),
            intArrayOf(86, 43, 29, 21, 17, 14, 12),
            intArrayOf(114, 57, 38, 29, 23, 19, 16),
            intArrayOf(150, 72, 48, 36, 29, 24, 20),
            intArrayOf(171, 86, 57, 43, 34, 29, 24),
            intArrayOf(200, 100, 67, 50, 40, 33, 29),
        )

        fun code(value: Double, base: Double, step: Double, maxCode: Int): Int =
            ((value - base) / step + 0.5).toInt().coerceIn(0, maxCode)
    }

    // ---- framebuffer -------------------------------------------------------------------

    /**
     * Colour of one framebuffer pixel: [BLACK], [WHITE] or [RED] ([BLACK] when out of range).
     *
     * @param x column
     * @param y row
     */
    fun getPixel(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return BLACK
        val index = y * rowBytes + (x shr 3)
        val mask = 0x80 shr (x and 7)
        val r = red
        if (r != null && (r[index].toInt() and mask) != 0) return RED
        return if ((bw[index].toInt() and mask) != 0) WHITE else BLACK
    }

    /** Bresenham line into the framebuffer. */
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, color: Int = BLACK) {
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

    /** Rectangle outline or, with [fill], a filled rectangle into the framebuffer. */
    fun drawRect(x: Int, y: Int, w: Int, h: Int, color: Int = BLACK, fill: Boolean = false) {
        if (w <= 0 || h <= 0) return
        if (fill) {
            for (j in 0 until h) for (i in 0 until w) setPixel(x + i, y + j, color)
            return
        }
        drawLine(x, y, x + w - 1, y, color)
        drawLine(x, y + h - 1, x + w - 1, y + h - 1, color)
        drawLine(x, y, x, y + h - 1, color)
        drawLine(x + w - 1, y, x + w - 1, y + h - 1, color)
    }

    /**
     * Copy a 1-bit-per-pixel bitmap (row-major, MSB-first, rows padded to whole
     * bytes) into the framebuffer. Set bits are drawn in [color], clear bits
     * leave the framebuffer unchanged.
     */
    fun blit(x: Int, y: Int, data: ByteArray, w: Int, h: Int, color: Int = BLACK) {
        if (w <= 0 || h <= 0) return
        val rb = (w + 7) / 8
        for (j in 0 until h) {
            for (i in 0 until w) {
                if ((data[j * rb + (i shr 3)].toInt() and (0x80 shr (i and 7))) != 0) {
                    setPixel(x + i, y + j, color)
                }
            }
        }
    }

    // ---- updates -----------------------------------------------------------------------

    /**
     * Update only a window: PTIN, PTL (window widened to whole 8-pixel columns),
     * the window's bytes of both planes, PON, DRF, POF, PTOUT. Saves SPI time;
     * the waveform is the OTP full waveform, so the panel still flashes.
     *
     * @throws IllegalArgumentException if the window is empty or outside the panel
     * @throws IOException on a bus error or BUSY_N timeout
     */
    fun showRegion(x: Int, y: Int, w: Int, h: Int) {
        var wx = x
        var wy = y
        var ww = w
        var wh = h
        require(ww > 0 && wh > 0) { "empty window" }
        if (wx < 0) { ww += wx; wx = 0 }
        if (wy < 0) { wh += wy; wy = 0 }
        require(wx < width && wy < height && ww > 0 && wh > 0) { "window outside the panel" }
        val x0 = wx and 7.inv()
        val x1 = minOf((wx + ww - 1) or 7, width - 1)
        val y0 = wy
        val y1 = minOf(wy + wh - 1, height - 1)
        if (asleep) wake()
        connection.sendCommand(CMD_PTIN, ByteArray(0))
        connection.sendCommand(
            CMD_PTL,
            byteArrayOf(
                ((x0 shr 8) and 1).toByte(), (x0 and 0xF8).toByte(),
                ((x1 shr 8) and 1).toByte(), ((x1 and 0xF8) or 0x07).toByte(),
                ((y0 shr 8) and 1).toByte(), (y0 and 0xFF).toByte(),
                ((y1 shr 8) and 1).toByte(), (y1 and 0xFF).toByte(),
                0x00, // PT_SCAN = 0: scan only inside the window
            ),
        )
        val winBytes = (x1 - x0 + 1) / 8
        for (plane in 0..1) {
            connection.sendCommand(if (plane == 0) CMD_DTM1 else CMD_DTM2, ByteArray(0))
            val buf = if (plane == 0) bw else red
            for (row in y0..y1) {
                if (buf == null) {
                    sendZeros(winBytes)
                } else {
                    val start = row * rowBytes + (x0 shr 3)
                    connection.sendData(buf.copyOfRange(start, start + winBytes))
                }
            }
        }
        try {
            powerCycleRefresh()
        } finally {
            connection.sendCommand(CMD_PTOUT, ByteArray(0))
        }
    }

    /**
     * Full-frame update using the `AUTO` sequence instead of PON/DRF/POF.
     *
     * `AUTO` is listed only in the chip datasheet. On the reference panel
     * (Waveshare 4.2inch e-Paper (B)) it starts no refresh: the call returns after
     * the plane transfer and the panel keeps its picture. Use [show] unless your
     * panel is known to accept it.
     *
     * @param deepSleep `true` runs `AUTO 0xA7` (also enters deep sleep)
     */
    fun refreshAuto(deepSleep: Boolean = false) {
        if (asleep) wake()
        writePlanes()
        connection.sendCommand(CMD_AUTO, byteArrayOf((if (deepSleep) 0xA7 else 0xA5).toByte()))
        waitReady()
        if (deepSleep) asleep = true
    }

    /** Hardware reset and initialisation (exit from deep sleep). Framebuffers are kept. */
    public override fun wake() = super.wake()

    /**
     * Wait for BUSY_N to go high.
     *
     * @param timeoutMs limit in milliseconds, `<= 0` for no limit
     * @return `true` when ready, `false` on timeout
     */
    fun waitBusy(timeoutMs: Long = busyTimeoutMs): Boolean = connection.waitBusy(false, timeoutMs)

    // ---- settings ----------------------------------------------------------------------

    private fun writePsr() = connection.sendCommand(CMD_PSR, byteArrayOf(psr.toByte()))

    /** Pixel mode: [MODE_KWR] (black/white/red, default) or [MODE_KW] (needs KW LUTs in OTP). */
    fun setMode(mode: Int) {
        psr = if (mode == MODE_KW) psr or 0x10 else psr and 0x10.inv()
        writePsr()
    }

    /** Mirror the picture: [horizontal] clears SHL, [vertical] clears UD. The framebuffer is unchanged. */
    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        psr = if (horizontal) psr and 0x04.inv() else psr or 0x04
        psr = if (vertical) psr and 0x08.inv() else psr or 0x08
        writePsr()
    }

    /**
     * Source/gate power (`PWR`, panel-datasheet layout, internal DC-DC).
     *
     * Advanced: the values must match the panel vendor's; a wrong set can drive
     * the panel outside its rating. The driver does not write PWR by default.
     *
     * @param vdh positive source voltage, 2.4..11.0 V (0.2 V steps)
     * @param vdl negative source voltage magnitude, 2.4..11.0 V
     * @param vdhr red source voltage, 2.4..11.0 V
     * @param vgh gate voltage magnitude: 16, 15, 14 or 13 V (VGL = -VGH)
     */
    fun setPower(vdh: Double, vdl: Double, vdhr: Double, vgh: Double = 16.0) {
        val lv = (16.0 - vgh + 0.5).toInt().coerceIn(0, 3)
        connection.sendCommand(
            CMD_PWR,
            byteArrayOf(
                0x03, // VDS_EN | VDG_EN
                lv.toByte(),
                code(vdh, 2.4, 0.2, 0x2B).toByte(),
                code(abs(vdl), 2.4, 0.2, 0x2B).toByte(),
                code(vdhr, 2.4, 0.2, 0x2B).toByte(),
            ),
        )
    }

    /** VCOM_DC (`VDCS`): -0.10 .. -3.25 V in 0.05 V steps (panel layout). */
    fun setVcomDc(volts: Double) {
        connection.sendCommand(CMD_VDCS, byteArrayOf(code(-0.10 - volts, 0.0, 0.05, 0x3F).toByte()))
    }

    /** Frame rate (`PLL`, M/N layout): the nearest rate of the panel's table, 4..200 Hz. */
    fun setFrameRate(hz: Int) {
        var best = 0x3C // M = 7, N = 4 (50 Hz)
        var bestDiff = Int.MAX_VALUE
        for (m in 1..7) {
            for (n in 1..7) {
                val diff = abs(PLL_HZ[m - 1][n - 1] - hz)
                if (diff < bestDiff) { bestDiff = diff; best = (m shl 3) or n }
            }
        }
        connection.sendCommand(CMD_PLL, byteArrayOf(best.toByte()))
    }

    /** Booster soft start (`BTST`): phase A, B, C bytes. */
    fun setSoftStart(a: Int, b: Int, c: Int) {
        connection.sendCommand(CMD_BTST, byteArrayOf(a.toByte(), b.toByte(), c.toByte()))
    }

    /**
     * VCOM and data interval (`CDI`).
     *
     * @param cdi interval, 0..15 (0b0111 = 10 hsync, the default)
     * @param border border data selection VBD, 0..3 (3 = floating in KWR)
     * @param polarity data polarity DDX, 0..3; the framebuffer mapping assumes 1
     */
    fun setInterval(cdi: Int, border: Int = 3, polarity: Int = 1) {
        val v = ((border and 3) shl 6) or ((polarity and 3) shl 4) or (cdi and 0x0F)
        connection.sendCommand(CMD_CDI, byteArrayOf(v.toByte()))
    }

    /** Temperature sensor source (`TSE`): internal (default) or external LM75, offset -8..+7 degrees Celsius. */
    fun setTemperatureSensor(external: Boolean, offset: Int = 0) {
        val v = (if (external) 0x80 else 0x00) or (offset and 0x0F)
        connection.sendCommand(CMD_TSE, byteArrayOf(v.toByte()))
    }

    /**
     * Load user waveform tables and select them (`PSR.REG = 1`). The LUT commands
     * exist only in the chip datasheet; verify the panel accepts them. Lengths:
     * 56, 42, 56, 56, 56 bytes. Any table may be `null` to leave it alone.
     */
    fun writeLut(lutC: ByteArray?, lutWW: ByteArray?, lutKW: ByteArray?, lutWK: ByteArray?, lutKK: ByteArray?) {
        val cmds = intArrayOf(CMD_LUTC, CMD_LUTWW, CMD_LUTKW, CMD_LUTWK, CMD_LUTKK)
        val tables = arrayOf(lutC, lutWW, lutKW, lutWK, lutKK)
        for (i in cmds.indices) tables[i]?.let { connection.sendCommand(cmds[i], it) }
        psr = psr or 0x20
        writePsr()
    }

    /** Send a raw command and parameters (escape hatch). */
    fun sendCommand(command: Int, data: ByteArray = ByteArray(0)) {
        connection.sendCommand(command, data)
    }

    // ---- reads (half-duplex wiring only) -----------------------------------------------

    private fun read(cmd: Int, length: Int): ByteArray {
        connection.sendCommand(cmd, ByteArray(0))
        return connection.readData(length)
    }

    /** Read `FLG`: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N. */
    fun readStatus(): Int = read(CMD_FLG, 1)[0].toInt() and 0xFF

    /** Read the internal temperature sensor (`TSC`) in signed degrees Celsius. */
    fun readTemperature(): Int = read(CMD_TSC, 2)[0].toInt()

    /**
     * Read `REV`. The panel datasheet gives a 1-byte LUT revision, the chip
     * datasheet 5 bytes (reserved, CHIP_REV, LUT_REV[7:0], [15:8], [23:16]).
     *
     * @param longForm `false` reads the 1-byte panel form, `true` the 5-byte chip form
     * @return `[chipRev, lutRev]`; `chipRev` is -1 for the 1-byte form
     */
    fun readRevision(longForm: Boolean = false): IntArray {
        if (!longForm) return intArrayOf(-1, read(CMD_REV, 1)[0].toInt() and 0xFF)
        val b = read(CMD_REV, 5)
        return intArrayOf(
            b[1].toInt() and 0xFF,
            (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8) or ((b[4].toInt() and 0xFF) shl 16),
        )
    }

    /** Read the measured VCOM (`VV`, panel layout) in volts: -0.10 V - 0.05 V x VV[5:0]. */
    fun readVcom(): Double = -0.10 - 0.05 * (read(CMD_VV, 1)[0].toInt() and 0x3F)

    /** Read low-power detection (`LPD`, with LPMD = 0): `true` when the supply is low. */
    fun readLowPower(): Boolean = (read(CMD_LPD, 1)[0].toInt() and 1) == 0

    /** Read [length] OTP bytes from address 0 (`ROTP`; the dummy byte is dropped). */
    fun readOtp(length: Int): ByteArray {
        connection.sendCommand(CMD_ROTP, ByteArray(0))
        connection.readData(1)
        return connection.readData(length)
    }
}
