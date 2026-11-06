package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * MMA8451Q full interface — extends {@link MMA8451QMinimal} with the complete
 * chip functionality.
 *
 * <p>Adds:
 * <ul>
 *   <li>Full-scale range (±2/±4/±8 <i>g</i>), output data rate
 *       (1.56 Hz – 800 Hz), oversampling mode, low-noise mode and 14-/8-bit
 *       resolution selection</li>
 *   <li>STANDBY / ACTIVE control, software reset and electrostatic self-test</li>
 *   <li>Raw count reading, status and system-mode readback</li>
 *   <li>High-pass-filtered output and per-axis offset correction</li>
 *   <li>32-sample FIFO with watermark, trigger sources and gate</li>
 *   <li>Freefall / motion, transient, tap and orientation detectors</li>
 *   <li>Auto-SLEEP / Auto-WAKE with selectable sleep rate and wake sources</li>
 *   <li>Interrupt enable / disable, routing, polarity and drive control, plus
 *       edge subscription</li>
 * </ul>
 *
 * <p>Methods that write a STANDBY-only register take care of the mode switch
 * themselves: they clear {@code ACTIVE}, write the register, restore
 * {@code ACTIVE} and wait for the turn-on time.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired
 * (falling edge — the INT pins are active-low by default), otherwise a 5&nbsp;ms
 * polling thread delivers the events.
 */
public class MMA8451QFull extends MMA8451QMinimal {

    /** Interrupt source constants — the INT_SOURCE / CTRL_REG4 / CTRL_REG5 bit positions. */
    public static final int SOURCE_DATA_READY  = 0x01;
    public static final int SOURCE_FF_MT       = 0x04;
    public static final int SOURCE_PULSE       = 0x08;
    public static final int SOURCE_ORIENTATION = 0x10;
    public static final int SOURCE_TRANSIENT   = 0x20;
    public static final int SOURCE_FIFO        = 0x40;
    public static final int SOURCE_AUTO_SLEEP  = 0x80;

    /** All valid interrupt sources combined. */
    protected static final int SOURCE_ALL = SOURCE_DATA_READY | SOURCE_FF_MT
            | SOURCE_PULSE | SOURCE_ORIENTATION | SOURCE_TRANSIENT
            | SOURCE_FIFO | SOURCE_AUTO_SLEEP;

    /** Full-scale range codes in XYZ_DATA_CFG (0x0E) bits 1:0 — indexed by range/2 - 1. */
    protected static final int[] FS_CODES = {0x00, 0x01, 0x02};

