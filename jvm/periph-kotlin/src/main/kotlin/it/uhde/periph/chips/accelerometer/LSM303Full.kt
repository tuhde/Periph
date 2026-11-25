package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * LSM303 — 3-axis accelerometer + magnetometer e-compass (STMicroelectronics)
 * — full interface.
 *
 * Extends [LSM303Minimal] with the complete chip API:
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
class LSM303Full constructor(
    accelConnection: RegisterConnection,
    magConnection: RegisterConnection
) : LSM303Minimal(accelConnection, magConnection) {

    companion object {
        /** FIFO mode: bypass — the FIFO is not used. */
        const val FIFO_BYPASS = 0
        /** FIFO mode: FIFO — the oldest sample is overwritten when full. */
        const val FIFO_MODE = 1
        /** FIFO mode: stream — the newest samples are kept. */
        const val FIFO_STREAM = 2
        /** FIFO mode: trigger — the FIFO stops filling once the trigger fires. */
        const val FIFO_TRIGGER = 3

        /** Magnetometer operating mode: continuous conversion. */
        const val MAG_MODE_CONTINUOUS = 0
        /** Magnetometer operating mode: single conversion. */
        const val MAG_MODE_SINGLE = 1
        /** Magnetometer operating mode: sleep. */
        const val MAG_MODE_SLEEP = 2

        /** Click detector axis mask: X axis. */
        const val CLICK_AXIS_X = 0x01
        /** Click detector axis mask: Y axis. */
        const val CLICK_AXIS_Y = 0x02
        /** Click detector axis mask: Z axis. */
        const val CLICK_AXIS_Z = 0x04

        /** Interrupt source: new accelerometer XYZ sample. */
        const val SOURCE_DATA_READY = 0x0001
        /** Interrupt source: FIFO content exceeds the watermark. */
        const val SOURCE_WATERMARK = 0x0002
        /** Interrupt source: FIFO overrun. */
        const val SOURCE_OVERRUN = 0x0004
        /** Interrupt source: inertial generator 1 event. */
        const val SOURCE_IA1 = 0x0008
        /** Interrupt source: inertial generator 2 event. */
        const val SOURCE_IA2 = 0x0010
        /** Interrupt source: single / double click. */
        const val SOURCE_CLICK = 0x0020
        /** Interrupt source: boot procedure running (INT2 only). */
        const val SOURCE_BOOT = 0x0040
        /** Interrupt source: new magnetometer sample (on the DRDY pin). */
        const val SOURCE_MAG_DATA_READY = 0x0080

        // CTRL_REG1_A bits.
        const val CTRL1_ODR_MASK = 0xF0
        const val CTRL1_LPEN = 0x08
        const val CTRL1_AXES_MASK = 0x07

        // CTRL_REG2_A bits.
        const val CTRL2_FDS = 0x08
        const val CTRL2_HPCLICK = 0x04
        const val CTRL2_HPIS2 = 0x02
        const val CTRL2_HPIS1 = 0x01

        // CTRL_REG3_A bits (routing to INT1).
        const val CTRL3_I1_CLICK = 0x80
        const val CTRL3_I1_AOI1 = 0x40
        const val CTRL3_I1_AOI2 = 0x20
        const val CTRL3_I1_DRDY1 = 0x10
        const val CTRL3_I1_WTM = 0x04
        const val CTRL3_I1_OVERRUN = 0x02

        // CTRL_REG4_A bits.
        const val CTRL4_FS_MASK = 0x30
        const val CTRL4_HR = 0x08

        // CTRL_REG5_A bits.
        const val CTRL5_BOOT = 0x80
        const val CTRL5_FIFO_EN = 0x40
        const val CTRL5_LIR_INT1 = 0x08
        const val CTRL5_D4D_INT1 = 0x04
        const val CTRL5_LIR_INT2 = 0x02
        const val CTRL5_D4D_INT2 = 0x01

        // CTRL_REG6_A bits (routing to INT2).
        const val CTRL6_I2_CLICK = 0x80
        const val CTRL6_I2_INT1 = 0x40
        const val CTRL6_I2_INT2 = 0x20
        const val CTRL6_BOOT_I1 = 0x10
        const val CTRL6_H_LACTIVE = 0x02

        // STATUS_REG_A bits.
        const val STATUS_ZYXDA = 0x08

        // FIFO_SRC_REG_A bits.
        const val FIFO_SRC_WTM = 0x80
        const val FIFO_SRC_OVERRUN = 0x40
        const val FIFO_SRC_EMPTY = 0x20
        const val FIFO_SRC_LEVEL_MASK = 0x1F

        // INTx_SOURCE_A / CLICK_SRC_A interrupt-activation bit.
        const val SOURCE_IA = 0x40

        // CRA_REG_M bits.
        const val CRA_TEMP_EN = 0x80
        const val CRA_DO_MASK = 0x1C

        // CRB_REG_M bits.
        const val CRB_GN_MASK = 0xE0

        // SR_REG_M bits.
        const val SR_LOCK = 0x02
        const val SR_DRDY = 0x01
    }

    protected var enabledSources: Int = 0
    protected var tempEnabled: Boolean = false

    // --- Accelerometer configuration ----------------------------------------

    /**
     * Set the accelerometer full-scale range.
     *
     * @param rangeG 2, 4, 8 or 16 (*g*)
     * @throws IOException on bus error or an unsupported range
     */
    fun setRange(rangeG: Int) {
        val code = when (rangeG) {
            2 -> 0x00
            4 -> 0x10
            8 -> 0x20
            16 -> 0x30
            else -> throw IOException("rangeG must be one of 2, 4, 8, 16")
        }
        val ctrl4 = accelReadReg(REG_CTRL4_A)
        accelWrite(REG_CTRL4_A, (ctrl4 and CTRL4_FS_MASK.inv()) or code)
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
    fun setDataRate(rateHz: Int) {
        val code = when (rateHz) {
            0 -> 0x0
            1 -> 0x1
            10 -> 0x2
            25 -> 0x3
            50 -> 0x4
            100 -> 0x5
            200 -> 0x6
            400 -> 0x7
            1620 -> 0x8
            1344, 5376 -> 0x9
            else -> throw IOException(
                "rateHz must be one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376"
            )
        }
        val lowPower = operatingMode == MODE_LOW_POWER
        if ((rateHz == 1620 || rateHz == 5376) && !lowPower) {
            throw IOException("$rateHz Hz is only available in low-power mode")
        }
        if (rateHz == 1344 && lowPower) {
            throw IOException("1344 Hz is not available in low-power mode (use 5376 Hz)")
        }
        val ctrl1 = accelReadReg(REG_CTRL1_A)
        accelWrite(REG_CTRL1_A, (ctrl1 and CTRL1_ODR_MASK.inv()) or (code shl 4))
        odrHz = rateHz.toDouble()
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
    fun setMode(mode: Int) {
        val lpen: Boolean
        val hr: Boolean
        when (mode) {
            MODE_LOW_POWER -> { lpen = true; hr = false }
            MODE_NORMAL -> { lpen = false; hr = false }
            MODE_HIGH_RESOLUTION -> { lpen = false; hr = true }
            else -> throw IOException(
                "mode must be MODE_LOW_POWER, MODE_NORMAL or MODE_HIGH_RESOLUTION"
            )
        }
        var ctrl1 = accelReadReg(REG_CTRL1_A)
        var ctrl4 = accelReadReg(REG_CTRL4_A)
        ctrl1 = if (lpen) ctrl1 or CTRL1_LPEN else ctrl1 and CTRL1_LPEN.inv()
        ctrl4 = if (hr) ctrl4 or CTRL4_HR else ctrl4 and CTRL4_HR.inv()
        accelWrite(REG_CTRL1_A, ctrl1)
        accelWrite(REG_CTRL4_A, ctrl4)
        this.operatingMode = mode
    }

    /**
     * Enable or disable the individual accelerometer axes.
     *
     * @param x true to enable the X axis
     * @param y true to enable the Y axis
     * @param z true to enable the Z axis
     */
    fun setAxes(x: Boolean, y: Boolean, z: Boolean) {
        var ctrl1 = accelReadReg(REG_CTRL1_A)
        var value = ctrl1 and CTRL1_AXES_MASK.inv()
        if (x) value = value or 0x01
        if (y) value = value or 0x02
        if (z) value = value or 0x04
        accelWrite(REG_CTRL1_A, value)
    }

    /**
     * Put the accelerometer into power-down mode.
     *
     * The register contents are retained, so [setDataRate] resumes the
     * conversions.
     */
    fun powerDown() {
        val ctrl1 = accelReadReg(REG_CTRL1_A)
        accelWrite(REG_CTRL1_A, ctrl1 and CTRL1_ODR_MASK.inv())
        odrHz = 0.0
    }

    // --- Accelerometer readings ---------------------------------------------

    /**
     * Read the acceleration as raw counts.
     *
     * @return array of three ints — X, Y, Z signed counts
     */
    fun readAccelerationRaw(): IntArray = rawAccel(accelRead(REG_OUT_X_L_A, 6))

    /**
     * Report whether a new accelerometer sample is available.
     *
     * @return true if `STATUS_REG_A.ZYXDA` is set
     */
    fun newDataAvailable(): Boolean = (accelReadReg(REG_STATUS_A) and STATUS_ZYXDA) != 0

    /** Read the raw accelerometer status register. */
    fun readStatus(): Int = accelReadReg(REG_STATUS_A)

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
    fun setHighPass(
        mode: Int,
        cutoff: Int,
        filterOutput: Boolean = false,
        onIa1: Boolean = false,
        onIa2: Boolean = false,
        onClick: Boolean = false
    ) {
        if (mode < 0 || mode > 3) {
            throw IOException("mode must be 0..3")
        }
        if (cutoff < 0 || cutoff > 3) {
            throw IOException("cutoff must be 0..3")
        }
        var ctrl2 = (mode shl 6) or (cutoff shl 4)
        if (filterOutput) ctrl2 = ctrl2 or CTRL2_FDS
        if (onClick) ctrl2 = ctrl2 or CTRL2_HPCLICK
        if (onIa2) ctrl2 = ctrl2 or CTRL2_HPIS2
        if (onIa1) ctrl2 = ctrl2 or CTRL2_HPIS1
        accelWrite(REG_CTRL2_A, ctrl2)
    }

    /**
     * Reset the high-pass filter and return the reference value.
     *
     * @return the reference value (0-255)
     */
    fun resetHighPass(): Int = accelReadReg(REG_REFERENCE_A)

    /**
     * Write the high-pass reference value.
     *
     * @param value the reference value 0-255
     */
    fun setReference(value: Int) {
        accelWrite(REG_REFERENCE_A, value and 0xFF)
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
    fun setFifo(mode: Int, watermark: Int = 0, triggerInt2: Boolean = false) {
        if (mode < 0 || mode > 3) {
            throw IOException("mode must be 0..3")
        }
        if (watermark < 0 || watermark > 31) {
            throw IOException("watermark must be 0..31")
        }
        var fifo = (mode shl 6) or (watermark and 0x1F)
        if (triggerInt2) fifo = fifo or 0x20
        accelWrite(REG_FIFO_CTRL_A, fifo)
        var ctrl5 = accelReadReg(REG_CTRL5_A)
        ctrl5 = if (mode == FIFO_BYPASS) {
            ctrl5 and CTRL5_FIFO_EN.inv()
        } else {
            ctrl5 or CTRL5_FIFO_EN
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Return the number of unread sample sets in the FIFO.
     *
     * @return the number of unread sample sets (0-32)
     */
    fun fifoLevel(): Int = accelReadReg(REG_FIFO_SRC_A) and FIFO_SRC_LEVEL_MASK

    /**
     * Read sample sets from the FIFO.
     *
     * Each sample set is popped with one six-byte burst read.
     *
     * @param count the number of sample sets to read
     * @return array of sample sets, each an array of three doubles in *g*
     */
    fun readFifo(count: Int): Array<DoubleArray> {
        val out = Array(count) { DoubleArray(3) }
        for (i in 0 until count) {
            out[i] = convertAccel(accelRead(REG_OUT_X_L_A, 6))
        }
        return out
    }

    /**
     * Read the decoded FIFO status.
     *
     * @return array of four values — watermark, overrun, empty and level
     */
    fun readFifoStatus(): IntArray {
        val src = accelReadReg(REG_FIFO_SRC_A)
        return intArrayOf(
            if ((src and FIFO_SRC_WTM) != 0) 1 else 0,
            if ((src and FIFO_SRC_OVERRUN) != 0) 1 else 0,
            if ((src and FIFO_SRC_EMPTY) != 0) 1 else 0,
            src and FIFO_SRC_LEVEL_MASK
        )
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
    fun setMotionInterrupt(
        generator: Int,
        events: Int,
        thresholdG: Double,
        durationMs: Double = 0.0,
        andMode: Boolean = false
    ) {
        val regs = intRegs(generator)
        if (events < 0 || events > 0x3F) {
            throw IOException("events must be a 6-bit mask (0..63)")
        }
        var value = events and 0x3F
        if (andMode) value = value or 0x80
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
    fun setFreeFall(generator: Int = 1, thresholdG: Double = 0.35, durationMs: Double = 30.0) {
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
    fun setOrientation(generator: Int = 1, position: Boolean = false, fourD: Boolean = false) {
        val regs = intRegs(generator)
        var events = 0x3F
        if (fourD) events = events and 0x30.inv()
        var value = events or 0x40
        if (position) value = value or 0x80
        accelWrite(regs[0], value)
        var ctrl5 = accelReadReg(REG_CTRL5_A)
        val bit = if (generator == 1) CTRL5_D4D_INT1 else CTRL5_D4D_INT2
        ctrl5 = if (fourD) ctrl5 or bit else ctrl5 and bit.inv()
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Latch or release the interrupt of one generator.
     *
     * @param generator 1 or 2
     * @param enabled true to latch, false to release
     * @throws IOException on bus error or an invalid generator
     */
    fun setLatch(generator: Int, enabled: Boolean) {
        if (generator != 1 && generator != 2) {
            throw IOException("generator must be 1 or 2")
        }
        var ctrl5 = accelReadReg(REG_CTRL5_A)
        val bit = if (generator == 1) CTRL5_LIR_INT1 else CTRL5_LIR_INT2
        ctrl5 = if (enabled) ctrl5 or bit else ctrl5 and bit.inv()
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Read the interrupt source register of one generator.
     *
     * Reading the register clears the latched interrupt.
     *
     * @param generator 1 or 2
     * @return the raw `INTx_SOURCE_A` byte
     * @throws IOException on bus error or an invalid generator
     */
    fun readInterruptSource(generator: Int): Int {
        if (generator != 1 && generator != 2) {
            throw IOException("generator must be 1 or 2")
        }
        return accelReadReg(if (generator == 1) REG_INT1_SOURCE_A else REG_INT2_SOURCE_A)
    }

    /**
     * Return the (config, threshold, duration) register triple of a generator.
     *
     * @throws IOException if the generator is invalid
     */
    protected fun intRegs(generator: Int): IntArray = when (generator) {
        1 -> intArrayOf(REG_INT1_CFG_A, REG_INT1_THS_A, REG_INT1_DURATION_A)
        2 -> intArrayOf(REG_INT2_CFG_A, REG_INT2_THS_A, REG_INT2_DURATION_A)
        else -> throw IOException("generator must be 1 or 2")
    }

    /**
     * Convert a threshold in *g* into a 7-bit register code.
     */
    protected fun encodeThreshold(thresholdG: Double): Int {
        var code = (thresholdG * 128.0 / fsG + 0.5).toInt()
        if (code < 0) code = 0
        if (code > 127) code = 127
        return code
    }

    /**
     * Convert a duration in milliseconds into a register code.
     *
     * One LSB is 1/ODR; the code is clamped to the register width.
     */
    protected fun encodeDuration(durationMs: Double, bits: Int): Int {
        var code = 0
        if (odrHz > 0) code = (durationMs * odrHz / 1000.0 + 0.5).toInt()
        val maxValue = (1 shl bits) - 1
        if (code < 0) code = 0
        if (code > maxValue) code = maxValue
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
    fun setClick(
        single: Int = 0,
        doubleClick: Int = 0,
        thresholdG: Double = 0.5,
        timeLimitMs: Double = 10.0,
        latencyMs: Double = 20.0,
        windowMs: Double = 50.0
    ) {
        if (single < 0 || single > 0x07) {
            throw IOException("single must be an axis mask 0..7")
        }
        if (doubleClick < 0 || doubleClick > 0x07) {
            throw IOException("double must be an axis mask 0..7")
        }
        var cfg = 0
        if ((single and CLICK_AXIS_X) != 0) cfg = cfg or 0x01
        if ((single and CLICK_AXIS_Y) != 0) cfg = cfg or 0x04
        if ((single and CLICK_AXIS_Z) != 0) cfg = cfg or 0x10
        if ((doubleClick and CLICK_AXIS_X) != 0) cfg = cfg or 0x02
        if ((doubleClick and CLICK_AXIS_Y) != 0) cfg = cfg or 0x08
        if ((doubleClick and CLICK_AXIS_Z) != 0) cfg = cfg or 0x20
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
     * @return the raw `CLICK_SRC_A` byte
     */
    fun readClickSource(): Int = accelReadReg(REG_CLICK_SRC_A)

    /**
     * Set the polarity of the INT1 / INT2 outputs.
     *
     * @param activeLow true for active-low, false for active-high (default)
     */
    fun setInterruptPolarity(activeLow: Boolean) {
        var ctrl6 = accelReadReg(REG_CTRL6_A)
        ctrl6 = if (activeLow) ctrl6 or CTRL6_H_LACTIVE else ctrl6 and CTRL6_H_LACTIVE.inv()
        accelWrite(REG_CTRL6_A, ctrl6)
    }

    // --- Magnetometer --------------------------------------------------------

    /**
     * Set the magnetometer full-scale range.
     *
     * @param rangeUt 130, 190, 250, 400, 470, 560 or 810 (µT)
     * @throws IOException on bus error or an unsupported range
     */
    fun setMagRange(rangeUt: Int) {
        val code = when (rangeUt) {
            130 -> 0x20
            190 -> 0x40
            250 -> 0x60
            400 -> 0x80
            470 -> 0xA0
            560 -> 0xC0
            810 -> 0xE0
            else -> throw IOException("rangeUt must be one of 130, 190, 250, 400, 470, 560, 810")
        }
        val crb = magReadReg(REG_CRB_M)
        magWrite(REG_CRB_M, (crb and CRB_GN_MASK.inv()) or code)
        this.magRangeUt = rangeUt
    }

    /**
     * Set the magnetometer output data rate.
     *
     * @param rateHz 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220
     * @throws IOException on bus error or an unsupported rate
     */
    fun setMagDataRate(rateHz: Double) {
        val rates = doubleArrayOf(0.75, 1.5, 3.0, 7.5, 15.0, 30.0, 75.0, 220.0)
        for (code in rates.indices) {
            if (Math.abs(rates[code] - rateHz) < 1e-6) {
                val cra = magReadReg(REG_CRA_M)
                magWrite(REG_CRA_M, (cra and CRA_DO_MASK.inv()) or (code shl 2))
                return
            }
        }
        throw IOException("rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220")
    }

    /**
     * Set the magnetometer operating mode.
     *
     * @param mode MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP
     * @throws IOException on bus error or an invalid mode
     */
    fun setMagMode(mode: Int) {
        if (mode != MAG_MODE_CONTINUOUS && mode != MAG_MODE_SINGLE && mode != MAG_MODE_SLEEP) {
            throw IOException(
                "mode must be MAG_MODE_CONTINUOUS, MAG_MODE_SINGLE or MAG_MODE_SLEEP"
            )
        }
        val mr = magReadReg(REG_MR_M)
        magWrite(REG_MR_M, (mr and 0x03.inv()) or mode)
    }

    /**
     * Read the magnetic field as raw counts in X, Y, Z order.
     *
     * @return array of three ints — X, Y, Z signed counts
     */
    fun readMagneticRaw(): IntArray = reorderMag(magRead(REG_OUT_X_H_M, 6))

    /**
     * Report whether a new magnetometer sample is available.
     *
     * @return true if `SR_REG_M.DRDY` is set
     */
    fun magDataReady(): Boolean = (magReadReg(REG_SR_M) and SR_DRDY) != 0

    /**
     * Read the decoded magnetometer status register.
     *
     * @return array of two values — data-ready and lock
     */
    fun readMagStatus(): BooleanArray {
        val sr = magReadReg(REG_SR_M)
        return booleanArrayOf((sr and SR_DRDY) != 0, (sr and SR_LOCK) != 0)
    }

    /**
     * Read the magnetometer identity bytes.
     *
     * @return the three identity bytes
     */
    fun readMagIdentity(): ByteArray = magRead(REG_IRA_M, 3)

    /**
     * Enable or disable the magnetometer temperature sensor.
     *
     * @param enabled true to enable the sensor, false to disable it
     */
    fun enableTemperature(enabled: Boolean) {
        var cra = magReadReg(REG_CRA_M)
        cra = if (enabled) cra or CRA_TEMP_EN else cra and CRA_TEMP_EN.inv()
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
    fun readTemperatureDelta(): Double {
        if (!tempEnabled) {
            enableTemperature(true)
        }
        val raw = magRead(REG_TEMP_OUT_H_M, 2)
        val value = Register.toSigned(
            ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16
        ) shr 4
        return value / 8.0
    }

    // --- Compass -------------------------------------------------------------

    /**
     * Compute the compass heading.
     *
     * @param tiltCompensate true to compensate for the tilt reported by the
     *                       accelerometer, false for the flat heading
     * @return the heading in degrees, 0-360
     */
    fun heading(tiltCompensate: Boolean = true): Double {
        val m = readMagnetic()
        val mx = m[0]
        val my = m[1]
        val mz = m[2]
        if (!tiltCompensate) {
            return wrapDegrees(Math.toDegrees(Math.atan2(my, mx)))
        }
        val a = readAcceleration()
        val ax = a[0]
        val ay = a[1]
        val az = a[2]
        val roll = Math.atan2(ay, az)
        val pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll))
        val xh = mx * Math.cos(pitch) + mz * Math.sin(pitch)
        val yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll) -
            mz * Math.sin(roll) * Math.cos(pitch)
        return wrapDegrees(Math.toDegrees(Math.atan2(yh, xh)))
    }

    /**
     * Wrap an angle in degrees into the 0-360 range.
     */
    protected fun wrapDegrees(degrees: Double): Double {
        var value = degrees % 360.0
        if (value < 0) value += 360.0
        return value
    }

    // --- Interrupt API --------------------------------------------------------

    /**
     * Route one interrupt source to INT1 or INT2.
     *
     * Only the routing is configured; the generator or function behind the
     * source is set up with the `set*` methods.
     *
     * @param source one of the `SOURCE_*` constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IOException on bus error, an invalid pin or an unroutable source
     */
    fun enableInterrupt(source: Int, pin: Int = 1) {
        if (pin != 1 && pin != 2) {
            throw IOException("pin must be 1 or 2")
        }
        if (source == SOURCE_MAG_DATA_READY) {
            throw IOException(
                "SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed"
            )
        }
        val bits = sourceBits(source)
        if (bits[0] == 0 && bits[1] == 0) {
            throw IOException("unknown interrupt source")
        }
        if (bits[0] != 0 && (pin == 1 || bits[1] == 0)) {
            val ctrl3 = accelReadReg(REG_CTRL3_A)
            accelWrite(REG_CTRL3_A, ctrl3 or bits[0])
        }
        if (bits[1] != 0 && (pin == 2 || bits[0] == 0)) {
            val ctrl6 = accelReadReg(REG_CTRL6_A)
            accelWrite(REG_CTRL6_A, ctrl6 or bits[1])
        }
        enabledSources = enabledSources or source
    }

    /**
     * Remove one interrupt source from INT1 or INT2.
     *
     * @param source one of the `SOURCE_*` constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IOException on bus error, an invalid pin or an unroutable source
     */
    fun disableInterrupt(source: Int, pin: Int = 1) {
        if (pin != 1 && pin != 2) {
            throw IOException("pin must be 1 or 2")
        }
        if (source == SOURCE_MAG_DATA_READY) {
            throw IOException(
                "SOURCE_MAG_DATA_READY is reported on the DRDY pin and cannot be routed"
            )
        }
        val bits = sourceBits(source)
        if (bits[0] == 0 && bits[1] == 0) {
            throw IOException("unknown interrupt source")
        }
        if (bits[0] != 0 && (pin == 1 || bits[1] == 0)) {
            val ctrl3 = accelReadReg(REG_CTRL3_A)
            accelWrite(REG_CTRL3_A, ctrl3 and bits[0].inv())
        }
        if (bits[1] != 0 && (pin == 2 || bits[0] == 0)) {
            val ctrl6 = accelReadReg(REG_CTRL6_A)
            accelWrite(REG_CTRL6_A, ctrl6 and bits[1].inv())
        }
        enabledSources = enabledSources and source.inv()
    }

    /**
     * Return the (CTRL_REG3_A, CTRL_REG6_A) routing bits of a source.
     */
    protected fun sourceBits(source: Int): IntArray = when (source) {
        SOURCE_DATA_READY -> intArrayOf(CTRL3_I1_DRDY1, 0)
        SOURCE_WATERMARK -> intArrayOf(CTRL3_I1_WTM, 0)
        SOURCE_OVERRUN -> intArrayOf(CTRL3_I1_OVERRUN, 0)
        SOURCE_IA1 -> intArrayOf(CTRL3_I1_AOI1, CTRL6_I2_INT1)
        SOURCE_IA2 -> intArrayOf(CTRL3_I1_AOI2, CTRL6_I2_INT2)
        SOURCE_CLICK -> intArrayOf(CTRL3_I1_CLICK, CTRL6_I2_CLICK)
        SOURCE_BOOT -> intArrayOf(0, CTRL6_BOOT_I1)
        else -> intArrayOf(0, 0)
    }

    /**
     * Read the interrupt status of every enabled source.
     *
     * @return a mask of the `SOURCE_*` constants
     */
    fun pollInterrupt(): Int {
        var status = 0
        if ((accelReadReg(REG_STATUS_A) and STATUS_ZYXDA) != 0) {
            status = status or SOURCE_DATA_READY
        }
        val fifo = accelReadReg(REG_FIFO_SRC_A)
        if ((fifo and FIFO_SRC_WTM) != 0) status = status or SOURCE_WATERMARK
        if ((fifo and FIFO_SRC_OVERRUN) != 0) status = status or SOURCE_OVERRUN
        if ((enabledSources and SOURCE_IA1) != 0 &&
            (accelReadReg(REG_INT1_SOURCE_A) and SOURCE_IA) != 0
        ) {
            status = status or SOURCE_IA1
        }
        if ((enabledSources and SOURCE_IA2) != 0 &&
            (accelReadReg(REG_INT2_SOURCE_A) and SOURCE_IA) != 0
        ) {
            status = status or SOURCE_IA2
        }
        if ((enabledSources and SOURCE_CLICK) != 0 &&
            (accelReadReg(REG_CLICK_SRC_A) and SOURCE_IA) != 0
        ) {
            status = status or SOURCE_CLICK
        }
        if ((magReadReg(REG_SR_M) and SR_DRDY) != 0) {
            status = status or SOURCE_MAG_DATA_READY
        }
        return status
    }

    // --- Miscellaneous --------------------------------------------------------

    /**
     * Reboot the accelerometer's memory content.
     *
     * The `BOOT` bit reloads the factory trim values. The initialization
     * sequence is run again afterwards, so any configuration applied after the
     * construction has to be repeated.
     */
    fun reboot() {
        val ctrl5 = accelReadReg(REG_CTRL5_A)
        accelWrite(REG_CTRL5_A, ctrl5 or CTRL5_BOOT)
        sleepMs(5)
        init()
        tempEnabled = false
    }
}
