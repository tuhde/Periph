package it.uhde.periph.chips.tof;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * VL53L1X full interface — extends {@link VL53L1XMinimal} with timed continuous ranging, the full
 * measurement record, distance mode, timing budget, inter-measurement period, signal and sigma
 * thresholds, region of interest, offset and crosstalk compensation with calibration helpers,
 * temperature update, address change, distance thresholds, identification, and the Level-2
 * interrupt API.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge,
 * GPIO1 is active low), otherwise a 5&nbsp;ms polling thread. The {@code SOURCE_*} constants are
 * inherited from {@link VL53Base}.
 */
public class VL53L1XFull extends VL53L1XMinimal {

    /** Distance mode for {@link #setDistanceMode(DistanceMode)}. */
    public enum DistanceMode {
        /** ~1.3&nbsp;m, robust against ambient light. */
        SHORT,
        /** Up to 4&nbsp;m in the dark (default). */
        LONG
    }

    /**
     * Decoded result block, as returned by {@link #readMeasurement()}.
     *
     * @param distanceMm         range in mm
     * @param rangeStatus        mapped range status (0 = valid, 255 = no update)
     * @param signalRateMcps     return signal rate in MCPS
     * @param ambientRateMcps    ambient rate in MCPS
     * @param effectiveSpadCount effective SPAD return count
     */
    public record Measurement(int distanceMm, int rangeStatus, double signalRateMcps, double ambientRateMcps,
                              double effectiveSpadCount) {}

    private static final int CALIBRATION_SAMPLES = 50;

    /** Timing budget table: ms, short A, short B, long A, long B (0 = not available). */
    private static final int[][] BUDGETS = {
        {15, 0x001D, 0x0027, 0, 0},
        {20, 0x0051, 0x006E, 0x001E, 0x0022},
        {33, 0x00D6, 0x006E, 0x0060, 0x006E},
        {50, 0x01AE, 0x01E8, 0x00AD, 0x00C6},
        {100, 0x02E1, 0x0388, 0x01CC, 0x01EA},
        {200, 0x03E1, 0x0496, 0x02D9, 0x02F8},
        {500, 0x0591, 0x05C1, 0x048F, 0x04A4},
    };

    /**
     * Construct the driver; same initialization as {@link VL53L1XMinimal#VL53L1XMinimal(Connection)}.
     *
     * @param connection configured I²C connection pointing at the device (0x29)
     * @throws IOException on bus error, a sensor ID other than 0xEACC, or an init poll timeout
     */
    public VL53L1XFull(Connection connection) throws IOException {
        super(connection);
    }

    /**
     * Start timed continuous ranging as fast as the timing budget allows (back-to-back).
     *
     * @throws IOException on bus error
     */
    public synchronized void startContinuous() throws IOException {
        startContinuous(0);
    }

    /**
     * Start timed continuous ranging.
     *
     * @param periodMs inter-measurement period in ms, 0–60000; 0 (and any value below the timing
     *                 budget) runs at the timing budget, i.e. back-to-back
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void startContinuous(int periodMs) throws IOException {
        if (periodMs < 0 || periodMs > 60000) throw new IllegalArgumentException("period must be 0 to 60000 ms");
        int period = Math.max(Math.max(periodMs, timingBudget() / 1000), 1);
        setInterMeasurement(period);
        write8(REG_INTERRUPT_CLEAR, 0x01);
        write8(REG_MODE_START, 0x40);
    }

    /**
     * Stop continuous ranging. Does not wait for a running measurement.
     *
     * @throws IOException on bus error
     */
    public synchronized void stopContinuous() throws IOException {
        write8(REG_MODE_START, 0x00);
    }

    /**
     * Wait for the next continuous-mode result and read it.
     *
     * @return distance in mm (check {@link #rangeValid()})
     * @throws IOException on bus error or if no result arrives within 500&nbsp;ms
     */
    public synchronized int readContinuous() throws IOException {
        return waitAndRead();
    }

