package it.uhde.periph.chips.tof

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.roundToInt

/**
 * VL53L0X full interface — extends [VL53L0XMinimal] with continuous and timed
 * ranging, the full measurement record, timing budget, signal-rate limit,
 * VCSEL pulse periods, ranging profiles, offset and crosstalk compensation,
 * reference recalibration, address change, distance thresholds,
 * identification, and the Level-2 interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (falling edge, GPIO1 is
 * active low), otherwise a 5 ms polling thread.
 *
 * @param connection configured I²C connection bound to the device (0x29)
 * @throws IOException on bus error, a model ID other than 0xEE, or an init poll timeout
 */
class VL53L0XFull(connection: Connection) : VL53L0XMinimal(connection) {

    companion object {
        /** Interrupt source: range < low threshold. */
        const val SOURCE_LEVEL_LOW = VL53Base.SOURCE_LEVEL_LOW
        /** Interrupt source: range > high threshold. */
        const val SOURCE_LEVEL_HIGH = VL53Base.SOURCE_LEVEL_HIGH
        /** Interrupt source: range < low threshold or > high threshold. */
        const val SOURCE_OUT_OF_WINDOW = VL53Base.SOURCE_OUT_OF_WINDOW
        /** Interrupt source: a new measurement is available (driver default). */
        const val SOURCE_NEW_SAMPLE_READY = VL53Base.SOURCE_NEW_SAMPLE_READY
    }

    /** VCSEL period type for [setVcselPulsePeriod]. */
    enum class VcselPeriodType {
        /** Pre-range: 12, 14, 16 or 18 PCLKs. */
        PRE_RANGE,
        /** Final-range: 8, 10, 12 or 14 PCLKs. */
        FINAL_RANGE,
    }

    /** Ranging profile for [setProfile]. */
    enum class Profile(val signalRateLimit: Double, val prePclks: Int, val finalPclks: Int, val budgetUs: Int) {
        /** 0.25 MCPS, 14/10 PCLKs, 33 ms. */
        DEFAULT(0.25, 14, 10, 33000),
        /** 0.10 MCPS, 18/14 PCLKs, 33 ms — dark conditions only. */
        LONG_RANGE(0.10, 18, 14, 33000),
        /** 0.25 MCPS, 14/10 PCLKs, 20 ms. */
        HIGH_SPEED(0.25, 14, 10, 20000),
        /** 0.25 MCPS, 14/10 PCLKs, 200 ms. */
        HIGH_ACCURACY(0.25, 14, 10, 200000),
    }

    /**
     * Decoded result block, as returned by [readMeasurement].
     *
     * @property distanceMm range in mm
     * @property rangeStatus device range status 0–15 (11 = valid)
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
     * Start continuous ranging.
     *
     * @param periodMs 0 for back-to-back mode; otherwise timed mode with this
     *   inter-measurement period in ms (should be ≥ the timing budget)
     */
    @Synchronized
    fun startContinuous(periodMs: Int = 0) {
        stopVariablePreamble()
        if (periodMs > 0) {
            val osc = rd16(REG_OSC_CALIBRATE_VAL)
            wr32(REG_SYSTEM_INTERMEASUREMENT, if (osc != 0) periodMs.toLong() * osc else periodMs.toLong())
            wr(REG_SYSRANGE_START, 0x04)
        } else {
            wr(REG_SYSRANGE_START, 0x02)
        }
    }

