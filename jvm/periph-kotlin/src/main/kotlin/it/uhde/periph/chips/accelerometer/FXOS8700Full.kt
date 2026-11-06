package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * FXOS8700CQ — 6-axis e-compass (NXP) — full interface.
 *
 * Extends [FXOS8700Minimal] with the complete chip functionality: operating
 * mode, range, data rate, oversampling, raw / hybrid / temperature reads,
 * offsets, the magnetometer calibration helpers, the 32-sample FIFO, the
 * freefall / motion, transient, tap and orientation detectors, Auto-SLEEP /
 * WAKE, the interrupt routing and a self-test.
 *
 * Methods that change a STANDBY-only register put the chip into STANDBY,
 * write, and restore ACTIVE internally, so the caller never manages the mode.
 * Every such change flushes the FIFO and resets the detectors' debounce
 * counters.
 *
 * The vector-magnitude detectors (0x5F-0x72), the magnetic threshold detector
 * (0x52-0x5A) and the per-axis freefall / motion thresholds (0x73-0x78) are
 * not wrapped: use [readRegister] / [writeRegister].
 *
 * @param connection RegisterConnection (I²C) bound to 0x1C-0x1F
 */
class FXOS8700Full constructor(
    connection: RegisterConnection
) : FXOS8700Minimal(connection) {

    companion object {
        /** New sample (accelerometer in accel-only mode, magnetometer otherwise). */
        const val SOURCE_DATA_READY = 0x01
        /** Accelerometer vector-magnitude change (configured via raw registers). */
        const val SOURCE_ACCEL_VECM = 0x02
        /** Freefall / motion (`A_FFMT_SRC.ea`). */
        const val SOURCE_FF_MT = 0x04
        /** Single / double tap (`PULSE_SRC.ea`). */
        const val SOURCE_PULSE = 0x08
        /** Orientation change (`PL_STATUS.newlp`). */
        const val SOURCE_ORIENTATION = 0x10
        /** Transient (`TRANSIENT_SRC.ea`). */
        const val SOURCE_TRANSIENT = 0x20
        /** FIFO overflow or watermark. */
        const val SOURCE_FIFO = 0x40
        /** WAKE / SLEEP transition. */
        const val SOURCE_AUTO_SLEEP = 0x80

        /** Operating mode: accelerometer only. */
        const val MODE_ACCEL_ONLY = 0
        /** Operating mode: magnetometer only. */
        const val MODE_MAG_ONLY = 1
        /** Operating mode: hybrid (both sensors at half the ODR each). */
        const val MODE_HYBRID = 2

        /** FIFO disabled. */
        const val FIFO_DISABLED = 0
        /** FIFO circular mode (oldest sample discarded). */
        const val FIFO_CIRCULAR = 1
        /** FIFO fill mode (stops accepting when full). */
        const val FIFO_FILL = 2
        /** FIFO trigger mode. */
        const val FIFO_TRIGGER = 3

        /** Debounce time steps in ms, indexed [mods][row]; row = dr code, shifted by one in hybrid mode. */
        private val STEP_MS = arrayOf(
            doubleArrayOf(1.25, 2.5, 5.0, 10.0, 20.0, 20.0, 20.0, 20.0),
            doubleArrayOf(1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 80.0, 80.0),
            doubleArrayOf(1.25, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5, 2.5),
            doubleArrayOf(1.25, 2.5, 5.0, 10.0, 20.0, 80.0, 160.0, 160.0)
        )

        /** PULSE_TMLT steps with the pulse low-pass filter off (LTCY / WIND are twice). */
        private val PULSE_STEP_NO_LPF_MS = arrayOf(
            doubleArrayOf(0.625, 0.625, 1.25, 2.5, 5.0, 5.0, 5.0, 5.0),
            doubleArrayOf(0.625, 0.625, 1.25, 2.5, 5.0, 20.0, 20.0, 20.0),
            doubleArrayOf(0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625, 0.625),
            doubleArrayOf(0.625, 1.25, 2.5, 5.0, 10.0, 40.0, 40.0, 40.0)
        )

        private val PL_DEG = intArrayOf(15, 20, 30, 35, 40, 45, 55, 60, 70, 75)
        private val PL_CODE = intArrayOf(0x07, 0x09, 0x0C, 0x0D, 0x0F, 0x10, 0x13, 0x14, 0x17, 0x19)
        private val ASLP_HZ = doubleArrayOf(50.0, 12.5, 6.25, 1.56)

        private fun threshold(thresholdG: Double): Int =
            Math.max(0, Math.min(127, Math.round(thresholdG / 0.063).toInt()))

        private fun count(timeMs: Double, stepMs: Double): Int =
            Math.max(0, Math.min(255, Math.round(timeMs / stepMs).toInt()))
    }

    // --- Mode / power ---------------------------------------------------------

    /** Poll `SYSMOD` until it reads 00 (up to 350 ms). */
    private fun waitStandby() {
        for (i in 0 until 350) {
            if ((readReg(REG_SYSMOD) and 0x03) == 0) {
                return
            }
            sleepMs(1)
        }
        throw IOException("FXOS8700 did not enter STANDBY")
    }

    /**
     * Enter STANDBY (`active` = 0) and wait until `SYSMOD` reads 0 (up to
     * 300 ms when leaving mag-only / hybrid mode).
     *
     * @throws IOException on bus error or timeout
     */
    fun standby() {
        val c1 = readReg(REG_CTRL1)
        writeReg(REG_CTRL1, c1 and CTRL1_ACTIVE.inv())
        waitStandby()
    }

    /**
     * Enter ACTIVE (`active` = 1) and wait the turn-on time.
     *
     * @throws IOException on bus error
     */
    fun activate() {
        val c1 = readReg(REG_CTRL1)
        writeReg(REG_CTRL1, c1 or CTRL1_ACTIVE)
        sleepMs(turnOnMs())
    }

    /** Enter STANDBY if ACTIVE; returns whether the chip was ACTIVE. */
    private fun begin(): Boolean {
        val c1 = readReg(REG_CTRL1)
        if ((c1 and CTRL1_ACTIVE) != 0) {
            writeReg(REG_CTRL1, c1 and CTRL1_ACTIVE.inv())
            waitStandby()
            return true
        }
        return false
    }

    /** Restore ACTIVE if [wasActive]. */
    private fun end(wasActive: Boolean) {
        if (wasActive) {
            activate()
        }
    }

    /**
     * Software reset (`CTRL_REG2.rst`). The bus error of the unacknowledged
     * write is ignored; every register returns to its reset value. Call
     * [init] afterwards.
     */
    fun reset() {
        softReset()
        operatingMode = 0
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
     * @param mode 0 accelerometer only, 1 magnetometer only, 2 hybrid (also enables `hyb_autoinc_mode`)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if [mode] is not 0, 1 or 2
     */
    fun setMode(mode: Int) {
        require(mode in 0..2) { "mode must be 0, 1 or 2" }
        val was = begin()
        modify(REG_M_CTRL1, M_CTRL1_HMS_MASK, intArrayOf(0, 1, 3)[mode])
        if (mode == 2) {
            modify(REG_M_CTRL2, M_CTRL2_HYB_AUTOINC, M_CTRL2_HYB_AUTOINC)
            hyb = true
        }
        operatingMode = mode
        end(was)
    }

    /**
     * Select the accelerometer full-scale range.
     *
     * @param rangeG 2, 4 or 8 (*g*)
     * @throws IOException on bus error
     * @throws IllegalArgumentException for any other value, or 8 while low-noise mode is on
     */
    fun setRange(rangeG: Int) {
        require(rangeG == 2 || rangeG == 4 || rangeG == 8) { "rangeG must be 2, 4 or 8" }
        require(!(rangeG == 8 && lnoise)) { "±8 g is not available in low-noise mode" }
        modify(REG_XYZ_DATA_CFG, 0x03, if (rangeG == 2) 0 else if (rangeG == 4) 1 else 2)
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
    fun setDataRate(rateHz: Double) {
        var code = -1
        for (i in ODR_HZ.indices) {
            if (Math.abs(ODR_HZ[i] - rateHz) <= 0.01 * ODR_HZ[i]) {
                code = i
            }
        }
        require(code >= 0) { "unsupported data rate" }
        val was = begin()
        modify(REG_CTRL1, CTRL1_DR_MASK, code shl 3)
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
    fun setAccelOversampling(mode: Int, sleepMode: Int) {
        require(mode in 0..3 && sleepMode in -1..3) { "oversampling mode must be 0-3" }
        val was = begin()
        modify(REG_CTRL2, CTRL2_MODS_MASK, mode)
        if (sleepMode >= 0) {
            modify(REG_CTRL2, CTRL2_SMODS_MASK, sleepMode shl 3)
        }
        mods = mode
        end(was)
    }

    /**
     * Select the magnetometer oversampling ratio code (`m_os`, datasheet Table 203).
     *
     * @param ratio 0-7 for active mode
     * @param sleepRatio 0-7 for Auto-SLEEP (`m_aslp_os`), or -1 to leave it unchanged
     * @throws IOException on bus error
     * @throws IllegalArgumentException if a value is outside 0-7
     */
    fun setMagOversampling(ratio: Int, sleepRatio: Int) {
        require(ratio in 0..7 && sleepRatio in -1..7) { "oversampling ratio must be 0-7" }
        modify(REG_M_CTRL1, M_CTRL1_OS_MASK, ratio shl 2)
        if (sleepRatio >= 0) {
            modify(REG_M_CTRL3, 0x70, sleepRatio shl 4)
        }
    }

    /**
     * Enable or disable the reduced-noise mode (limits the range to ±4 *g*).
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     * @throws IllegalArgumentException when enabling at ±8 *g*
     */
    fun setLowNoise(enabled: Boolean) {
        require(!(enabled && fsG == 8)) { "low-noise mode is not available at ±8 g" }
        val was = begin()
        modify(REG_CTRL1, CTRL1_LNOISE, if (enabled) CTRL1_LNOISE else 0)
        lnoise = enabled
        end(was)
    }

    /**
     * Select 14-bit or 8-bit (fast read) accelerometer output.
     *
     * @param bits 14 or 8
     * @throws IOException on bus error
     * @throws IllegalArgumentException if [bits] is not 14 or 8, or the FIFO is on
     */
    fun setResolution(bits: Int) {
        require(bits == 14 || bits == 8) { "bits must be 14 or 8" }
        require(fifoMode == 0) { "resolution cannot change while the FIFO is on" }
        val was = begin()
        modify(REG_CTRL1, CTRL1_F_READ, if (bits == 8) CTRL1_F_READ else 0)
        fRead = bits == 8
        end(was)
    }

    // --- Data -------------------------------------------------------------------

    /**
     * Read the raw accelerometer counts.
     *
     * @return signed `[x, y, z]` counts (14-bit right-aligned, or 8-bit)
     * @throws IOException on bus error
     */
    fun readRawAccel(): IntArray = readAccelCounts()

    /**
     * Read the raw magnetometer counts.
     *
     * @return signed 16-bit `[x, y, z]` counts (0.1 µT/LSB)
     * @throws IOException on bus error
     */
    fun readRawMag(): IntArray = readMagCounts()

    /**
     * Read both sensors with one burst from 0x01.
     *
     * @return `[ax, ay, az, mx, my, mz]` with acceleration in *g* and field in µT
     * @throws IOException on bus error
     * @throws IllegalStateException if the chip is not in hybrid mode
     */
    fun readHybrid(): DoubleArray {
        check(operatingMode == 2 && hyb) { "readHybrid needs hybrid mode with hyb_autoinc_mode" }
        val nAccel = if (fRead) 3 else 6
        val data = connection.read(REG_OUT_X_MSB, nAccel + 6)
        val a = decodeAccel(data, 0)
        val m = decodeMag(data, nAccel)
        val s = accelScale()
        return doubleArrayOf(a[0] / s, a[1] / s, a[2] / s, m[0] * 0.1, m[1] * 0.1, m[2] * 0.1)
    }

    /**
     * Read the die temperature (`int8(TEMP) * 0.96`; relative and uncalibrated).
     *
     * @return temperature in °C
     * @throws IOException on bus error
     * @throws IllegalStateException in accelerometer-only mode
     */
    fun readTemperature(): Double {
        check(operatingMode != 0) { "temperature needs the magnetometer to be active" }
        return Register.toSigned(readReg(REG_TEMP), 8) * 0.96
    }

    /**
     * Whether a new accelerometer sample is ready (`STATUS.zyxdr`; not valid while the FIFO is on).
     *
     * @throws IOException on bus error
     */
    fun newAccelData(): Boolean = (readReg(REG_STATUS) and STATUS_ZYXDR) != 0

    /**
     * Whether a new magnetometer sample is ready (`M_DR_STATUS.zyxdr`).
     *
     * @throws IOException on bus error
     */
    fun newMagData(): Boolean = (readReg(REG_M_DR_STATUS) and STATUS_ZYXDR) != 0

    /**
     * Read the `STATUS` byte.
     *
     * @throws IOException on bus error
     */
    fun readStatus(): Int = readReg(REG_STATUS)

    /**
     * Read the `M_DR_STATUS` byte.
     *
     * @throws IOException on bus error
     */
    fun readMagStatus(): Int = readReg(REG_M_DR_STATUS)

    /**
     * Read `SYSMOD` bits 1:0.
     *
     * @return 0 standby, 1 wake, 2 sleep
     * @throws IOException on bus error
     */
    fun readSystemMode(): Int = readReg(REG_SYSMOD) and 0x03

    /**
     * Select high-pass-filtered output data. The cutoff in Hz depends on the
     * ODR and oversampling mode (datasheet Table 64; effective ODR halved in
     * hybrid mode).
     *
     * @param enabled true to output high-pass-filtered data (`hpf_out`)
     * @param cutoff `sel` code 0-3
     * @throws IOException on bus error
     * @throws IllegalArgumentException if [cutoff] is outside 0-3
     */
    fun setHighPassOutput(enabled: Boolean, cutoff: Int) {
        require(cutoff in 0..3) { "cutoff must be 0-3" }
        val was = begin()
        modify(REG_XYZ_DATA_CFG, 0x10, if (enabled) 0x10 else 0)
        modify(REG_HP_FILTER_CUTOFF, 0x03, cutoff)
        end(was)
    }

    /**
     * Write the per-axis accelerometer offsets (added to the output, 2 mg/LSB, ±0.256 *g*).
     *
     * @param xG X offset in *g*
     * @param yG Y offset in *g*
     * @param zG Z offset in *g*
     * @throws IOException on bus error
     */
    fun setAccelOffset(xG: Double, yG: Double, zG: Double) {
        val v = doubleArrayOf(xG, yG, zG)
        val data = ByteArray(3) { i ->
            Math.max(-128, Math.min(127, Math.round(v[i] / 0.002).toInt())).toByte()
        }
        val was = begin()
        connection.write(REG_OFF_X, data)
        end(was)
    }

    /**
     * Read the accelerometer offsets.
     *
     * @return `[x, y, z]` in *g*
     * @throws IOException on bus error
     */
    fun getAccelOffset(): DoubleArray {
        val d = connection.read(REG_OFF_X, 3)
        return doubleArrayOf(d[0] * 0.002, d[1] * 0.002, d[2] * 0.002)
    }

    /**
     * Write the hard-iron offsets (15-bit value in bits 15:1, *subtracted* from
     * each measurement, 0.1 µT/LSB).
     *
     * @param xUt X offset to subtract, in µT
     * @param yUt Y offset to subtract, in µT
     * @param zUt Z offset to subtract, in µT
     * @throws IOException on bus error
     */
    fun setMagOffset(xUt: Double, yUt: Double, zUt: Double) {
        val v = doubleArrayOf(xUt, yUt, zUt)
        val data = ByteArray(6)
        for (i in 0 until 3) {
            val value = Math.max(-16384, Math.min(16383, Math.round(v[i] / 0.1).toInt()))
            val word = (value shl 1) and 0xFFFF
            data[2 * i] = (word shr 8).toByte()
            data[2 * i + 1] = (word and 0xFF).toByte()
        }
        connection.write(REG_M_OFF_X_MSB, data)
    }

    /**
     * Read the hard-iron offsets.
     *
     * @return `[x, y, z]` in µT
     * @throws IOException on bus error
     */
    fun getMagOffset(): DoubleArray {
        val d = connection.read(REG_M_OFF_X_MSB, 6)
        val out = DoubleArray(3)
        for (i in 0 until 3) {
            val word = ((d[2 * i].toInt() and 0xFF) shl 8) or (d[2 * i + 1].toInt() and 0xFF)
            out[i] = (Register.toSigned(word, 16) shr 1) * 0.1
        }
        return out
    }

    /**
     * Select raw magnetometer output (`m_raw`).
     *
     * @param raw true = the hard-iron offsets are not applied to the output
     * @throws IOException on bus error
     */
    fun setMagRaw(raw: Boolean) {
        modify(REG_M_CTRL3, 0x80, if (raw) 0x80 else 0)
    }

    /**
     * Enable or disable hardware hard-iron auto-calibration (`m_acal`). Enabling
     * also clears `m_raw` and enables min/max tracking. Rotate the board
     * through all orientations while on; the offsets stay in `M_OFF_*`
     * afterwards (NXP AN4399).
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     */
    fun setMagAutoCalibration(enabled: Boolean) {
        if (enabled) {
            modify(REG_M_CTRL3, 0x80, 0)
            val was = begin()
            modify(REG_M_CTRL2, 0x10, 0)
            end(was)
        }
        modify(REG_M_CTRL1, M_CTRL1_ACAL, if (enabled) M_CTRL1_ACAL else 0)
    }

    /**
     * Read the tracked magnetic minimum and maximum.
     *
     * @return `[minx, miny, minz, maxx, maxy, maxz]` in µT
     * @throws IOException on bus error
     */
    fun readMagMinMax(): DoubleArray {
        val mn = decodeMag(connection.read(REG_MIN_X_MSB, 6), 0)
        val mx = decodeMag(connection.read(REG_MAX_X_MSB, 6), 0)
        return doubleArrayOf(mn[0] * 0.1, mn[1] * 0.1, mn[2] * 0.1, mx[0] * 0.1, mx[1] * 0.1, mx[2] * 0.1)
    }

    /**
     * Reset the min/max registers to 0x7FFF / 0x8000 (`m_maxmin_rst`).
     *
     * @throws IOException on bus error
     */
    fun resetMagMinMax() {
        modify(REG_M_CTRL2, 0x04, 0x04)
    }

    /**
     * Select the automatic magnetic reset (degauss) period.
     *
     * @param mode 0 every ODR cycle, 1 every 16, 2 every 512, 3 disabled
     * @throws IOException on bus error
     * @throws IllegalArgumentException if [mode] is outside 0-3
     */
    fun setMagReset(mode: Int) {
        require(mode in 0..3) { "mode must be 0-3" }
        val was = begin()
        modify(REG_M_CTRL2, 0x03, mode)
        end(was)
    }

    /**
     * Trigger a one-shot magnetic reset (`m_rst`).
     *
     * @throws IOException on bus error
     */
    fun triggerMagReset() {
        modify(REG_M_CTRL1, M_CTRL1_RST, M_CTRL1_RST)
    }

    /**
     * Take one magnetic measurement in STANDBY (`m_ost`); ACTIVE is restored.
     *
     * @return `[x, y, z]` in µT
     * @throws IOException on bus error or a 1 s timeout
     * @throws IllegalStateException in accelerometer-only mode
     */
    fun measureMagOnce(): DoubleArray {
        check(operatingMode != 0) { "one-shot measurement needs the magnetometer" }
        val was = begin()
        modify(REG_M_CTRL1, M_CTRL1_OST, M_CTRL1_OST)
        var done = false
        for (i in 0 until 1000) {
            if ((readReg(REG_M_CTRL1) and M_CTRL1_OST) == 0) {
                done = true
                break
            }
            sleepMs(1)
        }
        if (!done) {
            end(was)
            throw IOException("magnetic one-shot measurement timed out")
        }
        val value = readMag()
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
    fun heading(declinationDeg: Double): Double {
        val a: DoubleArray
        val m: DoubleArray
        if (operatingMode == 2 && hyb) {
            val h = readHybrid()
            a = doubleArrayOf(h[0], h[1], h[2])
            m = doubleArrayOf(h[3], h[4], h[5])
        } else {
            a = readAccel()
            m = readMag()
        }
        val roll = Math.atan2(a[1], a[2])
        val pitch = Math.atan2(-a[0], a[1] * Math.sin(roll) + a[2] * Math.cos(roll))
        val mx2 = m[0] * Math.cos(pitch) + m[2] * Math.sin(pitch)
        val my2 = m[0] * Math.sin(roll) * Math.sin(pitch) + m[1] * Math.cos(roll) -
            m[2] * Math.sin(roll) * Math.cos(pitch)
        var deg = Math.toDegrees(Math.atan2(-my2, mx2)) + declinationDeg
        deg %= 360.0
        if (deg < 0) {
            deg += 360.0
        }
        return deg
    }

    // --- FIFO ---------------------------------------------------------------------

    /**
     * Configure the 32-sample accelerometer FIFO. Changing between two enabled
     * modes goes through disabled. The FIFO holds accelerometer data only.
     *
     * @param mode 0 disabled, 1 circular, 2 fill, 3 trigger
     * @param watermark 0-63 (0 disables the flag)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if an argument is out of range
     */
    fun setFifo(mode: Int, watermark: Int) {
        require(mode in 0..3 && watermark in 0..63) { "invalid FIFO mode or watermark" }
        val was = begin()
        if (fifoMode != 0 && mode != 0 && mode != fifoMode) {
            writeReg(REG_F_SETUP, 0x00)
        }
        writeReg(REG_F_SETUP, (mode shl 6) or watermark)
        fifoMode = mode
        end(was)
    }

    /**
     * Select the FIFO trigger sources (`TRIG_CFG`).
     *
     * @param transientTrigger transient trigger
     * @param orientation orientation trigger
     * @param pulse tap trigger
     * @param motion freefall / motion trigger
     * @throws IOException on bus error
     */
    fun setFifoTrigger(transientTrigger: Boolean, orientation: Boolean, pulse: Boolean, motion: Boolean) {
        val value = (if (transientTrigger) 0x20 else 0) or (if (orientation) 0x10 else 0) or
            (if (pulse) 0x08 else 0) or (if (motion) 0x04 else 0)
        val was = begin()
        writeReg(REG_TRIG_CFG, value)
        end(was)
    }

    /**
     * Number of samples in the FIFO (0-32).
     *
     * @throws IOException on bus error
     */
    fun fifoLevel(): Int = readReg(REG_STATUS) and 0x3F

    /**
     * Decode `F_STATUS` (clears [SOURCE_FIFO]).
     *
     * @return `[overflow, watermark, count]` (the flags are 0 or 1)
     * @throws IOException on bus error
     */
    fun readFifoStatus(): IntArray {
        val v = readReg(REG_STATUS)
        return intArrayOf((v shr 7) and 1, (v shr 6) and 1, v and 0x3F)
    }

    /**
     * Drain accelerometer samples from the FIFO, one 6-byte (3 in 8-bit mode)
     * transaction per sample so the hybrid auto-increment jump to the
     * magnetometer never matters.
     *
     * @param count maximum number of samples; zero or negative for all
     * @return the samples as `[x, y, z]` triples in *g*
     * @throws IOException on bus error
     */
    fun readFifo(count: Int): Array<DoubleArray> {
        var n = fifoLevel()
        if (count > 0 && count < n) {
            n = count
        }
        val s = accelScale()
        return Array(n) {
            val c = readAccelCounts()
            doubleArrayOf(c[0] / s, c[1] / s, c[2] / s)
        }
    }

    /**
     * Enable or disable the FIFO gate (`fifo_gate`).
     *
     * @param enabled true to block FIFO input around WAKE/SLEEP transitions
     * @throws IOException on bus error
     */
    fun setFifoGate(enabled: Boolean) {
        val was = begin()
        modify(REG_CTRL3, 0x80, if (enabled) 0x80 else 0)
        end(was)
    }

    // --- Detectors ----------------------------------------------------------------

    private fun row(): Int = Math.min(dr + (if (operatingMode == 2) 1 else 0), 7)

    private fun stepMs(): Double = STEP_MS[mods][row()]

    private fun pulseStepMs(lpf: Boolean): Double =
        if (lpf) STEP_MS[mods][row()] else PULSE_STEP_NO_LPF_MS[mods][row()]

    /**
     * Configure the freefall / motion detector (`A_FFMT_*`).
     *
     * @param motion true = motion (any enabled axis above the threshold), false = freefall (all below)
     * @param axes axis mask, X = 1, Y = 2, Z = 4
     * @param thresholdG threshold in *g* (0.063 g/LSB)
     * @param debounceMs debounce time in ms
     * @param latch true to latch the event until `A_FFMT_SRC` is read
     * @param debounceClear true to clear (instead of decrement) the debounce counter when the condition is lost
     * @throws IOException on bus error
     */
    fun setFreefallMotion(
        motion: Boolean, axes: Int, thresholdG: Double, debounceMs: Double,
        latch: Boolean, debounceClear: Boolean
    ) {
        val cfg = (if (latch) 0x80 else 0) or (if (motion) 0x40 else 0) or ((axes and 7) shl 3)
        val ths = (if (debounceClear) 0x80 else 0) or threshold(thresholdG)
        val cnt = count(debounceMs, stepMs())
        val was = begin()
        writeReg(REG_A_FFMT_CFG, cfg)
        writeReg(REG_A_FFMT_THS, ths)
        writeReg(REG_A_FFMT_COUNT, cnt)
        end(was)
    }

    /**
     * Read `A_FFMT_SRC` (clears the flags when latched).
     *
     * @throws IOException on bus error
     */
    fun readMotionSource(): Int = readReg(REG_A_FFMT_SRC)

    /**
     * Configure the transient detector (`TRANSIENT_*`).
     *
     * @param axes axis mask, X = 1, Y = 2, Z = 4
     * @param thresholdG threshold in *g*
     * @param debounceMs debounce time in ms
     * @param bypassHpf true to bypass the high-pass filter (motion-like behaviour)
     * @param latch true to latch the event until `TRANSIENT_SRC` is read
     * @throws IOException on bus error
     */
    fun setTransient(axes: Int, thresholdG: Double, debounceMs: Double, bypassHpf: Boolean, latch: Boolean) {
        val cfg = (if (latch) 0x10 else 0) or ((axes and 7) shl 1) or (if (bypassHpf) 1 else 0)
        val cnt = count(debounceMs, stepMs())
        val was = begin()
        writeReg(REG_TRANSIENT_CFG, cfg)
        writeReg(REG_TRANSIENT_THS, threshold(thresholdG))
        writeReg(REG_TRANSIENT_COUNT, cnt)
        end(was)
    }

    /**
     * Read `TRANSIENT_SRC` — **clears** the interrupt.
     *
     * @throws IOException on bus error
     */
    fun readTransientSource(): Int = readReg(REG_TRANSIENT_SRC)

    /**
     * Configure tap (pulse) detection (`PULSE_*`) with the default thresholds
     * (0.5 *g*), 30 ms time limit, 100 ms latency, 300 ms window and both filters on.
     *
     * @param single axis mask (X = 1, Y = 2, Z = 4) for single taps
     * @param doubleTap axis mask for double taps
     * @throws IOException on bus error
     */
    fun setPulse(single: Int, doubleTap: Int) {
        setPulse(single, doubleTap, doubleArrayOf(0.5, 0.5, 0.5), 30.0, 100.0, 300.0,
            false, false, true, true)
    }

    /**
     * Configure tap (pulse) detection (`PULSE_*`).
     *
     * @param single axis mask (X = 1, Y = 2, Z = 4) for single taps
     * @param doubleTap axis mask for double taps
     * @param thresholdG `[x, y, z]` thresholds in *g* (0.063 g/LSB)
     * @param timeLimitMs pulse time limit in ms
     * @param latencyMs latency between the taps in ms
     * @param windowMs window for the second tap in ms
     * @param latch true to latch until `PULSE_SRC` is read
     * @param doubleAbort true to abort double-tap detection on a pulse during the latency window
     * @param lpf true to enable the pulse low-pass filter
     * @param hpf true to enable the pulse high-pass filter
     * @throws IOException on bus error
     */
    fun setPulse(
        single: Int, doubleTap: Int, thresholdG: DoubleArray, timeLimitMs: Double,
        latencyMs: Double, windowMs: Double, latch: Boolean, doubleAbort: Boolean,
        lpf: Boolean, hpf: Boolean
    ) {
        var cfg = (if (doubleAbort) 0x80 else 0) or (if (latch) 0x40 else 0)
        for (i in 0 until 3) {
            if (((single shr i) and 1) != 0) {
                cfg = cfg or (1 shl (2 * i))
            }
            if (((doubleTap shr i) and 1) != 0) {
                cfg = cfg or (2 shl (2 * i))
            }
        }
        val step = pulseStepMs(lpf)
        val tmlt = count(timeLimitMs, step)
        val ltcy = count(latencyMs, 2 * step)
        val wind = count(windowMs, 2 * step)
        val ths = byteArrayOf(
            threshold(thresholdG[0]).toByte(), threshold(thresholdG[1]).toByte(),
            threshold(thresholdG[2]).toByte()
        )
        val was = begin()
        writeReg(REG_PULSE_CFG, cfg)
        connection.write(REG_PULSE_THSX, ths)
        writeReg(REG_PULSE_TMLT, tmlt)
        writeReg(REG_PULSE_LTCY, ltcy)
        writeReg(REG_PULSE_WIND, wind)
        modify(REG_HP_FILTER_CUTOFF, 0x30, (if (hpf) 0 else 0x20) or (if (lpf) 0x10 else 0))
        end(was)
    }

    /**
     * Read `PULSE_SRC` — **clears** the interrupt.
     *
     * @throws IOException on bus error
     */
    fun readPulseSource(): Int = readReg(REG_PULSE_SRC)

    /**
     * Configure orientation (portrait / landscape) detection.
     *
     * @param enabled true to enable the detector (`pl_en`)
     * @param debounceMs debounce time in ms
     * @param thresholdDeg trip angle: 15, 20, 30, 35, 40, 45, 55, 60, 70 or 75
     * @param hysteresis `hys` code 0-7
     * @param zLock Z-lock angle code 0-7
     * @param backFront back/front trip code 0-3
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an invalid argument or when `pl_ths + hys` is outside 1-31
     */
    fun setOrientation(
        enabled: Boolean, debounceMs: Double, thresholdDeg: Int, hysteresis: Int,
        zLock: Int, backFront: Int
    ) {
        var ths = -1
        for (i in PL_DEG.indices) {
            if (PL_DEG[i] == thresholdDeg) {
                ths = PL_CODE[i]
            }
        }
        require(ths >= 0) { "unsupported thresholdDeg" }
        require(hysteresis in 0..7 && zLock in 0..7 && backFront in 0..3) {
            "hysteresis/zLock/backFront out of range"
        }
        require(ths + hysteresis in 1..31) { "pl_ths + hys must be within 1-31" }
        val cnt = count(debounceMs, stepMs())
        val was = begin()
        writeReg(REG_PL_CFG, 0x80 or (if (enabled) 0x40 else 0))
        writeReg(REG_PL_COUNT, cnt)
        writeReg(REG_PL_THS, (ths shl 3) or hysteresis)
        writeReg(REG_PL_BF_ZCOMP, (backFront shl 6) or zLock)
        end(was)
    }

    /**
     * Decode `PL_STATUS` — **clears** `newlp`.
     *
     * @return `[changed, lockout, portraitLandscape, back]` where the flags are 0 or 1 and
     *         `portraitLandscape` is 0 portrait up, 1 portrait down, 2 landscape right, 3 landscape left
     * @throws IOException on bus error
     */
    fun readOrientation(): IntArray {
        val v = readReg(REG_PL_STATUS)
        return intArrayOf((v shr 7) and 1, (v shr 6) and 1, (v shr 1) and 0x03, v and 1)
    }

    /**
     * Configure Auto-SLEEP / WAKE.
     *
     * @param enabled true to enable Auto-SLEEP (`slpe`)
     * @param sleepRateHz 50, 12.5, 6.25 or 1.56 (halved in hybrid mode)
     * @param timeoutS inactivity time before SLEEP in seconds
     * @param wakeSources mask of [SOURCE_TRANSIENT], [SOURCE_ORIENTATION], [SOURCE_PULSE],
     *                    [SOURCE_FF_MT] and [SOURCE_ACCEL_VECM]
     * @throws IOException on bus error
     * @throws IllegalArgumentException for an unsupported rate or source
     */
    fun setAutoSleep(enabled: Boolean, sleepRateHz: Double, timeoutS: Double, wakeSources: Int) {
        var code = -1
        for (i in ASLP_HZ.indices) {
            if (Math.abs(ASLP_HZ[i] - sleepRateHz) <= 0.01 * ASLP_HZ[i]) {
                code = i
            }
        }
        require(code >= 0) { "unsupported sleep rate" }
        require((wakeSources and 0x3E.inv()) == 0) { "unsupported wake source" }
        val step = if (dr == 7) 0.64 else 0.32
        val cnt = count(timeoutS, step)
        val was = begin()
        modify(REG_CTRL1, CTRL1_ASLP_MASK, code shl 6)
        writeReg(REG_ASLP_COUNT, cnt)
        modify(REG_CTRL2, CTRL2_SLPE, if (enabled) CTRL2_SLPE else 0)
        modify(REG_CTRL3, 0x7C, (wakeSources and 0x3E) shl 1)
        end(was)
    }

    // --- Interrupts -----------------------------------------------------------------

    /**
     * Configure the INT1 / INT2 polarity and drive (`ipol`, `pp_od`).
     *
     * @param activeHigh true for active-high
     * @param openDrain true for open-drain (needs an external pull-up)
     * @throws IOException on bus error
     */
    fun setInterruptPins(activeHigh: Boolean, openDrain: Boolean) {
        val was = begin()
        modify(REG_CTRL3, 0x03, (if (activeHigh) 0x02 else 0) or (if (openDrain) 0x01 else 0))
        end(was)
    }

    /**
     * Enable an interrupt source and route it to INT1 or INT2. Only routes the
     * source; the detector behind it is configured with the `set*` methods.
     *
     * @param source a mask of the `SOURCE_*` constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IOException on bus error
     * @throws IllegalArgumentException if [pin] is not 1 or 2
     */
    fun enableInterrupt(source: Int, pin: Int) {
        require(pin == 1 || pin == 2) { "pin must be 1 or 2" }
        val was = begin()
        modify(REG_CTRL4, source, source)
        modify(REG_CTRL5, source, if (pin == 1) source else 0)
        end(was)
    }

    /**
     * Disable an interrupt source (clears its `int_en_*` bit).
     *
     * @param source a mask of the `SOURCE_*` constants
     * @throws IOException on bus error
     */
    fun disableInterrupt(source: Int) {
        val was = begin()
        modify(REG_CTRL4, source, 0)
        end(was)
    }

    /**
     * Read `INT_SOURCE` and clear the pending sources: for every set bit except
     * [SOURCE_DATA_READY] and [SOURCE_ACCEL_VECM] the matching source register
     * is read so the flag (and the pin) clears.
     *
     * @return a mask of the `SOURCE_*` constants
     * @throws IOException on bus error
     */
    fun pollInterrupt(): Int {
        val status = readReg(REG_INT_SOURCE)
        val clear = arrayOf(
            intArrayOf(SOURCE_AUTO_SLEEP, REG_SYSMOD),
            intArrayOf(SOURCE_FIFO, REG_STATUS),
            intArrayOf(SOURCE_TRANSIENT, REG_TRANSIENT_SRC),
            intArrayOf(SOURCE_ORIENTATION, REG_PL_STATUS),
            intArrayOf(SOURCE_PULSE, REG_PULSE_SRC),
            intArrayOf(SOURCE_FF_MT, REG_A_FFMT_SRC)
        )
        for (e in clear) {
            if ((status and e[0]) != 0) {
                readReg(e[1])
            }
        }
        return status
    }

    // --- Escape hatch / self-test ------------------------------------------------------

    /**
     * Read raw register bytes (vector-magnitude, magnetic-threshold and
     * per-axis FFMT registers). No STANDBY handling.
     *
     * @param reg start register address
     * @param length number of bytes
     * @throws IOException on bus error
     */
    fun readRegister(reg: Int, length: Int): ByteArray = connection.read(reg, length)

    /**
     * Write raw register bytes. No STANDBY handling.
     *
     * @param reg start register address
     * @param data bytes to write
     * @throws IOException on bus error
     */
    fun writeRegister(reg: Int, data: ByteArray) {
        connection.write(reg, data)
    }

    /** Average 8 accelerometer readings (accel-only, 14-bit), floor division. */
    private fun averageCounts(): IntArray {
        var sx = 0
        var sy = 0
        var sz = 0
        for (i in 0 until 8) {
            sleepMs(12)
            val c = decodeAccel(connection.read(REG_OUT_X_MSB, 6), 0)
            sx += c[0]
            sy += c[1]
            sz += c[2]
        }
        return intArrayOf(Math.floorDiv(sx, 8), Math.floorDiv(sy, 8), Math.floorDiv(sz, 8))
    }

    /**
     * Run the accelerometer self-test and restore the configuration. STANDBY,
     * ±2 *g*, 100 Hz accelerometer-only, normal oversampling; the difference
     * of two 8-sample averages (before / after `st`) is checked against the
     * datasheet minimum X >= +192, Y >= +270, Z >= +1275 counts.
     *
     * @return `[ok, dx, dy, dz]` with `ok` 1 or 0 and the differences in 14-bit counts
     * @throws IOException on bus error
     */
    fun selfTest(): IntArray {
        val sCtrl1 = readReg(REG_CTRL1)
        val sCtrl2 = readReg(REG_CTRL2)
        val sCfg = readReg(REG_XYZ_DATA_CFG)
        val sM1 = readReg(REG_M_CTRL1)
        val cMode = operatingMode
        val cDr = dr
        val cFs = fsG
        val cMods = mods
        val cLnoise = lnoise
        val cFRead = fRead
        standby()
        writeReg(REG_XYZ_DATA_CFG, 0x00)
        writeReg(REG_M_CTRL1, sM1 and M_CTRL1_HMS_MASK.inv() and 0xFF)
        writeReg(REG_CTRL2, 0x00)
        operatingMode = 0
        dr = 3
        fsG = 2
        mods = 0
        lnoise = false
        fRead = false
        writeReg(REG_CTRL1, (3 shl 3) or CTRL1_ACTIVE)
        sleepMs(turnOnMs())
        val before = averageCounts()
        writeReg(REG_CTRL2, CTRL2_ST)
        sleepMs(25)
        val after = averageCounts()
        writeReg(REG_CTRL2, 0x00)
        standby()
        writeReg(REG_XYZ_DATA_CFG, sCfg)
        writeReg(REG_M_CTRL1, sM1)
        writeReg(REG_CTRL2, sCtrl2)
        operatingMode = cMode
        dr = cDr
        fsG = cFs
        mods = cMods
        lnoise = cLnoise
        fRead = cFRead
        writeReg(REG_CTRL1, sCtrl1)
        if ((sCtrl1 and CTRL1_ACTIVE) != 0) {
            sleepMs(turnOnMs())
        }
        val dx = after[0] - before[0]
        val dy = after[1] - before[1]
        val dz = after[2] - before[2]
        return intArrayOf(if (dx >= 192 && dy >= 270 && dz >= 1275) 1 else 0, dx, dy, dz)
    }
}