    /**
     * Report whether a measurement is pending (non-blocking).
     *
     * @return true if GPIO__TIO_HV_STATUS shows the GPIO1 line asserted
     * @throws IOException on bus error
     */
    public synchronized boolean dataReady() throws IOException {
        return isDataReady();
    }

    /**
     * Read the full result block and clear the interrupt (non-blocking).
     *
     * @return decoded measurement record
     * @throws IOException on bus error
     */
    public synchronized Measurement readMeasurement() throws IOException {
        readResult();
        return new Measurement(resultWord(13), rangeStatus, resultWord(15) / 128.0, resultWord(7) / 128.0,
                resultWord(3) / 256.0);
    }

    /**
     * Mapped range status of the most recent measurement.
     *
     * @return 0 = valid, 1 = sigma fail, 2 = signal fail, 4 = out of bounds, 7 = wrap-around,
     *         255 = no update
     */
    public int rangeStatus() {
        return rangeStatus;
    }

    /**
     * Set the per-measurement timing budget (ULD table values only).
     *
     * @param budgetUs 15000 (short mode only), 20000, 33000, 50000, 100000, 200000 or 500000
     * @throws IllegalArgumentException if not in the table for the current distance mode
     * @throws IOException              on bus error
     */
    public synchronized void setTimingBudget(int budgetUs) throws IOException {
        int col = distanceMode() == DistanceMode.SHORT ? 1 : 3;
        if (budgetUs % 1000 == 0) {
            for (int[] row : BUDGETS) {
                if (row[0] * 1000 == budgetUs && row[col] != 0) {
                    write16(REG_RANGE_TIMEOUT_A, row[col]);
                    write16(REG_RANGE_TIMEOUT_B, row[col + 1]);
                    return;
                }
            }
        }
        throw new IllegalArgumentException("timing budget " + budgetUs + " us is not available in this distance mode");
    }

    /**
     * Decode the timing budget from RANGE_CONFIG__TIMEOUT_MACROP_A.
     *
     * @return budget in µs, or 0 if the register holds no table value
     * @throws IOException on bus error
     */
    public synchronized int timingBudget() throws IOException {
        int a = read16(REG_RANGE_TIMEOUT_A);
        for (int[] row : BUDGETS) {
            if (row[1] == a || (row[3] != 0 && row[3] == a)) return row[0] * 1000;
        }
        return 0;
    }

    /**
     * Select short or long distance mode, keeping the timing budget (100&nbsp;ms if the current
     * budget is unknown).
     *
     * @param mode short (~1.3&nbsp;m, robust in sunlight) or long (up to 4&nbsp;m)
     * @throws IllegalArgumentException when switching to long at a 15&nbsp;ms budget
     * @throws IOException              on bus error
     */
    public synchronized void setDistanceMode(DistanceMode mode) throws IOException {
        int budget = timingBudget();
        if (budget == 0) budget = 100000;
        if (mode == DistanceMode.LONG && budget == 15000) {
            throw new IllegalArgumentException("15 ms timing budget is only available in short distance mode");
        }
        boolean isShort = mode == DistanceMode.SHORT;
        write8(REG_PHASECAL_TIMEOUT, isShort ? 0x14 : 0x0A);
        write8(REG_RANGE_VCSEL_PERIOD_A, isShort ? 0x07 : 0x0F);
        write8(REG_RANGE_VCSEL_PERIOD_B, isShort ? 0x05 : 0x0D);
        write8(REG_RANGE_VALID_PHASE_HIGH, isShort ? 0x38 : 0xB8);
        write16(REG_SD_WOI_SD0, isShort ? 0x0705 : 0x0F0D);
        write16(REG_SD_INITIAL_PHASE_SD0, isShort ? 0x0606 : 0x0E0E);
        setTimingBudget(budget);
    }

