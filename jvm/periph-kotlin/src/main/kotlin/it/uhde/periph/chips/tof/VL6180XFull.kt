package it.uhde.periph.chips.tof

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * VL6180X full interface — extends [VL6180XMinimal] with ALS (lux), continuous and interleaved
 * modes, thresholds, gain / integration / scaler, convergence and averaging, range offset and
 * crosstalk, VHV recalibration, address change, identification, the history buffer and the
 * Level-2 interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (falling edge, GPIO1 is active low),
 * otherwise a 5 ms polling thread.
 *
 * @param connection I²C connection with a register width of 2, bound to 0x29
 * @throws IOException on bus error
 */
open class VL6180XFull(connection: RegisterConnection) : VL6180XMinimal(connection) {

    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Take one single-shot range measurement without failing on range errors.
     *
     * @return {range in mm, error code}; the range is valid only when the code is 0
     * @throws IOException on bus error or if no result arrives within 100 ms
     */
    @Synchronized
    fun readRangeStatus(): IntArray = measureRange()

    /**
     * Start continuous ranging.
     *
     * @param periodMs period in ms, 10–2550 in 10 ms steps
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun startContinuousRange(periodMs: Int) {
        require(periodMs in 10..2550) { "period must be 10 to 2550 ms" }
        wr8(REG_RANGE_INTERMEASUREMENT, periodMs / 10 - 1)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        wr8(REG_RANGE_START, 0x03)
    }

    /** Stop continuous ranging (the start/stop bit toggles the running mode). */
    @Synchronized
    fun stopContinuousRange() = wr8(REG_RANGE_START, 0x01)

    /**
     * Wait for the next continuous-mode range sample and read it.
     *
     * @return the distance in mm
     * @throws IOException on bus error, a non-zero range error code or a timeout
     */
    @Synchronized
    fun readRangeContinuous(): Int {
        val period = (rd8(REG_RANGE_INTERMEASUREMENT) + 1) * 10
        waitSample(0, RANGE_TIMEOUT_MS + period)
        val r = readRangeResult()
        if (r[1] != 0) throw IOException("VL6180X range error ${r[1]}")
        return r[0]
    }

    /**
     * Take one single-shot ALS measurement.
     *
     * @return the 16-bit ALS count
     * @throws IOException on bus error or if no result arrives within the integration time + 100 ms
     */
    @Synchronized
    fun readAlsRaw(): Int {
        wr8(REG_ALS_START, 0x01)
        try {
            waitSample(3, integrationMs + 100)
        } catch (e: IOException) {
            wr8(REG_INTERRUPT_CLEAR, 0x07)
            throw e
        }
        val raw = rd16(REG_ALS_VAL)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        return raw
    }

    /**
     * Take one single-shot ALS measurement and convert it to lux:
     * `0.32 * (count / gain) * (100 / integration_ms)` with the actual gain of the current code.
     *
     * @return illuminance in lux (without cover glass)
     */
    @Synchronized
    fun readLux(): Double {
        val raw = readAlsRaw()
        return 0.32 * (raw.toDouble() / GAIN[gainCode]) * (100.0 / integrationMs)
    }

    /**
     * Start continuous ALS measurements.
     *
     * @param periodMs period in ms, 10–2550 in 10 ms steps
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun startContinuousAls(periodMs: Int) {
        require(periodMs in 10..2550) { "period must be 10 to 2550 ms" }
        wr8(REG_ALS_INTERMEASUREMENT, periodMs / 10 - 1)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        wr8(REG_ALS_START, 0x03)
    }

    /** Stop continuous ALS measurements (the start/stop bit toggles the running mode). */
    @Synchronized
    fun stopContinuousAls() = wr8(REG_ALS_START, 0x01)

    /** Enable or disable interleaved ALS + range mode (started with [startContinuousAls]). */
    @Synchronized
    fun setInterleaved(on: Boolean) = wr8(REG_INTERLEAVED_ENABLE, if (on) 1 else 0)

