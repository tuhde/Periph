package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.Register;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * LSM303 full interface — extends {@link LSM303Minimal} with complete functionality.
 *
 * <p>Adds accelerometer range / data-rate / power-mode configuration, per-axis
 * enable, raw data access, the high-pass filter, the 32-level FIFO, two
 * inertial interrupt generators with free-fall and 6D/4D orientation,
 * single/double click detection, interrupt routing across INT1/INT2, the
 * magnetometer gain / data-rate / operating-mode controls, the relative
 * temperature sensor, the software compass heading, and memory reboot.
 *
 * <p>Interrupt API: {@link #onInterrupt(IntConsumer, int)} subscribes to INT1
 * or INT2 assertions; delivery uses the accelerometer connection's
 * {@code intPin()} when wired (both INT lines typically share one edge input)
 * and falls back to a 5 ms polling thread otherwise. The callback receives the
 * combined status int from {@link #pollInterrupt()}.
 */
public class LSM303Full extends LSM303Minimal {

    // Interrupt source bits — the pollInterrupt() status layout.
    /** New accelerometer XYZ sample (STATUS_REG_A.ZYXDA) — INT1. */
    public static final int SOURCE_DATA_READY = 0x0001;
    /** FIFO content exceeds the watermark (FIFO_SRC_REG_A.WTM) — INT1. */
    public static final int SOURCE_WATERMARK = 0x0002;
    /** FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO) — INT1. */
    public static final int SOURCE_OVERRUN = 0x0004;
    /** Inertial generator 1 event (INT1_SOURCE_A.IA) — INT1 or INT2. */
    public static final int SOURCE_IA1 = 0x0008;
    /** Inertial generator 2 event (INT2_SOURCE_A.IA) — INT1 or INT2. */
    public static final int SOURCE_IA2 = 0x0010;
    /** Single / double click (CLICK_SRC_A.IA) — INT1 or INT2. */
    public static final int SOURCE_CLICK = 0x0020;
    /** Boot procedure running — INT2 only, pin-only (never reported by pollInterrupt). */
    public static final int SOURCE_BOOT = 0x0040;
    /** New magnetometer sample (SR_REG_M.DRDY) — dedicated DRDY pin, not routable. */
    public static final int SOURCE_MAG_DATA_READY = 0x0080;

    // CTRL_REG3_A routing bits (INT1 side).
    private static final int I1_CLICK   = 0x80;
    private static final int I1_AOI1    = 0x40;
    private static final int I1_AOI2    = 0x20;
    private static final int I1_DRDY1   = 0x10;
    private static final int I1_WTM     = 0x04;
    private static final int I1_OVERRUN = 0x01;

    // CTRL_REG6_A routing bits (INT2 side).
    private static final int I2_CLICK = 0x80;
    private static final int I2_INT1  = 0x40;
    private static final int I2_INT2  = 0x20;
    private static final int BOOT_I1  = 0x10;

    private final IntConsumer[] intCallbacks = new IntConsumer[3];
    private final java.util.HashSet<Integer> intSources = new java.util.HashSet<>();
    private boolean polling = false;
    private Thread pollThread = null;

    /** The exact handler registered on the shared INT pin, kept for removal. */
    private it.uhde.periph.connection.EdgeHandler lastEdgeHandler;

    /** Adapter so this driver can implement the {@link it.uhde.periph.connection.EdgeHandler} lambda shape. */
    private final Runnable edgeRunnable = this::dispatchInterrupt;

    /**
     * Construct and initialise the LSM303 (same initialisation as Minimal).
     *
     * @param accelConnection RegisterConnection (I²C or SMBus) bound to the
     *                        accelerometer slave address 0x19
     * @param magConnection   RegisterConnection (I²C or SMBus) bound to the
     *                        magnetometer slave address 0x1E, on the same bus
     * @throws IOException on bus error or wrong magnetometer identity
     */
    public LSM303Full(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        super(accelConnection, magConnection);
    }

    // -------------------------------------------------------------------------
    // Accelerometer configuration
    // -------------------------------------------------------------------------

    /**
     * Set the accelerometer full-scale range.
     *
     * @param rangeG one of 2, 4, 8, 16 (<i>g</i>); updates the cached sensitivity
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code rangeG} is not 2/4/8/16
     */
    public void setRange(int rangeG) throws IOException {
        int code;
        switch (rangeG) {
            case 2:  code = 0; break;
            case 4:  code = 1; break;
            case 8:  code = 2; break;
            case 16: code = 3; break;
            default: throw new IllegalArgumentException("rangeG must be one of 2, 4, 8, 16");
        }
        this.rangeBits = code;
        int ctrl4 = accelRead(REG_CTRL4_A);
        ctrl4 = (ctrl4 & 0xCF) | (code << 4);
        accelWrite(REG_CTRL4_A, ctrl4);
    }

    /**
     * Set the accelerometer output data rate.
     *
     * @param rateHz one of 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376 Hz
     *               (use {@link #powerDown()} for 0). 1620 Hz and 5376 Hz exist
     *               in low-power mode only, 1344 Hz in normal / high-resolution mode
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the rate is not allowed in the current mode
     */
    public void setDataRate(int rateHz) throws IOException {
        int code = accelOdrCode(rateHz);
        if (code == 0x8 && mode != 0) {
            throw new IllegalArgumentException("1620 Hz requires low-power mode (setMode(0))");
        }
        if (code == 0x9 && rateHz == 5376 && mode != 0) {
            throw new IllegalArgumentException("5376 Hz requires low-power mode (setMode(0))");
        }
        if (code == 0x9 && rateHz == 1344 && mode == 0) {
            throw new IllegalArgumentException("1344 Hz requires normal or high-resolution mode");
        }
        int ctrl1 = accelRead(REG_CTRL1_A);
        ctrl1 = (ctrl1 & 0x0F) | (code << 4);
        accelWrite(REG_CTRL1_A, ctrl1);
        this.odrHz = rateHz;
    }

    /**
     * Set the accelerometer operating mode (resolution).
     *
     * @param mode 0 = low-power (8-bit), 1 = normal (10-bit), 2 = high-resolution
     *             (12-bit); updates the cached sensitivity
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code mode} is not 0/1/2
     */
    public void setMode(int mode) throws IOException {
        if (mode != 0 && mode != 1 && mode != 2) {
            throw new IllegalArgumentException("mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)");
        }
        int ctrl1 = accelRead(REG_CTRL1_A);
        int ctrl4 = accelRead(REG_CTRL4_A);
        if (mode == 0) {
            ctrl1 |= 0x08;      // LPen = 1
            ctrl4 &= ~0x08;     // HR = 0
        } else if (mode == 1) {
            ctrl1 &= ~0x08;
            ctrl4 &= ~0x08;
        } else {
            ctrl1 &= ~0x08;
            ctrl4 |= 0x08;
        }
        accelWrite(REG_CTRL1_A, ctrl1);
        accelWrite(REG_CTRL4_A, ctrl4);
        this.mode = mode;
    }

    /**
     * Enable or disable the accelerometer axes.
     *
     * @param x enable the X axis
     * @param y enable the Y axis
     * @param z enable the Z axis
     * @throws IOException on bus error
     */
    public void setAxes(boolean x, boolean y, boolean z) throws IOException {
        int ctrl1 = accelRead(REG_CTRL1_A);
        ctrl1 = (ctrl1 & 0xF8) | ((z ? 1 : 0) << 2) | ((y ? 1 : 0) << 1) | (x ? 1 : 0);
        accelWrite(REG_CTRL1_A, ctrl1);
    }

    /**
     * Enter power-down (ODR = 0000). Register contents are retained.
     *
     * @throws IOException on bus error
     */
    public void powerDown() throws IOException {
        int ctrl1 = accelRead(REG_CTRL1_A);
        accelWrite(REG_CTRL1_A, ctrl1 & 0x0F);
        this.odrHz = 0.0;
    }

    /**
     * Read raw accelerometer counts at the current resolution.
     *
     * @return array of three ints — X, Y, Z signed counts
     *         (12/10/8-bit right-aligned per mode)
     * @throws IOException on bus error
     */
    public int[] readAccelerationRaw() throws IOException {
        byte[] raw = accelConn.read(REG_OUT_X_L_A | 0x80, 6);
        return convertAccelSample(raw);
    }

    /**
     * Check whether a new accelerometer sample is available.
     *
     * @return true when STATUS_REG_A.ZYXDA (bit 3, 0x08) is set
     * @throws IOException on bus error
     */
    public boolean newDataAvailable() throws IOException {
        return (accelRead(REG_STATUS_A) & STATUS_ZYXDA) != 0;
    }

    /**
     * Read the accelerometer status register.
     *
     * @return STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun, ZYXDA/*DA data-ready)
     * @throws IOException on bus error
     */
    public int readStatus() throws IOException {
        return accelRead(REG_STATUS_A);
    }

    /**
     * Configure the high-pass filter.
     *
     * @param mode          HPM bits 7:6 — 0 normal (reset by reading REFERENCE_A),
     *                      1 reference signal for filtering, 2 normal, 3 autoreset
     * @param cutoff        HPCF bits 5:4 raw code 0–3 (no Hz values tabulated for this part)
     * @param filterOutput  true = filtered data goes to the output registers and FIFO
     * @param onIa1         true = high-pass filter enabled for interrupt generator 1
     * @param onIa2         true = high-pass filter enabled for interrupt generator 2
     * @param onClick       true = high-pass filter enabled for click detection
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if mode or cutoff is out of range
     */
    public void setHighPass(int mode, int cutoff, boolean filterOutput, boolean onIa1,
                            boolean onIa2, boolean onClick) throws IOException {
        int ctrl2 = ((mode & 0x03) << 6) | ((cutoff & 0x03) << 4);
        if (filterOutput) ctrl2 |= 0x08;
        if (onClick)      ctrl2 |= 0x04;
        if (onIa2)        ctrl2 |= 0x02;
        if (onIa1)        ctrl2 |= 0x01;
        accelWrite(REG_CTRL2_A, ctrl2);
    }

    /**
     * Reset the high-pass filter (HPM = 00) by reading REFERENCE_A.
     *
     * @return the REFERENCE_A register value
     * @throws IOException on bus error
     */
    public int resetHighPass() throws IOException {
        return accelRead(REG_REFERENCE_A);
    }

    /**
     * Write the high-pass reference register (for HPM = 01).
     *
     * @param value reference value 0–255
     * @throws IOException on bus error
     */
    public void setReference(int value) throws IOException {
        accelWrite(REG_REFERENCE_A, value & 0xFF);
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    /**
     * Configure the 32-level FIFO.
     *
     * @param mode         0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO);
     *                     returning to bypass clears the buffer
     * @param watermark    watermark level 0–31
     * @param triggerInt2  trigger-mode source — false = INT1, true = INT2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if mode or watermark is out of range
     */
    public void setFifo(int mode, int watermark, boolean triggerInt2) throws IOException {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("mode must be 0..3");
        }
        if (watermark < 0 || watermark > 31) {
            throw new IllegalArgumentException("watermark must be 0..31");
        }
        int fifoCtrl = ((mode & 0x03) << 6) | (triggerInt2 ? 0x20 : 0x00) | (watermark & 0x1F);
        accelWrite(REG_FIFO_CTRL_A, fifoCtrl);
        int ctrl5 = accelRead(REG_CTRL5_A);
        if (mode >= 1 && mode <= 3) {
            ctrl5 |= 0x40;
        } else {
            ctrl5 &= ~0x40;
        }
        accelWrite(REG_CTRL5_A, ctrl5);
    }

    /**
     * Return the number of unread sample sets in the FIFO (0–32).
     *
     * @return FSS bits 4:0 from FIFO_SRC_REG_A
     * @throws IOException on bus error
     */
    public int fifoLevel() throws IOException {
        return accelRead(REG_FIFO_SRC_A) & 0x1F;
    }

    /**
     * Drain up to {@code count} sample sets from the FIFO. Each sample is
     * popped with one 6-byte burst read of OUT_X_L_A..OUT_Z_H_A.
     *
     * @param count maximum number of sample sets to read, or ≤ 0 / Integer.MIN_VALUE
     *              to read all unread samples reported by FSS
     * @return list of {@code double[]} (x, y, z) in <i>g</i>
     * @throws IOException on bus error
     */
    public java.util.List<double[]> readFifo(int count) throws IOException {
        if (count < 0) {
            count = fifoLevel();
        }
        java.util.List<double[]> out = new java.util.ArrayList<>();
        double scale = accelSensitivityG();
        for (int i = 0; i < count; i++) {
            byte[] raw = accelConn.read(REG_OUT_X_L_A | 0x80, 6);
            int[] c = convertAccelSample(raw);
            out.add(new double[]{c[0] * scale, c[1] * scale, c[2] * scale});
        }
        return out;
    }

    /**
     * Read the decoded FIFO status.
     *
     * @return array {@code (watermark, overrun, empty, level)} — the first
     *         three entries are 1/0 as in {@code bool}, level is the FSS count
     * @throws IOException on bus error
     */
    public int[] readFifoStatus() throws IOException {
        int src = accelRead(REG_FIFO_SRC_A);
        return new int[]{(src & 0x80) != 0 ? 1 : 0, (src & 0x40) != 0 ? 1 : 0,
                (src & 0x20) != 0 ? 1 : 0, src & 0x1F};
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators and click detection
    // -------------------------------------------------------------------------

    /** Register base (CFG, SRC, THS, DUR) for generator 1 or 2. */
    private int[] intRegs(int generator) {
        if (generator == 1) {
            return new int[]{REG_INT1_CFG_A, REG_INT1_SRC_A, REG_INT1_THS_A, REG_INT1_DUR_A};
        }
        if (generator == 2) {
            return new int[]{REG_INT2_CFG_A, REG_INT2_SRC_A, REG_INT2_THS_A, REG_INT2_DUR_A};
        }
        throw new IllegalArgumentException("generator must be 1 or 2");
    }

    /** Convert a threshold in g to the 7-bit INTx_THS / CLICK_THS code. */
    private int encodeThreshold(double thresholdG) {
        int code = (int) Math.round(thresholdG * 128.0 / ACCEL_FULL_SCALE_G[rangeBits]);
        return Math.max(0, Math.min(127, code));
    }

    /** Convert a duration in ms to the 1/ODR code, clamped to {@code maxBits} bits. */
    private int encodeDuration(double durationMs, int maxBits) {
        int code = (int) Math.round(durationMs * odrHz / 1000.0);
        return Math.max(0, Math.min((1 << maxBits) - 1, code));
    }

    /**
     * Configure one inertial interrupt generator.
     *
     * @param generator   1 or 2 (INT1 generator / INT2 generator)
     * @param events      6-bit event mask (bit 5 = Z high, 4 = Z low, 3 = Y high,
     *                    2 = Y low, 1 = X high, 0 = X low; the same bits select
     *                    the direction zones in 6D modes)
     * @param thresholdG  event threshold in <i>g</i> (1 LSb = full-scale / 128)
     * @param durationMs  minimum event duration in ms (1 LSb = 1/ODR)
     * @param andMode     true = AND combination of the enabled events, false = OR
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if generator is not 1 or 2
     */
    public void setMotionInterrupt(int generator, int events, double thresholdG,
                                   double durationMs, boolean andMode) throws IOException {
        int[] regs = intRegs(generator);
        accelWrite(regs[0], (andMode ? 0x80 : 0x00) | (events & 0x3F));
        accelWrite(regs[2], encodeThreshold(thresholdG));
        accelWrite(regs[3], encodeDuration(durationMs, 7));
    }

    /**
     * Configure free-fall detection on one generator: all three axes' low
     * events, AND-combined (INTx_CFG_A = 0x95).
     *
     * @param generator   1 (default) or 2
     * @param thresholdG  free-fall threshold in <i>g</i>
     * @param durationMs  minimum free-fall duration in ms
     * @throws IOException on bus error
     */
    public void setFreeFall(int generator, double thresholdG, double durationMs) throws IOException {
        setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true);
    }

    /**
     * Configure 6D / 4D orientation recognition on one generator.
     *
     * @param generator 1 (default) or 2
     * @param position  false = 6D movement recognition (one-ODR pulse when the
     *                  orientation moves into a known zone), true = 6D position
     *                  recognition (stays active inside a known zone)
     * @param fourD     true = 4D detection (Z-axis zone disabled; needs the 6D bit)
     * @throws IOException on bus error
     */
    public void setOrientation(int generator, boolean position, boolean fourD) throws IOException {
        int[] regs = intRegs(generator);
        int value = 0x40 | 0x3F;  // 6D + all six direction zones
        if (position) {
            value |= 0x80;
        }
        accelWrite(regs[0], value);
        int ctrl5 = accelRead(REG_CTRL5_A);
        int d4dMask = (generator == 1) ? 0x04 : 0x01;
        if (fourD) {
            ctrl5 |= d4dMask;
        } else {
            ctrl5 &= ~d4dMask;
        }
        accelWrite(REG_CTRL5_A, ctrl5);
    }

    /**
     * Latch (or unlatch) one generator's interrupt until its source is read.
     *
     * @param generator 1 or 2
     * @param enabled   true = latch INT output until INTx_SOURCE_A is read
     * @throws IOException on bus error
     */
    public void setLatch(int generator, boolean enabled) throws IOException {
        int ctrl5 = accelRead(REG_CTRL5_A);
        int lirMask = (generator == 1) ? 0x08 : 0x02;
        if (enabled) {
            ctrl5 |= lirMask;
        } else {
            ctrl5 &= ~lirMask;
        }
        accelWrite(REG_CTRL5_A, ctrl5);
    }

    /**
     * Read one generator's source register — clears the interrupt.
     *
     * @param generator 1 or 2
     * @return INTx_SOURCE_A byte (bit 6 = IA, bits 5:0 = axis high/low flags)
     * @throws IOException on bus error
     */
    public int readInterruptSource(int generator) throws IOException {
        int[] regs = intRegs(generator);
        return accelRead(regs[1]);
    }

    /**
     * Configure single / double click detection.
     *
     * @param single       axis mask for single click (X=1, Y=2, Z=4; 0 = off)
     * @param singleAxisX  (ignored — part of the mask form; pass masks in {@code single})
     * @param doubleMask   axis mask for double click (X=1, Y=2, Z=4; 0 = off)
     * @param thresholdG   click threshold in <i>g</i> (1 LSb = full-scale / 128)
     * @param timeLimitMs  maximum click duration in ms (1 LSb = 1/ODR, 7-bit)
     * @param latencyMs    time after a click in which the click interrupt is not
     *                     re-armed (1 LSb = 1/ODR, 8-bit)
     * @param windowMs     time window for a second click (1 LSb = 1/ODR, 8-bit)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if a mask is not an X/Y/Z axis mask 0..7
     */
    public void setClick(int single, int doubleMask, double thresholdG, double timeLimitMs,
                         double latencyMs, double windowMs) throws IOException {
        if (single < 0 || single > 7 || doubleMask < 0 || doubleMask > 7) {
            throw new IllegalArgumentException("single / double are axis masks: X=1, Y=2, Z=4");
        }
        // Axis masks X=1/Y=2/Z=4 spread to CLICK_CFG_A bits: XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
        int singleCode = ((single & 1) << 1) | ((single & 2) << 2) | ((single & 4) << 3);
        int doubleCode = ((doubleMask & 1) << 0) | ((doubleMask & 2) << 1) | ((doubleMask & 4) << 2);
        accelWrite(REG_CLICK_CFG_A, singleCode | doubleCode);
        accelWrite(REG_CLICK_THS_A, encodeThreshold(thresholdG));
        accelWrite(REG_TIME_LIMIT_A, encodeDuration(timeLimitMs, 7));
        accelWrite(REG_TIME_LAT_A, encodeDuration(latencyMs, 8));
        accelWrite(REG_TIME_WIN_A, encodeDuration(windowMs, 8));
    }

    /**
     * Read the click source register — clears the click interrupt.
     *
     * @return CLICK_SRC_A byte (bit 6 = IA, bit 5 = DCLICK, bit 4 = SCLICK,
     *         bit 3 = sign, bits 2:0 = Z/Y/X event axis)
     * @throws IOException on bus error
     */
    public int readClickSource() throws IOException {
        return accelRead(REG_CLICK_SRC_A);
    }

    /**
     * Set the INT1 / INT2 output polarity.
     *
     * @param activeLow true = INT pins active-low, false = active-high (default)
     * @throws IOException on bus error
     */
    public void setInterruptPolarity(boolean activeLow) throws IOException {
        int ctrl6 = accelRead(REG_CTRL6_A);
        if (activeLow) {
            ctrl6 |= 0x02;
        } else {
            ctrl6 &= ~0x02;
        }
        accelWrite(REG_CTRL6_A, ctrl6);
    }

    // -------------------------------------------------------------------------
    // Magnetometer configuration
    // -------------------------------------------------------------------------

    /**
     * Set the magnetometer full-scale range (gain).
     *
     * @param rangeUt one of 130, 190, 250, 400, 470, 560, 810 (µT); updates
     *                the cached conversion gains
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code rangeUt} is not a valid range
     */
    public void setMagRange(int rangeUt) throws IOException {
        int index;
        switch (rangeUt) {
            case 130: index = 0; break;
            case 190: index = 1; break;
            case 250: index = 2; break;
            case 400: index = 3; break;
            case 470: index = 4; break;
            case 560: index = 5; break;
            case 810: index = 6; break;
            default: throw new IllegalArgumentException("rangeUt must be one of 130, 190, 250, 400, 470, 560, 810");
        }
        this.magGn = index;
        magWrite(REG_CRB_M, (index + 1) << 5);
    }

    /**
     * Set the magnetometer output data rate.
     *
     * @param rateHz one of 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz; preserves TEMP_EN
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code rateHz} is not a valid rate
     */
    public void setMagDataRate(double rateHz) throws IOException {
        int code = magOdrCode(rateHz);
        int cra = magRead(REG_CRA_M);
        magWrite(REG_CRA_M, (cra & 0x80) | (code << 2));
        this.magOdrHz = rateHz;
    }

    /**
     * Set the magnetometer operating mode.
     *
     * @param magMode 0 continuous conversion, 1 single conversion (one
     *                conversion, then back to sleep), 2 sleep
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code magMode} is not 0/1/2
     */
    public void setMagMode(int magMode) throws IOException {
        if (magMode != 0 && magMode != 1 && magMode != 2) {
            throw new IllegalArgumentException("mode must be 0 (continuous), 1 (single) or 2 (sleep)");
        }
        magWrite(REG_MR_M, magMode);
    }

    /**
     * Read raw magnetometer counts (X, Y, Z order, after reordering).
     *
     * @return array of three ints — X, Y, Z signed 12-bit counts
     * @throws IOException on bus error
     */
    public int[] readMagneticRaw() throws IOException {
        byte[] raw = magConn.read(REG_OUT_X_H_M, 6);
        int mx = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16);
        int mz = Register.toSigned(((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF), 16);
        int my = Register.toSigned(((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF), 16);
        return new int[]{mx, my, mz};
    }

    /**
     * Check whether a new magnetometer sample is available.
     *
     * @return true when SR_REG_M.DRDY is set
     * @throws IOException on bus error
     */
    public boolean magDataReady() throws IOException {
        return (magRead(REG_SR_M) & 0x01) != 0;
    }

    /**
     * Read the decoded magnetometer status register.
     *
     * @return array {@code (drdy, lock)} — DRDY = new set of measurements
     *         available (1/0), LOCK = output registers locked mid-read (1/0)
     * @throws IOException on bus error
     */
    public int[] readMagStatus() throws IOException {
        int sr = magRead(REG_SR_M);
        return new int[]{(sr & 0x01) != 0 ? 1 : 0, (sr & 0x02) != 0 ? 1 : 0};
    }

    /**
     * Read the magnetometer identification registers.
     *
     * @return IRA/IRB/IRC bytes ({@code 0x48 0x34 0x33} — identical to HMC5883L)
     * @throws IOException on bus error
     */
    public byte[] readMagIdentity() throws IOException {
        return magConn.read(REG_IRA_M, 3);
    }

    /**
     * Enable or disable the relative temperature sensor.
     *
     * @param enabled true = TEMP_EN set (updates at the magnetometer data rate)
     * @throws IOException on bus error
     */
    public void enableTemperature(boolean enabled) throws IOException {
        int cra = magRead(REG_CRA_M);
        if (enabled) {
            cra |= 0x80;
        } else {
            cra &= ~0x80;
        }
        magWrite(REG_CRA_M, cra);
        this.tempEnabled = enabled;
    }

    /**
     * Read the relative temperature. The sensor is relative only — the
     * datasheet gives no zero-degree reference — so the value is a delta,
     * never an absolute °C. Enables TEMP_EN on first use (read-modify-write).
     *
     * @return relative temperature in °C (8 LSB/°C)
     * @throws IOException on bus error
     */
    public double readTemperatureDelta() throws IOException {
        if (!tempEnabled) {
            enableTemperature(true);
        }
        byte[] raw = magConn.read(REG_TEMP_H_M, 2);
        int value = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16) >> 4;
        return value / 8.0;
    }

    // -------------------------------------------------------------------------
    // Software compass
    // -------------------------------------------------------------------------

    /**
     * Compute the compass heading from both sensors.
     *
     * <p>Uses the {@code atan2(y, x)} convention, wrapped to 0–360°. The
     * datasheet documents neither the axis alignment between the two dies nor
     * a declination, so the result assumes both sensors report in the same
     * body frame and is magnetic (not true) north.
     *
     * @param tiltCompensate true (default) = project the magnetic field onto
     *                       the horizontal plane using the current acceleration;
     *                       false = flat compass from the magnetic X/Y only
     * @return heading in degrees, 0–360
     * @throws IOException on bus error
     */
    public double heading(boolean tiltCompensate) throws IOException {
        double[] m = readMagnetic();
        double fx = m[0], fy = m[1], fz = m[2];
        double angle;
        if (!tiltCompensate) {
            angle = Math.toDegrees(Math.atan2(fy, fx));
        } else {
            double[] a = readAcceleration();
            double ax = a[0], ay = a[1], az = a[2];
            double norm = Math.sqrt(ax * ax + ay * ay + az * az);
            if (norm == 0.0) {
                norm = 1.0;
            }
            ax /= norm;
            ay /= norm;
            az /= norm;
            double roll = Math.atan2(ay, az);
            double pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll));
            double xh = fx * Math.cos(pitch) + fz * Math.sin(pitch);
            double yh = fx * Math.sin(roll) * Math.sin(pitch) + fy * Math.cos(roll)
                    - fz * Math.sin(roll) * Math.cos(pitch);
            angle = Math.toDegrees(Math.atan2(yh, xh));
        }
        return angle >= 0.0 ? angle : angle + 360.0;
    }

    // -------------------------------------------------------------------------
    // Interrupts (Level 3: INT1 / INT2 routing)
    // -------------------------------------------------------------------------

    /**
     * Route one interrupt source to INT1 or INT2. Only routes the source; the
     * generator or function behind it is configured with the
     * {@code setMotionInterrupt} / {@code setClick} / … methods. {@code pin}
     * is ignored for INT1-only / INT2-only sources.
     * {@link #SOURCE_MAG_DATA_READY} is rejected (it is always on the
     * dedicated magnetometer DRDY pin and is not routable).
     *
     * @param source one of the {@code SOURCE_*} constants
     * @param pin    1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException on unknown source or bad pin
     */
    public void enableInterrupt(int source, int pin) throws IOException {
        requireRoutable(source, pin);
        int ctrl3 = accelRead(REG_CTRL3_A);
        int ctrl6 = accelRead(REG_CTRL6_A);
        switch (source) {
            case SOURCE_DATA_READY: ctrl3 |= I1_DRDY1; break;
            case SOURCE_WATERMARK:  ctrl3 |= I1_WTM; break;
            case SOURCE_OVERRUN:    ctrl3 |= I1_OVERRUN; break;
            case SOURCE_IA1:
                if (pin == 1) ctrl3 |= I1_AOI1; else ctrl6 |= I2_INT1;
                break;
            case SOURCE_IA2:
                if (pin == 1) ctrl3 |= I1_AOI2; else ctrl6 |= I2_INT2;
                break;
            case SOURCE_CLICK:
                if (pin == 1) ctrl3 |= I1_CLICK; else ctrl6 |= I2_CLICK;
                break;
            case SOURCE_BOOT:
                ctrl6 |= BOOT_I1;  // must be pin 2 — checked above
                break;
            default: break;
        }
        accelWrite(REG_CTRL3_A, ctrl3);
        accelWrite(REG_CTRL6_A, ctrl6);
        intSources.add(source);
    }

    /**
     * Remove one interrupt source from INT1 or INT2.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @param pin    1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException on unknown source or bad pin
     */
    public void disableInterrupt(int source, int pin) throws IOException {
        requireRoutable(source, pin);
        int ctrl3 = accelRead(REG_CTRL3_A);
        int ctrl6 = accelRead(REG_CTRL6_A);
        switch (source) {
            case SOURCE_DATA_READY: ctrl3 &= ~I1_DRDY1; break;
            case SOURCE_WATERMARK:  ctrl3 &= ~I1_WTM; break;
            case SOURCE_OVERRUN:    ctrl3 &= ~I1_OVERRUN; break;
            case SOURCE_IA1:
                if (pin == 1) ctrl3 &= ~I1_AOI1; else ctrl6 &= ~I2_INT1;
                break;
            case SOURCE_IA2:
                if (pin == 1) ctrl3 &= ~I1_AOI2; else ctrl6 &= ~I2_INT2;
                break;
            case SOURCE_CLICK:
                if (pin == 1) ctrl3 &= ~I1_CLICK; else ctrl6 &= ~I2_CLICK;
                break;
            case SOURCE_BOOT:
                ctrl6 &= ~BOOT_I1;  // must be pin 2 — checked above
                break;
            default: break;
        }
        accelWrite(REG_CTRL3_A, ctrl3);
        accelWrite(REG_CTRL6_A, ctrl6);
        intSources.remove(source);
    }

    /**
     * Read the source registers of every enabled interrupt source.
     *
     * <p>Reading INTx_SOURCE_A / CLICK_SRC_A clears their latched flags, so a
     * poll acknowledges the INT line.
     *
     * @return combined status — bit 0 {@link #SOURCE_DATA_READY}, bit 1
     *         {@link #SOURCE_WATERMARK}, bit 2 {@link #SOURCE_OVERRUN}, bit 3
     *         {@link #SOURCE_IA1}, bit 4 {@link #SOURCE_IA2}, bit 5
     *         {@link #SOURCE_CLICK}, bit 7 {@link #SOURCE_MAG_DATA_READY}.
     *         {@link #SOURCE_BOOT} is pin-only and never reported.
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        int status = 0;
        if (intSources.contains(SOURCE_DATA_READY)) {
            if ((accelRead(REG_STATUS_A) & STATUS_ZYXDA) != 0) {
                status |= SOURCE_DATA_READY;
            }
        }
        if (intSources.contains(SOURCE_WATERMARK) || intSources.contains(SOURCE_OVERRUN)) {
            int fifoSrc = accelRead(REG_FIFO_SRC_A);
            if ((fifoSrc & 0x80) != 0) status |= SOURCE_WATERMARK;
            if ((fifoSrc & 0x40) != 0) status |= SOURCE_OVERRUN;
        }
        if (intSources.contains(SOURCE_IA1)) {
            if ((accelRead(REG_INT1_SRC_A) & 0x40) != 0) {
                status |= SOURCE_IA1;
            }
        }
        if (intSources.contains(SOURCE_IA2)) {
            if ((accelRead(REG_INT2_SRC_A) & 0x40) != 0) {
                status |= SOURCE_IA2;
            }
        }
        if (intSources.contains(SOURCE_CLICK)) {
            if ((accelRead(REG_CLICK_SRC_A) & 0x40) != 0) {
                status |= SOURCE_CLICK;
            }
        }
        if ((magRead(REG_SR_M) & 0x01) != 0) {
            status |= SOURCE_MAG_DATA_READY;
        }
        return status;
    }

    /**
     * Subscribe to INT1 (pin 1, default) or INT2 (pin 2) assertions.
     *
     * <p>Delivery uses the accelerometer connection's {@code intPin()} when
     * wired (both INT lines typically share one edge input); falls back to a
     * 5 ms polling thread otherwise. The callback receives the combined status
     * int from {@link #pollInterrupt()}.
     *
     * @param callback called with the combined status int on any assertion
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, 1);
    }

    /**
     * Subscribe to INT1 (pin 1) or INT2 (pin 2) assertions.
     *
     * @param callback called with the combined status int on any assertion
     * @param pin      1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if {@code pin} is not 1 or 2
     * @see #onInterrupt(IntConsumer)
     */
    public void onInterrupt(IntConsumer callback, int pin) throws IOException {
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("pin must be 1 or 2");
        }
        intCallbacks[pin] = callback;
        startInterruptDelivery();
    }

    /**
     * Unsubscribe from INT1 (pin 1) or INT2 (pin 2) assertions.
     *
     * @param pin 1 or 2
     * @throws IllegalArgumentException if {@code pin} is not 1 or 2
     */
    public void offInterrupt(int pin) {
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("pin must be 1 or 2");
        }
        intCallbacks[pin] = null;
        if (intCallbacks[1] == null && intCallbacks[2] == null) {
            stopInterruptDelivery();
        }
    }

    /** Unsubscribe from all INT assertions and stop delivery. */
    public void offInterrupt() {
        intCallbacks[1] = null;
        intCallbacks[2] = null;
        stopInterruptDelivery();
    }

    private void startInterruptDelivery() throws IOException {
        InputPin intPin = accelConn.intPin();
        if (intPin != null) {
            lastEdgeHandler = () -> edgeRunnable.run();
            intPin.onEdge(lastEdgeHandler, EdgeTrigger.FALLING);
            return;
        }
        if (polling) {
            return;
        }
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                edgeRunnable.run();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "lsm303-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopInterruptDelivery() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        InputPin intPin = accelConn.intPin();
        // Registered handlers are removed by exact instance — keep the reference.
        if (intPin != null && lastEdgeHandler != null) {
            intPin.offEdge(lastEdgeHandler);
            lastEdgeHandler = null;
        }
    }

    private void dispatchInterrupt() {
        IntConsumer cb1 = intCallbacks[1];
        IntConsumer cb2 = intCallbacks[2];
        if (cb1 == null && cb2 == null) {
            return;
        }
        int status;
        try {
            status = pollInterrupt();
        } catch (IOException e) {
            return;  // bus error; wait for the next edge/tick
        }
        if (cb1 != null) cb1.accept(status);
        if (cb2 != null) cb2.accept(status);
    }

    private void requireRoutable(int source, int pin) {
        if (source == SOURCE_MAG_DATA_READY) {
            throw new IllegalArgumentException("SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable");
        }
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("pin must be 1 or 2");
        }
        boolean known = source == SOURCE_DATA_READY || source == SOURCE_WATERMARK
                || source == SOURCE_OVERRUN || source == SOURCE_IA1 || source == SOURCE_IA2
                || source == SOURCE_CLICK || source == SOURCE_BOOT;
        if (!known) {
            throw new IllegalArgumentException("unknown interrupt source 0x" + Integer.toHexString(source));
        }
        if (source == SOURCE_BOOT && pin != 2) {
            throw new IllegalArgumentException("SOURCE_BOOT is INT2-only");
        }
    }

    // -------------------------------------------------------------------------
    // Reboot
    // -------------------------------------------------------------------------

    /**
     * Reboot the accelerometer memory content (BOOT = 1).
     *
     * <p>Reloads the factory trim values; waits ≥ 5 ms. The datasheet does not
     * say whether the control registers survive, so re-run the
     * initialisation (construct a fresh driver) afterwards.
     *
     * @throws IOException on bus error
     */
    public void reboot() throws IOException {
        int ctrl5 = accelRead(REG_CTRL5_A);
        accelWrite(REG_CTRL5_A, ctrl5 | 0x80);
        sleepMs(5);
    }

    // ---------------------------------------------------------------------
    // ODR code lookups
    // ---------------------------------------------------------------------

    /** Map a rate in Hz to the CTRL_REG1_A ODR code; throws when invalid. */
    private static int accelOdrCode(int rateHz) {
        switch (rateHz) {
            case 1:    return 0x1;
            case 10:   return 0x2;
            case 25:   return 0x3;
            case 50:   return 0x4;
            case 100:  return 0x5;
            case 200:  return 0x6;
            case 400:  return 0x7;
            case 1620: return 0x8;
            case 1344: // fall through — same code as 5376
            case 5376: return 0x9;
            default: throw new IllegalArgumentException(
                    "rateHz must be one of 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376");
        }
    }

    /** Map a rate in Hz to the CRA_REG_M DO code; throws when invalid. */
    private static int magOdrCode(double rateHz) {
        if (rateHz == 0.75) return 0x0;
        if (rateHz == 1.5)  return 0x1;
        if (rateHz == 3.0)  return 0x2;
        if (rateHz == 7.5)  return 0x3;
        if (rateHz == 15.0) return 0x4;
        if (rateHz == 30.0) return 0x5;
        if (rateHz == 75.0) return 0x6;
        if (rateHz == 220.0) return 0x7;
        throw new IllegalArgumentException("rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220");
    }
}