    /**
     * Read the current distance mode.
     *
     * @return short or long
     * @throws IOException on bus error, or if PHASECAL_CONFIG__TIMEOUT_MACROP holds neither mode's value
     */
    public synchronized DistanceMode distanceMode() throws IOException {
        int v = read8(REG_PHASECAL_TIMEOUT);
        if (v == 0x14) return DistanceMode.SHORT;
        if (v == 0x0A) return DistanceMode.LONG;
        throw new IOException(String.format("VL53L1X unknown distance mode register value 0x%02X", v));
    }

    /**
     * Set the continuous-mode inter-measurement period; should be ≥ the timing budget
     * ({@link #startContinuous(int)} enforces this).
     *
     * @param periodMs period in ms, 1–60000
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setInterMeasurement(int periodMs) throws IOException {
        if (periodMs < 1 || periodMs > 60000) {
            throw new IllegalArgumentException("inter-measurement period must be 1 to 60000 ms");
        }
        long clockPll = read16(REG_OSC_CALIBRATE_VAL) & 0x03FF;
        write32(REG_INTERMEASUREMENT_PERIOD, clockPll * periodMs * 1075 / 1000);
    }

    /**
     * Read the continuous-mode inter-measurement period.
     *
     * @return period in ms (0 if the oscillator calibration reads 0)
     * @throws IOException on bus error
     */
    public synchronized int interMeasurement() throws IOException {
        long clockPll = read16(REG_OSC_CALIBRATE_VAL) & 0x03FF;
        if (clockPll == 0) return 0;
        return (int) (read32(REG_INTERMEASUREMENT_PERIOD) * 1000 / (clockPll * 1075));
    }

    /**
     * Set the minimum return signal rate for a valid result.
     *
     * @param limitMcps limit in MCPS, 0 to 511.99 (default 1.0)
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setSignalRateLimit(double limitMcps) throws IOException {
        if (limitMcps < 0 || limitMcps > 511.99) {
            throw new IllegalArgumentException("signal rate limit must be 0 to 511.99 MCPS");
        }
        write16(REG_MIN_COUNT_RATE_RTN_LIMIT, (int) (limitMcps * 128 + 0.5));
    }

    /**
     * Read the minimum return signal rate.
     *
     * @return limit in MCPS
     * @throws IOException on bus error
     */
    public synchronized double signalRateLimit() throws IOException {
        return read16(REG_MIN_COUNT_RATE_RTN_LIMIT) / 128.0;
    }

    /**
     * Set the maximum estimated standard deviation for a valid result.
     *
     * @param sigmaMm threshold in mm, 0–16383 (default 90)
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setSigmaThreshold(int sigmaMm) throws IOException {
        if (sigmaMm < 0 || sigmaMm > 16383) throw new IllegalArgumentException("sigma threshold must be 0 to 16383 mm");
        write16(REG_SIGMA_THRESH, sigmaMm << 2);
    }

    /**
     * Read the sigma threshold.
     *
     * @return threshold in mm
     * @throws IOException on bus error
     */
    public synchronized int sigmaThreshold() throws IOException {
        return read16(REG_SIGMA_THRESH) >> 2;
    }

    /**
     * Set the receiving region-of-interest size; sizes above 10 SPADs re-centre the ROI on SPAD 199
     * (array centre) so it stays on the array.
     *
     * @param width  ROI width in SPADs, 4–16
     * @param height ROI height in SPADs, 4–16
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setRoi(int width, int height) throws IOException {
        if (width < 4 || width > 16 || height < 4 || height > 16) {
            throw new IllegalArgumentException("ROI width and height must be 4 to 16 SPADs");
        }
        if (width > 10 || height > 10) write8(REG_ROI_CENTRE_SPAD, 199);
        write8(REG_ROI_XY_SIZE, ((height - 1) << 4) | (width - 1));
    }

    /**
     * Read the region-of-interest size.
     *
     * @return {@code {width, height}} in SPADs
     * @throws IOException on bus error
     */
    public synchronized int[] roi() throws IOException {
        int v = read8(REG_ROI_XY_SIZE);
        return new int[]{(v & 0x0F) + 1, (v >> 4) + 1};
    }