    /**
     * Set the ALS analogue gain.
     *
     * @param code gain code 0–7 (0 = 20, 1 = 10, 2 = 5, 3 = 2.5, 4 = 1.67, 5 = 1.25, 6 = 1, 7 = 40)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAlsGain(code: Int) {
        require(code in 0..7) { "gain code must be 0 to 7" }
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr8(REG_ALS_GAIN, 0x40 or code)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
        gainCode = code
    }

    /**
     * Set the ALS integration time.
     *
     * @param ms integration time in ms, 1–512
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAlsIntegrationTime(ms: Int) {
        require(ms in 1..512) { "integration time must be 1 to 512 ms" }
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr16(REG_ALS_INTEGRATION, ms - 1)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
        integrationMs = ms
    }

    /**
     * Set the ALS result scaler (`FIRMWARE__RESULT_SCALER`).
     *
     * @param scaler scaler, 1–16
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAlsScaler(scaler: Int) {
        require(scaler in 1..16) { "scaler must be 1 to 16" }
        wr8(REG_RESULT_SCALER, scaler)
    }

    /**
     * Set the range interrupt thresholds (grouped-parameter-hold protocol).
     *
     * @param low low threshold in mm, 0–255
     * @param high high threshold in mm, low <= high <= 255
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setRangeThresholds(low: Int, high: Int) {
        require(low >= 0 && high <= 255 && low <= high) { "thresholds must satisfy 0 <= low <= high <= 255" }
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr8(REG_RANGE_THRESH_HIGH, high)
        wr8(REG_RANGE_THRESH_LOW, low)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
    }

    /**
     * Set the ALS interrupt thresholds (grouped-parameter-hold protocol).
     *
     * @param low low threshold in counts
     * @param high high threshold in counts, low <= high <= 65535
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAlsThresholds(low: Int, high: Int) {
        require(low >= 0 && high <= 65535 && low <= high) { "thresholds must satisfy 0 <= low <= high <= 65535" }
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr16(REG_ALS_THRESH_HIGH, high)
        wr16(REG_ALS_THRESH_LOW, low)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
    }

    /**
     * Set the maximum ranging convergence time.
     *
     * @param ms time in ms, 1–63
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setMaxConvergenceTime(ms: Int) {
        require(ms in 1..63) { "convergence time must be 1 to 63 ms" }
        wr8(REG_RANGE_MAX_CONVERGENCE, ms)
    }

    /**
     * Set the ranging averaging sample period.
     *
     * @param units 0–255; about 64.5 µs per unit (48 is the default)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAveragingPeriod(units: Int) {
        require(units in 0..255) { "averaging period must be 0 to 255" }
        wr8(REG_AVERAGING_PERIOD, units)
    }

    /**
     * Set the part-to-part range offset.
     *
     * @param mm offset in mm, -128 to 127 (two's complement on the chip)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setRangeOffset(mm: Int) {
        require(mm in -128..127) { "offset must be -128 to 127 mm" }
        wr8(REG_RANGE_OFFSET, mm and 0xFF)
    }

    /** Read the part-to-part range offset in mm (signed). */
    @Synchronized
    fun getRangeOffset(): Int = rd8(REG_RANGE_OFFSET).toByte().toInt()

    /**
     * Set the crosstalk compensation rate.
     *
     * @param rate rate in Mcps (stored as 9.7 fixed point)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setCrosstalkRate(rate: Double) {
        val code = (rate * 128 + 0.5).toLong()
        require(rate >= 0 && code <= 0xFFFF) { "crosstalk rate out of range" }
        wr16(REG_RANGE_CROSSTALK_RATE, code.toInt())
    }

    /**
     * Start a VHV recalibration and wait for the start bit to clear.
     *
     * @throws IOException on bus error or if it does not finish within 100 ms
     */
    @Synchronized
    fun recalibrateVhv() {
        wr8(REG_VHV_RECALIBRATE, 0x01)
        for (i in 0..VHV_TIMEOUT_MS) {
            if ((rd8(REG_VHV_RECALIBRATE) and 0x01) == 0) return
            sleepMs(1)
        }
        throw IOException("VL6180X timeout waiting for VHV recalibration")
    }

