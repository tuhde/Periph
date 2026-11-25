package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * MMA8451Q full interface — extends {@link MMA8451QMinimal} with the complete
 * chip functionality.
 *
 * Adds:
 * - Full-scale range (±2/±4/±8 *g*), output data rate (1.56 Hz – 800 Hz),
 *   oversampling mode, low-noise mode and 14-/8-bit resolution selection
 * - STANDBY / ACTIVE control, software reset and electrostatic self-test
 * - Raw count reading, status and system-mode readback
 * - High-pass-filtered output and per-axis offset correction
 * - 32-sample FIFO with watermark, trigger sources and gate
 * - Freefall / motion, transient, tap and orientation detectors
 * - Auto-SLEEP / Auto-WAKE with selectable sleep rate and wake sources
 * - Interrupt enable / disable, routing, polarity and drive control, plus edge
 *   subscription
 *
 * Methods that write a STANDBY-only register take care of the mode switch
 * themselves: they clear `ACTIVE`, write the register, restore `ACTIVE` and
 * wait for the turn-on time.
 *
 * {@link #onInterrupt(IntConsumer)} uses `connection.intPin()` if wired (falling
 * edge — the INT pins are active-low by default), otherwise a 5 ms polling
 * thread delivers the events.
 */
@CompileStatic
class MMA8451QFull extends MMA8451QMinimal {

    /** Interrupt source constants — the INT_SOURCE / CTRL_REG4 / CTRL_REG5 bit positions. */
    static final int SOURCE_DATA_READY  = 0x01
    static final int SOURCE_FF_MT       = 0x04
    static final int SOURCE_PULSE       = 0x08
    static final int SOURCE_ORIENTATION = 0x10
    static final int SOURCE_TRANSIENT   = 0x20
    static final int SOURCE_FIFO        = 0x40
    static final int SOURCE_AUTO_SLEEP  = 0x80

    /** All valid interrupt sources combined. */
    static final int SOURCE_ALL = SOURCE_DATA_READY | SOURCE_FF_MT | SOURCE_PULSE |
            SOURCE_ORIENTATION | SOURCE_TRANSIENT | SOURCE_FIFO | SOURCE_AUTO_SLEEP

    /** Full-scale range codes in XYZ_DATA_CFG (0x0E) bits 1:0. */
    static final int[] FS_CODES = [0x00, 0x01, 0x02] as int[]

    /** Supported output data rates in Hz, in code order. */
    static final double[] ODR_ORDER = [800.0d, 400.0d, 200.0d, 100.0d, 50.0d, 12.5d, 6.25d, 1.56d] as double[]

