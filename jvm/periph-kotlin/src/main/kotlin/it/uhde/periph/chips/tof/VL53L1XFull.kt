package it.uhde.periph.chips.tof

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import java.io.IOException
import java.util.function.IntConsumer

/**
 * VL53L1X full interface — extends [VL53L1XMinimal] with timed continuous
 * ranging, the full measurement record, distance mode, timing budget,
 * inter-measurement period, signal and sigma thresholds, region of interest,
 * offset and crosstalk compensation with calibration helpers, temperature
 * update, address change, distance thresholds, identification, and the
 * Level-2 interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (falling edge, GPIO1 is
 * active low), otherwise a 5 ms polling thread.
 *
 * @param connection configured I²C connection bound to the device (0x29)
 * @throws IOException on bus error, a sensor ID other than 0xEACC, or an init poll timeout
 */
class VL53L1XFull(connection: Connection) : VL53L1XMinimal(connection) {

    companion object {
        /** Interrupt source: range < low threshold. */
        const val SOURCE_LEVEL_LOW = VL53Base.SOURCE_LEVEL_LOW
        /** Interrupt source: range > high threshold. */
        const val SOURCE_LEVEL_HIGH = VL53Base.SOURCE_LEVEL_HIGH
        /** Interrupt source: range < low threshold or > high threshold. */
        const val SOURCE_OUT_OF_WINDOW = VL53Base.SOURCE_OUT_OF_WINDOW
        /** Interrupt source: a new measurement is available (driver default). */
        const val SOURCE_NEW_SAMPLE_READY = VL53Base.SOURCE_NEW_SAMPLE_READY
        /** Interrupt source: low ≤ range ≤ high. */
        const val SOURCE_IN_WINDOW = VL53Base.SOURCE_IN_WINDOW

        private const val CALIBRATION_SAMPLES = 50

        /** Timing budget table: ms, short A, short B, long A, long B (0 = not available). */
        private val BUDGETS = arrayOf(
            intArrayOf(15, 0x001D, 0x0027, 0, 0),
            intArrayOf(20, 0x0051, 0x006E, 0x001E, 0x0022),
            intArrayOf(33, 0x00D6, 0x006E, 0x0060, 0x006E),
            intArrayOf(50, 0x01AE, 0x01E8, 0x00AD, 0x00C6),
            intArrayOf(100, 0x02E1, 0x0388, 0x01CC, 0x01EA),
            intArrayOf(200, 0x03E1, 0x0496, 0x02D9, 0x02F8),
            intArrayOf(500, 0x0591, 0x05C1, 0x048F, 0x04A4),
        )

        private fun word(r: ByteArray, i: Int): Int = ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
    }

    /** Distance mode for [setDistanceMode]. */
    enum class DistanceMode {
        /** ~1.3 m, robust against ambient light. */
        SHORT,
        /** Up to 4 m in the dark (default). */
        LONG,
    }

    /**
     * Decoded result block, as returned by [readMeasurement].
     *
     * @property distanceMm range in mm
     * @property rangeStatus mapped range status (0 = valid, 255 = no update)
     * @property signalRateMcps return signal rate in MCPS
     * @property ambientRateMcps ambient rate in MCPS
     * @property effectiveSpadCount effective SPAD return count
     */
    data class Measurement(
        val distanceMm: Int,
        val rangeStatus: Int,
        val signalRateMcps: Double,
        val ambientRateMcps: Double,
        val effectiveSpadCount: Double,
    )

    /**
     * Start timed continuous ranging.
     *
     * @param periodMs inter-measurement period in ms, 0–60000; 0 (and any
     *   value below the timing budget) runs at the timing budget, i.e. back-to-back
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun startContinuous(periodMs: Int = 0) {
        require(periodMs in 0..60000) { "period must be 0 to 60000 ms" }
        setInterMeasurement(maxOf(periodMs, timingBudget() / 1000, 1))
        write8(REG_INTERRUPT_CLEAR, 0x01)
        write8(REG_MODE_START, 0x40)
    }

    /** Stop continuous ranging. Does not wait for a running measurement. */
    @Synchronized
    fun stopContinuous() {
        write8(REG_MODE_START, 0x00)
    }

    /**
     * Wait for the next continuous-mode result and read it.
     *
     * @return distance in mm (check [rangeValid])
     * @throws IOException on bus error or if no result arrives within 500 ms
     */
    @Synchronized
    fun readContinuous(): Int = waitAndRead()

