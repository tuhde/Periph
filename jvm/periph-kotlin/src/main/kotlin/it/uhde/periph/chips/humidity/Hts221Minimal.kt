package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * HTS221 — capacitive digital relative-humidity and temperature sensor (STMicroelectronics),
 * minimal driver.
 *
 * The chip is factory calibrated: construction reads the per-device two-point calibration
 * (registers `0x30`–`0x3F`) and writes `CTRL_REG1 = 0x84` (active, block data update, one-shot).
 * [read] starts a one-shot conversion, waits for it (timeout 100 ms) and converts the outputs by
 * linear interpolation. I²C address `0x5F` (fixed). Multi-byte transfers set the sub-address MSb
 * (register auto-increment), which the driver does itself.
 *
 * ## Configuration defaults
 * - Active mode, block data update on, one-shot (ODR = 00)
 * - Averaging at the power-on values (16 temperature / 32 humidity samples)
 * - Heater off, DRDY pin off
 *
 * @param conn I²C register connection bound to 0x5F
 * @throws IOException on bus error
 */
open class Hts221Minimal(protected val conn: RegisterConnection) {
    companion object {
        const val REG_WHO_AM_I      = 0x0F
        const val REG_AV_CONF       = 0x10
        const val REG_CTRL_REG1     = 0x20
        const val REG_CTRL_REG2     = 0x21
        const val REG_CTRL_REG3     = 0x22
        const val REG_STATUS        = 0x27
        const val REG_HUMIDITY_OUT  = 0x28
        const val REG_CALIB         = 0x30

        const val AUTO_INC          = 0x80
        const val CTRL_REG1_DEFAULT = 0x84
        const val CTRL1_PD          = 0x80
        const val CTRL1_BDU         = 0x04
        const val CTRL1_ODR_MASK    = 0x03
        const val CTRL2_BOOT        = 0x80
        const val CTRL2_HEATER      = 0x02
        const val CTRL2_ONE_SHOT    = 0x01
        const val STATUS_BOTH       = 0x03
        const val TIMEOUT_MS        = 100
    }

    protected var h0rH = 0.0
    protected var h1rH = 0.0
    protected var t0degC = 0.0
    protected var t1degC = 0.0
    protected var h0t0Out = 0
    protected var h1t0Out = 0
    protected var t0Out = 0
    protected var t1Out = 0

    init {
        loadCalibration()
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun writeReg(reg: Int, value: Int) = conn.write(reg, byteArrayOf(value.toByte()))

    protected fun readReg(reg: Int): Int = conn.read(reg, 1)[0].toInt() and 0xFF

    protected fun readBurst(reg: Int, length: Int): ByteArray = conn.read(reg or AUTO_INC, length)

    private fun word(b: ByteArray, i: Int): Int =
        (((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()

    protected fun loadCalibration() {
        val c = readBurst(REG_CALIB, 16)
        val msb = c[5].toInt() and 0xFF
        val t0 = ((msb and 0x03) shl 8) or (c[2].toInt() and 0xFF)
        val t1 = ((msb and 0x0C) shl 6) or (c[3].toInt() and 0xFF)
        h0rH = (c[0].toInt() and 0xFF) / 2.0
        h1rH = (c[1].toInt() and 0xFF) / 2.0
        t0degC = t0 / 8.0
        t1degC = t1 / 8.0
        h0t0Out = word(c, 6)
        h1t0Out = word(c, 10)
        t0Out = word(c, 12)
        t1Out = word(c, 14)
    }

    protected fun humidityFromRaw(raw: Int): Double {
        val span = h1t0Out.toDouble() - h0t0Out
        if (span == 0.0) return 0.0
        val v = (raw.toDouble() - h0t0Out) * (h1rH - h0rH) / span + h0rH
        return max(0.0, min(100.0, v))
    }

    protected fun temperatureFromRaw(raw: Int): Double {
        val span = t1Out.toDouble() - t0Out
        if (span == 0.0) return 0.0
        return (raw.toDouble() - t0Out) * (t1degC - t0degC) / span + t0degC
    }

    /** Burst-read the output registers: raw humidity and raw temperature, signed 16-bit. */
    protected fun readRawPair(): IntArray {
        val d = readBurst(REG_HUMIDITY_OUT, 4)
        return intArrayOf(word(d, 0), word(d, 2))
    }

    /** Start a one-shot conversion, wait for both flags and return the raw outputs. */
    protected fun oneShotRaw(): IntArray {
        val ctrl2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, ctrl2 or CTRL2_ONE_SHOT)
        var status = 0
        repeat(TIMEOUT_MS) {
            status = readReg(REG_STATUS)
            if ((status and STATUS_BOTH) == STATUS_BOTH) return readRawPair()
            sleepMs(1)
        }
        throw IOException("HTS221 conversion timeout (STATUS=0x${Integer.toHexString(status)})")
    }

    /**
     * Measure temperature and relative humidity (one-shot).
     *
     * @return {temperature in °C, relative humidity in %rH (clamped to 0–100)}
     * @throws IOException on bus error or when the conversion does not finish within 100 ms
     */
    fun read(): DoubleArray {
        val raw = oneShotRaw()
        return doubleArrayOf(temperatureFromRaw(raw[1]), humidityFromRaw(raw[0]))
    }
}