    /** Stop continuous ranging. Does not wait for a running measurement. */
    @Synchronized
    fun stopContinuous() {
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x01)
        wr(REG_SYSRANGE_START, 0x00)
        wr(REG_STOP_VARIABLE, 0x00)
        wr(REG_SYSRANGE_START, 0x01)
        wr(REG_PAGE_SELECT, 0x00)
    }

    /**
     * Wait for the next continuous-mode result and read it.
     *
     * @return distance in mm (check [rangeValid])
     * @throws IOException on bus error, or if no result arrives within 500 ms
     */
    @Synchronized
    fun readContinuous(): Int = waitAndRead()

    /**
     * Report whether a measurement is pending (non-blocking).
     *
     * @return `true` if `RESULT_INTERRUPT_STATUS` bits 2:0 are non-zero
     */
    @Synchronized
    fun dataReady(): Boolean = (rd(REG_RESULT_INTERRUPT_STATUS) and 0x07) != 0

    /**
     * Read the full result block and clear the interrupt (non-blocking).
     *
     * @return decoded measurement record
     */
    @Synchronized
    fun readMeasurement(): Measurement {
        readResult()
        return Measurement(resultWord(10), rangeStatus, resultWord(6) / 128.0, resultWord(8) / 128.0,
            resultWord(2) / 256.0)
    }

    /**
     * Device range status of the most recent measurement.
     *
     * @return 0–15; 11 = valid, 4 = no target (MSRC)
     */
    fun rangeStatus(): Int = rangeStatus

    /**
     * Set the per-measurement timing budget.
     *
     * @param budgetUs budget in µs, ≥ 20000
     * @throws IllegalArgumentException if below 20000 µs or below the enabled steps' overhead
     */
    @Synchronized
    fun setTimingBudget(budgetUs: Int) = setTimingBudgetInternal(budgetUs)

    /**
     * Compute the timing budget from the current registers.
     *
     * @return budget in µs
     */
    @Synchronized
    fun timingBudget(): Int = getTimingBudget()

    /**
     * Set the final-range return signal-rate limit. Lower values extend range
     * but admit noisier readings.
     *
     * @param limitMcps limit in MCPS, 0 to 511.99
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setSignalRateLimit(limitMcps: Double) {
        require(limitMcps in 0.0..511.99) { "signal rate limit must be 0 to 511.99 MCPS" }
        wr16(REG_FINAL_MIN_COUNT_RATE_RTN, (limitMcps * 128).roundToInt())
    }

    /**
     * Read the final-range return signal-rate limit.
     *
     * @return limit in MCPS
     */
    @Synchronized
    fun signalRateLimit(): Double = rd16(REG_FINAL_MIN_COUNT_RATE_RTN) / 128.0

    /**
     * Set a VCSEL pulse period, then re-apply the timing budget and redo the
     * phase reference calibration.
     *
     * @param type pre-range or final-range
     * @param pclks pre-range 12, 14, 16 or 18; final-range 8, 10, 12 or 14
     * @throws IllegalArgumentException if pclks is invalid for the type
     * @throws IOException on bus error or a calibration timeout
     */
    @Synchronized
    fun setVcselPulsePeriod(type: VcselPeriodType, pclks: Int) {
        val preHigh = when {
            type != VcselPeriodType.PRE_RANGE -> 0
            pclks == 12 -> 0x18
            pclks == 14 -> 0x30
            pclks == 16 -> 0x40
            pclks == 18 -> 0x50
            else -> throw IllegalArgumentException("pre-range VCSEL period must be 12, 14, 16 or 18")
        }
        // VALID_PHASE_HIGH, VALID_PHASE_LOW, VCSEL_WIDTH, PHASECAL_CONFIG_TIMEOUT, page-1 PHASECAL_LIM.
        val fin = when {
            type != VcselPeriodType.FINAL_RANGE -> intArrayOf()
            pclks == 8 -> intArrayOf(0x10, 0x08, 0x02, 0x0C, 0x30)
            pclks == 10 -> intArrayOf(0x28, 0x08, 0x03, 0x09, 0x20)
            pclks == 12 -> intArrayOf(0x38, 0x08, 0x03, 0x08, 0x20)
            pclks == 14 -> intArrayOf(0x48, 0x08, 0x03, 0x07, 0x20)
            else -> throw IllegalArgumentException("final-range VCSEL period must be 8, 10, 12 or 14")
        }

        val enables = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        val t = stepTimeouts(enables)
        val vcsel = encodeVcsel(pclks)

        if (type == VcselPeriodType.PRE_RANGE) {
            wr(REG_PRE_VALID_PHASE_HIGH, preHigh)
            wr(REG_PRE_VALID_PHASE_LOW, 0x08)
            wr(REG_PRE_RANGE_VCSEL_PERIOD, vcsel)
            wr16(REG_PRE_RANGE_TIMEOUT, encodeTimeout(usToMclks(t.preUs.toLong(), pclks)))
            val m = usToMclks(t.msrcUs.toLong(), pclks)
            wr(REG_MSRC_CONFIG_TIMEOUT, if (m > 256) 255 else m - 1)
        } else {
            wr(REG_FINAL_VALID_PHASE_HIGH, fin[0])
            wr(REG_FINAL_VALID_PHASE_LOW, fin[1])
            wr(REG_GLOBAL_CONFIG_VCSEL_WIDTH, fin[2])
            wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[3])
            wr(REG_PAGE_SELECT, 0x01)
            wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[4])
            wr(REG_PAGE_SELECT, 0x00)
            wr(REG_FINAL_RANGE_VCSEL_PERIOD, vcsel)
            var f = usToMclks(t.finalUs.toLong(), pclks)
            if ((enables and SEQ_PRE_RANGE) != 0) f += t.preMclks
            wr16(REG_FINAL_RANGE_TIMEOUT, encodeTimeout(f))
        }

        setTimingBudgetInternal(timingBudgetUs)
        val seq = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        try {
            wr(REG_SYSTEM_SEQUENCE_CONFIG, 0x02)
            singleRefCalibration(0x00)
        } finally {
            wr(REG_SYSTEM_SEQUENCE_CONFIG, seq)
        }
    }

    /**
     * Read a VCSEL pulse period.
     *
     * @param type pre-range or final-range
     * @return period in PCLKs
     */
    @Synchronized
    fun vcselPulsePeriod(type: VcselPeriodType): Int = decodeVcsel(
        rd(if (type == VcselPeriodType.PRE_RANGE) REG_PRE_RANGE_VCSEL_PERIOD else REG_FINAL_RANGE_VCSEL_PERIOD))

    /**
     * Apply a ranging profile: signal-rate limit, VCSEL periods (pre first),
     * then timing budget.
     *
     * @param profile profile to apply
     */
    @Synchronized
    fun setProfile(profile: Profile) {
        setSignalRateLimit(profile.signalRateLimit)
        setVcselPulsePeriod(VcselPeriodType.PRE_RANGE, profile.prePclks)
        setVcselPulsePeriod(VcselPeriodType.FINAL_RANGE, profile.finalPclks)
        setTimingBudgetInternal(profile.budgetUs)
    }

    /**
     * Override the part-to-part range offset (volatile).
     *
     * @param offsetMm offset in mm, −512.0 to 511.75 (0.25 mm steps)
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setOffset(offsetMm: Double) {
        require(offsetMm in -512.0..511.75) { "offset must be -512.0 to 511.75 mm" }
        val q = offsetMm * 4
        val steps = if (q >= 0) (q + 0.5).toInt() else -(-q + 0.5).toInt()
        wr16(REG_PART_TO_PART_RANGE_OFFSET, steps and 0x0FFF)
    }

    /**
     * Read the part-to-part range offset.
     *
     * @return offset in mm
     */
    @Synchronized
    fun offset(): Double {
        // 12-bit two's complement: sign-extend bit 11 explicitly.
        var raw = rd16(REG_PART_TO_PART_RANGE_OFFSET) and 0x0FFF
        if ((raw and 0x0800) != 0) raw -= 0x1000
        return raw * 0.25
    }

    /**
     * Set the crosstalk compensation peak rate (volatile).
     *
     * @param rateMcps 0 disables; otherwise 0 < rate < 8.0 MCPS, from the
     *   host's own cover-glass calibration
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setCrosstalkCompensation(rateMcps: Double) {
        require(rateMcps >= 0 && rateMcps < 8.0) { "crosstalk rate must be 0 (off) or below 8.0 MCPS" }
        wr16(REG_CROSSTALK_COMPENSATION, (rateMcps * 8192).roundToInt())
    }

    /**
     * Re-run the VHV and phase reference calibrations. Call in software
     * standby (not while continuous ranging), and after the die temperature
     * changes by more than 8 °C.
     *
     * @throws IOException on bus error or a calibration timeout
     */
    @Synchronized
    fun recalibrate() = refCalibration()

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
     * @param lowMm low threshold in mm (2 mm resolution)
     * @param highMm high threshold in mm, lowMm ≤ highMm ≤ 8190
     * @throws IllegalArgumentException if out of range
     */
    @Synchronized
    fun setInterruptThresholds(lowMm: Int, highMm: Int) {
        require(lowMm >= 0 && highMm >= lowMm && highMm <= 8190) {
            "thresholds must satisfy 0 <= low <= high <= 8190 mm"
        }
        wr16(REG_SYSTEM_THRESH_LOW, (lowMm / 2) and 0x0FFF)
        wr16(REG_SYSTEM_THRESH_HIGH, (highMm / 2) and 0x0FFF)
    }

    /**
     * Read the distance thresholds.
     *
     * @return (lowMm, highMm)
     */
    @Synchronized
    fun interruptThresholds(): Pair<Int, Int> =
        Pair((rd16(REG_SYSTEM_THRESH_LOW) and 0x0FFF) * 2, (rd16(REG_SYSTEM_THRESH_HIGH) and 0x0FFF) * 2)

    /**
     * Read `IDENTIFICATION_MODEL_ID`.
     *
     * @return 0xEE
     */
    @Synchronized
    fun modelId(): Int = rd(REG_MODEL_ID)

    /**
     * Read `IDENTIFICATION_REVISION_ID`.
     *
     * @return revision ID (0x10 on current silicon)
     */
    @Synchronized
    fun revisionId(): Int = rd(REG_REVISION_ID)

    /**
     * Select the GPIO1 interrupt source (replaces the active one). With a
     * threshold source active, [dataReady]/[readContinuous] only see a pending
     * status when the threshold condition is met.
     *
     * @param source one of the `SOURCE_*` constants
     * @throws IllegalArgumentException if source is not 1–4
     */
    @Synchronized
    fun enableInterrupt(source: Int) {
        require(source in SOURCE_LEVEL_LOW..SOURCE_NEW_SAMPLE_READY) { "source must be one of the SOURCE_* constants" }
        wr(REG_SYSTEM_INTERRUPT_CONFIG, source)
    }

    /**
     * Disable GPIO1 interrupts if [source] is the active one.
     *
     * @param source one of the `SOURCE_*` constants
     */
    @Synchronized
    fun disableInterrupt(source: Int) {
        if ((rd(REG_SYSTEM_INTERRUPT_CONFIG) and 0x07) == source) wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x00)
    }

    /**
     * Read and clear the pending interrupt status.
     *
     * @return the `SOURCE_*` value that fired, or 0 if nothing is pending
     */
    @Synchronized
    fun pollInterrupt(): Int = pollInterruptStatus()

    /**
     * Subscribe to GPIO1 events (falling edge, GPIO1 is active low). The
     * driver reads and clears the status before invoking the callback; the
     * polling fallback consumes results, so don't mix it with [readContinuous].
     *
     * @param callback called with the `SOURCE_*` value that fired
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