    /**
     * Report whether a measurement is pending (non-blocking).
     *
     * @return `true` if GPIO__TIO_HV_STATUS shows the GPIO1 line asserted
     */
    @Synchronized
    fun dataReady(): Boolean = isDataReady()

    /**
     * Read the full result block and clear the interrupt (non-blocking).
     *
     * @return decoded measurement record
     */
    @Synchronized
    fun readMeasurement(): Measurement {
        readResult()
        return Measurement(resultWord(13), rangeStatus, resultWord(15) / 128.0, resultWord(7) / 128.0,
            resultWord(3) / 256.0)
    }

    /**
     * Mapped range status of the most recent measurement.
     *
     * @return 0 = valid, 1 = sigma fail, 2 = signal fail, 4 = out of bounds,
     *   7 = wrap-around, 255 = no update
     */
    fun rangeStatus(): Int = rangeStatus

    /**
     * Set the per-measurement timing budget (ULD table values only).
     *
     * @param budgetUs 15000 (short mode only), 20000, 33000, 50000, 100000, 200000 or 500000
     * @throws IllegalArgumentException if not in the table for the current distance mode
     */
    @Synchronized
    fun setTimingBudget(budgetUs: Int) {
        val col = if (distanceMode() == DistanceMode.SHORT) 1 else 3
        val row = BUDGETS.firstOrNull { budgetUs % 1000 == 0 && it[0] * 1000 == budgetUs && it[col] != 0 }
            ?: throw IllegalArgumentException("timing budget $budgetUs us is not available in this distance mode")
        write16(REG_RANGE_TIMEOUT_A, row[col])
        write16(REG_RANGE_TIMEOUT_B, row[col + 1])
    }

    /**
     * Decode the timing budget from RANGE_CONFIG__TIMEOUT_MACROP_A.
     *
     * @return budget in µs, or 0 if the register holds no table value
     */
    @Synchronized
    fun timingBudget(): Int {
        val a = read16(REG_RANGE_TIMEOUT_A)
        return BUDGETS.firstOrNull { it[1] == a || (it[3] != 0 && it[3] == a) }?.let { it[0] * 1000 } ?: 0
    }

    /**
     * Select short or long distance mode, keeping the timing budget (100 ms if
     * the current budget is unknown).
     *
     * @param mode short (~1.3 m, robust in sunlight) or long (up to 4 m)
     * @throws IllegalArgumentException when switching to long at a 15 ms budget
     */
    @Synchronized
    fun setDistanceMode(mode: DistanceMode) {
        val budget = timingBudget().takeIf { it != 0 } ?: 100000
        require(!(mode == DistanceMode.LONG && budget == 15000)) {
            "15 ms timing budget is only available in short distance mode"
        }
        val short = mode == DistanceMode.SHORT
        write8(REG_PHASECAL_TIMEOUT, if (short) 0x14 else 0x0A)
        write8(REG_RANGE_VCSEL_PERIOD_A, if (short) 0x07 else 0x0F)
        write8(REG_RANGE_VCSEL_PERIOD_B, if (short) 0x05 else 0x0D)
        write8(REG_RANGE_VALID_PHASE_HIGH, if (short) 0x38 else 0xB8)
        write16(REG_SD_WOI_SD0, if (short) 0x0705 else 0x0F0D)
        write16(REG_SD_INITIAL_PHASE_SD0, if (short) 0x0606 else 0x0E0E)
        setTimingBudget(budget)
    }

    /**
     * Read the current distance mode.
     *
     * @return short or long
     * @throws IOException if PHASECAL_CONFIG__TIMEOUT_MACROP holds neither mode's value
     */
    @Synchronized
    fun distanceMode(): DistanceMode = when (val v = read8(REG_PHASECAL_TIMEOUT)) {
        0x14 -> DistanceMode.SHORT
        0x0A -> DistanceMode.LONG
        else -> throw IOException("VL53L1X unknown distance mode register value 0x%02X".format(v))
    }

