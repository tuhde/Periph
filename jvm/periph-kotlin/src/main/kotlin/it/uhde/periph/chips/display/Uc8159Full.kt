package it.uhde.periph.chips.display

import it.uhde.periph.connection.DisplayConn
import kotlin.math.abs

/**
 * UC8159 seven-colour e-paper controller — full driver.
 *
 * Extends [Uc8159Minimal] with drawing helpers, scan direction, frame rate,
 * border/data interval, temperature-sensor source, VCOM_DC and the read
 * commands (status, temperature, low power, VCOM).
 *
 * The `read*` methods need a half-duplex wire mode; with a SIMPLEX connection
 * they throw [UnsupportedOperationException].
 *
 * @param connection display connection
 * @param busyTimeoutMs limit for every BUSY wait, `<= 0` for no limit
 */
open class Uc8159Full @JvmOverloads constructor(
    connection: DisplayConn,
    busyTimeoutMs: Long = 60_000L,
) : Uc8159Minimal(connection, busyTimeoutMs) {

    /** Colour code (0..7) of one framebuffer pixel ([BLACK] when out of range). */
    fun getPixel(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) return BLACK
        val b = fb[(y * WIDTH + x) shr 1].toInt() and 0xFF
        return if (x and 1 == 1) b and 7 else (b shr 4) and 7
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

    /** Hardware reset and initialisation (exit from deep sleep). The framebuffer is kept. */
    public override fun wake() = super.wake()

    /**
     * Wait for BUSY to go high.
     *
     * @param timeoutMs limit in milliseconds, `<= 0` for no limit
     * @return `true` when ready, `false` on timeout
     */
    fun waitBusy(timeoutMs: Long = busyTimeoutMs): Boolean = connection.waitBusy(false, timeoutMs)

    /** Mirror the picture: [horizontal] clears SHL, [vertical] clears UD. The framebuffer is unchanged. */
    fun setFlip(horizontal: Boolean, vertical: Boolean) {
        psr = if (horizontal) psr and 0x04.inv() else psr or 0x04
        psr = if (vertical) psr and 0x08.inv() else psr or 0x08
        connection.sendCommand(CMD_PSR, byteArrayOf(psr.toByte(), 0x08))
    }

    /** Frame rate (`PLL` raw code): `0x3C` = 50 Hz (default), `0x3A` = 100 Hz, `0x39` = 200 Hz. */
    fun setFrameRate(code: Int) {
        connection.sendCommand(CMD_PLL, byteArrayOf(code.toByte()))
    }

    /**
     * VCOM and data interval (`CDI`).
     *
     * @param cdi interval, 0..15 (0b0111 = 10 hsync)
     * @param border border colour code 0..7
     * @param polarity DDX, 1 = default (the colour codes assume 1)
     */
    fun setInterval(cdi: Int, border: Int = WHITE, polarity: Int = 1) {
        this.cdi = ((border and 7) shl 5) or ((polarity and 1) shl 4) or (cdi and 0x0F)
        connection.sendCommand(CMD_CDI, byteArrayOf(this.cdi.toByte()))
    }

    /** Temperature sensor source (`TSE`): internal (default) or external LM75, offset -8..+7 degrees Celsius. */
    fun setTemperatureSensor(external: Boolean, offset: Int = 0) {
        val v = (if (external) 0x80 else 0x00) or (offset and 0x0F)
        connection.sendCommand(CMD_TSE, byteArrayOf(v.toByte()))
    }

    /** VCOM_DC (`VDCS`): -0.10 .. -4.0 V in 0.05 V steps. */
    fun setVcomDc(volts: Double) {
        val c = ((-0.10 - volts) / 0.05 + 2.5).toInt().coerceIn(2, 0x50)
        connection.sendCommand(CMD_VDCS, byteArrayOf(c.toByte()))
    }

    /** Send a raw command and parameters (escape hatch). */
    fun sendCommand(command: Int, data: ByteArray = ByteArray(0)) {
        connection.sendCommand(command, data)
    }

    private fun read(cmd: Int, length: Int): ByteArray {
        connection.sendCommand(cmd, ByteArray(0))
        return connection.readData(length)
    }

    /** Read `FLG`: bit 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag, 2 PON, 1 POF, 0 BUSY_N. */
    fun readStatus(): Int = read(CMD_FLG, 1)[0].toInt() and 0xFF

    /** Read the internal temperature sensor (`TSC`) in signed degrees Celsius. */
    fun readTemperature(): Int = read(CMD_TSC, 2)[0].toInt()

    /** Read low-power detection (`LPD`): `true` when VDD is below 2.5 V. */
    fun readLowPower(): Boolean = (read(CMD_LPD, 1)[0].toInt() and 1) == 0

    /** Read the measured VCOM (`VV`) in volts: -0.10 V - 0.05 V x (VV - 2). */
    fun readVcom(): Double {
        val c = read(CMD_VV, 1)[0].toInt() and 0x7F
        return -0.10 - 0.05 * (if (c > 2) c - 2 else 0)
    }
}
