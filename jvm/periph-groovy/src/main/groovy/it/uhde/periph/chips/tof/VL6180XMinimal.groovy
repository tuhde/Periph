package it.uhde.periph.chips.tof

import it.uhde.periph.connection.RegisterConnection

import groovy.transform.CompileStatic
import java.io.IOException

/**
 * VL6180X proximity and ambient-light sensor (STMicroelectronics) — minimal interface.
 *
 * <p>VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS) behind one
 * aperture. Measures absolute distance 0–100&nbsp;mm (8-bit result in mm) and ambient light
 * (16-bit count, converted to lux). Registers use a 16-bit big-endian index; 16-bit results and
 * thresholds are MSB first.
 *
 * <p>The connection must be created with a register width of 2 and point at address
 * {@code 0x29}. The constructor runs the initialization sequence: 1&nbsp;ms boot wait, then —
 * only while {@code SYSTEM__FRESH_OUT_OF_RESET} is 1 — the mandatory private-register settings
 * (ST AN4545, not documented in the datasheet), then the recommended settings (averaging period
 * 48, max convergence 49&nbsp;ms, ALS gain 1, 100&nbsp;ms ALS integration, new-sample interrupt
 * configuration). {@link #readRange()} then takes one single-shot measurement per call. Methods
 * are {@code synchronized}, serializing multi-register sequences against the Full class's
 * interrupt polling thread.
 */
@CompileStatic
class VL6180XMinimal {

    /** Power-on 7-bit I²C address. */
    public static final int DEFAULT_ADDRESS = 0x29

    /** Expected {@code IDENTIFICATION__MODEL_ID}. */
    public static final int MODEL_ID = 0xB4

    /** Interrupt source: range event. */
    public static final int SOURCE_RANGE = 0x01

    /** Interrupt source: ALS event. */
    public static final int SOURCE_ALS = 0x02

    /** Interrupt source: error event. */
    public static final int SOURCE_ERROR = 0x04

    /** Interrupt mode: value below the low threshold. */
    public static final int MODE_LEVEL_LOW = 1

    /** Interrupt mode: value above the high threshold. */
    public static final int MODE_LEVEL_HIGH = 2

    /** Interrupt mode: value outside the threshold window. */
    public static final int MODE_OUT_OF_WINDOW = 3

    /** Interrupt mode: new sample ready (default). */
    public static final int MODE_NEW_SAMPLE_READY = 4

    protected static final int REG_MODEL_ID              = 0x000
    protected static final int REG_MODEL_REV_MAJOR       = 0x001
    protected static final int REG_MODEL_REV_MINOR       = 0x002
    protected static final int REG_MODE_GPIO1            = 0x011
    protected static final int REG_INTERRUPT_CONFIG      = 0x014
    protected static final int REG_INTERRUPT_CLEAR       = 0x015
    protected static final int REG_FRESH_OUT_OF_RESET    = 0x016
    protected static final int REG_GROUPED_PARAM_HOLD    = 0x017
    protected static final int REG_RANGE_START           = 0x018
    protected static final int REG_RANGE_THRESH_HIGH     = 0x019
    protected static final int REG_RANGE_THRESH_LOW      = 0x01A
    protected static final int REG_RANGE_INTERMEASUREMENT = 0x01B
    protected static final int REG_RANGE_MAX_CONVERGENCE = 0x01C
    protected static final int REG_RANGE_CROSSTALK_RATE  = 0x01E
    protected static final int REG_RANGE_OFFSET          = 0x024
    protected static final int REG_VHV_RECALIBRATE       = 0x02E
    protected static final int REG_VHV_REPEAT_RATE       = 0x031
    protected static final int REG_ALS_START             = 0x038
    protected static final int REG_ALS_THRESH_HIGH       = 0x03A
    protected static final int REG_ALS_THRESH_LOW        = 0x03C
    protected static final int REG_ALS_INTERMEASUREMENT  = 0x03E
    protected static final int REG_ALS_GAIN              = 0x03F
    protected static final int REG_ALS_INTEGRATION       = 0x040
    protected static final int REG_RANGE_STATUS          = 0x04D
    protected static final int REG_INTERRUPT_STATUS      = 0x04F
    protected static final int REG_ALS_VAL               = 0x050
    protected static final int REG_HISTORY_BUFFER        = 0x052
    protected static final int REG_RANGE_VAL             = 0x062
    protected static final int REG_AVERAGING_PERIOD      = 0x10A
    protected static final int REG_RESULT_SCALER         = 0x120
    protected static final int REG_I2C_ADDRESS           = 0x212
    protected static final int REG_INTERLEAVED_ENABLE    = 0x2A3

    /** Mandatory private-register settings (ST AN4545), applied while fresh out of reset. */
    private static final int[][] PRIVATE_SETTINGS = [
        [0x0207, 0x01] as int[],
        [0x0208, 0x01] as int[],
        [0x0096, 0x00] as int[],
        [0x0097, 0xFD] as int[],
        [0x00E3, 0x01] as int[],
        [0x00E4, 0x03] as int[],
        [0x00E5, 0x02] as int[],
        [0x00E6, 0x01] as int[],
        [0x00E7, 0x03] as int[],
        [0x00F5, 0x02] as int[],
        [0x00D9, 0x05] as int[],
        [0x00DB, 0xCE] as int[],
        [0x00DC, 0x03] as int[],
        [0x00DD, 0xF8] as int[],
        [0x009F, 0x00] as int[],
        [0x00A3, 0x3C] as int[],
        [0x00B7, 0x00] as int[],
        [0x00BB, 0x3C] as int[],
        [0x00B2, 0x09] as int[],
        [0x00CA, 0x09] as int[],
        [0x0198, 0x01] as int[],
        [0x01B0, 0x17] as int[],
        [0x01AD, 0x00] as int[],
        [0x00FF, 0x05] as int[],
        [0x0100, 0x05] as int[],
        [0x0199, 0x05] as int[],
        [0x01A6, 0x1B] as int[],
        [0x01AC, 0x3E] as int[],
        [0x01A7, 0x1F] as int[],
        [0x0030, 0x00] as int[]
    ] as int[][]

