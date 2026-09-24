package it.uhde.periph.chips.tof

import groovy.transform.CompileStatic

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.InputPin

import java.io.IOException
import java.util.function.IntConsumer

/**
 * VL53L0X full interface — extends {@link VL53L0XMinimal} with continuous and timed ranging, the
 * full measurement record, timing budget, signal-rate limit, VCSEL pulse periods, ranging
 * profiles, offset and crosstalk compensation, reference recalibration, address change, distance
 * thresholds, identification, and the Level-2 interrupt API.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge,
 * GPIO1 is active low), otherwise a 5&nbsp;ms polling thread.
 */
@CompileStatic
class VL53L0XFull extends VL53L0XMinimal {

    /** Interrupt source: range &lt; low threshold. */
    public static final int SOURCE_LEVEL_LOW = VL53Base.SOURCE_LEVEL_LOW
    /** Interrupt source: range &gt; high threshold. */
    public static final int SOURCE_LEVEL_HIGH = VL53Base.SOURCE_LEVEL_HIGH
    /** Interrupt source: range &lt; low threshold or &gt; high threshold. */
    public static final int SOURCE_OUT_OF_WINDOW = VL53Base.SOURCE_OUT_OF_WINDOW
    /** Interrupt source: a new measurement is available (driver default). */
    public static final int SOURCE_NEW_SAMPLE_READY = VL53Base.SOURCE_NEW_SAMPLE_READY

    /** VCSEL period type for {@link #setVcselPulsePeriod(VcselPeriodType, int)}. */
    public enum VcselPeriodType {
        /** Pre-range: 12, 14, 16 or 18 PCLKs. */
        PRE_RANGE,
        /** Final-range: 8, 10, 12 or 14 PCLKs. */
        FINAL_RANGE
    }

    /** Ranging profile for {@link #setProfile(Profile)}. */
    public enum Profile {
        /** 0.25 MCPS, 14/10 PCLKs, 33&nbsp;ms. */
        DEFAULT(0.25, 14, 10, 33000),
        /** 0.10 MCPS, 18/14 PCLKs, 33&nbsp;ms — dark conditions only. */
        LONG_RANGE(0.10, 18, 14, 33000),
        /** 0.25 MCPS, 14/10 PCLKs, 20&nbsp;ms. */
        HIGH_SPEED(0.25, 14, 10, 20000),
        /** 0.25 MCPS, 14/10 PCLKs, 200&nbsp;ms. */
        HIGH_ACCURACY(0.25, 14, 10, 200000)

        final double signalRateLimit
        final int prePclks
        final int finalPclks
        final int budgetUs

        Profile(double signalRateLimit, int prePclks, int finalPclks, int budgetUs) {
            this.signalRateLimit = signalRateLimit
            this.prePclks = prePclks
            this.finalPclks = finalPclks
            this.budgetUs = budgetUs
        }
    }

    /**
     * Decoded result block, as returned by {@link #readMeasurement()}.
     *
     * @param distanceMm         range in mm
     * @param rangeStatus        device range status 0–15 (11 = valid)
     * @param signalRateMcps     return signal rate in MCPS
     * @param ambientRateMcps    ambient rate in MCPS
     * @param effectiveSpadCount effective SPAD return count
     */
    @groovy.transform.EqualsAndHashCode
    @groovy.transform.ToString(includeNames = true)
    static class Measurement {
        final int distanceMm
        final int rangeStatus
        final double signalRateMcps
        final double ambientRateMcps
        final double effectiveSpadCount

        Measurement(int distanceMm, int rangeStatus, double signalRateMcps, double ambientRateMcps,
                    double effectiveSpadCount) {
            this.distanceMm = distanceMm
            this.rangeStatus = rangeStatus
            this.signalRateMcps = signalRateMcps
            this.ambientRateMcps = ambientRateMcps
            this.effectiveSpadCount = effectiveSpadCount
        }
    }


    /**
     * Construct the driver; same initialization as {@link VL53L0XMinimal#VL53L0XMinimal(Connection)}.
     *
     * @param connection configured I²C connection pointing at the device (0x29)
     * @throws IOException on bus error, a model ID other than 0xEE, or an init poll timeout
     */
    VL53L0XFull(Connection connection) throws IOException {
        super(connection)
    }

