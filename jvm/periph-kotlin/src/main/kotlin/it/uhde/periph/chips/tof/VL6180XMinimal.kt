package it.uhde.periph.chips.tof

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * VL6180X proximity and ambient-light sensor (STMicroelectronics) — minimal interface.
 *
 * VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS) behind one aperture.
 * Measures absolute distance 0–100 mm (8-bit result in mm) and ambient light (16-bit count,
 * converted to lux). Registers use a 16-bit big-endian index; 16-bit results and thresholds are
 * MSB first.
 *
 * The connection must be created with a register width of 2 and point at address `0x29`. The
 * constructor runs the initialization sequence: 1 ms boot wait, then — only while
 * `SYSTEM__FRESH_OUT_OF_RESET` is 1 — the mandatory private-register settings (ST AN4545, not
 * documented in the datasheet), then the recommended settings (averaging period 48, max
 * convergence 49 ms, ALS gain 1, 100 ms ALS integration, new-sample interrupt configuration).
 * [readRange] then takes one single-shot measurement per call. Methods are `@Synchronized`,
 * serializing multi-register sequences against the Full class's interrupt polling thread.
 *
 * @param connection I²C connection with a register width of 2, bound to 0x29
 * @throws IOException on bus error
 */
open class VL6180XMinimal(protected val connection: RegisterConnection) {

