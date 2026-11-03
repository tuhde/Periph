package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * LSM303 — 3-axis accelerometer + magnetometer e-compass (STMicroelectronics)
 * — full interface.
 *
 * Extends {@link LSM303Minimal} with the complete chip API:
 * - Accelerometer range, output data rate, operating mode and per-axis
 *   enable, plus power-down
 * - Raw acceleration and raw magnetic readings
 * - High-pass filter configuration and the reference value
 * - The 32-level FIFO in bypass / FIFO / stream / trigger mode
 * - Two inertial interrupt generators with thresholds, durations, free-fall
 *   and 6D/4D orientation recognition, plus latching
 * - Single and double click detection
 * - Magnetometer gain, data rate, operating mode, identity, status and the
 *   relative temperature sensor
 * - A software compass heading, optionally tilt-compensated
 * - The Level-3 interrupt API (INT1 / INT2)
 */
@CompileStatic
class LSM303Full extends LSM303Minimal {

    /** FIFO mode: bypass — the FIFO is not used. */
    static final int FIFO_BYPASS = 0
    /** FIFO mode: FIFO — the oldest sample is overwritten when full. */
    static final int FIFO_MODE = 1
    /** FIFO mode: stream — the newest samples are kept. */
    static final int FIFO_STREAM = 2
    /** FIFO mode: trigger — the FIFO stops filling once the trigger fires. */
    static final int FIFO_TRIGGER = 3

    /** Magnetometer operating mode: continuous conversion. */
    static final int MAG_MODE_CONTINUOUS = 0
    /** Magnetometer operating mode: single conversion. */
    static final int MAG_MODE_SINGLE = 1
    /** Magnetometer operating mode: sleep. */
    static final int MAG_MODE_SLEEP = 2

    /** Click detector axis mask: X axis. */
    static final int CLICK_AXIS_X = 0x01
    /** Click detector axis mask: Y axis. */
    static final int CLICK_AXIS_Y = 0x02
    /** Click detector axis mask: Z axis. */
    static final int CLICK_AXIS_Z = 0x04

    /** Interrupt source: new accelerometer XYZ sample. */
    static final int SOURCE_DATA_READY = 0x0001
    /** Interrupt source: FIFO content exceeds the watermark. */
    static final int SOURCE_WATERMARK = 0x0002
    /** Interrupt source: FIFO overrun. */
    static final int SOURCE_OVERRUN = 0x0004
    /** Interrupt source: inertial generator 1 event. */
    static final int SOURCE_IA1 = 0x0008
    /** Interrupt source: inertial generator 2 event. */
    static final int SOURCE_IA2 = 0x0010
    /** Interrupt source: single / double click. */
    static final int SOURCE_CLICK = 0x0020
    /** Interrupt source: boot procedure running (INT2 only). */
    static final int SOURCE_BOOT = 0x0040
    /** Interrupt source: new magnetometer sample (on the DRDY pin). */
    static final int SOURCE_MAG_DATA_READY = 0x0080

    // CTRL_REG1_A bits.
    static final int CTRL1_ODR_MASK = 0xF0
    static final int CTRL1_LPEN = 0x08
    static final int CTRL1_AXES_MASK = 0x07

    // CTRL_REG2_A bits.
    static final int CTRL2_FDS = 0x08
    static final int CTRL2_HPCLICK = 0x04
    static final int CTRL2_HPIS2 = 0x02
    static final int CTRL2_HPIS1 = 0x01

    // CTRL_REG3_A bits (routing to INT1).
    static final int CTRL3_I1_CLICK = 0x80
    static final int CTRL3_I1_AOI1 = 0x40
    static final int CTRL3_I1_AOI2 = 0x20
    static final int CTRL3_I1_DRDY1 = 0x10
    static final int CTRL3_I1_WTM = 0x04
    static final int CTRL3_I1_OVERRUN = 0x02

    // CTRL_REG4_A bits.
    static final int CTRL4_FS_MASK = 0x30
    static final int CTRL4_HR = 0x08

    // CTRL_REG5_A bits.
    static final int CTRL5_BOOT = 0x80
    static final int CTRL5_FIFO_EN = 0x40
    static final int CTRL5_LIR_INT1 = 0x08
    static final int CTRL5_D4D_INT1 = 0x04
    static final int CTRL5_LIR_INT2 = 0x02
    static final int CTRL5_D4D_INT2 = 0x01

