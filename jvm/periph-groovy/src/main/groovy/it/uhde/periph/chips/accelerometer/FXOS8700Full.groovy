package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * FXOS8700CQ — 6-axis e-compass (NXP) — full interface.
 *
 * <p>Extends {@link FXOS8700Minimal} with the complete chip functionality:
 * operating mode, range, data rate, oversampling, raw / hybrid / temperature
 * reads, offsets, the magnetometer calibration helpers, the 32-sample FIFO,
 * the freefall / motion, transient, tap and orientation detectors,
 * Auto-SLEEP / WAKE, the interrupt routing and a self-test.
 *
 * <p>Methods that change a STANDBY-only register put the chip into STANDBY,
 * write, and restore ACTIVE internally, so the caller never manages the mode.
 * Every such change flushes the FIFO and resets the detectors' debounce
 * counters.
 *
 * <p>The vector-magnitude detectors (0x5F-0x72), the magnetic threshold
 * detector (0x52-0x5A) and the per-axis freefall / motion thresholds
 * (0x73-0x78) are not wrapped: use {@link #readRegister} / {@link #writeRegister}.
 */
@CompileStatic
class FXOS8700Full extends FXOS8700Minimal {

    /** New sample (accelerometer in accel-only mode, magnetometer otherwise). */
    static final int SOURCE_DATA_READY = 0x01
    /** Accelerometer vector-magnitude change (configured via raw registers). */
    static final int SOURCE_ACCEL_VECM = 0x02
    /** Freefall / motion ({@code A_FFMT_SRC.ea}). */
    static final int SOURCE_FF_MT = 0x04
    /** Single / double tap ({@code PULSE_SRC.ea}). */
    static final int SOURCE_PULSE = 0x08
    /** Orientation change ({@code PL_STATUS.newlp}). */
    static final int SOURCE_ORIENTATION = 0x10
    /** Transient ({@code TRANSIENT_SRC.ea}). */
    static final int SOURCE_TRANSIENT = 0x20
    /** FIFO overflow or watermark. */
    static final int SOURCE_FIFO = 0x40
    /** WAKE / SLEEP transition. */
    static final int SOURCE_AUTO_SLEEP = 0x80
    /** Operating mode: accelerometer only. */
    static final int MODE_ACCEL_ONLY = 0
    /** Operating mode: magnetometer only. */
    static final int MODE_MAG_ONLY = 1
    /** Operating mode: hybrid (both sensors at half the ODR each). */
    static final int MODE_HYBRID = 2
    /** FIFO disabled. */
    static final int FIFO_DISABLED = 0
    /** FIFO circular mode (oldest sample discarded). */
    static final int FIFO_CIRCULAR = 1
    /** FIFO fill mode (stops accepting when full). */
    static final int FIFO_FILL = 2
    /** FIFO trigger mode. */
    static final int FIFO_TRIGGER = 3
    /** Debounce time steps in ms, indexed [mods][row]; row = dr code, shifted by one in hybrid mode. */
    private static final double[][] STEP_MS = [
        [1.25, 2.5, 5.0, 10.0, 20.0, 20.0, 20.0, 20.0] as double[],
        [1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 80.0, 80.0] as double[],
        [1.25, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5] as double[],
        [1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 160.0, 160.0] as double[]
    ] as double[][]
    /** PULSE_TMLT steps with the pulse low-pass filter off (LTCY / WIND are twice). */
    private static final double[][] PULSE_STEP_NO_LPF_MS = [
        [0.625, 0.625, 1.25, 2.5, 5.0, 5.0, 5.0, 5.0] as double[],
        [0.625, 0.625, 1.25, 2.5, 5.0, 20.0, 20.0, 20.0] as double[],
        [0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625] as double[],
        [0.625, 1.25, 2.5, 5.0, 10.0, 40.0, 40.0, 40.0] as double[]
    ] as double[][]
    /** Orientation trip angles (degrees) and their P_L_THS codes. */
    private static final int[] PL_DEG = [15, 20, 30, 35, 40, 45, 55, 60, 70, 75] as int[]
    private static final int[] PL_CODE = [0x07, 0x09, 0x0C, 0x0D, 0x0F, 0x10, 0x13, 0x14, 0x17, 0x19] as int[]
    /** Auto-SLEEP rates (Hz) and their aslp_rate codes. */
    private static final double[] ASLP_HZ = [50.0, 12.5, 6.25, 1.56] as double[]
    /**
     * Create the driver and run the initialization sequence.
     *
     * @param connection RegisterConnection (I²C) bound to 0x1C-0x1F
     * @throws IOException on bus error or a wrong {@code WHO_AM_I}
     */
    FXOS8700Full(RegisterConnection connection) {
        super(connection)
    }

    // --- Mode / power -----------------------------------------------------------

    /**
     * Poll {@code SYSMOD} until it reads 00 (up to 350 ms).
     *
     * @throws IOException on bus error or if the chip does not reach STANDBY
     */
    private void waitStandby() {
        for (int i = 0; i < 350; i++) {
            if ((readReg(REG_SYSMOD) & 0x03) == 0) {
                return
            }
            sleepMs(1)
        }
        throw new IOException("FXOS8700 did not enter STANDBY")
    }

    /**
     * Enter STANDBY ({@code active} = 0) and wait until {@code SYSMOD} reads 0
     * (up to 300 ms when leaving mag-only / hybrid mode).
     *
     * @throws IOException on bus error or timeout
     */
    void standby() {
        int c1 = readReg(REG_CTRL1)
        writeReg(REG_CTRL1, c1 & ~CTRL1_ACTIVE)
        waitStandby()
    }

    /**
     * Enter ACTIVE ({@code active} = 1) and wait the turn-on time.
     *
     * @throws IOException on bus error
     */
    void activate() {
        int c1 = readReg(REG_CTRL1)
        writeReg(REG_CTRL1, c1 | CTRL1_ACTIVE)
        sleepMs(turnOnMs())
    }

    /**
     * Enter STANDBY if ACTIVE.
     *
     * @return whether the chip was ACTIVE
     * @throws IOException on bus error
     */
    private boolean begin() {
        int c1 = readReg(REG_CTRL1)
        if ((c1 & CTRL1_ACTIVE) != 0) {
            writeReg(REG_CTRL1, c1 & ~CTRL1_ACTIVE)
            waitStandby()
            return true
        }
        return false
    }

    /**
     * Restore ACTIVE if {@code wasActive}.
     *
     * @param wasActive value returned by {@link #begin()}
     * @throws IOException on bus error
     */
    private void end(boolean wasActive) {
        if (wasActive) {
            activate()
        }
    }

    /**
     * Software reset ({@code CTRL_REG2.rst}). The bus error of the
     * unacknowledged write is ignored; every register returns to its reset
     * value. Call {@link #init()} afterwards.
     */
    void reset() {
        softReset()
        this.@mode = 0
        dr = 0
        fsG = 2
        mods = 0
        lnoise = false
        fRead = false
        fifoMode = 0
        hyb = false
    }

    /**
     * Select accelerometer-only, magnetometer-only or hybrid operation.
     *
     * @param mode 0 accelerometer only, 1 magnetometer only, 2 hybrid (also
     *             enables {@code hyb_autoinc_mode})
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code mode} is not 0, 1 or 2
     */
    void setMode(int mode) {
        if (mode < 0 || mode > 2) {
            throw new IllegalArgumentException("mode must be 0, 1 or 2")
        }
        boolean was = begin()
        modify(REG_M_CTRL1, M_CTRL1_HMS_MASK, ([0, 1, 3] as int[])[mode])
        if (mode == 2) {
            modify(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC, M_CTRL2_HYB_AUTOINC)
            hyb = true
        }
        this.@mode = mode
        end(was)
    }

    /**
     * Select the accelerometer full-scale range.
     *
     * @param rangeG 2, 4 or 8 (<i>g</i>)
     * @throws IOException on bus error
     * @throws IllegalArgumentException for any other value, or 8 while low-noise mode is on
     */
    void setRange(int rangeG) {
        if (rangeG != 2 && rangeG != 4 && rangeG != 8) {
            throw new IllegalArgumentException("rangeG must be 2, 4 or 8")
        }
        if (rangeG == 8 && lnoise) {
            throw new IllegalArgumentException("±8 g is not available in low-noise mode")
        }
        modify(REG_XYZ_DATA_CFG, 0x03, rangeG == 2 ? 0 : (rangeG == 4 ? 1 : 2))
        fsG = rangeG
    }

    /**
     * Select the system output data rate (each sensor runs at half this rate
     * in hybrid mode).
     *
     * @param rateHz 800, 400, 200, 100, 50, 12.5, 6.25 or 1.5625 (1.56 accepted)
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an unsupported rate
     */
    void setDataRate(double rateHz) {
        int code = -1
        for (int i = 0; i < ODR_HZ.length; i++) {
            if (Math.abs(ODR_HZ[i] - rateHz) <= 0.01 * ODR_HZ[i]) {
                code = i
            }
        }
        if (code < 0) {
            throw new IllegalArgumentException("unsupported data rate")
        }
        boolean was = begin()
        modify(REG_CTRL1, CTRL1_DR_MASK, code << 3)
        dr = code
        end(was)
    }

    /**
     * Select the accelerometer oversampling mode.
     *
     * @param mode 0 normal, 1 low-noise low-power, 2 high resolution, 3 low power
     * @param sleepMode the same encoding for SLEEP mode, or -1 to leave it unchanged
     * @throws IOException on bus error
     * @throws IllegalArgumentException if a mode is outside 0-3
     */
    void setAccelOversampling(int mode, int sleepMode) {
        if (mode < 0 || mode > 3 || sleepMode < -1 || sleepMode > 3) {
            throw new IllegalArgumentException("oversampling mode must be 0-3")
        }
        boolean was = begin()
        modify(REG_CTRL2, CTRL2_MODS_MASK, mode)
        if (sleepMode >= 0) {
            modify(REG_CTRL2, CTRL2_SMODS_MASK, sleepMode << 3)
        }
        mods = mode
        end(was)
    }

    /**
     * Select the magnetometer oversampling ratio code ({@code m_os},
     * datasheet Table 203).
     *
     * @param ratio 0-7 for active mode
     * @param sleepRatio 0-7 for Auto-SLEEP ({@code m_aslp_os}), or -1 to leave it unchanged
     * @throws IOException on bus error
     * @throws IllegalArgumentException if a value is outside 0-7
     */
    void setMagOversampling(int ratio, int sleepRatio) {
        if (ratio < 0 || ratio > 7 || sleepRatio < -1 || sleepRatio > 7) {
            throw new IllegalArgumentException("oversampling ratio must be 0-7")
        }
        modify(REG_M_CTRL1, M_CTRL1_OS_MASK, ratio << 2)
        if (sleepRatio >= 0) {
            modify(REG_M_CTRL3, 0x70, sleepRatio << 4)
        }
    }

    /**
     * Enable or disable the reduced-noise mode (limits the range to ±4 <i>g</i>).
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     * @throws IllegalArgumentException when enabling at ±8 <i>g</i>
     */
    void setLowNoise(boolean enabled) {
        if (enabled && fsG == 8) {
            throw new IllegalArgumentException("low-noise mode is not available at ±8 g")
        }
        boolean was = begin()
        modify(REG_CTRL1, CTRL1_LNOISE, enabled ? CTRL1_LNOISE : 0)
        lnoise = enabled
        end(was)
    }

    /**
     * Select 14-bit or 8-bit (fast read) accelerometer output.
     *
     * @param bits 14 or 8
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code bits} is not 14 or 8, or the FIFO is on
     */
    void setResolution(int bits) {
        if (bits != 14 && bits != 8) {
            throw new IllegalArgumentException("bits must be 14 or 8")
        }
        if (fifoMode != 0) {
            throw new IllegalArgumentException("resolution cannot change while the FIFO is on")
        }
        boolean was = begin()
        modify(REG_CTRL1, CTRL1_F_READ, bits == 8 ? CTRL1_F_READ : 0)
        fRead = bits == 8
        end(was)
    }

    // --- Data ---------------------------------------------------------------------

    /**
     * Read the raw accelerometer counts.
     *
     * @return signed {@code {x, y, z}} counts (14-bit right-aligned, or 8-bit)
     * @throws IOException on bus error
     */
    int[] readRawAccel() {
        return readAccelCounts()
    }

    /**
     * Read the raw magnetometer counts.
     *
     * @return signed 16-bit {@code {x, y, z}} counts (0.1 µT/LSB)
     * @throws IOException on bus error
     */
    int[] readRawMag() {
        return readMagCounts()
    }

    /**
     * Read both sensors with one burst from 0x01.
     *
     * @return {@code {ax, ay, az, mx, my, mz}} with acceleration in <i>g</i> and field in µT
     * @throws IOException on bus error
     * @throws IllegalStateException if the chip is not in hybrid mode
     */
    double[] readHybrid() {
        if (mode != 2 || !hyb) {
            throw new IllegalStateException("readHybrid needs hybrid mode with hyb_autoinc_mode")
        }
        int nAccel = fRead ? 3 : 6
        byte[] data = connection.read(REG_OUT_X_MSB, nAccel + 6)
        int[] a = decodeAccel(data, 0)
        int[] m = decodeMag(data, nAccel)
        double s = accelScale()
        return [a[0] / s, a[1] / s, a[2] / s, m[0] * 0.1, m[1] * 0.1, m[2] * 0.1] as double[]
    }

    /**
     * Read the die temperature ({@code int8(TEMP) * 0.96}; relative and uncalibrated).
     *
     * @return temperature in °C
     * @throws IOException on bus error
     * @throws IllegalStateException in accelerometer-only mode
     */
    double readTemperature() {
        if (mode == 0) {
            throw new IllegalStateException("temperature needs the magnetometer to be active")
        }
        return Register.toSigned(readReg(REG_TEMP), 8) * 0.96
    }

    /**
     * Whether a new accelerometer sample is ready ({@code STATUS.zyxdr}; not
     * valid while the FIFO is on).
     *
     * @return true if new data is available
     * @throws IOException on bus error
     */
    boolean newAccelData() {
        return (readReg(REG_STATUS) & STATUS_ZYXDR) != 0
    }

    /**
     * Whether a new magnetometer sample is ready ({@code M_DR_STATUS.zyxdr}).
     *
     * @return true if new data is available
     * @throws IOException on bus error
     */
    boolean newMagData() {
        return (readReg(REG_M_DR_STATUS) & STATUS_ZYXDR) != 0
    }

    /**
     * Read the {@code STATUS} byte.
     *
     * @return the status byte
     * @throws IOException on bus error
     */
    int readStatus() {
        return readReg(REG_STATUS)
    }

    /**
     * Read the {@code M_DR_STATUS} byte.
     *
     * @return the status byte
     * @throws IOException on bus error
     */
    int readMagStatus() {
        return readReg(REG_M_DR_STATUS)
    }

    /**
     * Read {@code SYSMOD} bits 1:0.
     *
     * @return 0 standby, 1 wake, 2 sleep
     * @throws IOException on bus error
     */
    int readSystemMode() {
        return readReg(REG_SYSMOD) & 0x03
    }

    /**
     * Select high-pass-filtered output data. The cutoff in Hz depends on the
     * ODR and oversampling mode (datasheet Table 64; effective ODR halved in
     * hybrid mode).
     *
     * @param enabled true to output high-pass-filtered data ({@code hpf_out})
     * @param cutoff {@code sel} code 0-3
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code cutoff} is outside 0-3
     */
    void setHighPassOutput(boolean enabled, int cutoff) {
        if (cutoff < 0 || cutoff > 3) {
            throw new IllegalArgumentException("cutoff must be 0-3")
        }
        boolean was = begin()
        modify(REG_XYZ_DATA_CFG, 0x10, enabled ? 0x10 : 0)
        modify(REG_HP_FILTER_CUTOFF, 0x03, cutoff)
        end(was)
    }

    /**
     * Write the per-axis accelerometer offsets (added to the output, 2 mg/LSB, ±0.256 <i>g</i>).
     *
     * @param xG X offset in <i>g</i>
     * @param yG Y offset in <i>g</i>
     * @param zG Z offset in <i>g</i>
     * @throws IOException on bus error
     */
    void setAccelOffset(double xG, double yG, double zG) {
        byte[] data = new byte[3]
        double[] v = [xG, yG, zG] as double[]
        for (int i = 0; i < 3; i++) {
            data[i] = (byte) Math.max(-128, Math.min(127, (int) Math.round(v[i] / 0.002)))
        }
        boolean was = begin()
        connection.write(REG_OFF_X, data)
        end(was)
    }

    /**
     * Read the accelerometer offsets.
     *
     * @return {@code {x, y, z}} in <i>g</i>
     * @throws IOException on bus error
     */
    double[] getAccelOffset() {
        byte[] d = connection.read(REG_OFF_X, 3)
        return [d[0] * 0.002, d[1] * 0.002, d[2] * 0.002] as double[]
    }

    /**
     * Write the hard-iron offsets (15-bit value in bits 15:1, <i>subtracted</i>
     * from each measurement, 0.1 µT/LSB).
     *
     * @param xUt X offset to subtract, in µT
     * @param yUt Y offset to subtract, in µT
     * @param zUt Z offset to subtract, in µT
     * @throws IOException on bus error
     */
    void setMagOffset(double xUt, double yUt, double zUt) {
        byte[] data = new byte[6]
        double[] v = [xUt, yUt, zUt] as double[]
        for (int i = 0; i < 3; i++) {
            int value = Math.max(-16384, Math.min(16383, (int) Math.round(v[i] / 0.1)))
            int word = (value << 1) & 0xFFFF
            data[2 * i] = (byte) (word >> 8)
            data[2 * i + 1] = (byte) (word & 0xFF)
        }
        connection.write(REG_M_OFF_X_MSB, data)
    }

    /**
     * Read the hard-iron offsets.
     *
     * @return {@code {x, y, z}} in µT
     * @throws IOException on bus error
     */
    double[] getMagOffset() {
        byte[] d = connection.read(REG_M_OFF_X_MSB, 6)
        double[] out = new double[3]
        for (int i = 0; i < 3; i++) {
            int word = ((d[2 * i] & 0xFF) << 8) | (d[2 * i + 1] & 0xFF)
            out[i] = (Register.toSigned(word, 16) >> 1) * 0.1
        }
        return out
    }

    /**
     * Select raw magnetometer output ({@code m_raw}).
     *
     * @param raw true = the hard-iron offsets are not applied to the output
     * @throws IOException on bus error
     */
    void setMagRaw(boolean raw) {
        modify(REG_M_CTRL3, 0x80, raw ? 0x80 : 0)
    }

    /**
     * Enable or disable hardware hard-iron auto-calibration ({@code m_acal}).
     * Enabling also clears {@code m_raw} and enables min/max tracking. Rotate
     * the board through all orientations while on; the offsets stay in
     * {@code M_OFF_*} afterwards (NXP AN4399).
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     */
    void setMagAutoCalibration(boolean enabled) {
        if (enabled) {
            modify(REG_M_CTRL3, 0x80, 0)
            boolean was = begin()
            modify(REG_M_CTRL2, 0x10, 0)
            end(was)
        }
        modify(REG_M_CTRL1, M_CTRL1_ACAL, enabled ? M_CTRL1_ACAL : 0)
    }

    /**
     * Read the tracked magnetic minimum and maximum.
     *
     * @return {@code {minx, miny, minz, maxx, maxy, maxz}} in µT
     * @throws IOException on bus error
     */
    double[] readMagMinMax() {
        int[] mn = decodeMag(connection.read(REG_MIN_X_MSB, 6), 0)
        int[] mx = decodeMag(connection.read(REG_MAX_X_MSB, 6), 0)
        return [mn[0] * 0.1, mn[1] * 0.1, mn[2] * 0.1, mx[0] * 0.1, mx[1] * 0.1, mx[2] * 0.1] as double[]
    }

    /**
     * Reset the min/max registers to 0x7FFF / 0x8000 ({@code m_maxmin_rst}).
     *
     * @throws IOException on bus error
     */
    void resetMagMinMax() {
        modify(REG_M_CTRL2, 0x04, 0x04)
    }

    /**
     * Select the automatic magnetic reset (degauss) period.
     *
     * @param mode 0 every ODR cycle, 1 every 16, 2 every 512, 3 disabled
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code mode} is outside 0-3
     */
    void setMagReset(int mode) {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("mode must be 0-3")
        }
        boolean was = begin()
        modify(REG_M_CTRL2, 0x03, mode)
        end(was)
    }

    /**
     * Trigger a one-shot magnetic reset ({@code m_rst}).
     *
     * @throws IOException on bus error
     */
    void triggerMagReset() {
        modify(REG_M_CTRL1, M_CTRL1_RST, M_CTRL1_RST)
    }

    /**
     * Take one magnetic measurement in STANDBY ({@code m_ost}); ACTIVE is restored.
     *
     * @return {@code {x, y, z}} in µT
     * @throws IOException on bus error or a 1 s timeout
     * @throws IllegalStateException in accelerometer-only mode
     */
    double[] measureMagOnce() {
        if (mode == 0) {
            throw new IllegalStateException("one-shot measurement needs the magnetometer")
        }
        boolean was = begin()
        modify(REG_M_CTRL1, M_CTRL1_OST, M_CTRL1_OST)
        boolean done = false
        for (int i = 0; i < 1000; i++) {
            if ((readReg(REG_M_CTRL1) & M_CTRL1_OST) == 0) {
                done = true
                break
            }
            sleepMs(1)
        }
        if (!done) {
            end(was)
            throw new IOException("magnetic one-shot measurement timed out")
        }
        double[] value = readMag()
        end(was)
        return value
    }

    /**
     * Compute the tilt-compensated compass heading from one accelerometer and
     * one magnetometer sample (chip frame: X forward, Y right, Z down; verify
     * the axis signs on hardware).
     *
     * @param declinationDeg magnetic declination added to the result
     * @return heading in degrees, [0, 360)
     * @throws IOException on bus error
     */
    double heading(double declinationDeg) {
        double[] a
        double[] m
        if (mode == 2 && hyb) {
            double[] h = readHybrid()
            a = [h[0], h[1], h[2]] as double[]
            m = [h[3], h[4], h[5]] as double[]
        } else {
            a = readAccel()
            m = readMag()
        }
        double roll = Math.atan2(a[1], a[2])
        double pitch = Math.atan2(-a[0], a[1] * Math.sin(roll) + a[2] * Math.cos(roll))
        double mx2 = m[0] * Math.cos(pitch) + m[2] * Math.sin(pitch)
        double my2 = m[0] * Math.sin(roll) * Math.sin(pitch) + m[1] * Math.cos(roll)
                - m[2] * Math.sin(roll) * Math.cos(pitch)
        double deg = Math.toDegrees(Math.atan2(-my2, mx2)) + declinationDeg
        deg %= 360.0
        if (deg < 0) {
            deg += 360.0
        }
        return deg
    }

    // --- FIFO ------------------------------------------------------------------------

    /**
     * Configure the 32-sample accelerometer FIFO. Changing between two enabled
     * modes goes through disabled. The FIFO holds accelerometer data only.
     *
     * @param mode 0 disabled, 1 circular, 2 fill, 3 trigger
     * @param watermark 0-63 (0 disables the flag)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if an argument is out of range
     */
    void setFifo(int mode, int watermark) {
        if (mode < 0 || mode > 3 || watermark < 0 || watermark > 63) {
            throw new IllegalArgumentException("invalid FIFO mode or watermark")
        }
        boolean was = begin()
        if (fifoMode != 0 && mode != 0 && mode != fifoMode) {
            writeReg(REG_F_SETUP, 0x00)
        }
        writeReg(REG_F_SETUP, (mode << 6) | watermark)
        fifoMode = mode
        end(was)
    }

    /**
     * Select the FIFO trigger sources ({@code TRIG_CFG}).
     *
     * @param transientTrigger transient trigger
     * @param orientation orientation trigger
     * @param pulse tap trigger
     * @param motion freefall / motion trigger
     * @throws IOException on bus error
     */
    void setFifoTrigger(boolean transientTrigger, boolean orientation, boolean pulse, boolean motion)
            {
        int value = (transientTrigger ? 0x20 : 0) | (orientation ? 0x10 : 0) | (pulse ? 0x08 : 0)
                | (motion ? 0x04 : 0)
        boolean was = begin()
        writeReg(REG_TRIG_CFG, value)
        end(was)
    }

    /**
     * Number of samples in the FIFO.
     *
     * @return 0-32
     * @throws IOException on bus error
     */
    int fifoLevel() {
        return readReg(REG_STATUS) & 0x3F
    }

    /**
     * Decode {@code F_STATUS} (clears {@link #SOURCE_FIFO}).
     *
     * @return {@code {overflow, watermark, count}} (the flags are 0 or 1)
     * @throws IOException on bus error
     */
    int[] readFifoStatus() {
        int v = readReg(REG_STATUS)
        return [(v >> 7) & 1, (v >> 6) & 1, v & 0x3F] as int[]
    }

    /**
     * Drain accelerometer samples from the FIFO, one 6-byte (3 in 8-bit mode)
     * transaction per sample so the hybrid auto-increment jump to the
     * magnetometer never matters.
     *
     * @param count maximum number of samples; zero or negative for all
     * @return the samples as {@code {x, y, z}} triples in <i>g</i>
     * @throws IOException on bus error
     */
    double[][] readFifo(int count) {
        int n = fifoLevel()
        if (count > 0 && count < n) {
            n = count
        }
        double s = accelScale()
        double[][] out = new double[n][]
        for (int i = 0; i < n; i++) {
            int[] c = readAccelCounts()
            out[i] = [c[0] / s, c[1] / s, c[2] / s] as double[]
        }
        return out
    }

    /**
     * Enable or disable the FIFO gate ({@code fifo_gate}).
     *
     * @param enabled true to block FIFO input around WAKE/SLEEP transitions
     * @throws IOException on bus error
     */
    void setFifoGate(boolean enabled) {
        boolean was = begin()
        modify(REG_CTRL3, 0x80, enabled ? 0x80 : 0)
        end(was)
    }

    // --- Detectors -----------------------------------------------------------------------

    private int row() {
        return Math.min(dr + (mode == 2 ? 1 : 0), 7)
    }

    private double stepMs() {
        return STEP_MS[mods][row()]
    }

    private double pulseStepMs(boolean lpf) {
        return lpf ? STEP_MS[mods][row()] : PULSE_STEP_NO_LPF_MS[mods][row()]
    }

    private static int threshold(double thresholdG) {
        return Math.max(0, Math.min(127, (int) Math.round(thresholdG / 0.063)))
    }

    private static int count(double timeMs, double stepMs) {
        return Math.max(0, Math.min(255, (int) Math.round(timeMs / stepMs)))
    }

    /**
     * Configure the freefall / motion detector ({@code A_FFMT_*}).
     *
     * @param motion true = motion (any enabled axis above the threshold), false = freefall (all below)
     * @param axes axis mask, X = 1, Y = 2, Z = 4
     * @param thresholdG threshold in <i>g</i> (0.063 g/LSB)
     * @param debounceMs debounce time in ms
     * @param latch true to latch the event until {@code A_FFMT_SRC} is read
     * @param debounceClear true to clear (instead of decrement) the debounce counter when the condition is lost
     * @throws IOException on bus error
     */
    void setFreefallMotion(boolean motion, int axes, double thresholdG, double debounceMs,
                                  boolean latch, boolean debounceClear) {
        int cfg = (latch ? 0x80 : 0) | (motion ? 0x40 : 0) | ((axes & 7) << 3)
        int ths = (debounceClear ? 0x80 : 0) | threshold(thresholdG)
        int cnt = count(debounceMs, stepMs())
        boolean was = begin()
        writeReg(REG_A_FFMT_CFG, cfg)
        writeReg(REG_A_FFMT_THS, ths)
        writeReg(REG_A_FFMT_COUNT, cnt)
        end(was)
    }

    /**
     * Read {@code A_FFMT_SRC} (clears the flags when latched).
     *
     * @return the source byte
     * @throws IOException on bus error
     */
    int readMotionSource() {
        return readReg(REG_A_FFMT_SRC)
    }

    /**
     * Configure the transient detector ({@code TRANSIENT_*}).
     *
     * @param axes axis mask, X = 1, Y = 2, Z = 4
     * @param thresholdG threshold in <i>g</i>
     * @param debounceMs debounce time in ms
     * @param bypassHpf true to bypass the high-pass filter (motion-like behaviour)
     * @param latch true to latch the event until {@code TRANSIENT_SRC} is read
     * @throws IOException on bus error
     */
    void setTransient(int axes, double thresholdG, double debounceMs, boolean bypassHpf,
                             boolean latch) {
        int cfg = (latch ? 0x10 : 0) | ((axes & 7) << 1) | (bypassHpf ? 1 : 0)
        int cnt = count(debounceMs, stepMs())
        boolean was = begin()
        writeReg(REG_TRANSIENT_CFG, cfg)
        writeReg(REG_TRANSIENT_THS, threshold(thresholdG))
        writeReg(REG_TRANSIENT_COUNT, cnt)
        end(was)
    }

    /**
     * Read {@code TRANSIENT_SRC} — <b>clears</b> the interrupt.
     *
     * @return the source byte
     * @throws IOException on bus error
     */
    int readTransientSource() {
        return readReg(REG_TRANSIENT_SRC)
    }

    /**
     * Configure tap (pulse) detection ({@code PULSE_*}) with the default
     * thresholds (0.5 <i>g</i>), 30 ms time limit, 100 ms latency, 300 ms window
     * and both filters on.
     *
     * @param single axis mask (X = 1, Y = 2, Z = 4) for single taps
     * @param doubleTap axis mask for double taps
     * @throws IOException on bus error
     */
    void setPulse(int single, int doubleTap) {
        setPulse(single, doubleTap, [0.5, 0.5, 0.5] as double[], 30.0, 100.0, 300.0,
                false, false, true, true)
    }

    /**
     * Configure tap (pulse) detection ({@code PULSE_*}).
     *
     * @param single axis mask (X = 1, Y = 2, Z = 4) for single taps
     * @param doubleTap axis mask for double taps
     * @param thresholdG {@code {x, y, z}} thresholds in <i>g</i> (0.063 g/LSB)
     * @param timeLimitMs pulse time limit in ms
     * @param latencyMs latency between the taps in ms
     * @param windowMs window for the second tap in ms
     * @param latch true to latch until {@code PULSE_SRC} is read
     * @param doubleAbort true to abort double-tap detection on a pulse during the latency window
     * @param lpf true to enable the pulse low-pass filter
     * @param hpf true to enable the pulse high-pass filter
     * @throws IOException on bus error
     */
    void setPulse(int single, int doubleTap, double[] thresholdG, double timeLimitMs,
                         double latencyMs, double windowMs, boolean latch, boolean doubleAbort,
                         boolean lpf, boolean hpf) {
        int cfg = (doubleAbort ? 0x80 : 0) | (latch ? 0x40 : 0)
        for (int i = 0; i < 3; i++) {
            if (((single >> i) & 1) != 0) {
                cfg |= 1 << (2 * i)
            }
            if (((doubleTap >> i) & 1) != 0) {
                cfg |= 2 << (2 * i)
            }
        }
        double step = pulseStepMs(lpf)
        int tmlt = count(timeLimitMs, step)
        int ltcy = count(latencyMs, 2 * step)
        int wind = count(windowMs, 2 * step)
        byte[] ths = [(byte) threshold(thresholdG[0]), (byte) threshold(thresholdG[1]),
                      (byte) threshold(thresholdG[2])] as byte[]
        boolean was = begin()
        writeReg(REG_PULSE_CFG, cfg)
        connection.write(REG_PULSE_THSX, ths)
        writeReg(REG_PULSE_TMLT, tmlt)
        writeReg(REG_PULSE_LTCY, ltcy)
        writeReg(REG_PULSE_WIND, wind)
        modify(REG_HP_FILTER_CUTOFF, 0x30, (hpf ? 0 : 0x20) | (lpf ? 0x10 : 0))
        end(was)
    }

    /**
     * Read {@code PULSE_SRC} — <b>clears</b> the interrupt.
     *
     * @return the source byte
     * @throws IOException on bus error
     */
    int readPulseSource() {
        return readReg(REG_PULSE_SRC)
    }

    /**
     * Configure orientation (portrait / landscape) detection.
     *
     * @param enabled true to enable the detector ({@code pl_en})
     * @param debounceMs debounce time in ms
     * @param thresholdDeg trip angle: 15, 20, 30, 35, 40, 45, 55, 60, 70 or 75
     * @param hysteresis {@code hys} code 0-7
     * @param zLock Z-lock angle code 0-7
     * @param backFront back/front trip code 0-3
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an invalid argument or when {@code pl_ths + hys} is outside 1-31
     */
    void setOrientation(boolean enabled, double debounceMs, int thresholdDeg, int hysteresis,
                               int zLock, int backFront) {
        int ths = -1
        for (int i = 0; i < PL_DEG.length; i++) {
            if (PL_DEG[i] == thresholdDeg) {
                ths = PL_CODE[i]
            }
        }
        if (ths < 0) {
            throw new IllegalArgumentException("unsupported thresholdDeg")
        }
        if (hysteresis < 0 || hysteresis > 7 || zLock < 0 || zLock > 7 || backFront < 0 || backFront > 3) {
            throw new IllegalArgumentException("hysteresis/zLock/backFront out of range")
        }
        if (ths + hysteresis < 1 || ths + hysteresis > 31) {
            throw new IllegalArgumentException("pl_ths + hys must be within 1-31")
        }
        int cnt = count(debounceMs, stepMs())
        boolean was = begin()
        writeReg(REG_PL_CFG, 0x80 | (enabled ? 0x40 : 0))
        writeReg(REG_PL_COUNT, cnt)
        writeReg(REG_PL_THS, (ths << 3) | hysteresis)
        writeReg(REG_PL_BF_ZCOMP, (backFront << 6) | zLock)
        end(was)
    }

    /**
     * Decode {@code PL_STATUS} — <b>clears</b> {@code newlp}.
     *
     * @return {@code {changed, lockout, portraitLandscape, back}} where the flags are 0 or 1 and
     *         {@code portraitLandscape} is 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left
     * @throws IOException on bus error
     */
    int[] readOrientation() {
        int v = readReg(REG_PL_STATUS)
        return [(v >> 7) & 1, (v >> 6) & 1, (v >> 1) & 0x03, v & 1] as int[]
    }

    /**
     * Configure Auto-SLEEP / WAKE.
     *
     * @param enabled true to enable Auto-SLEEP ({@code slpe})
     * @param sleepRateHz 50, 12.5, 6.25 or 1.56 (halved in hybrid mode)
     * @param timeoutS inactivity time before SLEEP in seconds
     * @param wakeSources mask of {@link #SOURCE_TRANSIENT}, {@link #SOURCE_ORIENTATION},
     *                    {@link #SOURCE_PULSE}, {@link #SOURCE_FF_MT} and {@link #SOURCE_ACCEL_VECM}
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an unsupported rate or source
     */
    void setAutoSleep(boolean enabled, double sleepRateHz, double timeoutS, int wakeSources)
            {
        int code = -1
        for (int i = 0; i < ASLP_HZ.length; i++) {
            if (Math.abs(ASLP_HZ[i] - sleepRateHz) <= 0.01 * ASLP_HZ[i]) {
                code = i
            }
        }
        if (code < 0) {
            throw new IllegalArgumentException("unsupported sleep rate")
        }
        if ((wakeSources & ~0x3E) != 0) {
            throw new IllegalArgumentException("unsupported wake source")
        }
        double step = dr == 7 ? 0.64 : 0.32
        int cnt = count(timeoutS, step)
        boolean was = begin()
        modify(REG_CTRL1, CTRL1_ASLP_MASK, code << 6)
        writeReg(REG_ASLP_COUNT, cnt)
        modify(REG_CTRL2, CTRL2_SLPE, enabled ? CTRL2_SLPE : 0)
        modify(REG_CTRL3, 0x7C, (wakeSources & 0x3E) << 1)
        end(was)
    }

    // --- Interrupts ----------------------------------------------------------------------------

    /**
     * Configure the INT1 / INT2 polarity and drive ({@code ipol}, {@code pp_od}).
     *
     * @param activeHigh true for active-high
     * @param openDrain true for open-drain (needs an external pull-up)
     * @throws IOException on bus error
     */
    void setInterruptPins(boolean activeHigh, boolean openDrain) {
        boolean was = begin()
        modify(REG_CTRL3, 0x03, (activeHigh ? 0x02 : 0) | (openDrain ? 0x01 : 0))
        end(was)
    }

    /**
     * Enable an interrupt source and route it to INT1 or INT2. Only routes the
     * source; the detector behind it is configured with the {@code set*} methods.
     *
     * @param source a mask of the {@code SOURCE_*} constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IOException on bus error
     * @throws IllegalArgumentException if {@code pin} is not 1 or 2
     */
    void enableInterrupt(int source, int pin) {
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("pin must be 1 or 2")
        }
        boolean was = begin()
        modify(REG_CTRL4, source, source)
        modify(REG_CTRL5, source, pin == 1 ? source : 0)
        end(was)
    }

    /**
     * Disable an interrupt source (clears its {@code int_en_*} bit).
     *
     * @param source a mask of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    void disableInterrupt(int source) {
        boolean was = begin()
        modify(REG_CTRL4, source, 0)
        end(was)
    }

    /**
     * Read {@code INT_SOURCE} and clear the pending sources: for every set bit
     * except {@link #SOURCE_DATA_READY} and {@link #SOURCE_ACCEL_VECM} the
     * matching source register is read so the flag (and the pin) clears.
     *
     * @return a mask of the {@code SOURCE_*} constants
     * @throws IOException on bus error
     */
    int pollInterrupt() {
        int status = readReg(REG_INT_SOURCE)
        int[][] clear = [
            [SOURCE_AUTO_SLEEP, REG_SYSMOD] as int[],
            [SOURCE_FIFO, REG_STATUS] as int[],
            [SOURCE_TRANSIENT, REG_TRANSIENT_SRC] as int[],
            [SOURCE_ORIENTATION, REG_PL_STATUS] as int[],
            [SOURCE_PULSE, REG_PULSE_SRC] as int[],
            [SOURCE_FF_MT, REG_A_FFMT_SRC] as int[]
        ] as int[][]
        for (int[] e in clear) {
            if ((status & e[0]) != 0) {
                readReg(e[1])
            }
        }
        return status
    }

    // --- Escape hatch / self-test ------------------------------------------------------------------

    /**
     * Read raw register bytes (vector-magnitude, magnetic-threshold and
     * per-axis FFMT registers). No STANDBY handling.
     *
     * @param reg start register address
     * @param length number of bytes
     * @return the register contents
     * @throws IOException on bus error
     */
    byte[] readRegister(int reg, int length) {
        return connection.read(reg, length)
    }

    /**
     * Write raw register bytes. No STANDBY handling.
     *
     * @param reg start register address
     * @param data bytes to write
     * @throws IOException on bus error
     */
    void writeRegister(int reg, byte[] data) {
        connection.write(reg, data)
    }

    /**
     * Average 8 accelerometer readings (accel-only, 14-bit).
     *
     * @return the mean counts {@code {x, y, z}} (floor division)
     * @throws IOException on bus error
     */
    private int[] averageCounts() {
        int sx = 0
        int sy = 0
        int sz = 0
        for (int i = 0; i < 8; i++) {
            sleepMs(12)
            int[] c = decodeAccel(connection.read(REG_OUT_X_MSB, 6), 0)
            sx += c[0]
            sy += c[1]
            sz += c[2]
        }
        return [Math.floorDiv(sx, 8), Math.floorDiv(sy, 8), Math.floorDiv(sz, 8)] as int[]
    }

    /**
     * Run the accelerometer self-test and restore the configuration. STANDBY,
     * ±2 <i>g</i>, 100 Hz accelerometer-only, normal oversampling; the
     * difference of two 8-sample averages (before / after {@code st}) is
     * checked against the datasheet minimum X &gt;= +192, Y &gt;= +270,
     * Z &gt;= +1275 counts.
     *
     * @return {@code {ok, dx, dy, dz}} with {@code ok} 1 or 0 and the differences in 14-bit counts
     * @throws IOException on bus error
     */
    int[] selfTest() {
        int sCtrl1 = readReg(REG_CTRL1)
        int sCtrl2 = readReg(REG_CTRL2)
        int sCfg = readReg(REG_XYZ_DATA_CFG)
        int sM1 = readReg(REG_M_CTRL1)
        int cMode = mode
        int cDr = dr
        int cFs = fsG
        int cMods = mods
        boolean cLnoise = lnoise
        boolean cFRead = fRead
        standby()
        writeReg(REG_XYZ_DATA_CFG, 0x00)
        writeReg(REG_M_CTRL1, sM1 & ~M_CTRL1_HMS_MASK & 0xFF)
        writeReg(REG_CTRL2, 0x00)
        this.@mode = 0
        dr = 3
        fsG = 2
        mods = 0
        lnoise = false
        fRead = false
        writeReg(REG_CTRL1, (3 << 3) | CTRL1_ACTIVE)
        sleepMs(turnOnMs())
        int[] before = averageCounts()
        writeReg(REG_CTRL2, CTRL2_ST)
        sleepMs(25)
        int[] after = averageCounts()
        writeReg(REG_CTRL2, 0x00)
        standby()
        writeReg(REG_XYZ_DATA_CFG, sCfg)
        writeReg(REG_M_CTRL1, sM1)
        writeReg(REG_CTRL2, sCtrl2)
        this.@mode = cMode
        dr = cDr
        fsG = cFs
        mods = cMods
        lnoise = cLnoise
        fRead = cFRead
        writeReg(REG_CTRL1, sCtrl1)
        if ((sCtrl1 & CTRL1_ACTIVE) != 0) {
            sleepMs(turnOnMs())
        }
        int dx = after[0] - before[0]
        int dy = after[1] - before[1]
        int dz = after[2] - before[2]
        return [(dx >= 192 && dy >= 270 && dz >= 1275) ? 1 : 0, dx, dy, dz] as int[]
    }
}