    /**
     * Move the region of interest to a centre SPAD (ST UM2555 numbering, 199 = array centre). The
     * caller keeps the ROI inside the array.
     *
     * @param spad SPAD number, 0–255
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setRoiCenter(int spad) throws IOException {
        if (spad < 0 || spad > 255) throw new IllegalArgumentException("ROI centre SPAD must be 0 to 255");
        write8(REG_ROI_CENTRE_SPAD, spad);
    }

    /**
     * Read the region-of-interest centre SPAD.
     *
     * @return SPAD number
     * @throws IOException on bus error
     */
    public synchronized int roiCenter() throws IOException {
        return read8(REG_ROI_CENTRE_SPAD);
    }

    /**
     * Read the factory-measured optical-centre SPAD from NVM.
     *
     * @return SPAD number; pass to {@link #setRoiCenter(int)} to align the ROI with the lens
     * @throws IOException on bus error
     */
    public synchronized int opticalCenter() throws IOException {
        return read8(REG_MODE_ROI_CENTRE_SPAD);
    }

    /**
     * Override the part-to-part range offset (volatile).
     *
     * @param offsetMm offset in mm, −1024.0 to 1023.75 (0.25&nbsp;mm steps)
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setOffset(double offsetMm) throws IOException {
        if (offsetMm < -1024.0 || offsetMm > 1023.75) {
            throw new IllegalArgumentException("offset must be -1024.0 to 1023.75 mm");
        }
        double q = offsetMm * 4;
        int raw = q >= 0 ? (int) (q + 0.5) : -(int) (-q + 0.5);
        write16(REG_PART_TO_PART_OFFSET, raw & 0x1FFF);
        write16(REG_MM_INNER_OFFSET, 0);
        write16(REG_MM_OUTER_OFFSET, 0);
    }

    /**
     * Read the part-to-part range offset.
     *
     * @return offset in mm
     * @throws IOException on bus error
     */
    public synchronized double offset() throws IOException {
        int raw = read16(REG_PART_TO_PART_OFFSET) & 0x1FFF;
        if ((raw & 0x1000) != 0) raw -= 0x2000;
        return raw * 0.25;
    }

    /**
     * Set the per-SPAD crosstalk compensation rate (volatile).
     *
     * @param rateMcps 0 disables; otherwise 0 &lt; rate &lt; 0.128 MCPS
     * @throws IllegalArgumentException if out of range
     * @throws IOException              on bus error
     */
    public synchronized void setCrosstalkCompensation(double rateMcps) throws IOException {
        if (rateMcps < 0 || rateMcps >= 0.128) {
            throw new IllegalArgumentException("crosstalk rate must be 0 (off) or below 0.128 MCPS");
        }
        write16(REG_XTALK_X_GRADIENT, 0);
        write16(REG_XTALK_Y_GRADIENT, 0);
        write16(REG_XTALK_PLANE_OFFSET, Math.min((int) (rateMcps * 512000 + 0.5), 0xFFFF));
    }

    /**
     * Read the per-SPAD crosstalk compensation rate.
     *
     * @return rate in MCPS
     * @throws IOException on bus error
     */
    public synchronized double crosstalkCompensation() throws IOException {
        return read16(REG_XTALK_PLANE_OFFSET) / 512000.0;
    }

    /** Range 50 timed-mode samples into {@code out}; always stops ranging. */
    private byte[][] collect() throws IOException {
        byte[][] out = new byte[CALIBRATION_SAMPLES][];
        write8(REG_INTERRUPT_CLEAR, 0x01);
        write8(REG_MODE_START, 0x40);
        try {
            for (int i = 0; i < CALIBRATION_SAMPLES; i++) {
                waitUntil(this::isDataReady, "data ready");
                readResult();
                out[i] = result.clone();
            }
        } finally {
            write8(REG_MODE_START, 0x00);
        }
        return out;
    }