    // CTRL_REG6_A bits (routing to INT2).
    static final int CTRL6_I2_CLICK = 0x80
    static final int CTRL6_I2_INT1 = 0x40
    static final int CTRL6_I2_INT2 = 0x20
    static final int CTRL6_BOOT_I1 = 0x10
    static final int CTRL6_H_LACTIVE = 0x02

    // STATUS_REG_A bits.
    static final int STATUS_ZYXDA = 0x08

    // FIFO_SRC_REG_A bits.
    static final int FIFO_SRC_WTM = 0x80
    static final int FIFO_SRC_OVERRUN = 0x40
    static final int FIFO_SRC_EMPTY = 0x20
    static final int FIFO_SRC_LEVEL_MASK = 0x1F

    // INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
    static final int SOURCE_IA = 0x40

    // CRA_REG_M bits.
    static final int CRA_TEMP_EN = 0x80
    static final int CRA_DO_MASK = 0x1C

    // CRB_REG_M bits.
    static final int CRB_GN_MASK = 0xE0

    // SR_REG_M bits.
    static final int SR_LOCK = 0x02
    static final int SR_DRDY = 0x01

    protected int enabledSources = 0
    protected boolean tempEnabled = false

    LSM303Full(RegisterConnection accelConnection, RegisterConnection magConnection) {
        super(accelConnection, magConnection)
    }

    // --- Accelerometer configuration ----------------------------------------

    /**
     * Set the accelerometer full-scale range.
     *
     * @param rangeG 2, 4, 8 or 16 (*g*)
     * @throws IOException on bus error or an unsupported range
     */
    void setRange(int rangeG) {
        int code
        switch (rangeG) {
            case 2: code = 0x00; break
            case 4: code = 0x10; break
            case 8: code = 0x20; break
            case 16: code = 0x30; break
            default: throw new IOException("rangeG must be one of 2, 4, 8, 16")
        }
        int ctrl4 = accelReadReg(REG_CTRL4_A)
        accelWrite(REG_CTRL4_A, (ctrl4 & ~CTRL4_FS_MASK) | code)
        this.fsG = rangeG
    }

    /**
     * Set the accelerometer output data rate.
     *
     * The rates 1620 and 5376 Hz exist in low-power mode only, 1344 Hz in
     * normal and high-resolution mode only.
     *
     * @param rateHz 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620
     *                or 5376
     * @throws IOException on bus error or an unsupported rate
     */
    void setDataRate(int rateHz) {
        int code
        switch (rateHz) {
            case 0: code = 0x0; break
            case 1: code = 0x1; break
            case 10: code = 0x2; break
            case 25: code = 0x3; break
            case 50: code = 0x4; break
            case 100: code = 0x5; break
            case 200: code = 0x6; break
            case 400: code = 0x7; break
            case 1620: code = 0x8; break
            case 1344:
            case 5376: code = 0x9; break
            default:
                throw new IOException("rateHz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376")
        }
        boolean lowPower = mode == MODE_LOW_POWER
        if ((rateHz == 1620 || rateHz == 5376) && !lowPower) {
            throw new IOException(rateHz + " Hz is only available in low-power mode")
        }
        if (rateHz == 1344 && lowPower) {
            throw new IOException("1344 Hz is not available in low-power mode (use 5376 Hz)")
        }
        int ctrl1 = accelReadReg(REG_CTRL1_A)
        accelWrite(REG_CTRL1_A, (ctrl1 & ~CTRL1_ODR_MASK) | (code << 4))
        odrHz = rateHz
    }