    /** Output data rate codes in CTRL_REG1 (0x2A) bits 5:3. */
    static final int[] DR_CODES = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07] as int[]

    /** Supported Auto-SLEEP output rates in Hz, in code order. */
    static final double[] ASLP_RATE_ORDER = [50.0d, 12.5d, 6.25d, 1.56d] as double[]

    /** Auto-SLEEP output rate codes in CTRL_REG1 (0x2A) bits 7:6. */
    static final int[] ASLP_RATE_CODES = [0x00, 0x01, 0x02, 0x03] as int[]

    /** Oversampling mode codes in CTRL_REG2 (0x2B) bits 1:0 and 4:3. */
    static final int[] MODS_CODES = [0x00, 0x01, 0x02, 0x03] as int[]

    /**
     * Debounce step in ms, indexed by [ODR index][oversampling mode].
     * Rows: 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz.
     * Columns: normal, low-noise low-power, high resolution, low power.
     */
    static final double[][] DEBOUNCE_STEPS = [
            [1.25d, 1.25d, 1.25d, 1.25d] as double[],
            [2.5d, 2.5d, 2.5d, 2.5d] as double[],
            [5.0d, 5.0d, 2.5d, 5.0d] as double[],
            [10.0d, 10.0d, 2.5d, 10.0d] as double[],
            [20.0d, 20.0d, 2.5d, 20.0d] as double[],
            [20.0d, 80.0d, 2.5d, 80.0d] as double[],
            [20.0d, 80.0d, 2.5d, 160.0d] as double[],
            [20.0d, 80.0d, 2.5d, 160.0d] as double[],
    ] as double[][]

    /** Pulse time-limit step in ms, indexed by [ODR index][oversampling mode], LPF enabled. */
    static final double[][] PULSE_TMLT_LPF = [
            [1.25d, 1.25d, 1.25d, 1.25d] as double[],
            [2.5d, 2.5d, 2.5d, 2.5d] as double[],
            [5.0d, 5.0d, 2.5d, 5.0d] as double[],
            [10.0d, 10.0d, 2.5d, 10.0d] as double[],
            [20.0d, 20.0d, 2.5d, 20.0d] as double[],
            [20.0d, 80.0d, 2.5d, 80.0d] as double[],
            [20.0d, 80.0d, 2.5d, 160.0d] as double[],
            [20.0d, 80.0d, 2.5d, 160.0d] as double[],
    ] as double[][]

    /** Pulse latency / window step in ms, indexed by [ODR index][oversampling mode], LPF enabled. */
    static final double[][] PULSE_LTCY_LPF = [
            [2.5d, 2.5d, 2.5d, 2.5d] as double[],
            [5.0d, 5.0d, 5.0d, 5.0d] as double[],
            [10.0d, 10.0d, 5.0d, 10.0d] as double[],
            [20.0d, 20.0d, 5.0d, 20.0d] as double[],
            [40.0d, 40.0d, 5.0d, 40.0d] as double[],
            [40.0d, 160.0d, 5.0d, 160.0d] as double[],
            [40.0d, 160.0d, 5.0d, 320.0d] as double[],
            [40.0d, 160.0d, 5.0d, 320.0d] as double[],
    ] as double[][]

    /** Pulse time-limit step in ms, indexed by [ODR index][oversampling mode], LPF disabled. */
    static final double[][] PULSE_TMLT_NO_LPF = [
            [0.625d, 0.625d, 0.625d, 0.625d] as double[],
            [0.625d, 0.625d, 0.625d, 1.25d] as double[],
            [1.25d, 1.25d, 0.625d, 2.5d] as double[],
            [2.5d, 2.5d, 0.625d, 5.0d] as double[],
            [5.0d, 5.0d, 0.625d, 10.0d] as double[],
            [5.0d, 20.0d, 0.625d, 40.0d] as double[],
            [5.0d, 20.0d, 0.625d, 40.0d] as double[],
            [5.0d, 20.0d, 0.625d, 40.0d] as double[],
    ] as double[][]

    /** Pulse latency / window step in ms, indexed by [ODR index][oversampling mode], LPF disabled. */
    static final double[][] PULSE_LTCY_NO_LPF = [
            [1.25d, 1.25d, 1.25d, 1.25d] as double[],
            [1.25d, 1.25d, 1.25d, 2.5d] as double[],
            [2.5d, 2.5d, 1.25d, 5.0d] as double[],
            [5.0d, 5.0d, 1.25d, 10.0d] as double[],
            [10.0d, 10.0d, 1.25d, 20.0d] as double[],
            [10.0d, 40.0d, 1.25d, 80.0d] as double[],
            [10.0d, 40.0d, 1.25d, 80.0d] as double[],
            [10.0d, 40.0d, 1.25d, 80.0d] as double[],
    ] as double[][]

    /**
     * Portrait / landscape trip angles and their `P_L_THS<4:0>` codes
     * (datasheet Table 32).
     */
    static final int[] ORIENTATION_ANGLES = [15, 20, 30, 35, 40, 45, 55, 60, 70, 75] as int[]
    static final int[] ORIENTATION_THRESHOLDS = [0x07, 0x09, 0x0C, 0x0D, 0x0F,
            0x10, 0x13, 0x14, 0x17, 0x19] as int[]

    /** Detector threshold LSB size in *g* (7-bit thresholds, 0.063 g/LSB). */
    static final double THRESHOLD_LSB_G = 0.063d

    /** Offset register LSB size in *g* (2 mg/LSB). */
    static final double OFFSET_LSB_G = 0.002d

    /** Self-test typical count deltas per axis (datasheet Table 2). */
    static final int[] SELF_TEST_TYPICAL = [181, 255, 1680] as int[]

    /**
     * Decoded FIFO status, as returned by {@link #readFifoStatus()}.
     */
    static class FifoStatus {
        /** {@code true} when the FIFO overflowed. */
        final boolean overflow
        /** {@code true} when the FIFO count reached the watermark. */
        final boolean watermark
        /** Number of samples in the FIFO (0–32). */
        final int count

        FifoStatus(boolean overflow, boolean watermark, int count) {
            this.overflow = overflow
            this.watermark = watermark
            this.count = count
        }

        /** @return {@code true} when the FIFO overflowed. */
        boolean isOverflow() { return overflow }

        /** @return {@code true} when the FIFO count reached the watermark. */
        boolean isWatermark() { return watermark }

        /** @return the number of samples in the FIFO (0–32). */
        int getCount() { return count }
    }

    /**
     * Decoded orientation status, as returned by {@link #readOrientation()}.
     */
    static class Orientation {
        /** {@code true} when the orientation changed. */
        final boolean changed
        /** {@code true} while the orientation is frozen (Z-tilt lockout). */
        final boolean lockout
        /** 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left. */
        final int portraitLandscape
        /** {@code true} when the device is face down. */
        final boolean back

        Orientation(boolean changed, boolean lockout, int portraitLandscape, boolean back) {
            this.changed = changed
            this.lockout = lockout
            this.portraitLandscape = portraitLandscape
            this.back = back
        }

        /** @return {@code true} when the orientation changed. */
        boolean isChanged() { return changed }

        /** @return {@code true} while the orientation is frozen. */
        boolean isLockout() { return lockout }

        /** @return the portrait/landscape code. */
        int getPortraitLandscape() { return portraitLandscape }

        /** @return {@code true} when the device is face down. */
        boolean isBack() { return back }
    }

    /**
     * Result of the electrostatic self-test, as returned by {@link #selfTest()}.
     */
    static class SelfTestResult {
        /** {@code true} when every axis moved by more than half its typical delta. */
        final boolean ok
        /** Measured count delta on the X axis. */
        final int dx
        /** Measured count delta on the Y axis. */
        final int dy
        /** Measured count delta on the Z axis. */
        final int dz

        SelfTestResult(boolean ok, int dx, int dy, int dz) {
            this.ok = ok
            this.dx = dx
            this.dy = dy
            this.dz = dz
        }

        /** @return {@code true} when the self-test passed. */
        boolean isOk() { return ok }

        /** @return the measured count delta on the X axis. */
        int getDx() { return dx }

        /** @return the measured count delta on the Y axis. */
        int getDy() { return dy }

        /** @return the measured count delta on the Z axis. */
        int getDz() { return dz }
    }

    /** Callback invoked with the interrupt status byte. */
    private volatile IntConsumer callback

    /** INT-line pin currently armed, or {@code null} if polling. */
    private InputPin subscribedPin

    /** {@code true} while the polling-fallback thread is running. */
    private volatile boolean polling = false

    /** Background daemon thread used only when no {@link InputPin} is available. */
    private Thread pollThread

    /**
     * Fixed edge-handler instance, reused across onEdge()/offEdge() calls.
     *
     * A method reference is not guaranteed by the JLS to evaluate to the same
     * object each time, so {@code offEdge()} could silently fail to remove the
     * handler {@code onEdge()} registered.
     */
    private final EdgeHandler edgeHandler = this.&handleEdge

    MMA8451QFull(RegisterConnection connection) {
        super(connection)
    }

    // =========================================================================
    // Configuration
    // =========================================================================

    /**
     * Set the full-scale measurement range.
     *
     * @param rangeG one of 2, 4 or 8 (*g*)
     * @throws IllegalArgumentException if the range is not 2, 4 or 8 *g*, or if
     *         ±8 *g* is requested while low-noise mode is enabled
     */
    void setRange(int rangeG) {
        if (rangeG != 2 && rangeG != 4 && rangeG != 8) {
            throw new IllegalArgumentException("range must be one of 2, 4, 8 g")
        }
        if (rangeG == 8 && lowNoiseEnabled) {
            throw new IllegalArgumentException("+/-8 g is not available in low-noise mode")
        }
        int xyzCfg = readReg(REG_XYZ_DATA_CFG)
        int fsCode = rangeG == 4 ? FS_CODES[1] : (rangeG == 8 ? FS_CODES[2] : FS_CODES[0])
        xyzCfg = (xyzCfg & 0xFC) | fsCode
        standbyWrite(REG_XYZ_DATA_CFG, xyzCfg)
        this.rangeG = rangeG
    }

    /**
     * Set the output data rate.
     *
     * @param rateHz one of 800, 400, 200, 100, 50, 12.5, 6.25 or 1.56 (Hz)
     * @throws IllegalArgumentException if the rate is not supported
     */
    void setDataRate(double rateHz) {
        int idx = odrIndex(rateHz)
        if (idx < 0) {
            throw new IllegalArgumentException("rate must be one of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz")
        }
        int ctrl = readReg(REG_CTRL_REG1)
        ctrl = (ctrl & 0xC7) | (DR_CODES[idx] << 3)
        standbyWrite(REG_CTRL_REG1, ctrl)
        this.odrHz = ODR_ORDER[idx]
    }

    /**
     * Set the oversampling mode.
     *
     * @param mode 0 normal, 1 low-noise low-power, 2 high resolution, 3 low
     *        power — applies in WAKE / ACTIVE mode
     * @param sleepMode oversampling mode in SLEEP; negative means "same as mode"
     * @throws IllegalArgumentException if a mode is out of range
     */
    void setOversampling(int mode, int sleepMode = -1) {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("mode must be 0, 1, 2 or 3")
        }
        int smods = sleepMode < 0 ? mode : sleepMode
        if (smods < 0 || smods > 3) {
            throw new IllegalArgumentException("sleepMode must be 0, 1, 2 or 3")
        }
        int ctrl2 = readReg(REG_CTRL_REG2)
        ctrl2 = (ctrl2 & 0xE7) | (MODS_CODES[smods] << 3) | MODS_CODES[mode]
        standbyWrite(REG_CTRL_REG2, ctrl2)
        this.oversamplingMode = mode
        this.sleepOversamplingMode = smods
    }

    /**
     * Enable or disable the reduced-noise (low-noise) mode.
     *
     * Low-noise mode limits the dynamic range to ±4 *g*.
     *
     * @param enabled {@code true} to enable low-noise mode
     * @throws IllegalArgumentException if low-noise mode is requested while the
     *         range is ±8 *g*
     */
    void setLowNoise(boolean enabled) {
        if (enabled && rangeG == 8) {
            throw new IllegalArgumentException("low-noise mode limits the range to +/-4 g")
        }
        int ctrl = readReg(REG_CTRL_REG1)
        ctrl = enabled ? (ctrl | CTRL1_LNOISE) : (ctrl & ~CTRL1_LNOISE & 0xFF)
        standbyWrite(REG_CTRL_REG1, ctrl)
        this.lowNoiseEnabled = enabled
    }

    /**
     * Select the output resolution.
     *
     * @param bits 14 for the normal two-byte output, 8 for fast read (MSB
     *        register only)
     * @throws IllegalArgumentException if the resolution is not 14 or 8 bits, or
     *         if the change is attempted while the FIFO is enabled
     */
    void setResolution(int bits) {
        if (bits != 8 && bits != 14) {
            throw new IllegalArgumentException("bits must be 8 or 14")
        }
        if (fifoMode != 0) {
            throw new IllegalArgumentException("resolution cannot change while the FIFO is enabled")
        }
        int ctrl = readReg(REG_CTRL_REG1)
        ctrl = bits == 8 ? (ctrl | CTRL1_F_READ) : (ctrl & ~CTRL1_F_READ & 0xFF)
        standbyWrite(REG_CTRL_REG1, ctrl)
        this.bits = bits
    }

    /**
     * Switch the chip to STANDBY mode.
     */
    void standby() {
        int ctrl = readReg(REG_CTRL_REG1)
        if ((ctrl & CTRL1_ACTIVE) == 0) {
            return
        }
        writeReg(REG_CTRL_REG1, ctrl & ~CTRL1_ACTIVE & 0xFF)
    }

    /**
     * Switch the chip to ACTIVE mode and wait for the turn-on time.
     */
    void activate() {
        int ctrl = readReg(REG_CTRL_REG1)
        if ((ctrl & CTRL1_ACTIVE) != 0) {
            return
        }
        writeReg(REG_CTRL_REG1, ctrl | CTRL1_ACTIVE)
        waitTurnOn()
    }

    /**
     * Perform a software reset.
     *
     * All registers return to their reset values and the chip ends up in
     * STANDBY. Re-run the initialization (by constructing a new driver)
     * afterwards.
     */
    void reset() {
        writeReg(REG_CTRL_REG2, CTRL2_RST)
        delay(1)
        waitResetDone()
        this.rangeG = DEFAULT_RANGE_G
        this.odrHz = DEFAULT_ODR_HZ
        this.bits = DEFAULT_BITS
        this.lowNoiseEnabled = false
        this.oversamplingMode = 0
        this.sleepOversamplingMode = 0
        this.fifoMode = 0
    }

    /**
     * Enable or disable the high-pass-filtered output and select its cutoff.
     *
     * @param enabled {@code true} to route high-pass-filtered data to the
     *        output registers and the FIFO
     * @param cutoff high-pass cutoff selector 0–3 (the actual frequency depends
     *        on the ODR and oversampling mode; datasheet Table 23)
     * @throws IllegalArgumentException if the cutoff is out of range
     */
    void setHighPassOutput(boolean enabled, int cutoff = 0) {
        if (cutoff < 0 || cutoff > 3) {
            throw new IllegalArgumentException("cutoff must be 0, 1, 2 or 3")
        }
        int xyzCfg = readReg(REG_XYZ_DATA_CFG)
        xyzCfg = enabled ? (xyzCfg | 0x10) : (xyzCfg & ~0x10 & 0xFF)
        int hpFilter = readReg(REG_HP_FILTER)
        hpFilter = (hpFilter & 0xFC) | cutoff
        standbyWriteMulti(REG_XYZ_DATA_CFG, xyzCfg, [[REG_HP_FILTER, hpFilter]] as int[][])
    }

    /**
     * Set the per-axis zero-*g* offset correction.
     *
     * @param xG X offset in *g*
     * @param yG Y offset in *g*
     * @param zG Z offset in *g*
     */
    void setOffset(double xG, double yG, double zG) {
        int ox = offsetCode(xG)
        int oy = offsetCode(yG)
        int oz = offsetCode(zG)
        standbyWriteMulti(REG_OFF_X, ox, [[REG_OFF_Y, oy], [REG_OFF_Z, oz]] as int[][])
    }

    /**
     * Read the per-axis offset correction.
     *
     * @return array of three doubles — X, Y, Z offsets in *g*
     */
    double[] getOffset() {
        int ox = Register.toSigned(readReg(REG_OFF_X), 8)
        int oy = Register.toSigned(readReg(REG_OFF_Y), 8)
        int oz = Register.toSigned(readReg(REG_OFF_Z), 8)
        return [ox * OFFSET_LSB_G, oy * OFFSET_LSB_G, oz * OFFSET_LSB_G] as double[]
    }

    // =========================================================================
    // Status
    // =========================================================================

    /**
     * Read raw signed acceleration counts.
     *
     * @return array of three ints — X, Y, Z signed counts (14-bit
     *         right-aligned, or 8-bit in fast-read mode)
     */
    int[] readRaw() {
        byte[] raw = readBurst(REG_OUT_X_MSB, 6)
        if (bits == 8) {
            return [Register.toSigned(raw[0] & 0xFF, 8),
                    Register.toSigned(raw[2] & 0xFF, 8),
                    Register.toSigned(raw[4] & 0xFF, 8)] as int[]
        }
        int rx = (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) >> 2
        int ry = (((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF)) >> 2
        int rz = (((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF)) >> 2
        return [Register.toSigned(rx, 14),
                Register.toSigned(ry, 14),
                Register.toSigned(rz, 14)] as int[]
    }

    /**
     * Check whether a new sample set is ready.
     *
     * @return {@code true} when {@code STATUS.ZYXDR} is set — not valid while
     *         the FIFO is enabled, use {@link #fifoLevel()} instead
     */
    boolean newDataAvailable() {
        int status = readReg(REG_STATUS)
        return (status & STATUS_ZYXDR) != 0
    }

    /**
     * Read the STATUS register.
     *
     * @return the STATUS byte ({@code ZYXOW} bit 7 … {@code XDR} bit 0)
     */
    int readStatus() {
        return readReg(REG_STATUS)
    }

    /**
     * Read the current power mode.
     *
     * @return {@code SYSMOD} bits 1:0 — 0 standby, 1 wake, 2 sleep
     */
    int readSystemMode() {
        return readReg(REG_SYSMOD) & 0x03
    }

    // =========================================================================
    // FIFO
    // =========================================================================

    /**
     * Configure the FIFO.
     *
     * @param mode 0 disabled, 1 circular, 2 fill, 3 trigger
     * @param watermark FIFO watermark 0–32 (0 disables the watermark flag); in
     *        trigger mode this is the number of pre-trigger samples
     * @throws IllegalArgumentException if the mode is out of range or the
     *         watermark is outside 0–32
     */
    void setFifo(int mode, int watermark = 0) {
        if (mode < 0 || mode > 3) {
            throw new IllegalArgumentException("mode must be 0, 1, 2 or 3")
        }
        if (watermark < 0 || watermark > 32) {
            throw new IllegalArgumentException("watermark must be 0..32")
        }
        if (mode != 0 && fifoMode != 0 && fifoMode != mode) {
            // The mode must go through 00 to switch between enabled modes.
            writeFifoSetup(0, 0)
        }
        writeFifoSetup(mode, watermark)
        this.fifoMode = mode
    }

    /**
     * Select the FIFO trigger sources.
     *
     * @param transientEvent enable the transient detector as a trigger source
     * @param orientation enable the orientation detector as a trigger source
     * @param pulse enable the pulse (tap) detector as a trigger source
     * @param motion enable the freefall / motion detector as a trigger source
     */
    void setFifoTrigger(boolean transientEvent = false, boolean orientation = false,
                        boolean pulse = false, boolean motion = false) {
        int trig = 0
        if (transientEvent) trig |= 0x20
        if (orientation) trig |= 0x10
        if (pulse) trig |= 0x08
        if (motion) trig |= 0x04
        standbyWrite(REG_TRIG_CFG, trig)
    }

    /**
     * Read the number of samples currently in the FIFO.
     *
     * @return the sample count (0–32)
     */
    int fifoLevel() {
        return readReg(REG_STATUS) & 0x3F
    }

    /**
     * Read and decode the FIFO status.
     *
     * Reading F_STATUS clears the FIFO interrupt source.
     *
     * @return the decoded {@link FifoStatus}
     */
    FifoStatus readFifoStatus() {
        int status = readReg(REG_STATUS)
        return new FifoStatus((status & 0x80) != 0, (status & 0x40) != 0, status & 0x3F)
    }

    /**
     * Drain samples from the FIFO.
     *
     * One sample is read per transaction starting at 0x01 (6 bytes, or 3 in
     * 8-bit mode).
     *
     * @param count maximum number of samples to read; negative reads all
     *        samples currently in the FIFO
     * @return array of {@code [x, y, z]} acceleration triples in *g*
     */
    double[][] readFifo(int count = -1) {
        int n = count < 0 ? fifoLevel() : count
        double[][] samples = new double[n][3]
        for (int i = 0; i < n; i++) {
            samples[i] = decode(readBurst(REG_OUT_X_MSB, 6))
        }
        return samples
    }

    // =========================================================================
    // Detectors
    // =========================================================================

    /**
     * Configure the freefall / motion detector.
     *
     * @param motion {@code true} for motion detection (any enabled axis above
     *        the threshold), {@code false} for freefall (all enabled axes below
     *        the threshold)
     * @param axes axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG threshold in *g* (0.063 g/LSB, valid to 8 *g*)
     * @param debounceMs debounce time in ms
     * @param latch {@code true} to latch the event flags until they are read
     * @param debounceClear {@code true} to clear the debounce counter when the
     *        condition is lost ({@code DBCNTM})
     * @throws IllegalArgumentException if the axis mask is out of range
     */
    void setFreefallMotion(boolean motion, int axes = 7, double thresholdG = 0.0d,
                            double debounceMs = 0.0d, boolean latch = false,
                            boolean debounceClear = false) {
        if (axes < 0 || axes > 7) {
            throw new IllegalArgumentException("axes must be a 3-bit mask (0..7)")
        }
        int cfg = 0
        if (latch) cfg |= 0x80
        if (motion) cfg |= 0x40
        if ((axes & 0x04) != 0) cfg |= 0x20
        if ((axes & 0x02) != 0) cfg |= 0x10
        if ((axes & 0x01) != 0) cfg |= 0x08
        int ths = thresholdCode(thresholdG)
        if (debounceClear) {
            ths |= 0x80
        }
        int count = debounceCode(debounceMs)
        standbyWriteMulti(REG_FF_MT_CFG, cfg,
                [[REG_FF_MT_THS, ths], [REG_FF_MT_COUNT, count]] as int[][])
    }

    /**
     * Read the freefall / motion event flags.
     *
     * @return the {@code FF_MT_SRC} byte — cleared when the events are latched
     */
    int readMotionSource() {
        return readReg(REG_FF_MT_SRC)
    }

    /**
     * Configure the transient detector.
     *
     * @param axes axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG threshold in *g* (0.063 g/LSB)
     * @param debounceMs debounce time in ms
     * @param bypassHpf {@code true} to bypass the high-pass filter (the detector
     *        then behaves like motion detection)
     * @param latch {@code true} to latch the event flags until they are read
     * @throws IllegalArgumentException if the axis mask is out of range
     */
    void setTransient(int axes = 7, double thresholdG = 0.0d, double debounceMs = 0.0d,
                      boolean bypassHpf = false, boolean latch = false) {
        if (axes < 0 || axes > 7) {
            throw new IllegalArgumentException("axes must be a 3-bit mask (0..7)")
        }
        int cfg = 0
        if (latch) cfg |= 0x10
        if ((axes & 0x04) != 0) cfg |= 0x08
        if ((axes & 0x02) != 0) cfg |= 0x04
        if ((axes & 0x01) != 0) cfg |= 0x02
        if (bypassHpf) cfg |= 0x01
        int ths = thresholdCode(thresholdG)
        int count = debounceCode(debounceMs)
        standbyWriteMulti(REG_TRANSIENT_CFG, cfg,
                [[REG_TRANSIENT_THS, ths], [REG_TRANSIENT_CNT, count]] as int[][])
    }

    /**
     * Read the transient event flags.
     *
     * Reading clears the flags and the interrupt source.
     *
     * @return the {@code TRANSIENT_SRC} byte
     */
    int readTransientSource() {
        return readReg(REG_TRANSIENT_SRC)
    }

    /**
     * Configure the tap ("pulse") detector.
     *
     * @param single axis mask for single-tap detection (bit 0 = X, bit 1 = Y, bit 2 = Z)
     * @param doubleTap axis mask for double-tap detection
     * @param thresholdX X pulse threshold in *g* (0.063 g/LSB)
     * @param thresholdY Y pulse threshold in *g*
     * @param thresholdZ Z pulse threshold in *g*
     * @param timeLimitMs pulse time limit in ms
     * @param latencyMs pulse latency in ms
     * @param windowMs second-pulse window in ms
     * @param latch {@code true} to latch the event flags until they are read
     * @param doubleAbort {@code true} to abort double-tap detection when a pulse
     *        starts during the latency window
     * @param lpf {@code true} to enable the pulse low-pass filter
     * @param hpf {@code true} to keep the pulse high-pass filter enabled
     * @throws IllegalArgumentException if an axis mask is out of range
     */
    void setPulse(int single = 0, int doubleTap = 0, double thresholdX = 0.5d,
                  double thresholdY = 0.5d, double thresholdZ = 0.5d, double timeLimitMs = 30.0d,
                  double latencyMs = 100.0d, double windowMs = 300.0d, boolean latch = false,
                  boolean doubleAbort = false, boolean lpf = true, boolean hpf = true) {
        if (single < 0 || single > 7 || doubleTap < 0 || doubleTap > 7) {
            throw new IllegalArgumentException("single and doubleTap must be 3-bit axis masks (0..7)")
        }
        int cfg = 0
        if (doubleAbort) cfg |= 0x80
        if (latch) cfg |= 0x40
        if ((doubleTap & 0x04) != 0) cfg |= 0x20
        if ((doubleTap & 0x02) != 0) cfg |= 0x10
        if ((doubleTap & 0x01) != 0) cfg |= 0x08
        if ((single & 0x04) != 0) cfg |= 0x04
        if ((single & 0x02) != 0) cfg |= 0x02
        if ((single & 0x01) != 0) cfg |= 0x01
        int thsx = thresholdCode(thresholdX)
        int thsy = thresholdCode(thresholdY)
        int thsz = thresholdCode(thresholdZ)
        int tmlt = pulseTimeCode(timeLimitMs, lpf, true)
        int ltcy = pulseTimeCode(latencyMs, lpf, false)
        int wind = pulseTimeCode(windowMs, lpf, false)
        int hpFilter = readReg(REG_HP_FILTER)
        hpFilter = hpf ? (hpFilter & ~0x20 & 0xFF) : (hpFilter | 0x20)
        hpFilter = lpf ? (hpFilter | 0x10) : (hpFilter & ~0x10 & 0xFF)
        standbyWriteMulti(REG_PULSE_CFG, cfg,
                [[REG_PULSE_THSX, thsx], [REG_PULSE_THSY, thsy], [REG_PULSE_THSZ, thsz],
                 [REG_PULSE_TMLT, tmlt], [REG_PULSE_LTCY, ltcy], [REG_PULSE_WIND, wind],
                 [REG_HP_FILTER, hpFilter]] as int[][])
    }

    /**
     * Read the tap event flags.
     *
     * Reading clears the flags and the interrupt source.
     *
     * @return the {@code PULSE_SRC} byte
     */
    int readPulseSource() {
        return readReg(REG_PULSE_SRC)
    }

    /**
     * Configure the portrait / landscape orientation detector.
     *
     * @param enabled {@code true} to enable the detector
     * @param debounceMs debounce time in ms
     * @param thresholdDeg trip angle — one of 15, 20, 30, 35, 40, 45, 55, 60,
     *        70 or 75 degrees
     * @param hysteresis hysteresis code 0–7 (adds 0/±4/±7/±11/±14/±17/±21/±24 degrees)
     * @param zLock Z-lock angle code 0–7 (14/18/21/25/29/33/37/42 degrees)
     * @param backFront back/front threshold code 0–3
     * @throws IllegalArgumentException if the trip angle is not supported, a code
     *         is out of range, or the register constraint {@code THS + HYS} in
     *         1…31 is violated
     */
    void setOrientation(boolean enabled = true, double debounceMs = 0.0d, int thresholdDeg = 45,
                        int hysteresis = 4, int zLock = 4, int backFront = 1) {
        int ths = orientationThreshold(thresholdDeg)
        if (hysteresis < 0 || hysteresis > 7) {
            throw new IllegalArgumentException("hysteresis must be 0..7")
        }
        if (zLock < 0 || zLock > 7) {
            throw new IllegalArgumentException("zLock must be 0..7")
        }
        if (backFront < 0 || backFront > 3) {
            throw new IllegalArgumentException("backFront must be 0..3")
        }
        if (ths + hysteresis < 1 || ths + hysteresis > 31) {
            throw new IllegalArgumentException("thresholdDeg + hysteresis must give a register value in 1..31")
        }
        int plCfg = 0x80 | (enabled ? 0x40 : 0x00)
        int plThs = (ths << 3) | hysteresis
        int plBf = ((backFront & 0x03) << 6) | (zLock & 0x07)
        int count = debounceCode(debounceMs)
        standbyWriteMulti(REG_PL_CFG, plCfg,
                [[REG_PL_COUNT, count], [REG_P_L_THS, plThs], [REG_PL_BF_ZCOMP, plBf]] as int[][])
    }

    /**
     * Read the orientation status.
     *
     * Reading clears the {@code NEWLP} flag.
     *
     * @return the decoded {@link Orientation}
     */
    Orientation readOrientation() {
        int status = readReg(REG_PL_STATUS)
        return new Orientation((status & 0x80) != 0, (status & 0x40) != 0,
                (status >> 1) & 0x03, (status & 0x01) != 0)
    }

    /**
     * Configure Auto-SLEEP / Auto-WAKE.
     *
     * @param enabled {@code true} to enable Auto-SLEEP
     * @param sleepRateHz output rate while asleep — 50, 12.5, 6.25 or 1.56 Hz
     * @param timeoutS inactivity timeout in seconds (0–81 s)
     * @param wakeSources mask of wake sources — any combination of
     *        {@link #SOURCE_TRANSIENT}, {@link #SOURCE_ORIENTATION},
     *        {@link #SOURCE_PULSE} and {@link #SOURCE_FF_MT}
     * @throws IllegalArgumentException if the sleep rate is not supported
     */
    void setAutoSleep(boolean enabled, double sleepRateHz = 50.0d, double timeoutS = 10.0d,
                       int wakeSources = 0) {
        int rateIdx = aslpRateIndex(sleepRateHz)
        if (rateIdx < 0) {
            throw new IllegalArgumentException("sleepRateHz must be one of 50, 12.5, 6.25, 1.56")
        }
        int ctrl1 = readReg(REG_CTRL_REG1)
        ctrl1 = (ctrl1 & 0x3F) | (ASLP_RATE_CODES[rateIdx] << 6)
        int ctrl2 = readReg(REG_CTRL_REG2)
        ctrl2 = enabled ? (ctrl2 | 0x04) : (ctrl2 & ~0x04 & 0xFF)
        int ctrl3 = readReg(REG_CTRL_REG3) & 0x07
        if ((wakeSources & SOURCE_TRANSIENT) != 0) ctrl3 |= 0x40
        if ((wakeSources & SOURCE_ORIENTATION) != 0) ctrl3 |= 0x20
        if ((wakeSources & SOURCE_PULSE) != 0) ctrl3 |= 0x10
        if ((wakeSources & SOURCE_FF_MT) != 0) ctrl3 |= 0x08
        double stepS = odrHz <= 1.56d ? 0.64d : 0.32d
        int aslpCount = clamp((int) Math.round(timeoutS / stepS), 0, 255)
        standbyWriteMulti(REG_CTRL_REG1, ctrl1,
                [[REG_CTRL_REG2, ctrl2], [REG_CTRL_REG3, ctrl3], [REG_ASLP_COUNT, aslpCount]] as int[][])
    }

    /**
     * Enable or disable the FIFO gate.
     *
     * With the gate enabled the FIFO blocks new input around WAKE / SLEEP
     * transitions until it has been emptied.
     *
     * @param enabled {@code true} to enable the FIFO gate
     */
    void setFifoGate(boolean enabled) {
        int ctrl3 = readReg(REG_CTRL_REG3)
        ctrl3 = enabled ? (ctrl3 | 0x80) : (ctrl3 & ~0x80 & 0xFF)
        standbyWrite(REG_CTRL_REG3, ctrl3)
    }

    /**
     * Configure the interrupt pin polarity and drive.
     *
     * Both INT pins share these settings.
     *
     * @param activeHigh {@code true} for active-high interrupts (the default is
     *        active-low)
     * @param openDrain {@code true} for open-drain drive (the default is
     *        push-pull)
     */
    void setInterruptPins(boolean activeHigh = false, boolean openDrain = false) {
        int ctrl3 = readReg(REG_CTRL_REG3)
        ctrl3 = activeHigh ? (ctrl3 | 0x02) : (ctrl3 & ~0x02 & 0xFF)
        ctrl3 = openDrain ? (ctrl3 | 0x01) : (ctrl3 & ~0x01 & 0xFF)
        standbyWrite(REG_CTRL_REG3, ctrl3)
    }

    // =========================================================================
    // Interrupts
    // =========================================================================

    /**
     * Enable one interrupt source and route it to INT1 or INT2.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IllegalArgumentException if the source or the pin is invalid
     */
    void enableInterrupt(int source, int pin = 1) {
        checkSource(source)
        if (pin != 1 && pin != 2) {
            throw new IllegalArgumentException("pin must be 1 or 2")
        }
        int ctrl4 = readReg(REG_CTRL_REG4) | source
        int ctrl5 = readReg(REG_CTRL_REG5)
        ctrl5 = pin == 1 ? (ctrl5 | source) : (ctrl5 & ~source & 0xFF)
        standbyWriteMulti(REG_CTRL_REG4, ctrl4, [[REG_CTRL_REG5, ctrl5]] as int[][])
    }

    /**
     * Disable one interrupt source.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @throws IllegalArgumentException if the source is invalid
     */
    void disableInterrupt(int source) {
        checkSource(source)
        int ctrl4 = readReg(REG_CTRL_REG4) & ~source & 0xFF
        standbyWrite(REG_CTRL_REG4, ctrl4)
    }

    /**
     * Subscribe to INT line assertions.
     *
     * When the connection has no INT pin wired, a 5 ms polling thread is
     * started instead.
     *
     * @param callback called with the interrupt status byte
     * @param pin 1 for INT1, 2 for INT2 — informational, the physical pin is
     *        selected by the connection's INT pin
     */
    void onInterrupt(IntConsumer callback, int pin = 1) {
        offInterrupt()
        this.callback = callback
        InputPin intPin = connection.intPin()
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /**
     * Unsubscribe from INT line assertions.
     *
     * @param pin 1 for INT1, 2 for INT2 — informational
     */
    void offInterrupt(int pin = 1) {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        stopPolling()
    }

    /**
     * Read the interrupt status and clear the per-source flags.
     *
     * Every set bit except {@link #SOURCE_DATA_READY} additionally reads the
     * matching source register so the flag (and the interrupt pin) is cleared.
     * {@link #SOURCE_DATA_READY} stays set — clear it by reading the data.
     *
     * @return the {@code INT_SOURCE} byte
     */
    int pollInterrupt() {
        int status = readReg(REG_INT_SOURCE)
        if ((status & SOURCE_FIFO) != 0) {
            readReg(REG_STATUS)
        }
        if ((status & SOURCE_FF_MT) != 0) {
            readReg(REG_FF_MT_SRC)
        }
        if ((status & SOURCE_PULSE) != 0) {
            readReg(REG_PULSE_SRC)
        }
        if ((status & SOURCE_ORIENTATION) != 0) {
            readReg(REG_PL_STATUS)
        }
        if ((status & SOURCE_TRANSIENT) != 0) {
            readReg(REG_TRANSIENT_SRC)
        }
        if ((status & SOURCE_AUTO_SLEEP) != 0) {
            readReg(REG_SYSMOD)
        }
        return status
    }

    // =========================================================================
    // Self-test
    // =========================================================================

    /**
     * Run the electrostatic self-test.
     *
     * The chip is put into STANDBY, set to ±4 *g* at 100 Hz with normal
     * oversampling, and the acceleration is sampled before and after the
     * self-test force is applied. The previous configuration is restored
     * afterwards.
     *
     * @return the {@link SelfTestResult}
     */
    SelfTestResult selfTest() {
        int savedRange = rangeG
        int savedBits = bits
        int savedMods = oversamplingMode
        int savedSmods = sleepOversamplingMode
        boolean savedLnoise = lowNoiseEnabled
        int savedFifo = fifoMode
        double savedOdr = odrHz
        int[] before
        int[] after
        try {
            setFifo(0, 0)
            standby()
            setRange(4)
            setDataRate(100.0d)
            setOversampling(0, -1)
            setResolution(14)
            activate()
            before = averageCounts()
            int ctrl2 = readReg(REG_CTRL_REG2)
            writeReg(REG_CTRL_REG2, ctrl2 | CTRL2_ST)
            delay((long) Math.ceil(turnOnTimeMs()))
            after = averageCounts()
            writeReg(REG_CTRL_REG2, ctrl2 & ~CTRL2_ST & 0xFF)
        } finally {
            // Restore the previous configuration.
            standby()
            setResolution(savedFifo == 0 ? savedBits : 14)
            setOversampling(savedMods, savedSmods)
            setDataRate(savedOdr)
            setLowNoise(savedLnoise)
            setRange(savedRange)
            if (savedFifo != 0) {
                setFifo(savedFifo, 0)
            }
            activate()
        }
        int dx = after[0] - before[0]
        int dy = after[1] - before[1]
        int dz = after[2] - before[2]
        boolean ok = dx > SELF_TEST_TYPICAL[0] / 2 &&
                dy > SELF_TEST_TYPICAL[1] / 2 &&
                dz > SELF_TEST_TYPICAL[2] / 2
        return new SelfTestResult(ok, dx, dy, dz)
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Average a few raw samples and return the mean counts.
     *
     * @return array of three ints — the mean X, Y, Z counts
     */
    protected int[] averageCounts() {
        int[] total = new int[3]
        for (int i = 0; i < 4; i++) {
            int[] sample = readRaw()
            total[0] += sample[0]
            total[1] += sample[1]
            total[2] += sample[2]
        }
        total[0] /= 4
        total[1] /= 4
        total[2] /= 4
        return total
    }

    /**
     * Convert a threshold in *g* to the 7-bit register code.
     *
     * @param thresholdG threshold in *g*
     * @return the register code (0–127)
     */
    protected static int thresholdCode(double thresholdG) {
        return clamp((int) Math.round(thresholdG / THRESHOLD_LSB_G), 0, 127)
    }

    /**
     * Convert an offset in *g* to the signed 8-bit register code.
     *
     * @param offsetG offset in *g*
     * @return the register code (-128…127)
     */
    protected static int offsetCode(double offsetG) {
        return clamp((int) Math.round(offsetG / OFFSET_LSB_G), -128, 127)
    }

    /**
     * Convert a debounce time in ms to the 8-bit counter code.
     *
     * @param debounceMs debounce time in ms
     * @return the counter code (0–255)
     */
    protected int debounceCode(double debounceMs) {
        int idx = odrIndex(odrHz)
        if (idx < 0) {
            idx = 3
        }
        double step = DEBOUNCE_STEPS[idx][oversamplingMode & 3]
        return clamp((int) Math.round(debounceMs / step), 0, 255)
    }

    /**
     * Convert a pulse timing in ms to the 8-bit register code.
     *
     * @param timeMs the timing in ms
     * @param lpf {@code true} when the pulse low-pass filter is enabled
     * @param isTimeLimit {@code true} for the time limit register, {@code false}
     *        for the latency and window registers
     * @return the register code (0–255)
     */
    protected int pulseTimeCode(double timeMs, boolean lpf, boolean isTimeLimit) {
        int idx = odrIndex(odrHz)
        if (idx < 0) {
            idx = 3
        }
        double step = lpf ?
                (isTimeLimit ? PULSE_TMLT_LPF : PULSE_LTCY_LPF)[idx][oversamplingMode & 3] :
                (isTimeLimit ? PULSE_TMLT_NO_LPF : PULSE_LTCY_NO_LPF)[idx][oversamplingMode & 3]
        return clamp((int) Math.round(timeMs / step), 0, 255)
    }

    /**
     * Write {@code F_SETUP} (mode bits 7:6, watermark bits 5:0).
     *
     * @param mode FIFO mode (0–3)
     * @param watermark FIFO watermark (0–32)
     */
    protected void writeFifoSetup(int mode, int watermark) {
        int value = ((mode & 0x03) << 6) | (watermark & 0x3F)
        standbyWrite(REG_F_SETUP, value)
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
                return i
            }
        }
        return -1
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
                return i
            }
        }
        return -1
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
                return ORIENTATION_THRESHOLDS[i]
            }
        }
        throw new IllegalArgumentException("thresholdDeg must be one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75")
    }

    /**
     * Verify that an interrupt source is valid.
     *
     * @param source the source value to check
     * @throws IllegalArgumentException if the source is not a valid {@code SOURCE_*} value
     */
    protected static void checkSource(int source) {
        if (source == 0 || (source & ~SOURCE_ALL) != 0) {
            throw new IllegalArgumentException("invalid interrupt source 0x" + Integer.toHexString(source))
        }
    }

    /**
     * Clamp a value into an inclusive range.
     *
     * @param value the value to clamp
     * @param low the lower bound
     * @param high the upper bound
     * @return the clamped value
     */
    protected static int clamp(int value, int low, int high) {
        if (value < low) {
            return low
        }
        if (value > high) {
            return high
        }
        return value
    }

    private void startPolling() {
        polling = true
        Thread thread = new Thread(new Runnable() {
            @Override
            void run() {
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        })
        thread.setDaemon(true)
        thread.setName("mma8451q-poll")
        pollThread = thread
        thread.start()
    }

    private void stopPolling() {
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt()
            IntConsumer cb = callback
            if (status != 0 && cb != null) {
                cb.accept(status)
            }
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