    /**
     * Start continuous ranging.
     *
     * @param periodMs 0 for back-to-back mode; otherwise timed mode with this inter-measurement
     *                 period in ms (should be ≥ the timing budget)
     * @throws IOException on bus error
     */
    public synchronized void startContinuous(int periodMs) throws IOException {
        stopVariablePreamble()
        if (periodMs > 0) {
            int osc = rd16(REG_OSC_CALIBRATE_VAL)
            wr32(REG_SYSTEM_INTERMEASUREMENT, osc != 0 ? (long) periodMs * osc : (long) periodMs)
            wr(REG_SYSRANGE_START, 0x04)
        } else {
            wr(REG_SYSRANGE_START, 0x02)
        }
    }

    /**
     * Start back-to-back continuous ranging.
     *
     * @throws IOException on bus error
     */
    public void startContinuous() throws IOException {
        startContinuous(0)
    }

    /**
     * Stop continuous ranging. Does not wait for a running measurement.
     *
     * @throws IOException on bus error
     */
    public synchronized void stopContinuous() throws IOException {
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
     * @return distance in mm (check {@link #rangeValid()})
     * @throws IOException on bus error, or if no result arrives within 500&nbsp;ms
     */
    public synchronized int readContinuous() throws IOException {
        return waitAndRead()
    }

    /**
     * Report whether a measurement is pending (non-blocking).
     *
     * @return {@code true} if {@code RESULT_INTERRUPT_STATUS} bits 2:0 are non-zero
     * @throws IOException on bus error
     */
    public synchronized boolean dataReady() throws IOException {
        return (rd(REG_RESULT_INTERRUPT_STATUS) & 0x07) != 0
    }

    /**
     * Read the full result block and clear the interrupt (non-blocking).
     *
     * @return decoded measurement record
     * @throws IOException on bus error
     */
    public synchronized Measurement readMeasurement() throws IOException {
        readResult()
        return new Measurement(resultWord(10), rangeStatus, resultWord(6) / 128.0d, resultWord(8) / 128.0d,
                resultWord(2) / 256.0d)
    }

    /**
     * Device range status of the most recent measurement.
     *
     * @return 0–15; 11 = valid, 4 = no target (MSRC)
     */
    public int rangeStatus() {
        return rangeStatus
    }

    /**
     * Set the per-measurement timing budget.
     *
     * @param budgetUs budget in µs, ≥ 20000
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if below 20000&nbsp;µs or below the enabled steps' overhead
     */
    public synchronized void setTimingBudget(int budgetUs) throws IOException {
        setTimingBudgetInternal(budgetUs)
    }

    /**
     * Compute the timing budget from the current registers.
     *
     * @return budget in µs
     * @throws IOException on bus error
     */
    public synchronized int timingBudget() throws IOException {
        return getTimingBudget()
    }

    /**
     * Set the final-range return signal-rate limit. Lower values extend range but admit noisier
     * readings.
     *
     * @param limitMcps limit in MCPS, 0 to 511.99
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setSignalRateLimit(double limitMcps) throws IOException {
        if (!(limitMcps >= 0 && limitMcps <= 511.99)) {
            throw new IllegalArgumentException("signal rate limit must be 0 to 511.99 MCPS")
        }
        wr16(REG_FINAL_MIN_COUNT_RATE_RTN, (int) Math.round(limitMcps * 128))
    }

    /**
     * Read the final-range return signal-rate limit.
     *
     * @return limit in MCPS
     * @throws IOException on bus error
     */
    public synchronized double signalRateLimit() throws IOException {
        return rd16(REG_FINAL_MIN_COUNT_RATE_RTN) / 128.0d
    }

    /**
     * Set a VCSEL pulse period, then re-apply the timing budget and redo the phase reference
     * calibration.
     *
     * @param type  pre-range or final-range
     * @param pclks pre-range 12, 14, 16 or 18; final-range 8, 10, 12 or 14
     * @throws IOException              on bus error or a calibration timeout
     * @throws IllegalArgumentException if pclks is invalid for the type
     */
    public synchronized void setVcselPulsePeriod(VcselPeriodType type, int pclks) throws IOException {
        int preHigh = 0
        int[] fin = null
        if (type == VcselPeriodType.PRE_RANGE) {
            if (pclks == 12) preHigh = 0x18
            else if (pclks == 14) preHigh = 0x30
            else if (pclks == 16) preHigh = 0x40
            else if (pclks == 18) preHigh = 0x50
            else throw new IllegalArgumentException("pre-range VCSEL period must be 12, 14, 16 or 18")
        } else {
            // VALID_PHASE_HIGH, VALID_PHASE_LOW, VCSEL_WIDTH, PHASECAL_CONFIG_TIMEOUT, page-1 PHASECAL_LIM.
            if (pclks == 8) fin = [0x10, 0x08, 0x02, 0x0C, 0x30] as int[]
            else if (pclks == 10) fin = [0x28, 0x08, 0x03, 0x09, 0x20] as int[]
            else if (pclks == 12) fin = [0x38, 0x08, 0x03, 0x08, 0x20] as int[]
            else if (pclks == 14) fin = [0x48, 0x08, 0x03, 0x07, 0x20] as int[]
            else throw new IllegalArgumentException("final-range VCSEL period must be 8, 10, 12 or 14")
        }

        int enables = rd(REG_SYSTEM_SEQUENCE_CONFIG)
        StepTimeouts t = stepTimeouts(enables)
        int vcsel = encodeVcsel(pclks)

        if (type == VcselPeriodType.PRE_RANGE) {
            wr(REG_PRE_VALID_PHASE_HIGH, preHigh)
            wr(REG_PRE_VALID_PHASE_LOW, 0x08)
            wr(REG_PRE_RANGE_VCSEL_PERIOD, vcsel)
            wr16(REG_PRE_RANGE_TIMEOUT, encodeTimeout(usToMclks(t.preUs, pclks)))
            int m = usToMclks(t.msrcUs, pclks)
            wr(REG_MSRC_CONFIG_TIMEOUT, m > 256 ? 255 : m - 1)
        } else {
            wr(REG_FINAL_VALID_PHASE_HIGH, fin[0])
            wr(REG_FINAL_VALID_PHASE_LOW, fin[1])
            wr(REG_GLOBAL_CONFIG_VCSEL_WIDTH, fin[2])
            wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[3])
            wr(REG_PAGE_SELECT, 0x01)
            wr(REG_PHASECAL_CONFIG_TIMEOUT, fin[4])
            wr(REG_PAGE_SELECT, 0x00)
            wr(REG_FINAL_RANGE_VCSEL_PERIOD, vcsel)
            int f = usToMclks(t.finalUs, pclks)
            if ((enables & SEQ_PRE_RANGE) != 0) f += t.preMclks
            wr16(REG_FINAL_RANGE_TIMEOUT, encodeTimeout(f))
        }

        setTimingBudgetInternal(timingBudgetUs)
        int seq = rd(REG_SYSTEM_SEQUENCE_CONFIG)
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
     * @throws IOException on bus error
     */
    public synchronized int vcselPulsePeriod(VcselPeriodType type) throws IOException {
        return decodeVcsel(rd(type == VcselPeriodType.PRE_RANGE
                ? REG_PRE_RANGE_VCSEL_PERIOD : REG_FINAL_RANGE_VCSEL_PERIOD))
    }

    /**
     * Apply a ranging profile: signal-rate limit, VCSEL periods (pre first), then timing budget.
     *
     * @param profile profile to apply
     * @throws IOException on bus error or a calibration timeout
     */
    public synchronized void setProfile(Profile profile) throws IOException {
        setSignalRateLimit(profile.signalRateLimit)
        setVcselPulsePeriod(VcselPeriodType.PRE_RANGE, profile.prePclks)
        setVcselPulsePeriod(VcselPeriodType.FINAL_RANGE, profile.finalPclks)
        setTimingBudgetInternal(profile.budgetUs)
    }

    /**
     * Override the part-to-part range offset (volatile).
     *
     * @param offsetMm offset in mm, −512.0 to 511.75 (0.25&nbsp;mm steps)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setOffset(double offsetMm) throws IOException {
        if (!(offsetMm >= -512.0 && offsetMm <= 511.75)) {
            throw new IllegalArgumentException("offset must be -512.0 to 511.75 mm")
        }
        double q = offsetMm * 4
        int steps = q >= 0 ? (int) (q + 0.5) : -(int) (-q + 0.5)
        wr16(REG_PART_TO_PART_RANGE_OFFSET, steps & 0x0FFF)
    }

    /**
     * Read the part-to-part range offset.
     *
     * @return offset in mm
     * @throws IOException on bus error
     */
    public synchronized double offset() throws IOException {
        int raw = rd16(REG_PART_TO_PART_RANGE_OFFSET) & 0x0FFF
        if ((raw & 0x0800) != 0) raw -= 0x1000
        return raw * 0.25
    }

    /**
     * Set the crosstalk compensation peak rate (volatile).
     *
     * @param rateMcps 0 disables; otherwise 0 &lt; rate &lt; 8.0 MCPS, from the host's own cover-glass
     *                 calibration
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setCrosstalkCompensation(double rateMcps) throws IOException {
        if (!(rateMcps >= 0 && rateMcps < 8.0)) {
            throw new IllegalArgumentException("crosstalk rate must be 0 (off) or below 8.0 MCPS")
        }
        wr16(REG_CROSSTALK_COMPENSATION, (int) Math.round(rateMcps * 8192))
    }

    /**
     * Re-run the VHV and phase reference calibrations. Call in software standby (not while
     * continuous ranging), and after the die temperature changes by more than 8&nbsp;°C.
     *
     * @throws IOException on bus error or a calibration timeout
     */
    public synchronized void recalibrate() throws IOException {
        refCalibration()
    }

    /**
     * Change the chip's I²C address (volatile). The chip answers on the new address immediately
     * this driver instance becomes unusable — construct a new connection at the new address and a
     * new driver.
     *
     * @param address new 7-bit address, 0x08–0x77
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAddress(int address) throws IOException {
        setAddressReg(REG_I2C_SLAVE_DEVICE_ADDRESS, address)
    }

    /**
     * Set the distance thresholds used by the threshold interrupt sources.
     *
     * @param lowMm  low threshold in mm (2&nbsp;mm resolution)
     * @param highMm high threshold in mm, lowMm ≤ highMm ≤ 8190
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setInterruptThresholds(int lowMm, int highMm) throws IOException {
        if (lowMm < 0 || highMm < lowMm || highMm > 8190) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low <= high <= 8190 mm")
        }
        wr16(REG_SYSTEM_THRESH_LOW, lowMm.intdiv(2) & 0x0FFF)
        wr16(REG_SYSTEM_THRESH_HIGH, highMm.intdiv(2) & 0x0FFF)
    }

    /**
     * Read the distance thresholds.
     *
     * @return {@code {lowMm, highMm}}
     * @throws IOException on bus error
     */
    public synchronized int[] interruptThresholds() throws IOException {
        return [(rd16(REG_SYSTEM_THRESH_LOW) & 0x0FFF) * 2, (rd16(REG_SYSTEM_THRESH_HIGH) & 0x0FFF) * 2] as int[]
    }

    /**
     * Read {@code IDENTIFICATION_MODEL_ID}.
     *
     * @return 0xEE
     * @throws IOException on bus error
     */
    public synchronized int modelId() throws IOException {
        return rd(REG_MODEL_ID)
    }

    /**
     * Read {@code IDENTIFICATION_REVISION_ID}.
     *
     * @return revision ID (0x10 on current silicon)
     * @throws IOException on bus error
     */
    public synchronized int revisionId() throws IOException {
        return rd(REG_REVISION_ID)
    }

    /**
     * Select the GPIO1 interrupt source (replaces the active one). With a threshold source active,
     * {@link #dataReady()}/{@link #readContinuous()} only see a pending status when the threshold
     * condition is met.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if source is not 1–4
     */
    public synchronized void enableInterrupt(int source) throws IOException {
        if (source < SOURCE_LEVEL_LOW || source > SOURCE_NEW_SAMPLE_READY) {
            throw new IllegalArgumentException("source must be one of the SOURCE_* constants")
        }
        wr(REG_SYSTEM_INTERRUPT_CONFIG, source)
    }

    /**
     * Disable GPIO1 interrupts if {@code source} is the active one.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public synchronized void disableInterrupt(int source) throws IOException {
        if ((rd(REG_SYSTEM_INTERRUPT_CONFIG) & 0x07) == source) wr(REG_SYSTEM_INTERRUPT_CONFIG, 0x00)
    }

    /**
     * Read and clear the pending interrupt status.
     *
     * @return the {@code SOURCE_*} value that fired, or 0 if nothing is pending
     * @throws IOException on bus error
     */
    public synchronized int pollInterrupt() throws IOException {
        return pollInterruptStatus()
    }

    /**
     * Subscribe to GPIO1 events using {@code connection.intPin()} (or a 5&nbsp;ms polling thread if
     * none is wired).
     *
     * @param callback called with the {@code SOURCE_*} value that fired
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to GPIO1 events, overriding which {@link InputPin} delivers edges (falling edge,
     * GPIO1 is active low). The driver reads and clears the status before invoking the callback
     * the polling fallback consumes results, so don't mix it with {@link #readContinuous()}.
     *
     * @param callback called with the {@code SOURCE_*} value that fired
     * @param intPin   GPIO1 pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        subscribe(callback, intPin)
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        unsubscribe()
    }
}