    private static int word(byte[] r, int i) {
        return ((r[i] & 0xFF) << 8) | (r[i + 1] & 0xFF);
    }

    /**
     * Measure and apply the range offset against a target at a known distance (ULD
     * CalibrateOffset; ST recommends 88&nbsp;% white at 140&nbsp;mm). Ranges 50 times with the offset
     * zeroed; must not be called while ranging. Store the result and re-apply it with
     * {@link #setOffset(double)} after each power-up.
     *
     * @param targetMm true target distance in mm
     * @return applied offset in mm (target − mean measured distance)
     * @throws IllegalArgumentException if the resulting offset is out of range
     * @throws IOException              on bus error or a result timeout
     */
    public synchronized double calibrateOffset(int targetMm) throws IOException {
        write16(REG_PART_TO_PART_OFFSET, 0);
        write16(REG_MM_INNER_OFFSET, 0);
        write16(REG_MM_OUTER_OFFSET, 0);
        long sum = 0;
        for (byte[] r : collect()) sum += word(r, 13);
        double offset = targetMm - (double) sum / CALIBRATION_SAMPLES;
        setOffset(offset);
        return offset;
    }

    /**
     * Measure and apply crosstalk compensation for a cover glass (ULD CalibrateXtalk; ST uses a
     * 17&nbsp;% grey target where the sensor starts to under-range). Ranges 50 times with
     * compensation off; must not be called while ranging. Store the result and re-apply it with
     * {@link #setCrosstalkCompensation(double)} after each power-up.
     *
     * @param targetMm true target distance in mm (&gt; 0)
     * @return applied per-SPAD crosstalk rate in MCPS (0–0.127)
     * @throws IllegalArgumentException if targetMm is not positive
     * @throws IOException              on bus error or a result timeout
     */
    public synchronized double calibrateCrosstalk(int targetMm) throws IOException {
        if (targetMm <= 0) throw new IllegalArgumentException("target distance must be positive");
        write16(REG_XTALK_PLANE_OFFSET, 0);
        double distance = 0, signal = 0, spads = 0;
        for (byte[] r : collect()) {
            distance += word(r, 13);
            signal += word(r, 15) / 128.0;
            spads += word(r, 3) / 256.0;
        }
        double n = CALIBRATION_SAMPLES;
        double meanDistance = distance / n, meanSignal = signal / n, meanSpads = spads / n;
        double rate = meanSpads > 0 ? meanSignal * (1 - meanDistance / targetMm) / meanSpads : 0;
        rate = Math.min(Math.max(rate, 0), 0.127);
        setCrosstalkCompensation(rate);
        return rate;
    }

    /**
     * Run the temperature update (ULD StartTemperatureUpdate). Call in software standby (not while
     * ranging), after the temperature changes by more than about 8&nbsp;°C.
     *
     * @throws IOException on bus error or if the update ranging times out
     */
    public synchronized void recalibrate() throws IOException {
        write8(REG_VHV_CONFIG_LOOP_BOUND, 0x81);
        write8(REG_VHV_INIT, 0x92);
        write8(REG_MODE_START, 0x40);
        try {
            waitUntil(this::isDataReady, "temperature update");
        } finally {
            write8(REG_INTERRUPT_CLEAR, 0x01);
            write8(REG_MODE_START, 0x00);
            write8(REG_VHV_CONFIG_LOOP_BOUND, 0x09);
            write8(REG_VHV_INIT, 0x00);
        }
    }