    companion object {
        /** Power-on 7-bit I²C address. */
        const val DEFAULT_ADDRESS = 0x29

        /** Expected `IDENTIFICATION__MODEL_ID`. */
        const val MODEL_ID = 0xB4

        /** Interrupt source: range event. */
        const val SOURCE_RANGE = 0x01

        /** Interrupt source: ALS event. */
        const val SOURCE_ALS = 0x02

        /** Interrupt source: error event. */
        const val SOURCE_ERROR = 0x04

        /** Interrupt mode: value below the low threshold. */
        const val MODE_LEVEL_LOW = 1

        /** Interrupt mode: value above the high threshold. */
        const val MODE_LEVEL_HIGH = 2

        /** Interrupt mode: value outside the threshold window. */
        const val MODE_OUT_OF_WINDOW = 3

        /** Interrupt mode: new sample ready (default). */
        const val MODE_NEW_SAMPLE_READY = 4

        const val REG_MODEL_ID               = 0x000
        const val REG_MODEL_REV_MAJOR        = 0x001
        const val REG_MODEL_REV_MINOR        = 0x002
        const val REG_MODE_GPIO1             = 0x011
        const val REG_INTERRUPT_CONFIG       = 0x014
        const val REG_INTERRUPT_CLEAR        = 0x015
        const val REG_FRESH_OUT_OF_RESET     = 0x016
        const val REG_GROUPED_PARAM_HOLD     = 0x017
        const val REG_RANGE_START            = 0x018
        const val REG_RANGE_THRESH_HIGH      = 0x019
        const val REG_RANGE_THRESH_LOW       = 0x01A
        const val REG_RANGE_INTERMEASUREMENT = 0x01B
        const val REG_RANGE_MAX_CONVERGENCE  = 0x01C
        const val REG_RANGE_CROSSTALK_RATE   = 0x01E
        const val REG_RANGE_OFFSET           = 0x024
        const val REG_VHV_RECALIBRATE        = 0x02E
        const val REG_VHV_REPEAT_RATE        = 0x031
        const val REG_ALS_START              = 0x038
        const val REG_ALS_THRESH_HIGH        = 0x03A
        const val REG_ALS_THRESH_LOW         = 0x03C
        const val REG_ALS_INTERMEASUREMENT   = 0x03E
        const val REG_ALS_GAIN               = 0x03F
        const val REG_ALS_INTEGRATION        = 0x040
        const val REG_RANGE_STATUS           = 0x04D
        const val REG_INTERRUPT_STATUS       = 0x04F
        const val REG_ALS_VAL                = 0x050
        const val REG_HISTORY_BUFFER         = 0x052
        const val REG_RANGE_VAL              = 0x062
        const val REG_AVERAGING_PERIOD       = 0x10A
        const val REG_RESULT_SCALER          = 0x120
        const val REG_I2C_ADDRESS            = 0x212
        const val REG_INTERLEAVED_ENABLE     = 0x2A3

        const val RANGE_TIMEOUT_MS = 100
        const val VHV_TIMEOUT_MS = 100

        /** Actual ALS gain per gain code 0–7. */
        val GAIN = doubleArrayOf(20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0)

        /** Mandatory private-register settings (ST AN4545), applied while fresh out of reset. */
        private val PRIVATE_SETTINGS = arrayOf(
            intArrayOf(0x0207, 0x01),
            intArrayOf(0x0208, 0x01),
            intArrayOf(0x0096, 0x00),
            intArrayOf(0x0097, 0xFD),
            intArrayOf(0x00E3, 0x01),
            intArrayOf(0x00E4, 0x03),
            intArrayOf(0x00E5, 0x02),
            intArrayOf(0x00E6, 0x01),
            intArrayOf(0x00E7, 0x03),
            intArrayOf(0x00F5, 0x02),
            intArrayOf(0x00D9, 0x05),
            intArrayOf(0x00DB, 0xCE),
            intArrayOf(0x00DC, 0x03),
            intArrayOf(0x00DD, 0xF8),
            intArrayOf(0x009F, 0x00),
            intArrayOf(0x00A3, 0x3C),
            intArrayOf(0x00B7, 0x00),
            intArrayOf(0x00BB, 0x3C),
            intArrayOf(0x00B2, 0x09),
            intArrayOf(0x00CA, 0x09),
            intArrayOf(0x0198, 0x01),
            intArrayOf(0x01B0, 0x17),
            intArrayOf(0x01AD, 0x00),
            intArrayOf(0x00FF, 0x05),
            intArrayOf(0x0100, 0x05),
            intArrayOf(0x0199, 0x05),
            intArrayOf(0x01A6, 0x1B),
            intArrayOf(0x01AC, 0x3E),
            intArrayOf(0x01A7, 0x1F),
            intArrayOf(0x0030, 0x00)
        )

        /** Recommended public settings (the 0x040 integration period is written as 16 bit separately). */
        private val RECOMMENDED_SETTINGS = arrayOf(
            intArrayOf(REG_MODE_GPIO1, 0x10), intArrayOf(REG_AVERAGING_PERIOD, 0x30),
            intArrayOf(REG_ALS_GAIN, 0x46), intArrayOf(REG_VHV_REPEAT_RATE, 0xFF),
            intArrayOf(REG_VHV_RECALIBRATE, 0x01), intArrayOf(REG_RANGE_INTERMEASUREMENT, 0x09),
            intArrayOf(REG_ALS_INTERMEASUREMENT, 0x31), intArrayOf(REG_INTERRUPT_CONFIG, 0x24)
        )

        @JvmStatic
        protected fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected var gainCode = 6
    protected var integrationMs = 100
    private var lastError = 0

    init {
        init()
    }

    protected fun rd8(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun wr8(reg: Int, value: Int) = connection.write(reg, byteArrayOf(value.toByte()))

    protected fun rd16(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    protected fun wr16(reg: Int, value: Int) =
        connection.write(reg, byteArrayOf((value shr 8).toByte(), value.toByte()))

    @Synchronized
    private fun init() {
        sleepMs(1)
        if (rd8(REG_FRESH_OUT_OF_RESET) == 1) {
            for (s in PRIVATE_SETTINGS) wr8(s[0], s[1])
            wr8(REG_FRESH_OUT_OF_RESET, 0)
        }
        for (s in RECOMMENDED_SETTINGS) wr8(s[0], s[1])
        wr16(REG_ALS_INTEGRATION, 0x0063)
        gainCode = 6
        integrationMs = 100
    }

    /** Poll the interrupt status until `(status >> shift) & 7 == 4`, at most [timeoutMs]. */
    protected fun waitSample(shift: Int, timeoutMs: Int) {
        for (i in 0..timeoutMs) {
            if (((rd8(REG_INTERRUPT_STATUS) shr shift) and 0x07) == 4) return
            sleepMs(1)
        }
        throw IOException("VL6180X timeout waiting for a result")
    }

    /** Read the range result, clear the interrupts; returns {mm, errorCode}. */
    protected fun readRangeResult(): IntArray {
        val error = rd8(REG_RANGE_STATUS) shr 4
        val mm = rd8(REG_RANGE_VAL)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        lastError = error
        return intArrayOf(mm, error)
    }

    /** Start a single-shot range measurement and read it; returns {mm, errorCode}. */
    protected fun measureRange(): IntArray {
        wr8(REG_RANGE_START, 0x01)
        try {
            waitSample(0, RANGE_TIMEOUT_MS)
        } catch (e: IOException) {
            wr8(REG_INTERRUPT_CLEAR, 0x07)
            throw e
        }
        return readRangeResult()
    }

    /**
     * Take one single-shot range measurement.
     *
     * @return the distance in mm (0–255; reliable up to about 100 mm)
     * @throws IOException on bus error, a non-zero range error code ([lastError]) or if no result
     *         arrives within 100 ms
     */
    @Synchronized
    fun readRange(): Int {
        val r = measureRange()
        if (r[1] != 0) throw IOException("VL6180X range error ${r[1]}")
        return r[0]
    }

    /** Range error code of the last measurement: 0 = valid, 1–15 are the datasheet range error codes. */
    @Synchronized
    fun lastError(): Int = lastError

    /**
     * Read the interrupt status and clear all pending sources.
     *
     * @return bit mask of [SOURCE_RANGE], [SOURCE_ALS], [SOURCE_ERROR] (0 if none)
     */
    @Synchronized
    protected open fun pollInterruptStatus(): Int {
        val status = rd8(REG_INTERRUPT_STATUS)
        var sources = 0
        if ((status and 0x07) != 0) sources = sources or SOURCE_RANGE
        if ((status and 0x38) != 0) sources = sources or SOURCE_ALS
        if ((status and 0xC0) != 0) sources = sources or SOURCE_ERROR
        if (sources != 0) wr8(REG_INTERRUPT_CLEAR, 0x07)
        return sources
    }
}