    /**
     * Set the continuous-mode inter-measurement period; should be ≥ the
     * timing budget ([startContinuous] enforces this).
     *
     * @param periodMs period in ms, 1–60000
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setInterMeasurement(periodMs: Int) {
        require(periodMs in 1..60000) { "inter-measurement period must be 1 to 60000 ms" }
        val clockPll = (read16(REG_OSC_CALIBRATE_VAL) and 0x03FF).toLong()
        write32(REG_INTERMEASUREMENT_PERIOD, clockPll * periodMs * 1075 / 1000)
    }

    /**
     * Read the continuous-mode inter-measurement period.
     *
     * @return period in ms (0 if the oscillator calibration reads 0)
     */
    @Synchronized
    fun interMeasurement(): Int {
        val clockPll = (read16(REG_OSC_CALIBRATE_VAL) and 0x03FF).toLong()
        if (clockPll == 0L) return 0
        return (read32(REG_INTERMEASUREMENT_PERIOD) * 1000 / (clockPll * 1075)).toInt()
    }

    /**
     * Set the minimum return signal rate for a valid result.
     *
     * @param limitMcps limit in MCPS, 0 to 511.99 (default 1.0)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setSignalRateLimit(limitMcps: Double) {
        require(limitMcps in 0.0..511.99) { "signal rate limit must be 0 to 511.99 MCPS" }
        write16(REG_MIN_COUNT_RATE_RTN_LIMIT, (limitMcps * 128 + 0.5).toInt())
    }

    /** @return minimum return signal rate in MCPS */
    @Synchronized
    fun signalRateLimit(): Double = read16(REG_MIN_COUNT_RATE_RTN_LIMIT) / 128.0

    /**
     * Set the maximum estimated standard deviation for a valid result.
     *
     * @param sigmaMm threshold in mm, 0–16383 (default 90)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setSigmaThreshold(sigmaMm: Int) {
        require(sigmaMm in 0..16383) { "sigma threshold must be 0 to 16383 mm" }
        write16(REG_SIGMA_THRESH, sigmaMm shl 2)
    }

    /** @return sigma threshold in mm */
    @Synchronized
    fun sigmaThreshold(): Int = read16(REG_SIGMA_THRESH) shr 2

    /**
     * Set the receiving region-of-interest size; sizes above 10 SPADs
     * re-centre the ROI on SPAD 199 (array centre).
     *
     * @param width ROI width in SPADs, 4–16
     * @param height ROI height in SPADs, 4–16
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setRoi(width: Int, height: Int) {
        require(width in 4..16 && height in 4..16) { "ROI width and height must be 4 to 16 SPADs" }
        if (width > 10 || height > 10) write8(REG_ROI_CENTRE_SPAD, 199)
        write8(REG_ROI_XY_SIZE, ((height - 1) shl 4) or (width - 1))
    }

    /** @return region-of-interest size `(width, height)` in SPADs */
    @Synchronized
    fun roi(): Pair<Int, Int> {
        val v = read8(REG_ROI_XY_SIZE)
        return Pair((v and 0x0F) + 1, (v shr 4) + 1)
    }

    /**
     * Move the region of interest to a centre SPAD (ST UM2555 numbering, 199 =
     * array centre). The caller keeps the ROI inside the array.
     *
     * @param spad SPAD number, 0–255
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setRoiCenter(spad: Int) {
        require(spad in 0..255) { "ROI centre SPAD must be 0 to 255" }
        write8(REG_ROI_CENTRE_SPAD, spad)
    }

    /** @return region-of-interest centre SPAD */
    @Synchronized
    fun roiCenter(): Int = read8(REG_ROI_CENTRE_SPAD)

    /** @return factory-measured optical-centre SPAD from NVM; pass to [setRoiCenter] to align the ROI */
    @Synchronized
    fun opticalCenter(): Int = read8(REG_MODE_ROI_CENTRE_SPAD)

    /**
     * Override the part-to-part range offset (volatile).
     *
     * @param offsetMm offset in mm, −1024.0 to 1023.75 (0.25 mm steps)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setOffset(offsetMm: Double) {
        require(offsetMm in -1024.0..1023.75) { "offset must be -1024.0 to 1023.75 mm" }
        val q = offsetMm * 4
        val raw = if (q >= 0) (q + 0.5).toInt() else -((-q + 0.5).toInt())
        write16(REG_PART_TO_PART_OFFSET, raw and 0x1FFF)
        write16(REG_MM_INNER_OFFSET, 0)
        write16(REG_MM_OUTER_OFFSET, 0)
    }

    /** @return part-to-part range offset in mm (13-bit two's complement × 0.25) */
    @Synchronized
    fun offset(): Double {
        var raw = read16(REG_PART_TO_PART_OFFSET) and 0x1FFF
        if ((raw and 0x1000) != 0) raw -= 0x2000
        return raw * 0.25
    }