    /**
     * Set the accelerometer operating mode.
     *
     * Low-power mode uses 8-bit output, normal mode 10-bit and
     * high-resolution mode 12-bit; the sensitivity changes accordingly.
     *
     * @param mode MODE_LOW_POWER, MODE_NORMAL or MODE_HIGH_RESOLUTION
     * @throws IOException on bus error or an invalid mode
     */
    void setMode(int mode) {
        boolean lpen
        boolean hr
        switch (mode) {
            case MODE_LOW_POWER: lpen = true; hr = false; break
            case MODE_NORMAL: lpen = false; hr = false; break
            case MODE_HIGH_RESOLUTION: lpen = false; hr = true; break
            default:
                throw new IOException("mode must be MODE_LOW_POWER, MODE_NORMAL or MODE_HIGH_RESOLUTION")
        }
        int ctrl1 = accelReadReg(REG_CTRL1_A)
        int ctrl4 = accelReadReg(REG_CTRL4_A)
        if (lpen) {
            ctrl1 |= CTRL1_LPEN
        } else {
            ctrl1 &= ~CTRL1_LPEN
        }
        if (hr) {
            ctrl4 |= CTRL4_HR
        } else {
            ctrl4 &= ~CTRL4_HR
        }
        accelWrite(REG_CTRL1_A, ctrl1)
        accelWrite(REG_CTRL4_A, ctrl4)
        this.@mode = mode
    }

    /**
     * Enable or disable the individual accelerometer axes.
     *
     * @param x true to enable the X axis
     * @param y true to enable the Y axis
     * @param z true to enable the Z axis
     */
    void setAxes(boolean x, boolean y, boolean z) {
        int ctrl1 = accelReadReg(REG_CTRL1_A)
        int value = ctrl1 & ~CTRL1_AXES_MASK
        if (x) {
            value |= 0x01
        }
        if (y) {
            value |= 0x02
        }
        if (z) {
            value |= 0x04
        }
        accelWrite(REG_CTRL1_A, value)
    }

    /**
     * Put the accelerometer into power-down mode.
     *
     * The register contents are retained, so {@code setDataRate} resumes the
     * conversions.
     */
    void powerDown() {
        int ctrl1 = accelReadReg(REG_CTRL1_A)
        accelWrite(REG_CTRL1_A, ctrl1 & ~CTRL1_ODR_MASK)
        odrHz = 0.0d
    }

    // --- Accelerometer readings ---------------------------------------------

    /**
     * Read the acceleration as raw counts.
     *
     * @return array of three ints — X, Y, Z signed counts
     */
    int[] readAccelerationRaw() {
        return rawAccel(accelRead(REG_OUT_X_L_A, 6))
    }

    /**
     * Report whether a new accelerometer sample is available.
     *
     * @return true if {@code STATUS_REG_A.ZYXDA} is set
     */
    boolean newDataAvailable() {
        return (accelReadReg(REG_STATUS_A) & STATUS_ZYXDA) != 0
    }

    /** Read the raw accelerometer status register. */
    int readStatus() {
        return accelReadReg(REG_STATUS_A)
    }

    // --- High-pass filter ----------------------------------------------------

    /**
     * Configure the accelerometer high-pass filter.
     *
     * @param mode the HPM code 0-3
     * @param cutoff the HPCF code 0-3
     * @param filterOutput true to route the filtered data to the output
     *                     registers and the FIFO
     * @param onIa1 true to high-pass the input of interrupt generator 1
     * @param onIa2 true to high-pass the input of interrupt generator 2
     * @param onClick true to high-pass the click detector input
     * @throws IOException on bus error or an out-of-range mode / cutoff
     */
    void setHighPass(int mode, int cutoff, boolean filterOutput = false,
                     boolean onIa1 = false, boolean onIa2 = false,
                     boolean onClick = false) {
        if (mode < 0 || mode > 3) {
            throw new IOException("mode must be 0..3")
        }
        if (cutoff < 0 || cutoff > 3) {
            throw new IOException("cutoff must be 0..3")
        }
        int ctrl2 = (mode << 6) | (cutoff << 4)
        if (filterOutput) {
            ctrl2 |= CTRL2_FDS
        }
        if (onClick) {
            ctrl2 |= CTRL2_HPCLICK
        }
        if (onIa2) {
            ctrl2 |= CTRL2_HPIS2
        }
        if (onIa1) {
            ctrl2 |= CTRL2_HPIS1
        }
        accelWrite(REG_CTRL2_A, ctrl2)
    }

    /**
     * Reset the high-pass filter and return the reference value.
     *
     * @return the reference value (0-255)
     */
    int resetHighPass() {
        return accelReadReg(REG_REFERENCE_A)
    }