    /** Output data rate codes in CTRL_REG1 (0x2A) bits 5:3. */
    protected static final double[] ODR_ORDER = {800, 400, 200, 100, 50, 12.5, 6.25, 1.56};
    protected static final int[] DR_CODES = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};

    /** Auto-SLEEP output rate codes in CTRL_REG1 (0x2A) bits 7:6. */
    protected static final double[] ASLP_RATE_ORDER = {50, 12.5, 6.25, 1.56};
    protected static final int[] ASLP_RATE_CODES = {0x00, 0x01, 0x02, 0x03};

    /** Oversampling mode codes in CTRL_REG2 (0x2B) bits 1:0 and 4:3. */
    protected static final int[] MODS_CODES = {0x00, 0x01, 0x02, 0x03};

    /**
     * Debounce step in ms, indexed by [ODR index][oversampling mode].
     * Rows: 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz.
     * Columns: normal, low-noise low-power, high resolution, low power.
     */
    protected static final double[][] DEBOUNCE_STEPS = {
            {1.25, 1.25, 1.25, 1.25},
            {2.5, 2.5, 2.5, 2.5},
            {5.0, 5.0, 2.5, 5.0},
            {10.0, 10.0, 2.5, 10.0},
            {20.0, 20.0, 2.5, 20.0},
            {20.0, 80.0, 2.5, 80.0},
            {20.0, 80.0, 2.5, 160.0},
            {20.0, 80.0, 2.5, 160.0},
    };

    /** Pulse time-limit step in ms, indexed by [ODR index][oversampling mode], LPF enabled. */
    protected static final double[][] PULSE_TMLT_LPF = {
            {1.25, 1.25, 1.25, 1.25},
            {2.5, 2.5, 2.5, 2.5},
            {5.0, 5.0, 2.5, 5.0},
            {10.0, 10.0, 2.5, 10.0},
            {20.0, 20.0, 2.5, 20.0},
            {20.0, 80.0, 2.5, 80.0},
            {20.0, 80.0, 2.5, 160.0},
            {20.0, 80.0, 2.5, 160.0},
    };

    /** Pulse latency / window step in ms, indexed by [ODR index][oversampling mode], LPF enabled. */
    protected static final double[][] PULSE_LTCY_LPF = {
            {2.5, 2.5, 2.5, 2.5},
            {5.0, 5.0, 5.0, 5.0},
            {10.0, 10.0, 5.0, 10.0},
            {20.0, 20.0, 5.0, 20.0},
            {40.0, 40.0, 5.0, 40.0},
            {40.0, 160.0, 5.0, 160.0},
            {40.0, 160.0, 5.0, 320.0},
            {40.0, 160.0, 5.0, 320.0},
    };

    /** Pulse time-limit step in ms, indexed by [ODR index][oversampling mode], LPF disabled. */
    protected static final double[][] PULSE_TMLT_NO_LPF = {
            {0.625, 0.625, 0.625, 0.625},
            {0.625, 0.625, 0.625, 1.25},
            {1.25, 1.25, 0.625, 2.5},
            {2.5, 2.5, 0.625, 5.0},
            {5.0, 5.0, 0.625, 10.0},
            {5.0, 20.0, 0.625, 40.0},
            {5.0, 20.0, 0.625, 40.0},
            {5.0, 20.0, 0.625, 40.0},
    };

    /** Pulse latency / window step in ms, indexed by [ODR index][oversampling mode], LPF disabled. */
    protected static final double[][] PULSE_LTCY_NO_LPF = {
            {1.25, 1.25, 1.25, 1.25},
            {1.25, 1.25, 1.25, 2.5},
            {2.5, 2.5, 1.25, 5.0},
            {5.0, 5.0, 1.25, 10.0},
            {10.0, 10.0, 1.25, 20.0},
            {10.0, 40.0, 1.25, 80.0},
            {10.0, 40.0, 1.25, 80.0},
            {10.0, 40.0, 1.25, 80.0},
    };

    /**
     * Portrait / landscape trip angles and their {@code P_L_THS<4:0>} codes
     * (datasheet Table 32) — indexed by the trip angle in degrees.
     */
    protected static final int[] ORIENTATION_ANGLES = {15, 20, 30, 35, 40, 45, 55, 60, 70, 75};
    protected static final int[] ORIENTATION_THRESHOLDS = {0x07, 0x09, 0x0C, 0x0D, 0x0F,
            0x10, 0x13, 0x14, 0x17, 0x19};

    /** Detector threshold LSB size in <i>g</i> (7-bit thresholds, 0.063 <i>g</i>/LSB). */
    protected static final double THRESHOLD_LSB_G = 0.063;

    /** Offset register LSB size in <i>g</i> (2 mg/LSB). */
    protected static final double OFFSET_LSB_G = 0.002;

    /** Self-test typical count deltas per axis (datasheet Table 2). */
    protected static final int[] SELF_TEST_TYPICAL = {181, 255, 1680};

    /** Callback invoked with the interrupt status byte. */
    private volatile IntConsumer callback;

    /** INT-line pin currently armed, or {@code null} if polling. */
    private InputPin subscribedPin;

    /** {@code true} while the polling-fallback thread is running. */
    private volatile boolean polling = false;

    /** Background daemon thread used only when no {@link InputPin} is available. */
    private Thread pollThread;

    /**
     * Fixed edge-handler instance, reused across onEdge()/offEdge() calls.
     *
     * <p>A method reference is not guaranteed by the JLS to evaluate to the
     * same object each time, so {@code offEdge()} could silently fail to remove
     * the handler {@code onEdge()} registered.
     */
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Decoded FIFO status, as returned by {@link #readFifoStatus()}.
     *
     * @param overflow  {@code true} when the FIFO overflowed
     * @param watermark {@code true} when the FIFO count reached the watermark
     * @param count     number of samples in the FIFO (0–32)
     */
    public record FifoStatus(boolean overflow, boolean watermark, int count) {}

    /**
     * Decoded orientation status, as returned by {@link #readOrientation()}.
     *
     * @param changed           {@code true} when the orientation changed
     * @param lockout           {@code true} while the orientation is frozen
     *                          (Z-tilt lockout)
     * @param portraitLandscape 0 portrait up, 1 portrait down, 2 landscape right,
     *                          3 landscape left
     * @param back              {@code true} when the device is face down
     */
    public record Orientation(boolean changed, boolean lockout, int portraitLandscape, boolean back) {}

    /**
     * Result of the electrostatic self-test, as returned by {@link #selfTest()}.
     *
     * @param ok  {@code true} when every axis moved by more than half its
     *            typical self-test delta
     * @param dx  measured count delta on the X axis
     * @param dy  measured count delta on the Y axis
     * @param dz  measured count delta on the Z axis
     */
    public record SelfTestResult(boolean ok, int dx, int dy, int dz) {}

    /**
     * Construct the full driver and run the chip's initialization sequence.
     *
     * @param connection configured I²C, SMBus, or SPI register connection
     * @throws IOException on bus error, a wrong {@code WHO_AM_I} value, or an
     *         interrupted wait
     */
    public MMA8451QFull(RegisterConnection connection) throws IOException {
        super(connection);
    }

    // =========================================================================
    // Configuration
    // =========================================================================

    /**
     * Set the full-scale measurement range.
     *
     * @param rangeG one of 2, 4 or 8 (<i>g</i>)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the range is not 2, 4 or 8 <i>g</i>, or
     *         if ±8 <i>g</i> is requested while low-noise mode is enabled
     */
    public void setRange(int rangeG) throws IOException {
        if (rangeG != 2 && rangeG != 4 && rangeG != 8) {
            throw new IllegalArgumentException("range must be one of 2, 4, 8 g");
        }
        if (rangeG == 8 && lowNoise) {
            throw new IllegalArgumentException("+/-8 g is not available in low-noise mode");
        }
        int xyzCfg = readReg(REG_XYZ_DATA_CFG);
        int fsCode = (rangeG == 4) ? FS_CODES[1] : (rangeG == 8 ? FS_CODES[2] : FS_CODES[0]);
        xyzCfg = (xyzCfg & 0xFC) | fsCode;
        standbyWrite(REG_XYZ_DATA_CFG, xyzCfg);
        this.rangeG = rangeG;
    }

    /**
     * Set the output data rate.
     *
     * @param rateHz one of 800, 400, 200, 100, 50, 12.5, 6.25 or 1.56 (Hz)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the rate is not supported
     */
    public void setDataRate(double rateHz) throws IOException {
        int idx = odrIndex(rateHz);
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "rate must be one of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz");
        }
        int ctrl = readReg(REG_CTRL_REG1);
        ctrl = (ctrl & 0xC7) | (DR_CODES[idx] << 3);
        standbyWrite(REG_CTRL_REG1, ctrl);
        this.odrHz = ODR_ORDER[idx];
    }

    /**
     * Set the oversampling mode.
     *
     * @param mode      0 normal, 1 low-noise low-power, 2 high resolution,
     *                  3 low power — applies in WAKE / ACTIVE mode
     * @param sleepMode oversampling mode in SLEEP; defaults to {@code mode} when
     *                  negative
     * @throws IOException on bus error
     * @throws IllegalArgumentException if a mode is out of range
     */
    public void setOversampling(int mode, int sleepMode) throws IOException {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("mode must be 0, 1, 2 or 3");
        }
        int smods = sleepMode < 0 ? mode : sleepMode;
        if (smods < 0 || smods > 3) {
            throw new IllegalArgumentException("sleepMode must be 0, 1, 2 or 3");
        }
        int ctrl2 = readReg(REG_CTRL_REG2);
        ctrl2 = (ctrl2 & 0xE7) | (MODS_CODES[smods] << 3) | MODS_CODES[mode];
        standbyWrite(REG_CTRL_REG2, ctrl2);
        this.oversampling = mode;
        this.sleepOversampling = smods;
    }

    /**
     * Set the oversampling mode with the same mode in WAKE and SLEEP.
     *
     * @param mode 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the mode is out of range
     */
    public void setOversampling(int mode) throws IOException {
        setOversampling(mode, -1);
    }

    /**
     * Enable or disable the reduced-noise (low-noise) mode.
     *
     * <p>Low-noise mode limits the dynamic range to ±4 <i>g</i>.
     *
     * @param enabled {@code true} to enable low-noise mode
     * @throws IOException on bus error
     * @throws IllegalArgumentException if low-noise mode is requested while the
     *         range is ±8 <i>g</i>
     */
    public void setLowNoise(boolean enabled) throws IOException {
        if (enabled && rangeG == 8) {
            throw new IllegalArgumentException("low-noise mode limits the range to +/-4 g");
        }
        int ctrl = readReg(REG_CTRL_REG1);
        ctrl = enabled ? (ctrl | CTRL1_LNOISE) : (ctrl & ~CTRL1_LNOISE & 0xFF);
        standbyWrite(REG_CTRL_REG1, ctrl);
        this.lowNoise = enabled;
    }

    /**
     * Select the output resolution.
     *
     * @param bits 14 for the normal two-byte output, 8 for fast read (MSB
     *             register only)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the resolution is not 14 or 8 bits, or
     *         if the change is attempted while the FIFO is enabled
     */
    public void setResolution(int bits) throws IOException {
        if (bits != 8 && bits != 14) {
            throw new IllegalArgumentException("bits must be 8 or 14");
        }
        if (fifoMode != 0) {
            throw new IllegalArgumentException("resolution cannot change while the FIFO is enabled");
        }
        int ctrl = readReg(REG_CTRL_REG1);
        ctrl = bits == 8 ? (ctrl | CTRL1_F_READ) : (ctrl & ~CTRL1_F_READ & 0xFF);
        standbyWrite(REG_CTRL_REG1, ctrl);
        this.bits = bits;
    }

    /**
     * Switch the chip to STANDBY mode.
     *
     * @throws IOException on bus error
     */
    public void standby() throws IOException {
        int ctrl = readReg(REG_CTRL_REG1);
        if ((ctrl & CTRL1_ACTIVE) == 0) {
            return;
        }
        writeReg(REG_CTRL_REG1, ctrl & ~CTRL1_ACTIVE & 0xFF);
    }

    /**
     * Switch the chip to ACTIVE mode and wait for the turn-on time.
     *
     * @throws IOException on bus error
     */
    public void activate() throws IOException {
        int ctrl = readReg(REG_CTRL_REG1);
        if ((ctrl & CTRL1_ACTIVE) != 0) {
            return;
        }
        writeReg(REG_CTRL_REG1, ctrl | CTRL1_ACTIVE);
        waitTurnOn();
    }

    /**
     * Perform a software reset.
     *
     * <p>All registers return to their reset values and the chip ends up in
     * STANDBY. Re-run {@link MMA8451QMinimal#init()} (by constructing a new
     * driver) afterwards.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        writeReg(REG_CTRL_REG2, CTRL2_RST);
        delay(1);
        waitResetDone();
        this.rangeG = DEFAULT_RANGE_G;
        this.odrHz = DEFAULT_ODR_HZ;
        this.bits = DEFAULT_BITS;
        this.lowNoise = false;
        this.oversampling = 0;
        this.sleepOversampling = 0;
        this.fifoMode = 0;
    }

    /**
     * Enable or disable the high-pass-filtered output and select its cutoff.
     *
     * @param enabled {@code true} to route high-pass-filtered data to the
     *                output registers and the FIFO
     * @param cutoff  high-pass cutoff selector 0–3 (the actual frequency depends
     *                on the ODR and oversampling mode; datasheet Table 23)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the cutoff is out of range
     */
    public void setHighPassOutput(boolean enabled, int cutoff) throws IOException {
        if (cutoff < 0 || cutoff > 3) {
            throw new IllegalArgumentException("cutoff must be 0, 1, 2 or 3");
        }
        int xyzCfg = readReg(REG_XYZ_DATA_CFG);
        xyzCfg = enabled ? (xyzCfg | 0x10) : (xyzCfg & ~0x10 & 0xFF);
        int hpFilter = readReg(REG_HP_FILTER);
        hpFilter = (hpFilter & 0xFC) | cutoff;
        standbyWriteMulti(REG_XYZ_DATA_CFG, xyzCfg, new int[][]{{REG_HP_FILTER, hpFilter}});
    }

    /**
     * Enable or disable the high-pass-filtered output using the default cutoff.
     *
     * @param enabled {@code true} to route high-pass-filtered data to the
     *                output registers and the FIFO
     * @throws IOException on bus error
     */
    public void setHighPassOutput(boolean enabled) throws IOException {
        setHighPassOutput(enabled, 0);
    }

    /**
     * Set the per-axis zero-<i>g</i> offset correction.
     *
     * @param xG X offset in <i>g</i>
     * @param yG Y offset in <i>g</i>
     * @param zG Z offset in <i>g</i>
     * @throws IOException on bus error
     */
    public void setOffset(double xG, double yG, double zG) throws IOException {
        int ox = offsetCode(xG);
        int oy = offsetCode(yG);
        int oz = offsetCode(zG);
        standbyWriteMulti(REG_OFF_X, ox, new int[][]{
                {REG_OFF_Y, oy}, {REG_OFF_Z, oz}});
    }

    /**
     * Read the per-axis offset correction.
     *
     * @return array of three doubles — X, Y, Z offsets in <i>g</i>
     * @throws IOException on bus error
     */
    public double[] getOffset() throws IOException {
        int ox = Register.toSigned(readReg(REG_OFF_X), 8);
        int oy = Register.toSigned(readReg(REG_OFF_Y), 8);
        int oz = Register.toSigned(readReg(REG_OFF_Z), 8);
        return new double[]{ox * OFFSET_LSB_G, oy * OFFSET_LSB_G, oz * OFFSET_LSB_G};
    }

    // =========================================================================
    // Status
    // =========================================================================

    /**
     * Read raw signed acceleration counts.
     *
     * @return array of three ints — X, Y, Z signed counts (14-bit
     *         right-aligned, or 8-bit in fast-read mode)
     * @throws IOException on bus error
     */
    public int[] readRaw() throws IOException {
        byte[] raw = readBurst(REG_OUT_X_MSB, 6);
        if (bits == 8) {
            return new int[]{
                    Register.toSigned(raw[0] & 0xFF, 8),
                    Register.toSigned(raw[2] & 0xFF, 8),
                    Register.toSigned(raw[4] & 0xFF, 8)};
        }
        int rx = (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) >> 2;
        int ry = (((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF)) >> 2;
        int rz = (((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF)) >> 2;
        return new int[]{
                Register.toSigned(rx, 14),
                Register.toSigned(ry, 14),
                Register.toSigned(rz, 14)};
    }

    /**
     * Check whether a new sample set is ready.
     *
     * @return {@code true} when {@code STATUS.ZYXDR} is set — not valid while
     *         the FIFO is enabled, use {@link #fifoLevel()} instead
     * @throws IOException on bus error
     */
    public boolean newDataAvailable() throws IOException {
        int status = readReg(REG_STATUS);
        return (status & STATUS_ZYXDR) != 0;
    }

    /**
     * Read the STATUS register.
     *
     * @return the STATUS byte ({@code ZYXOW} bit 7 … {@code XDR} bit 0)
     * @throws IOException on bus error
     */
    public int readStatus() throws IOException {
        return readReg(REG_STATUS);
    }

    /**
     * Read the current power mode.
     *
     * @return {@code SYSMOD} bits 1:0 — 0 standby, 1 wake, 2 sleep
     * @throws IOException on bus error
     */
    public int readSystemMode() throws IOException {
        return readReg(REG_SYSMOD) & 0x03;
    }

    // =========================================================================
    // FIFO
    // =========================================================================

    /**
     * Configure the FIFO.
     *
     * @param mode      0 disabled, 1 circular, 2 fill, 3 trigger
     * @param watermark FIFO watermark 0–32 (0 disables the watermark flag); in
     *                  trigger mode this is the number of pre-trigger samples
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the mode is out of range or the
     *         watermark is outside 0–32
     */
    public void setFifo(int mode, int watermark) throws IOException {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("mode must be 0, 1, 2 or 3");
        }
        if (watermark < 0 || watermark > 32) {
            throw new IllegalArgumentException("watermark must be 0..32");
        }
        if (mode != 0 && fifoMode != 0 && fifoMode != mode) {
            // The mode must go through 00 to switch between enabled modes.
            writeFifoSetup(0, 0);
        }
        writeFifoSetup(mode, watermark);
        this.fifoMode = mode;
    }

    /**
     * Configure the FIFO without a watermark.
     *
     * @param mode 0 disabled, 1 circular, 2 fill, 3 trigger
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the mode is out of range
     */
    public void setFifo(int mode) throws IOException {
        setFifo(mode, 0);
    }

    /**
     * Select the FIFO trigger sources.
     *
     * @param transientEvent enable the transient detector as a trigger source
     * @param orientation   enable the orientation detector as a trigger source
     * @param pulse         enable the pulse (tap) detector as a trigger source
     * @param motion        enable the freefall / motion detector as a trigger source
     * @throws IOException on bus error
     */
    public void setFifoTrigger(boolean transientEvent, boolean orientation, boolean pulse,
                               boolean motion) throws IOException {
        int trig = 0;
        if (transientEvent) trig |= 0x20;
        if (orientation)    trig |= 0x10;
        if (pulse)          trig |= 0x08;
        if (motion)         trig |= 0x04;
        standbyWrite(REG_TRIG_CFG, trig);
    }

    /**
     * Clear all FIFO trigger sources.
     *
     * @throws IOException on bus error
     */
    public void setFifoTrigger() throws IOException {
        setFifoTrigger(false, false, false, false);
    }

    /**
     * Read the number of samples currently in the FIFO.
     *
     * @return the sample count (0–32)
     * @throws IOException on bus error
     */
    public int fifoLevel() throws IOException {
        return readReg(REG_STATUS) & 0x3F;
    }

    /**
     * Read and decode the FIFO status.
     *
     * <p>Reading F_STATUS clears the FIFO interrupt source.
     *
     * @return the decoded {@link FifoStatus}
     * @throws IOException on bus error
     */
    public FifoStatus readFifoStatus() throws IOException {
        int status = readReg(REG_STATUS);
        return new FifoStatus((status & 0x80) != 0, (status & 0x40) != 0, status & 0x3F);
    }

    /**
     * Drain samples from the FIFO.
     *
     * <p>One sample is read per transaction starting at 0x01 (6 bytes, or 3 in
     * 8-bit mode).
     *
     * @param count maximum number of samples to read; a negative value reads
     *              all samples currently in the FIFO
     * @return array of {@code {x, y, z}} acceleration triples in <i>g</i>
     * @throws IOException on bus error
     */
    public double[][] readFifo(int count) throws IOException {
        int n = count < 0 ? fifoLevel() : count;
        double[][] samples = new double[n][];
        for (int i = 0; i < n; i++) {
            samples[i] = decode(readBurst(REG_OUT_X_MSB, 6));
        }
        return samples;
    }

    /**
     * Drain all samples currently in the FIFO.
     *
     * @return array of {@code {x, y, z}} acceleration triples in <i>g</i>
     * @throws IOException on bus error
     */
    public double[][] readFifo() throws IOException {
        return readFifo(-1);
    }

    // =========================================================================
    // Detectors
    // =========================================================================

    /**
     * Configure the freefall / motion detector.
     *
     * @param motion        {@code true} for motion detection (any enabled axis
     *                      above the threshold), {@code false} for freefall
     *                      (all enabled axes below the threshold)
     * @param axes          axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG    threshold in <i>g</i> (0.063 <i>g</i>/LSB, valid to 8 <i>g</i>)
     * @param debounceMs    debounce time in ms
     * @param latch         {@code true} to latch the event flags until they are read
     * @param debounceClear {@code true} to clear the debounce counter when the
     *                      condition is lost ({@code DBCNTM})
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the axis mask is out of range
     */
    public void setFreefallMotion(boolean motion, int axes, double thresholdG, double debounceMs,
                                  boolean latch, boolean debounceClear) throws IOException {
        if (axes < 0 || axes > 7) {
            throw new IllegalArgumentException("axes must be a 3-bit mask (0..7)");
        }
        int cfg = 0;
        if (latch)           cfg |= 0x80;
        if (motion)          cfg |= 0x40;
        if ((axes & 0x04) != 0) cfg |= 0x20;
        if ((axes & 0x02) != 0) cfg |= 0x10;
        if ((axes & 0x01) != 0) cfg |= 0x08;
        int ths = thresholdCode(thresholdG);
        if (debounceClear) {
            ths |= 0x80;
        }
        int count = debounceCode(debounceMs);
        standbyWriteMulti(REG_FF_MT_CFG, cfg, new int[][]{
                {REG_FF_MT_THS, ths}, {REG_FF_MT_COUNT, count}});
    }

    /**
     * Configure the freefall / motion detector with the default axis mask and
     * no debounce.
     *
     * @param motion     {@code true} for motion detection, {@code false} for
     *                   freefall
     * @param thresholdG threshold in <i>g</i> (0.063 <i>g</i>/LSB)
     * @throws IOException on bus error
     */
    public void setFreefallMotion(boolean motion, double thresholdG) throws IOException {
        setFreefallMotion(motion, 7, thresholdG, 0.0, false, false);
    }

    /**
     * Read the freefall / motion event flags.
     *
     * @return the {@code FF_MT_SRC} byte — cleared when the events are latched
     * @throws IOException on bus error
     */
    public int readMotionSource() throws IOException {
        return readReg(REG_FF_MT_SRC);
    }

    /**
     * Configure the transient detector.
     *
     * @param axes       axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG threshold in <i>g</i> (0.063 <i>g</i>/LSB)
     * @param debounceMs debounce time in ms
     * @param bypassHpf  {@code true} to bypass the high-pass filter (the detector
     *                   then behaves like motion detection)
     * @param latch      {@code true} to latch the event flags until they are read
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the axis mask is out of range
     */
    public void setTransient(int axes, double thresholdG, double debounceMs, boolean bypassHpf,
                             boolean latch) throws IOException {
        if (axes < 0 || axes > 7) {
            throw new IllegalArgumentException("axes must be a 3-bit mask (0..7)");
        }
        int cfg = 0;
        if (latch)             cfg |= 0x10;
        if ((axes & 0x04) != 0) cfg |= 0x08;
        if ((axes & 0x02) != 0) cfg |= 0x04;
        if ((axes & 0x01) != 0) cfg |= 0x02;
        if (bypassHpf)         cfg |= 0x01;
        int ths = thresholdCode(thresholdG);
        int count = debounceCode(debounceMs);
        standbyWriteMulti(REG_TRANSIENT_CFG, cfg, new int[][]{
                {REG_TRANSIENT_THS, ths}, {REG_TRANSIENT_CNT, count}});
    }

    /**
     * Configure the transient detector with the default axis mask and no
     * debounce.
     *
     * @param axes       axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG threshold in <i>g</i> (0.063 <i>g</i>/LSB)
     * @throws IOException on bus error
     */
    public void setTransient(int axes, double thresholdG) throws IOException {
        setTransient(axes, thresholdG, 0.0, false, false);
    }

    /**
     * Read the transient event flags.
     *
     * <p>Reading clears the flags and the interrupt source.
     *
     * @return the {@code TRANSIENT_SRC} byte
     * @throws IOException on bus error
     */
    public int readTransientSource() throws IOException {
        return readReg(REG_TRANSIENT_SRC);
    }

    /**
     * Configure the tap ("pulse") detector.
     *
     * @param single      axis mask for single-tap detection (bit 0 = X, bit 1 = Y, bit 2 = Z)
     * @param doubleTap   axis mask for double-tap detection
     * @param thresholdX  X pulse threshold in <i>g</i> (0.063 <i>g</i>/LSB)
     * @param thresholdY  Y pulse threshold in <i>g</i>
     * @param thresholdZ  Z pulse threshold in <i>g</i>
     * @param timeLimitMs pulse time limit in ms
     * @param latencyMs   pulse latency in ms
     * @param windowMs    second-pulse window in ms
     * @param latch       {@code true} to latch the event flags until they are read
     * @param doubleAbort {@code true} to abort double-tap detection when a pulse
     *                    starts during the latency window
     * @param lpf         {@code true} to enable the pulse low-pass filter
     * @param hpf         {@code true} to keep the pulse high-pass filter enabled
     * @throws IOException on bus error
     * @throws IllegalArgumentException if an axis mask is out of range
     */
    public void setPulse(int single, int doubleTap, double thresholdX, double thresholdY,
                         double thresholdZ, double timeLimitMs, double latencyMs, double windowMs,
                         boolean latch, boolean doubleAbort, boolean lpf, boolean hpf)
            throws IOException {
        if (single < 0 || single > 7 || doubleTap < 0 || doubleTap > 7) {
            throw new IllegalArgumentException("single and doubleTap must be 3-bit axis masks (0..7)");
        }
        int cfg = 0;
        if (doubleAbort)         cfg |= 0x80;
        if (latch)               cfg |= 0x40;
        if ((doubleTap & 0x04) != 0) cfg |= 0x20;
        if ((doubleTap & 0x02) != 0) cfg |= 0x10;
        if ((doubleTap & 0x01) != 0) cfg |= 0x08;
        if ((single & 0x04) != 0) cfg |= 0x04;
        if ((single & 0x02) != 0) cfg |= 0x02;
        if ((single & 0x01) != 0) cfg |= 0x01;
        int thsx = thresholdCode(thresholdX);
        int thsy = thresholdCode(thresholdY);
        int thsz = thresholdCode(thresholdZ);
        int tmlt = pulseTimeCode(timeLimitMs, lpf, true);
        int ltcy = pulseTimeCode(latencyMs, lpf, false);
        int wind = pulseTimeCode(windowMs, lpf, false);
        int hpFilter = readReg(REG_HP_FILTER);
        hpFilter = hpf ? (hpFilter & ~0x20 & 0xFF) : (hpFilter | 0x20);
        hpFilter = lpf ? (hpFilter | 0x10) : (hpFilter & ~0x10 & 0xFF);
        standbyWriteMulti(REG_PULSE_CFG, cfg, new int[][]{
                {REG_PULSE_THSX, thsx}, {REG_PULSE_THSY, thsy}, {REG_PULSE_THSZ, thsz},
                {REG_PULSE_TMLT, tmlt}, {REG_PULSE_LTCY, ltcy}, {REG_PULSE_WIND, wind},
                {REG_HP_FILTER, hpFilter}});
    }

    /**
     * Configure the tap ("pulse") detector with the default thresholds, timings
     * and no axis enabled.
     *
     * @throws IOException on bus error
     */
    public void setPulse() throws IOException {
        setPulse(0, 0, 0.5, 0.5, 0.5, 30.0, 100.0, 300.0, false, false, true, true);
    }

    /**
     * Read the tap event flags.
     *
     * <p>Reading clears the flags and the interrupt source.
     *
     * @return the {@code PULSE_SRC} byte
     * @throws IOException on bus error
     */
    public int readPulseSource() throws IOException {
        return readReg(REG_PULSE_SRC);
    }

    /**
     * Configure the portrait / landscape orientation detector.
     *
     * @param enabled     {@code true} to enable the detector
     * @param debounceMs  debounce time in ms
     * @param thresholdDeg trip angle — one of 15, 20, 30, 35, 40, 45, 55, 60,
     *                    70 or 75 degrees
     * @param hysteresis  hysteresis code 0–7 (adds 0/±4/±7/±11/±14/±17/±21/±24 degrees)
     * @param zLock       Z-lock angle code 0–7 (14/18/21/25/29/33/37/42 degrees)
     * @param backFront   back/front threshold code 0–3
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the trip angle is not supported, a code
     *         is out of range, or the register constraint {@code THS + HYS} in
     *         1…31 is violated
     */
    public void setOrientation(boolean enabled, double debounceMs, int thresholdDeg,
                               int hysteresis, int zLock, int backFront) throws IOException {
        int ths = orientationThreshold(thresholdDeg);
        if (hysteresis < 0 || hysteresis > 7) {
            throw new IllegalArgumentException("hysteresis must be 0..7");
        }
        if (zLock < 0 || zLock > 7) {
            throw new IllegalArgumentException("zLock must be 0..7");
        }
        if (backFront < 0 || backFront > 3) {
            throw new IllegalArgumentException("backFront must be 0..3");
        }
        if (ths + hysteresis < 1 || ths + hysteresis > 31) {
            throw new IllegalArgumentException(
                    "thresholdDeg + hysteresis must give a register value in 1..31");
        }
        // PL_CFG: DBCNTM (bit 7) stays at its reset value 1, PL_EN (bit 6)
        // enables the detector.
        int plCfg = 0x80 | (enabled ? 0x40 : 0x00);
        int plThs = (ths << 3) | hysteresis;
        int plBf = ((backFront & 0x03) << 6) | (zLock & 0x07);
        int count = debounceCode(debounceMs);
        standbyWriteMulti(REG_PL_CFG, plCfg, new int[][]{
                {REG_PL_COUNT, count}, {REG_P_L_THS, plThs}, {REG_PL_BF_ZCOMP, plBf}});
    }

    /**
     * Configure the orientation detector with the default trip angle,
     * hysteresis, Z-lock and back/front codes.
     *
     * @param enabled {@code true} to enable the detector
     * @throws IOException on bus error
     */
    public void setOrientation(boolean enabled) throws IOException {
        setOrientation(enabled, 0.0, 45, 4, 4, 1);
    }

    /**
     * Read the orientation status.
     *
     * <p>Reading clears the {@code NEWLP} flag.
     *
     * @return the decoded {@link Orientation}
     * @throws IOException on bus error
     */
    public Orientation readOrientation() throws IOException {
        int status = readReg(REG_PL_STATUS);
        return new Orientation((status & 0x80) != 0, (status & 0x40) != 0,
                (status >> 1) & 0x03, (status & 0x01) != 0);
    }

    /**
     * Configure Auto-SLEEP / Auto-WAKE.
     *
     * @param enabled      {@code true} to enable Auto-SLEEP
     * @param sleepRateHz  output rate while asleep — 50, 12.5, 6.25 or 1.56 Hz
     * @param timeoutS     inactivity timeout in seconds (0–81 s)
     * @param wakeSources  mask of wake sources — any combination of
     *                     {@link #SOURCE_TRANSIENT}, {@link #SOURCE_ORIENTATION},
     *                     {@link #SOURCE_PULSE} and {@link #SOURCE_FF_MT}
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the sleep rate is not supported
     */
    public void setAutoSleep(boolean enabled, double sleepRateHz, double timeoutS, int wakeSources)
            throws IOException {
        int rateIdx = aslpRateIndex(sleepRateHz);
        if (rateIdx < 0) {
            throw new IllegalArgumentException("sleepRateHz must be one of 50, 12.5, 6.25, 1.56");
        }
        int ctrl1 = readReg(REG_CTRL_REG1);
        ctrl1 = (ctrl1 & 0x3F) | (ASLP_RATE_CODES[rateIdx] << 6);
        int ctrl2 = readReg(REG_CTRL_REG2);
        ctrl2 = enabled ? (ctrl2 | 0x04) : (ctrl2 & ~0x04 & 0xFF);
        int ctrl3 = readReg(REG_CTRL_REG3) & 0x07;
        if ((wakeSources & SOURCE_TRANSIENT) != 0)   ctrl3 |= 0x40;
        if ((wakeSources & SOURCE_ORIENTATION) != 0) ctrl3 |= 0x20;
        if ((wakeSources & SOURCE_PULSE) != 0)       ctrl3 |= 0x10;
        if ((wakeSources & SOURCE_FF_MT) != 0)       ctrl3 |= 0x08;
        double stepS = odrHz <= 1.56 ? 0.64 : 0.32;
        int aslpCount = clamp((int) Math.round(timeoutS / stepS), 0, 255);
        standbyWriteMulti(REG_CTRL_REG1, ctrl1, new int[][]{
                {REG_CTRL_REG2, ctrl2}, {REG_CTRL_REG3, ctrl3}, {REG_ASLP_COUNT, aslpCount}});
    }

    /**
     * Configure Auto-SLEEP / Auto-WAKE with the default sleep rate, timeout and
     * no wake sources.
     *
     * @param enabled {@code true} to enable Auto-SLEEP
     * @throws IOException on bus error
     */
    public void setAutoSleep(boolean enabled) throws IOException {
        setAutoSleep(enabled, 50.0, 10.0, 0);
    }

    /**
     * Configure Auto-SLEEP / Auto-WAKE with the default timeout and no wake
     * sources.
     *
     * @param enabled     {@code true} to enable Auto-SLEEP
     * @param sleepRateHz output rate while asleep — 50, 12.5, 6.25 or 1.56 Hz
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the sleep rate is not supported
     */
    public void setAutoSleep(boolean enabled, double sleepRateHz) throws IOException {
        setAutoSleep(enabled, sleepRateHz, 10.0, 0);
    }

    /**
     * Enable or disable the FIFO gate.
     *
     * <p>With the gate enabled the FIFO blocks new input around WAKE / SLEEP
     * transitions until it has been emptied.
     *
     * @param enabled {@code true} to enable the FIFO gate
     * @throws IOException on bus error
     */
    public void setFifoGate(boolean enabled) throws IOException {
        int ctrl3 = readReg(REG_CTRL_REG3);
        ctrl3 = enabled ? (ctrl3 | 0x80) : (ctrl3 & ~0x80 & 0xFF);
        standbyWrite(REG_CTRL_REG3, ctrl3);
    }

    /**
     * Configure the interrupt pin polarity and drive.
     *
     * <p>Both INT pins share these settings.
     *
     * @param activeHigh {@code true} for active-high interrupts (the default is
     *                   active-low)
     * @param openDrain  {@code true} for open-drain drive (the default is
     *                   push-pull)
     * @throws IOException on bus error
     */
    public void setInterruptPins(boolean activeHigh, boolean openDrain) throws IOException {
        int ctrl3 = readReg(REG_CTRL_REG3);
        ctrl3 = activeHigh ? (ctrl3 | 0x02) : (ctrl3 & ~0x02 & 0xFF);
        ctrl3 = openDrain ? (ctrl3 | 0x01) : (ctrl3 & ~0x01 & 0xFF);
        standbyWrite(REG_CTRL_REG3, ctrl3);
    }

    /**
     * Reset the interrupt pin polarity and drive to the defaults (active-low,
     * push-pull).
     *
     * @throws IOException on bus error
     */
    public void setInterruptPins() throws IOException {
        setInterruptPins(false, false);
    }

    // =========================================================================
    // Interrupts
    // =========================================================================

    /**
     * Enable one interrupt source and route it to INT1 or INT2.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @param pin    1 for INT1, 2 for INT2
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the source or the pin is invalid
     */
    public void enableInterrupt(int source, int pin) throws IOException {
        checkSource(source);
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("pin must be 1 or 2");
        }
        int ctrl4 = readReg(REG_CTRL_REG4) | source;
        int ctrl5 = readReg(REG_CTRL_REG5);
        ctrl5 = pin == 1 ? (ctrl5 | source) : (ctrl5 & ~source & 0xFF);
        standbyWriteMulti(REG_CTRL_REG4, ctrl4, new int[][]{{REG_CTRL_REG5, ctrl5}});
    }

    /**
     * Enable one interrupt source and route it to INT1.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the source is invalid
     */
    public void enableInterrupt(int source) throws IOException {
        enableInterrupt(source, 1);
    }

    /**
     * Disable one interrupt source.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     * @throws IllegalArgumentException if the source is invalid
     */
    public void disableInterrupt(int source) throws IOException {
        checkSource(source);
        int ctrl4 = readReg(REG_CTRL_REG4) & ~source & 0xFF;
        standbyWrite(REG_CTRL_REG4, ctrl4);
    }

    /**
     * Subscribe to INT line assertions.
     *
     * <p>When the connection has no INT pin wired, a 5&nbsp;ms polling thread
     * is started instead.
     *
     * @param callback called with the interrupt status byte
     * @param pin      1 for INT1, 2 for INT2 — informational, the physical pin is
     *                 selected by the connection's INT pin
     */
    public void onInterrupt(IntConsumer callback, int pin) {
        offInterrupt();
        this.callback = callback;
        InputPin intPin = connection.intPin();
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            startPolling();
        }
    }

    /**
     * Subscribe to INT line assertions.
     *
     * @param callback called with the interrupt status byte
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, 1);
    }

    /**
     * Unsubscribe from INT line assertions.
     *
     * @param pin 1 for INT1, 2 for INT2 — informational
     */
    public void offInterrupt(int pin) {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        stopPolling();
    }

    /**
     * Unsubscribe from INT line assertions.
     */
    public void offInterrupt() {
        offInterrupt(1);
    }

    /**
     * Read the interrupt status and clear the per-source flags.
     *
     * <p>Every set bit except {@link #SOURCE_DATA_READY} additionally reads the
     * matching source register so the flag (and the interrupt pin) is cleared.
     * {@link #SOURCE_DATA_READY} stays set — clear it by reading the data.
     *
     * @return the {@code INT_SOURCE} byte
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int status = readReg(REG_INT_SOURCE);
        if ((status & SOURCE_FIFO) != 0) {
            readReg(REG_STATUS);
        }
        if ((status & SOURCE_FF_MT) != 0) {
            readReg(REG_FF_MT_SRC);
        }
        if ((status & SOURCE_PULSE) != 0) {
            readReg(REG_PULSE_SRC);
        }
        if ((status & SOURCE_ORIENTATION) != 0) {
            readReg(REG_PL_STATUS);
        }
        if ((status & SOURCE_TRANSIENT) != 0) {
            readReg(REG_TRANSIENT_SRC);
        }
        if ((status & SOURCE_AUTO_SLEEP) != 0) {
            readReg(REG_SYSMOD);
        }
        return status;
    }

    // =========================================================================
    // Self-test
    // =========================================================================

    /**
     * Run the electrostatic self-test.
     *
     * <p>The chip is put into STANDBY, set to ±4 <i>g</i> at 100 Hz with normal
     * oversampling, and the acceleration is sampled before and after the
     * self-test force is applied. The previous configuration is restored
     * afterwards.
     *
     * @return the {@link SelfTestResult}
     * @throws IOException on bus error
     */
    public SelfTestResult selfTest() throws IOException {
        int savedRange = rangeG;
        int savedBits = bits;
        int savedMods = oversampling;
        int savedSmods = sleepOversampling;
        boolean savedLnoise = lowNoise;
        int savedFifo = fifoMode;
        double savedOdr = odrHz;
        int[] before;
        int[] after;
        try {
            setFifo(0, 0);
            standby();
            setRange(4);
            setDataRate(100);
            setOversampling(0, -1);
            setResolution(14);
            activate();
            before = averageCounts();
            int ctrl2 = readReg(REG_CTRL_REG2);
            writeReg(REG_CTRL_REG2, ctrl2 | CTRL2_ST);
            delay((long) Math.ceil(turnOnTimeMs()));
            after = averageCounts();
            writeReg(REG_CTRL_REG2, ctrl2 & ~CTRL2_ST & 0xFF);
        } finally {
            // Restore the previous configuration.
            standby();
            setResolution(savedFifo == 0 ? savedBits : 14);
            setOversampling(savedMods, savedSmods);
            setDataRate(savedOdr);
            setLowNoise(savedLnoise);
            setRange(savedRange);
            if (savedFifo != 0) {
                setFifo(savedFifo, 0);
            }
            activate();
        }
        int dx = after[0] - before[0];
        int dy = after[1] - before[1];
        int dz = after[2] - before[2];
        boolean ok = dx > SELF_TEST_TYPICAL[0] / 2
                && dy > SELF_TEST_TYPICAL[1] / 2
                && dz > SELF_TEST_TYPICAL[2] / 2;
        return new SelfTestResult(ok, dx, dy, dz);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Average a few raw samples and return the mean counts.
     *
     * @return array of three ints — the mean X, Y, Z counts
     * @throws IOException on bus error
     */
    protected int[] averageCounts() throws IOException {
        int[] total = new int[3];
        for (int i = 0; i < 4; i++) {
            int[] sample = readRaw();
            total[0] += sample[0];
            total[1] += sample[1];
            total[2] += sample[2];
        }
        total[0] /= 4;
        total[1] /= 4;
        total[2] /= 4;
        return total;
    }

    /**
     * Convert a threshold in <i>g</i> to the 7-bit register code.
     *
     * @param thresholdG threshold in <i>g</i>
     * @return the register code (0–127)
     */
    protected static int thresholdCode(double thresholdG) {
        return clamp((int) Math.round(thresholdG / THRESHOLD_LSB_G), 0, 127);
    }

    /**
     * Convert an offset in <i>g</i> to the signed 8-bit register code.
     *
     * @param offsetG offset in <i>g</i>
     * @return the register code (-128…127)
     */
    protected static int offsetCode(double offsetG) {
        return clamp((int) Math.round(offsetG / OFFSET_LSB_G), -128, 127);
    }

    /**
     * Convert a debounce time in ms to the 8-bit counter code.
     *
     * @param debounceMs debounce time in ms
     * @return the counter code (0–255)
     */
    protected int debounceCode(double debounceMs) {
        int idx = odrIndex(odrHz);
        if (idx < 0) {
            idx = 3;
        }
        double step = DEBOUNCE_STEPS[idx][oversampling & 3];
        return clamp((int) Math.round(debounceMs / step), 0, 255);
    }

    /**
     * Convert a pulse timing in ms to the 8-bit register code.
     *
     * @param timeMs     the timing in ms
     * @param lpf        {@code true} when the pulse low-pass filter is enabled
     * @param isTimeLimit {@code true} for the time limit register, {@code false}
     *                    for the latency and window registers
     * @return the register code (0–255)
     */
    protected int pulseTimeCode(double timeMs, boolean lpf, boolean isTimeLimit) {
        int idx = odrIndex(odrHz);
        if (idx < 0) {
            idx = 3;
        }
        double step = lpf
                ? (isTimeLimit ? PULSE_TMLT_LPF : PULSE_LTCY_LPF)[idx][oversampling & 3]
                : (isTimeLimit ? PULSE_TMLT_NO_LPF : PULSE_LTCY_NO_LPF)[idx][oversampling & 3];
        return clamp((int) Math.round(timeMs / step), 0, 255);
    }

    /**
     * Write {@code F_SETUP} (mode bits 7:6, watermark bits 5:0).
     *
     * @param mode      FIFO mode (0–3)
     * @param watermark FIFO watermark (0–32)
     * @throws IOException on bus error
     */
    protected void writeFifoSetup(int mode, int watermark) throws IOException {
        int value = ((mode & 0x03) << 6) | (watermark & 0x3F);
        standbyWrite(REG_F_SETUP, value);
    }

    /**
     * Look up the index of an output data rate in {@link #ODR_ORDER}.
     *
     * @param rateHz the rate in Hz
     * @return the index, or -1 when the rate is not supported
     */
    protected static int odrIndex(double rateHz) {
        for (int i = 0; i < ODR_ORDER.length; i++) {
            if (Math.abs(ODR_ORDER[i] - rateHz) < 1e-6) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Look up the index of a sleep rate in {@link #ASLP_RATE_ORDER}.
     *
     * @param rateHz the rate in Hz
     * @return the index, or -1 when the rate is not supported
     */
    protected static int aslpRateIndex(double rateHz) {
        for (int i = 0; i < ASLP_RATE_ORDER.length; i++) {
            if (Math.abs(ASLP_RATE_ORDER[i] - rateHz) < 1e-6) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Look up the {@code P_L_THS} code of a trip angle.
     *
     * @param thresholdDeg the trip angle in degrees
     * @return the register code
     * @throws IllegalArgumentException if the angle is not one of the supported values
     */
    protected static int orientationThreshold(int thresholdDeg) {
        for (int i = 0; i < ORIENTATION_ANGLES.length; i++) {
            if (ORIENTATION_ANGLES[i] == thresholdDeg) {
                return ORIENTATION_THRESHOLDS[i];
            }
        }
        throw new IllegalArgumentException(
                "thresholdDeg must be one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75");
    }

    /**
     * Verify that an interrupt source is valid.
     *
     * @param source the source value to check
     * @throws IllegalArgumentException if the source is not a valid {@code SOURCE_*} value
     */
    protected static void checkSource(int source) {
        if (source == 0 || (source & ~SOURCE_ALL) != 0) {
            throw new IllegalArgumentException(
                    "invalid interrupt source 0x" + Integer.toHexString(source));
        }
    }

    /**
     * Clamp a value into an inclusive range.
     *
     * @param value the value to clamp
     * @param low   the lower bound
     * @param high  the upper bound
     * @return the clamped value
     */
    protected static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        if (value > high) {
            return high;
        }
        return value;
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                handleEdge();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "mma8451q-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (status != 0 && cb != null) {
                cb.accept(status);
            }
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