    /**
     * Set the per-SPAD crosstalk compensation rate (volatile).
     *
     * @param rateMcps 0 disables; otherwise 0 < rate < 0.128 MCPS
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setCrosstalkCompensation(rateMcps: Double) {
        require(rateMcps >= 0 && rateMcps < 0.128) { "crosstalk rate must be 0 (off) or below 0.128 MCPS" }
        write16(REG_XTALK_X_GRADIENT, 0)
        write16(REG_XTALK_Y_GRADIENT, 0)
        write16(REG_XTALK_PLANE_OFFSET, minOf((rateMcps * 512000 + 0.5).toInt(), 0xFFFF))
    }

    /** @return per-SPAD crosstalk compensation rate in MCPS */
    @Synchronized
    fun crosstalkCompensation(): Double = read16(REG_XTALK_PLANE_OFFSET) / 512000.0

    /** Range 50 timed-mode samples; always stops ranging. */
    private fun collect(): List<ByteArray> {
        val out = ArrayList<ByteArray>(CALIBRATION_SAMPLES)
        write8(REG_INTERRUPT_CLEAR, 0x01)
        write8(REG_MODE_START, 0x40)
        try {
            repeat(CALIBRATION_SAMPLES) {
                waitUntil({ isDataReady() }, "data ready")
                readResult()
                out += result.copyOf()
            }
        } finally {
            write8(REG_MODE_START, 0x00)
        }
        return out
    }

    /**
     * Measure and apply the range offset against a target at a known distance
     * (ULD CalibrateOffset; ST recommends 88 % white at 140 mm). Ranges 50
     * times with the offset zeroed; must not be called while ranging. Store
     * the result and re-apply it with [setOffset] after each power-up.
     *
     * @param targetMm true target distance in mm
     * @return applied offset in mm (target − mean measured distance)
     * @throws IllegalArgumentException if the resulting offset is out of range
     * @throws IOException on bus error or a result timeout
     */
    @Synchronized
    fun calibrateOffset(targetMm: Int): Double {
        write16(REG_PART_TO_PART_OFFSET, 0)
        write16(REG_MM_INNER_OFFSET, 0)
        write16(REG_MM_OUTER_OFFSET, 0)
        val mean = collect().sumOf { word(it, 13).toLong() }.toDouble() / CALIBRATION_SAMPLES
        val offset = targetMm - mean
        setOffset(offset)
        return offset
    }

    /**
     * Measure and apply crosstalk compensation for a cover glass (ULD
     * CalibrateXtalk; ST uses a 17 % grey target where the sensor starts to
     * under-range). Ranges 50 times with compensation off; must not be called
     * while ranging. Store the result and re-apply it with
     * [setCrosstalkCompensation] after each power-up.
     *
     * @param targetMm true target distance in mm (> 0)
     * @return applied per-SPAD crosstalk rate in MCPS (0–0.127)
     * @throws IllegalArgumentException if targetMm is not positive
     * @throws IOException on bus error or a result timeout
     */
    @Synchronized
    fun calibrateCrosstalk(targetMm: Int): Double {
        require(targetMm > 0) { "target distance must be positive" }
        write16(REG_XTALK_PLANE_OFFSET, 0)
        val samples = collect()
        val n = CALIBRATION_SAMPLES.toDouble()
        val meanDistance = samples.sumOf { word(it, 13).toDouble() } / n
        val meanSignal = samples.sumOf { word(it, 15) / 128.0 } / n
        val meanSpads = samples.sumOf { word(it, 3) / 256.0 } / n
        val rate = (if (meanSpads > 0) meanSignal * (1 - meanDistance / targetMm) / meanSpads else 0.0)
            .coerceIn(0.0, 0.127)
        setCrosstalkCompensation(rate)
        return rate
    }