    /**
     * Write the high-pass reference value.
     *
     * @param value the reference value 0-255
     */
    void setReference(int value) {
        accelWrite(REG_REFERENCE_A, value & 0xFF)
    }

    // --- FIFO ----------------------------------------------------------------

    /**
     * Configure the accelerometer FIFO.
     *
     * @param mode FIFO_BYPASS, FIFO_MODE, FIFO_STREAM or FIFO_TRIGGER
     * @param watermark the watermark level 0-31
     * @param triggerInt2 true to use INT2 as the trigger source in trigger mode
     * @throws IOException on bus error or an out-of-range mode / watermark
     */
    void setFifo(int mode, int watermark = 0, boolean triggerInt2 = false) {
        if (mode < 0 || mode > 3) {
            throw new IOException("mode must be 0..3")
        }
        if (watermark < 0 || watermark > 31) {
            throw new IOException("watermark must be 0..31")
        }
        int fifo = (mode << 6) | (watermark & 0x1F)
        if (triggerInt2) {
            fifo |= 0x20
        }
        accelWrite(REG_FIFO_CTRL_A, fifo)
        int ctrl5 = accelReadReg(REG_CTRL5_A)
        if (mode == FIFO_BYPASS) {
            ctrl5 &= ~CTRL5_FIFO_EN
        } else {
            ctrl5 |= CTRL5_FIFO_EN
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Return the number of unread sample sets in the FIFO.
     *
     * @return the number of unread sample sets (0-32)
     */
    int fifoLevel() {
        return accelReadReg(REG_FIFO_SRC_A) & FIFO_SRC_LEVEL_MASK
    }

    /**
     * Read sample sets from the FIFO.
     *
     * Each sample set is popped with one six-byte burst read.
     *
     * @param count the number of sample sets to read
     * @return array of sample sets, each an array of three doubles in *g*
     */
    double[][] readFifo(int count) {
        double[][] out = new double[count][3]
        for (int i = 0; i < count; i++) {
            out[i] = convertAccel(accelRead(REG_OUT_X_L_A, 6))
        }
        return out
    }

    /**
     * Read the decoded FIFO status.
     *
     * @return array of four values — watermark, overrun, empty and level
     */
    int[] readFifoStatus() {
        int src = accelReadReg(REG_FIFO_SRC_A)
        return [
            (src & FIFO_SRC_WTM) != 0 ? 1 : 0,
            (src & FIFO_SRC_OVERRUN) != 0 ? 1 : 0,
            (src & FIFO_SRC_EMPTY) != 0 ? 1 : 0,
            src & FIFO_SRC_LEVEL_MASK,
        ] as int[]
    }

    // --- Inertial interrupt generators ---------------------------------------

    /**
     * Configure one inertial interrupt generator.
     *
     * @param generator 1 or 2
     * @param events a 6-bit mask of the enabled axis events
     * @param thresholdG the threshold in *g*, relative to the current range
     * @param durationMs the minimum event duration in milliseconds
     * @param andMode true to combine the events with AND instead of OR
     * @throws IOException on bus error or an invalid generator / event mask
     */
    void setMotionInterrupt(int generator, int events, double thresholdG,
                             double durationMs = 0.0d, boolean andMode = false) {
        int[] regs = intRegs(generator)
        if (events < 0 || events > 0x3F) {
            throw new IOException("events must be a 6-bit mask (0..63)")
        }
        int value = events & 0x3F
        if (andMode) {
            value |= 0x80
        }
        accelWrite(regs[0], value)
        accelWrite(regs[1], encodeThreshold(thresholdG))
        accelWrite(regs[2], encodeDuration(durationMs, 7))
    }

    /**
     * Arm the free-fall detector of one generator.
     *
     * All three axes are watched for a low event and combined with AND.
     *
     * @param generator 1 or 2
     * @param thresholdG the threshold in *g*
     * @param durationMs the minimum duration in milliseconds
     * @throws IOException on bus error or an invalid generator
     */
    void setFreeFall(int generator = 1, double thresholdG = 0.35d, double durationMs = 30.0d) {
        setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
    }

    /**
     * Arm the 6D orientation detector of one generator.
     *
     * @param generator 1 or 2
     * @param position false for movement recognition, true for position
     *                 recognition
     * @param fourD true for 4D detection, which drops the Z axis
     * @throws IOException on bus error or an invalid generator
     */
    void setOrientation(int generator = 1, boolean position = false, boolean fourD = false) {
        int[] regs = intRegs(generator)
        int events = 0x3F
        if (fourD) {
            events &= ~0x30
        }
        int value = events | 0x40
        if (position) {
            value |= 0x80
        }
        accelWrite(regs[0], value)
        int ctrl5 = accelReadReg(REG_CTRL5_A)
        int bit = generator == 1 ? CTRL5_D4D_INT1 : CTRL5_D4D_INT2
        if (fourD) {
            ctrl5 |= bit
        } else {
            ctrl5 &= ~bit
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Latch or release the interrupt of one generator.
     *
     * @param generator 1 or 2
     * @param enabled true to latch, false to release
     * @throws IOException on bus error or an invalid generator
     */
    void setLatch(int generator, boolean enabled) {
        if (generator != 1 && generator != 2) {
            throw new IOException("generator must be 1 or 2")
        }
        int ctrl5 = accelReadReg(REG_CTRL5_A)
        int bit = generator == 1 ? CTRL5_LIR_INT1 : CTRL5_LIR_INT2
        if (enabled) {
            ctrl5 |= bit
        } else {
            ctrl5 &= ~bit
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Read the interrupt source register of one generator.
     *
     * Reading the register clears the latched interrupt.
     *
     * @param generator 1 or 2
     * @return the raw {@code INTx_SOURCE_A} byte
     * @throws IOException on bus error or an invalid generator
     */
    int readInterruptSource(int generator) {
        if (generator != 1 && generator != 2) {
            throw new IOException("generator must be 1 or 2")
        }
        return accelReadReg(generator == 1 ? REG_INT1_SOURCE_A : REG_INT2_SOURCE_A)
    }

    /**
     * Return the (config, threshold, duration) register triple of a generator.
     *
     * @throws IOException if the generator is invalid
     */
    protected int[] intRegs(int generator) {
        if (generator == 1) {
            return [REG_INT1_CFG_A, REG_INT1_THS_A, REG_INT1_DURATION_A] as int[]
        }
        if (generator == 2) {
            return [REG_INT2_CFG_A, REG_INT2_THS_A, REG_INT2_DURATION_A] as int[]
        }
        throw new IOException("generator must be 1 or 2")
    }

    /**
     * Convert a threshold in *g* into a 7-bit register code.
     */
    protected int encodeThreshold(double thresholdG) {
        int code = (int) (thresholdG * 128.0d / fsG + 0.5d)
        if (code < 0) {
            code = 0
        }
        if (code > 127) {
            code = 127
        }
        return code
    }

    /**
     * Convert a duration in milliseconds into a register code.
     *
     * One LSB is 1/ODR; the code is clamped to the register width.
     */
    protected int encodeDuration(double durationMs, int bits) {
        int code = 0
        if (odrHz > 0) {
            code = (int) (durationMs * odrHz / 1000.0d + 0.5d)
        }
        int maxValue = (1 << bits) - 1
        if (code < 0) {
            code = 0
        }
        if (code > maxValue) {
            code = maxValue
        }
        return code
    }

    // --- Click detection -----------------------------------------------------

    /**
     * Configure the click detector.
     *
     * @param single the axis mask of the enabled single-click detectors
     * @param doubleClick the axis mask of the enabled double-click detectors
     * @param thresholdG the click threshold in *g*
     * @param timeLimitMs the maximum time between the two clicks of a double
     *                    click, in milliseconds
     * @param latencyMs the minimum time between two consecutive clicks, in
     *                  milliseconds
     * @param windowMs the time the click detector observes, in milliseconds
     * @throws IOException on bus error or an out-of-range axis mask
     */
    void setClick(int single = 0, int doubleClick = 0, double thresholdG = 0.5d,
                  double timeLimitMs = 10.0d, double latencyMs = 20.0d,
                  double windowMs = 50.0d) {
        if (single < 0 || single > 0x07) {
            throw new IOException("single must be an axis mask 0..7")
        }
        if (doubleClick < 0 || doubleClick > 0x07) {
            throw new IOException("double must be an axis mask 0..7")
        }
        int cfg = 0
        if ((single & CLICK_AXIS_X) != 0) {
            cfg |= 0x01
        }
        if ((single & CLICK_AXIS_Y) != 0) {
            cfg |= 0x04
        }
        if ((single & CLICK_AXIS_Z) != 0) {
            cfg |= 0x10
        }
        if ((doubleClick & CLICK_AXIS_X) != 0) {
            cfg |= 0x02
        }
        if ((doubleClick & CLICK_AXIS_Y) != 0) {
            cfg |= 0x08
        }
        if ((doubleClick & CLICK_AXIS_Z) != 0) {
            cfg |= 0x20
        }
        accelWrite(REG_CLICK_CFG_A, cfg)
        accelWrite(REG_CLICK_THS_A, encodeThreshold(thresholdG))
        accelWrite(REG_TIME_LIMIT_A, encodeDuration(timeLimitMs, 7))
        accelWrite(REG_TIME_LATENCY_A, encodeDuration(latencyMs, 8))
        accelWrite(REG_TIME_WINDOW_A, encodeDuration(windowMs, 8))
    }

    /**
     * Read the click source register.
     *
     * Reading the register clears the latched click interrupt.
     *
     * @return the raw {@code CLICK_SRC_A} byte
     */
    int readClickSource() {
        return accelReadReg(REG_CLICK_SRC_A)
    }

    /**
     * Set the polarity of the INT1 / INT2 outputs.
     *
     * @param activeLow true for active-low, false for active-high (default)
     */
    void setInterruptPolarity(boolean activeLow) {
        int ctrl6 = accelReadReg(REG_CTRL6_A)
        if (activeLow) {
            ctrl6 |= CTRL6_H_LACTIVE
        } else {
            ctrl6 &= ~CTRL6_H_LACTIVE
        }
        accelWrite(REG_CTRL6_A, ctrl6)
    }

    // --- Magnetometer --------------------------------------------------------

    /**
     * Set the magnetometer full-scale range.
     *
     * @param rangeUt 130, 190, 250, 400, 470, 560 or 810 (µT)
     * @throws IOException on bus error or an unsupported range
     */
    void setMagRange(int rangeUt) {
        int code
        switch (rangeUt) {
            case 130: code = 0x20; break
            case 190: code = 0x40; break
            case 250: code = 0x60; break
            case 400: code = 0x80; break
            case 470: code = 0xA0; break
            case 560: code = 0xC0; break
            case 810: code = 0xE0; break
            default:
                throw new IOException("rangeUt must be one of 130, 190, 250, 400, 470, 560, 810")
        }
        int crb = magReadReg(REG_CRB_M)
        magWrite(REG_CRB_M, (crb & ~CRB_GN_MASK) | code)
        this.magRangeUt = rangeUt
    }

    /**
     * Set the magnetometer output data rate.
     *
     * @param rateHz 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220
     * @throws IOException on bus error or an unsupported rate
     */
    void setMagDataRate(double rateHz) {
        double[] rates = [0.75d, 1.5d, 3.0d, 7.5d, 15.0d, 30.0d, 75.0d, 220.0d] as double[]
        for (int code = 0; code < rates.length; code++) {
            if (Math.abs(rates[code] - rateHz) < 1e-6d) {
                int cra = magReadReg(REG_CRA_M)
                magWrite(REG_CRA_M, (cra & ~CRA_DO_MASK) | (code << 2))
                return
            }
        }
        throw new IOException("rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220")
    }

    /**
     * Set the magnetometer operating mode.
     *
     * @param mode MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP
     * @throws IOException on bus error or an invalid mode
     */
    void setMagMode(int mode) {
        if (mode != MAG_MODE_CONTINUOUS && mode != MAG_MODE_SINGLE && mode != MAG_MODE_SLEEP) {
            throw new IOException("mode must be MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP")
        }
        int mr = magReadReg(REG_MR_M)
        magWrite(REG_MR_M, (mr & ~0x03) | mode)
    }

    /**
     * Read the magnetic field as raw counts in X, Y, Z order.
     *
     * @return array of three ints — X, Y, Z signed counts
     */
    int[] readMagneticRaw() {
        return reorderMag(magRead(REG_OUT_X_H_M, 6))
    }

    /**
     * Report whether a new magnetometer sample is available.
     *
     * @return true if {@code SR_REG_M.DRDY} is set
     */
    boolean magDataReady() {
        return (magReadReg(REG_SR_M) & SR_DRDY) != 0
    }

    /**
     * Read the decoded magnetometer status register.
     *
     * @return array of two values — data-ready and lock
     */
    boolean[] readMagStatus() {
        int sr = magReadReg(REG_SR_M)
        return [(sr & SR_DRDY) != 0, (sr & SR_LOCK) != 0] as boolean[]
    }

    /**
     * Read the magnetometer identity bytes.
     *
     * @return the three identity bytes
     */
    byte[] readMagIdentity() {
        return magRead(REG_IRA_M, 3)
    }

    /**
     * Enable or disable the magnetometer temperature sensor.
     *
     * @param enabled true to enable the sensor, false to disable it
     */
    void enableTemperature(boolean enabled) {
        int cra = magReadReg(REG_CRA_M)
        if (enabled) {
            cra |= CRA_TEMP_EN
        } else {
            cra &= ~CRA_TEMP_EN
        }
        magWrite(REG_CRA_M, cra)
        tempEnabled = enabled
    }

    /**
     * Read the relative temperature of the magnetometer.
     *
     * The sensor is enabled on first use. The datasheet gives no zero
     * reference, so the value is a *delta* relative to an unspecified
     * temperature and not an absolute °C reading.
     *
     * @return the temperature difference in °C (8 LSB/°C)
     */
    double readTemperatureDelta() {
        if (!tempEnabled) {
            enableTemperature(true)
        }
        byte[] raw = magRead(REG_TEMP_OUT_H_M, 2)
        int value = Register.toSigned(((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF), 16) >> 4
        return value / 8.0d
    }

    // --- Compass -------------------------------------------------------------

    /**
     * Compute the compass heading.
     *
     * @param tiltCompensate true to compensate for the tilt reported by the
     *                       accelerometer, false for the flat heading
     * @return the heading in degrees, 0-360
     */
    double heading(boolean tiltCompensate = true) {
        double[] m = readMagnetic()
        double mx = m[0]
        double my = m[1]
        double mz = m[2]
        if (!tiltCompensate) {
            return wrapDegrees(Math.toDegrees(Math.atan2(my, mx)))
        }
        double[] a = readAcceleration()
        double ax = a[0]
        double ay = a[1]
        double az = a[2]
        double roll = Math.atan2(ay, az)
        double pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll))
        double xh = mx * Math.cos(pitch) + mz * Math.sin(pitch)
        double yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll) -
                mz * Math.sin(roll) * Math.cos(pitch)
        return wrapDegrees(Math.toDegrees(Math.atan2(yh, xh)))
    }

    /**
     * Wrap an angle in degrees into the 0-360 range.
     */
    protected static double wrapDegrees(double degrees) {
        double value = degrees % 360.0d
        if (value < 0) {
            value += 360.0d
        }
        return value
    }

    // --- Interrupt API --------------------------------------------------------

    /**
     * Route one interrupt source to INT1 or INT2.
     *
     * Only the routing is configured; the generator or function behind the
     * source is set up with the {@code set*} methods.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IOException on bus error, an invalid pin or an unroutable source
     */
    void enableInterrupt(int source, int pin = 1) {
        if (pin != 1 && pin != 2) {
            throw new IOException("pin must be 1 or 2")
        }
        if (source == SOURCE_MAG_DATA_READY) {
            throw new IOException("SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed")
        }
        int[] bits = sourceBits(source)
        if (bits[0] == 0 && bits[1] == 0) {
            throw new IOException("unknown interrupt source")
        }
        if (bits[0] != 0 && (pin == 1 || bits[1] == 0)) {
            int ctrl3 = accelReadReg(REG_CTRL3_A)
            accelWrite(REG_CTRL3_A, ctrl3 | bits[0])
        }
        if (bits[1] != 0 && (pin == 2 || bits[0] == 0)) {
            int ctrl6 = accelReadReg(REG_CTRL6_A)
            accelWrite(REG_CTRL6_A, ctrl6 | bits[1])
        }
        enabledSources |= source
    }

    /**
     * Remove one interrupt source from INT1 or INT2.
     *
     * @param source one of the {@code SOURCE_*} constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IOException on bus error, an invalid pin or an unroutable source
     */
    void disableInterrupt(int source, int pin = 1) {
        if (pin != 1 && pin != 2) {
            throw new IOException("pin must be 1 or 2")
        }
        if (source == SOURCE_MAG_DATA_READY) {
            throw new IOException("SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed")
        }
        int[] bits = sourceBits(source)
        if (bits[0] == 0 && bits[1] == 0) {
            throw new IOException("unknown interrupt source")
        }
        if (bits[0] != 0 && (pin == 1 || bits[1] == 0)) {
            int ctrl3 = accelReadReg(REG_CTRL3_A)
            accelWrite(REG_CTRL3_A, ctrl3 & ~bits[0])
        }
        if (bits[1] != 0 && (pin == 2 || bits[0] == 0)) {
            int ctrl6 = accelReadReg(REG_CTRL6_A)
            accelWrite(REG_CTRL6_A, ctrl6 & ~bits[1])
        }
        enabledSources &= ~source
    }

    /**
     * Return the (CTRL_REG3_A, CTRL_REG6_A) routing bits of a source.
     */
    protected int[] sourceBits(int source) {
        switch (source) {
            case SOURCE_DATA_READY: return [CTRL3_I1_DRDY1, 0] as int[]
            case SOURCE_WATERMARK: return [CTRL3_I1_WTM, 0] as int[]
            case SOURCE_OVERRUN: return [CTRL3_I1_OVERRUN, 0] as int[]
            case SOURCE_IA1: return [CTRL3_I1_AOI1, CTRL6_I2_INT1] as int[]
            case SOURCE_IA2: return [CTRL3_I1_AOI2, CTRL6_I2_INT2] as int[]
            case SOURCE_CLICK: return [CTRL3_I1_CLICK, CTRL6_I2_CLICK] as int[]
            case SOURCE_BOOT: return [0, CTRL6_BOOT_I1] as int[]
            default: return [0, 0] as int[]
        }
    }

    /**
     * Read the interrupt status of every enabled source.
     *
     * @return a mask of the {@code SOURCE_*} constants
     */
    int pollInterrupt() {
        int status = 0
        if ((accelReadReg(REG_STATUS_A) & STATUS_ZYXDA) != 0) {
            status |= SOURCE_DATA_READY
        }
        int fifo = accelReadReg(REG_FIFO_SRC_A)
        if ((fifo & FIFO_SRC_WTM) != 0) {
            status |= SOURCE_WATERMARK
        }
        if ((fifo & FIFO_SRC_OVERRUN) != 0) {
            status |= SOURCE_OVERRUN
        }
        if ((enabledSources & SOURCE_IA1) != 0 &&
                (accelReadReg(REG_INT1_SOURCE_A) & SOURCE_IA) != 0) {
            status |= SOURCE_IA1
        }
        if ((enabledSources & SOURCE_IA2) != 0 &&
                (accelReadReg(REG_INT2_SOURCE_A) & SOURCE_IA) != 0) {
            status |= SOURCE_IA2
        }
        if ((enabledSources & SOURCE_CLICK) != 0 &&
                (accelReadReg(REG_CLICK_SRC_A) & SOURCE_IA) != 0) {
            status |= SOURCE_CLICK
        }
        if ((magReadReg(REG_SR_M) & SR_DRDY) != 0) {
            status |= SOURCE_MAG_DATA_READY
        }
        return status
    }

    // --- Miscellaneous --------------------------------------------------------

    /**
     * Reboot the accelerometer's memory content.
     *
     * The {@code BOOT} bit reloads the factory trim values. The initialization
     * sequence is run again afterwards, so any configuration applied after the
     * construction has to be repeated.
     */
    void reboot() {
        int ctrl5 = accelReadReg(REG_CTRL5_A)
        accelWrite(REG_CTRL5_A, ctrl5 | CTRL5_BOOT)
        sleepMs(5)
        init()
        tempEnabled = false
    }
}