    /**
     * Change the chip's I²C address (volatile). The chip answers on the new address immediately;
     * this driver instance becomes unusable — construct a new connection at the new address and a
     * new driver.
     *
     * @param address new 7-bit address, 0x08–0x77
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAddress(int address) throws IOException {
        setAddressReg(REG_I2C_SLAVE_DEVICE_ADDRESS, address);
    }

    /**
     * Set the distance thresholds used by the threshold interrupt sources.
     *
     * @param lowMm  low threshold in mm
     * @param highMm high threshold in mm, lowMm ≤ highMm ≤ 65535
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setInterruptThresholds(int lowMm, int highMm) throws IOException {
        if (lowMm < 0 || highMm < lowMm || highMm > 65535) {
            throw new IllegalArgumentException("thresholds must satisfy 0 <= low <= high <= 65535 mm");
        }
        write16(REG_THRESH_HIGH, highMm);
        write16(REG_THRESH_LOW, lowMm);
    }

    /**
     * Read the distance thresholds.
     *
     * @return {@code {lowMm, highMm}}
     * @throws IOException on bus error
     */
    public synchronized int[] interruptThresholds() throws IOException {
        return new int[]{read16(REG_THRESH_LOW), read16(REG_THRESH_HIGH)};
    }

    /**
     * Read {@code IDENTIFICATION__MODEL_ID}.
     *
     * @return 0xEA
     * @throws IOException on bus error
     */
    public synchronized int modelId() throws IOException {
        return read8(REG_MODEL_ID);
    }

    /**
     * Read {@code IDENTIFICATION__MODULE_TYPE}.
     *
     * @return 0xCC
     * @throws IOException on bus error
     */
    public synchronized int moduleType() throws IOException {
        return read8(REG_MODULE_TYPE);
    }

    /**
     * Read {@code IDENTIFICATION__REVISION_ID} (mask revision).
     *
     * @return 0x10
     * @throws IOException on bus error
     */
    public synchronized int revisionId() throws IOException {
        return read8(REG_REVISION_ID);
    }

    /**
     * Select the GPIO1 interrupt source (replaces the active one). With a threshold source active,
     * {@link #dataReady()}/{@link #readContinuous()} only see a pending result when the threshold
     * condition is met.
     *
     * @param source one of the {@code SOURCE_*} constants (1–5)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if source is not 1–5
     */
    public synchronized void enableInterrupt(int source) throws IOException {
        int config = switch (source) {
            case SOURCE_LEVEL_LOW -> 0x00;
            case SOURCE_LEVEL_HIGH -> 0x01;
            case SOURCE_OUT_OF_WINDOW -> 0x02;
            case SOURCE_NEW_SAMPLE_READY -> 0x20;
            case SOURCE_IN_WINDOW -> 0x03;
            default -> throw new IllegalArgumentException("source must be one of the SOURCE_* constants");
        };
        write8(REG_INTERRUPT_CONFIG_GPIO, config);
    }

    /**
     * Revert to {@code SOURCE_NEW_SAMPLE_READY} if {@code source} is the active threshold source.
     * The chip has no disabled state, so disabling {@code SOURCE_NEW_SAMPLE_READY} is a no-op; use
     * {@link #offInterrupt()} to stop callbacks.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    public synchronized void disableInterrupt(int source) throws IOException {
        if (source != SOURCE_NEW_SAMPLE_READY && activeSource() == source) write8(REG_INTERRUPT_CONFIG_GPIO, 0x20);
    }

    /**
     * Read and clear a pending interrupt.
     *
     * @return the active {@code SOURCE_*} value if GPIO1 is asserted, else 0
     * @throws IOException on bus error
     */
    public synchronized int pollInterrupt() throws IOException {
        return pollInterruptStatus();
    }

    /**
     * Subscribe to GPIO1 events using {@code connection.intPin()} (or a 5&nbsp;ms polling thread if
     * none is wired).
     *
     * @param callback called with the active {@code SOURCE_*} value
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to GPIO1 events, overriding which {@link InputPin} delivers edges (falling edge,
     * GPIO1 is active low). The driver clears the interrupt before invoking the callback; the
     * polling fallback consumes results, so don't mix it with {@link #readContinuous()}.
     *
     * @param callback called with the active {@code SOURCE_*} value
     * @param intPin   GPIO1 pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        subscribe(callback, intPin);
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
        unsubscribe();
    }
}