    /** Recommended public settings (the 0x040 integration period is written as 16 bit separately). */
    private static final int[][] RECOMMENDED_SETTINGS = [
        [REG_MODE_GPIO1, 0x10] as int[],
        [REG_AVERAGING_PERIOD, 0x30] as int[],
        [REG_ALS_GAIN, 0x46] as int[],
        [REG_VHV_REPEAT_RATE, 0xFF] as int[],
        [REG_VHV_RECALIBRATE, 0x01] as int[],
        [REG_RANGE_INTERMEASUREMENT, 0x09] as int[],
        [REG_ALS_INTERMEASUREMENT, 0x31] as int[],
        [REG_INTERRUPT_CONFIG, 0x24] as int[]
    ] as int[][]

    /** Actual ALS gain per gain code 0–7. */
    protected static final double[] GAIN = [20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0] as double[]

    protected static final int RANGE_TIMEOUT_MS = 100
    protected static final int VHV_TIMEOUT_MS = 100

    /** Configured I²C connection (register width 2) pointing at the device. */
    protected final RegisterConnection connection
    protected int gainCode = 6
    protected int integrationMs = 100
    private int lastError = 0

    /**
     * Create the driver and run the initialization sequence.
     *
     * @param connection I²C connection with a register width of 2, bound to 0x29
     * @throws IOException on bus error
     */
    public VL6180XMinimal(RegisterConnection connection) throws IOException {
        this.connection = connection
        init()
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    protected int rd8(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected void wr8(int reg, int value) throws IOException {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected int rd16(int reg) throws IOException {
        byte[] b = connection.read(reg, 2)
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    protected void wr16(int reg, int value) throws IOException {
        connection.write(reg, [(byte) (value >> 8), (byte) value] as byte[])
    }

    private synchronized void init() throws IOException {
        sleepMs(1)
        if (rd8(REG_FRESH_OUT_OF_RESET) == 1) {
            for (int[] s : PRIVATE_SETTINGS) wr8(s[0], s[1])
            wr8(REG_FRESH_OUT_OF_RESET, 0)
        }
        for (int[] s : RECOMMENDED_SETTINGS) wr8(s[0], s[1])
        wr16(REG_ALS_INTEGRATION, 0x0063)
        gainCode = 6
        integrationMs = 100
    }

    /** Poll the interrupt status until {@code (status >> shift) & 7 == 4}, at most {@code timeoutMs}. */
    protected void waitSample(int shift, int timeoutMs) throws IOException {
        for (int i = 0; i <= timeoutMs; i++) {
            if (((rd8(REG_INTERRUPT_STATUS) >> shift) & 0x07) == 4) return
            sleepMs(1)
        }
        throw new IOException("VL6180X timeout waiting for a result")
    }

    /** Read the range result, clear the interrupts; returns {mm, errorCode}. */
    protected int[] readRangeResult() throws IOException {
        int error = rd8(REG_RANGE_STATUS) >> 4
        int mm = rd8(REG_RANGE_VAL)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        lastError = error
        return [mm, error] as int[]
    }

    /** Start a single-shot range measurement and read it; returns {mm, errorCode}. */
    protected int[] measureRange() throws IOException {
        wr8(REG_RANGE_START, 0x01)
        try {
            waitSample(0, RANGE_TIMEOUT_MS)
        } catch (IOException e) {
            wr8(REG_INTERRUPT_CLEAR, 0x07)
            throw e
        }
        return readRangeResult()
    }

    /**
     * Take one single-shot range measurement.
     *
     * @return the distance in mm (0–255; reliable up to about 100&nbsp;mm)
     * @throws IOException on bus error, a non-zero range error code ({@link #lastError()}) or if
     *                     no result arrives within 100&nbsp;ms
     */
    public synchronized int readRange() throws IOException {
        int[] r = measureRange()
        if (r[1] != 0) throw new IOException("VL6180X range error " + r[1])
        return r[0]
    }

    /**
     * Range error code of the last measurement.
     *
     * @return 0 = valid, 1–15 are the datasheet range error codes
     */
    public synchronized int lastError() {
        return lastError
    }

    /**
     * Read the interrupt status and clear all pending sources.
     *
     * @return bit mask of {@link #SOURCE_RANGE}, {@link #SOURCE_ALS}, {@link #SOURCE_ERROR} (0 if none)
     * @throws IOException on bus error
     */
    protected synchronized int pollInterruptStatus() throws IOException {
        int status = rd8(REG_INTERRUPT_STATUS)
        int sources = 0
        if ((status & 0x07) != 0) sources |= SOURCE_RANGE
        if ((status & 0x38) != 0) sources |= SOURCE_ALS
        if ((status & 0xC0) != 0) sources |= SOURCE_ERROR
        if (sources != 0) wr8(REG_INTERRUPT_CLEAR, 0x07)
        return sources
    }
}