    /**
     * Change the chip's I²C address (volatile). The connection passed at construction still points
     * at the old address; create a new connection and driver to continue.
     *
     * @param address new 7-bit address, 0x08–0x77
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAddress(address: Int) {
        require(address in 0x08..0x77) { "address must be 0x08 to 0x77" }
        wr8(REG_I2C_ADDRESS, address and 0x7F)
    }

    /** Read the model ID (0xB4 for the VL6180X). */
    @Synchronized
    fun modelId(): Int = rd8(REG_MODEL_ID)

    /** Read the model revision: {major, minor}, bits 2:0 of each revision register. */
    @Synchronized
    fun revision(): IntArray = intArrayOf(rd8(REG_MODEL_REV_MAJOR) and 0x07, rd8(REG_MODEL_REV_MINOR) and 0x07)

    /**
     * Read the eight-entry history buffer (enable it through the history control register first).
     *
     * @return eight 16-bit entries (range in mm or ALS counts, per the history mode)
     */
    @Synchronized
    fun readHistory(): IntArray {
        val b = connection.read(REG_HISTORY_BUFFER, 16)
        return IntArray(8) { ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
    }

    private fun setInterruptMode(source: Int, mode: Int) {
        val shift = when (source) {
            SOURCE_RANGE -> 0
            SOURCE_ALS -> 3
            else -> throw IllegalArgumentException("source must be SOURCE_RANGE or SOURCE_ALS")
        }
        synchronized(this) {
            val value = (rd8(REG_INTERRUPT_CONFIG) and (0x07 shl shift).inv() and 0xFF) or (mode shl shift)
            wr8(REG_GROUPED_PARAM_HOLD, 1)
            wr8(REG_INTERRUPT_CONFIG, value)
            wr8(REG_GROUPED_PARAM_HOLD, 0)
        }
    }

    /**
     * Select the interrupt mode of the range or ALS source.
     *
     * @param source [SOURCE_RANGE] or [SOURCE_ALS]
     * @param mode [MODE_LEVEL_LOW], [MODE_LEVEL_HIGH], [MODE_OUT_OF_WINDOW] or [MODE_NEW_SAMPLE_READY]
     * @throws IllegalArgumentException if source or mode is invalid
     */
    fun enableInterrupt(source: Int, mode: Int) {
        require(mode in 1..4) { "mode must be 1 to 4" }
        setInterruptMode(source, mode)
    }

    /**
     * Disable the interrupt of the range or ALS source (mode 0).
     *
     * @param source [SOURCE_RANGE] or [SOURCE_ALS]
     * @throws IllegalArgumentException if source is invalid
     */
    fun disableInterrupt(source: Int) = setInterruptMode(source, 0)

    /**
     * Read the interrupt status and clear all pending sources.
     *
     * @return bit mask of [SOURCE_RANGE], [SOURCE_ALS], [SOURCE_ERROR] (0 if none)
     */
    fun pollInterrupt(): Int = pollInterruptStatus()

    /**
     * Subscribe to GPIO1 events using `connection.intPin()` if wired (falling edge), otherwise a
     * 5 ms polling thread. Pending sources are cleared before the callback runs.
     *
     * @param callback called with the `SOURCE_*` bit mask
     */
    fun onInterrupt(callback: IntConsumer) = onInterrupt(callback, connection.intPin())

    /**
     * Subscribe to GPIO1 events, overriding which [InputPin] delivers edges.
     *
     * @param callback called with the `SOURCE_*` bit mask
     * @param intPin GPIO1 pin to arm, or `null` to force the 5 ms polling fallback
     */
    fun onInterrupt(callback: IntConsumer, intPin: InputPin?) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread {
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }.also {
                it.name = "vl6180x-poll"
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Detach the edge handler or stop the polling thread, and clear the callback. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterruptStatus()
            val cb = callback
            if (status != 0 && cb != null) cb.accept(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