    /**
     * Run the temperature update (ULD StartTemperatureUpdate). Call in
     * software standby (not while ranging), after the temperature changes by
     * more than about 8 °C.
     *
     * @throws IOException on bus error or if the update ranging times out
     */
    @Synchronized
    fun recalibrate() {
        write8(REG_VHV_CONFIG_LOOP_BOUND, 0x81)
        write8(REG_VHV_INIT, 0x92)
        write8(REG_MODE_START, 0x40)
        try {
            waitUntil({ isDataReady() }, "temperature update")
        } finally {
            write8(REG_INTERRUPT_CLEAR, 0x01)
            write8(REG_MODE_START, 0x00)
            write8(REG_VHV_CONFIG_LOOP_BOUND, 0x09)
            write8(REG_VHV_INIT, 0x00)
        }
    }

    /**
     * Change the chip's I²C address (volatile). The chip answers on the new
     * address immediately; this driver instance becomes unusable — construct
     * a new connection at the new address and a new driver.
     *
     * @param address new 7-bit address, 0x08–0x77
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setAddress(address: Int) {
        setAddressReg(REG_I2C_SLAVE_DEVICE_ADDRESS, address)
    }

    /**
     * Set the distance thresholds used by the threshold interrupt sources.
     *
     * @param lowMm low threshold in mm
     * @param highMm high threshold in mm, lowMm ≤ highMm ≤ 65535
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setInterruptThresholds(lowMm: Int, highMm: Int) {
        require(lowMm >= 0 && highMm >= lowMm && highMm <= 65535) {
            "thresholds must satisfy 0 <= low <= high <= 65535 mm"
        }
        write16(REG_THRESH_HIGH, highMm)
        write16(REG_THRESH_LOW, lowMm)
    }

    /** @return distance thresholds `(lowMm, highMm)` */
    @Synchronized
    fun interruptThresholds(): Pair<Int, Int> = Pair(read16(REG_THRESH_LOW), read16(REG_THRESH_HIGH))

    /** @return `IDENTIFICATION__MODEL_ID` — 0xEA */
    @Synchronized
    fun modelId(): Int = read8(REG_MODEL_ID)

    /** @return `IDENTIFICATION__MODULE_TYPE` — 0xCC */
    @Synchronized
    fun moduleType(): Int = read8(REG_MODULE_TYPE)

    /** @return `IDENTIFICATION__REVISION_ID` (mask revision) — 0x10 */
    @Synchronized
    fun revisionId(): Int = read8(REG_REVISION_ID)

    /**
     * Select the GPIO1 interrupt source (replaces the active one). With a
     * threshold source active, [dataReady]/[readContinuous] only see a pending
     * result when the threshold condition is met.
     *
     * @param source one of the `SOURCE_*` constants (1–5)
     * @throws IllegalArgumentException if source is not 1–5
     */
    @Synchronized
    fun enableInterrupt(source: Int) {
        val config = when (source) {
            SOURCE_LEVEL_LOW -> 0x00
            SOURCE_LEVEL_HIGH -> 0x01
            SOURCE_OUT_OF_WINDOW -> 0x02
            SOURCE_NEW_SAMPLE_READY -> 0x20
            SOURCE_IN_WINDOW -> 0x03
            else -> throw IllegalArgumentException("source must be one of the SOURCE_* constants")
        }
        write8(REG_INTERRUPT_CONFIG_GPIO, config)
    }

    /**
     * Revert to `SOURCE_NEW_SAMPLE_READY` if [source] is the active threshold
     * source. The chip has no disabled state, so disabling
     * `SOURCE_NEW_SAMPLE_READY` is a no-op; use [offInterrupt] to stop callbacks.
     *
     * @param source one of the `SOURCE_*` constants
     */
    @Synchronized
    fun disableInterrupt(source: Int) {
        if (source != SOURCE_NEW_SAMPLE_READY && activeSource() == source) write8(REG_INTERRUPT_CONFIG_GPIO, 0x20)
    }

    /**
     * Read and clear a pending interrupt.
     *
     * @return the active `SOURCE_*` value if GPIO1 is asserted, else 0
     */
    @Synchronized
    fun pollInterrupt(): Int = pollInterruptStatus()

    /**
     * Subscribe to GPIO1 events (falling edge, GPIO1 is active low). The
     * driver clears the interrupt before invoking the callback; the polling
     * fallback consumes results, so don't mix it with [readContinuous].
     *
     * @param callback called with the active `SOURCE_*` value
     * @param intPin GPIO1 pin to arm (defaults to `connection.intPin()`), or
     *   `null` to force the 5 ms polling fallback
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        subscribe(IntConsumer { callback(it) }, intPin)
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        unsubscribe()
    }
}
