package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * MMA8451Q full interface — extends [MMA8451QMinimal] with the complete chip
 * functionality.
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
 * [onInterrupt] uses `connection.intPin()` if wired (falling edge — the INT
 * pins are active-low by default), otherwise a 5 ms polling thread delivers the
 * events.
 */
open class MMA8451QFull constructor(connection: RegisterConnection) : MMA8451QMinimal(connection) {

    /**
     * Decoded FIFO status, as returned by [readFifoStatus].
     *
     * @param overflow `true` when the FIFO overflowed
     * @param watermark `true` when the FIFO count reached the watermark
     * @param count number of samples in the FIFO (0–32)
     */
    data class FifoStatus(val overflow: Boolean, val watermark: Boolean, val count: Int)

    /**
     * Decoded orientation status, as returned by [readOrientation].
     *
     * @param changed `true` when the orientation changed
     * @param lockout `true` while the orientation is frozen (Z-tilt lockout)
     * @param portraitLandscape 0 portrait up, 1 portrait down, 2 landscape right,
     *        3 landscape left
     * @param back `true` when the device is face down
     */
    data class Orientation(
        val changed: Boolean,
        val lockout: Boolean,
        val portraitLandscape: Int,
        val back: Boolean,
    )

    /**
     * Result of the electrostatic self-test, as returned by [selfTest].
     *
     * @param ok `true` when every axis moved by more than half its typical
     *        self-test delta
     * @param dx measured count delta on the X axis
     * @param dy measured count delta on the Y axis
     * @param dz measured count delta on the Z axis
     */
    data class SelfTestResult(val ok: Boolean, val dx: Int, val dy: Int, val dz: Int)

    companion object {
        /** Interrupt source constants — the INT_SOURCE / CTRL_REG4 / CTRL_REG5 bit positions. */
        const val SOURCE_DATA_READY  = 0x01
        const val SOURCE_FF_MT       = 0x04
        const val SOURCE_PULSE       = 0x08
        const val SOURCE_ORIENTATION = 0x10
        const val SOURCE_TRANSIENT   = 0x20
        const val SOURCE_FIFO        = 0x40
        const val SOURCE_AUTO_SLEEP  = 0x80

        /** All valid interrupt sources combined. */
        const val SOURCE_ALL = SOURCE_DATA_READY or SOURCE_FF_MT or SOURCE_PULSE or
                SOURCE_ORIENTATION or SOURCE_TRANSIENT or SOURCE_FIFO or SOURCE_AUTO_SLEEP

        /** Full-scale range codes in XYZ_DATA_CFG (0x0E) bits 1:0. */
        val FS_CODES = intArrayOf(0x00, 0x01, 0x02)

        /** Supported output data rates in Hz, in code order. */
        val ODR_ORDER = doubleArrayOf(800.0, 400.0, 200.0, 100.0, 50.0, 12.5, 6.25, 1.56)

        /** Output data rate codes in CTRL_REG1 (0x2A) bits 5:3. */
        val DR_CODES = intArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)

        /** Supported Auto-SLEEP output rates in Hz, in code order. */
        val ASLP_RATE_ORDER = doubleArrayOf(50.0, 12.5, 6.25, 1.56)

        /** Auto-SLEEP output rate codes in CTRL_REG1 (0x2A) bits 7:6. */
        val ASLP_RATE_CODES = intArrayOf(0x00, 0x01, 0x02, 0x03)

        /** Oversampling mode codes in CTRL_REG2 (0x2B) bits 1:0 and 4:3. */
        val MODS_CODES = intArrayOf(0x00, 0x01, 0x02, 0x03)

        /**
         * Debounce step in ms, indexed by [ODR index][oversampling mode].
         * Rows: 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz.
         * Columns: normal, low-noise low-power, high resolution, low power.
         */
        val DEBOUNCE_STEPS = arrayOf(
            doubleArrayOf(1.25, 1.25, 1.25, 1.25),
            doubleArrayOf(2.5, 2.5, 2.5, 2.5),
            doubleArrayOf(5.0, 5.0, 2.5, 5.0),
            doubleArrayOf(10.0, 10.0, 2.5, 10.0),
            doubleArrayOf(20.0, 20.0, 2.5, 20.0),
            doubleArrayOf(20.0, 80.0, 2.5, 80.0),
            doubleArrayOf(20.0, 80.0, 2.5, 160.0),
            doubleArrayOf(20.0, 80.0, 2.5, 160.0),
        )

        /** Pulse time-limit step in ms, indexed by [ODR index][oversampling mode], LPF enabled. */
        val PULSE_TMLT_LPF = arrayOf(
            doubleArrayOf(1.25, 1.25, 1.25, 1.25),
            doubleArrayOf(2.5, 2.5, 2.5, 2.5),
            doubleArrayOf(5.0, 5.0, 2.5, 5.0),
            doubleArrayOf(10.0, 10.0, 2.5, 10.0),
            doubleArrayOf(20.0, 20.0, 2.5, 20.0),
            doubleArrayOf(20.0, 80.0, 2.5, 80.0),
            doubleArrayOf(20.0, 80.0, 2.5, 160.0),
            doubleArrayOf(20.0, 80.0, 2.5, 160.0),
        )

        /** Pulse latency / window step in ms, indexed by [ODR index][oversampling mode], LPF enabled. */
        val PULSE_LTCY_LPF = arrayOf(
            doubleArrayOf(2.5, 2.5, 2.5, 2.5),
            doubleArrayOf(5.0, 5.0, 5.0, 5.0),
            doubleArrayOf(10.0, 10.0, 5.0, 10.0),
            doubleArrayOf(20.0, 20.0, 5.0, 20.0),
            doubleArrayOf(40.0, 40.0, 5.0, 40.0),
            doubleArrayOf(40.0, 160.0, 5.0, 160.0),
            doubleArrayOf(40.0, 160.0, 5.0, 320.0),
            doubleArrayOf(40.0, 160.0, 5.0, 320.0),
        )

        /** Pulse time-limit step in ms, indexed by [ODR index][oversampling mode], LPF disabled. */
        val PULSE_TMLT_NO_LPF = arrayOf(
            doubleArrayOf(0.625, 0.625, 0.625, 0.625),
            doubleArrayOf(0.625, 0.625, 0.625, 1.25),
            doubleArrayOf(1.25, 1.25, 0.625, 2.5),
            doubleArrayOf(2.5, 2.5, 0.625, 5.0),
            doubleArrayOf(5.0, 5.0, 0.625, 10.0),
            doubleArrayOf(5.0, 20.0, 0.625, 40.0),
            doubleArrayOf(5.0, 20.0, 0.625, 40.0),
            doubleArrayOf(5.0, 20.0, 0.625, 40.0),
        )

        /** Pulse latency / window step in ms, indexed by [ODR index][oversampling mode], LPF disabled. */
        val PULSE_LTCY_NO_LPF = arrayOf(
            doubleArrayOf(1.25, 1.25, 1.25, 1.25),
            doubleArrayOf(1.25, 1.25, 1.25, 2.5),
            doubleArrayOf(2.5, 2.5, 1.25, 5.0),
            doubleArrayOf(5.0, 5.0, 1.25, 10.0),
            doubleArrayOf(10.0, 10.0, 1.25, 20.0),
            doubleArrayOf(10.0, 40.0, 1.25, 80.0),
            doubleArrayOf(10.0, 40.0, 1.25, 80.0),
            doubleArrayOf(10.0, 40.0, 1.25, 80.0),
        )

        /**
         * Portrait / landscape trip angles and their `P_L_THS<4:0>` codes
         * (datasheet Table 32).
         */
        val ORIENTATION_ANGLES = intArrayOf(15, 20, 30, 35, 40, 45, 55, 60, 70, 75)
        val ORIENTATION_THRESHOLDS = intArrayOf(0x07, 0x09, 0x0C, 0x0D, 0x0F,
                0x10, 0x13, 0x14, 0x17, 0x19)

        /** Detector threshold LSB size in *g* (7-bit thresholds, 0.063 g/LSB). */
        const val THRESHOLD_LSB_G = 0.063

        /** Offset register LSB size in *g* (2 mg/LSB). */
        const val OFFSET_LSB_G = 0.002

        /** Self-test typical count deltas per axis (datasheet Table 2). */
        val SELF_TEST_TYPICAL = intArrayOf(181, 255, 1680)
    }

    /** Callback invoked with the interrupt status byte. */
    @Volatile
    private var callback: IntConsumer? = null

    /** INT-line pin currently armed, or `null` if polling. */
    private var subscribedPin: InputPin? = null

    /** `true` while the polling-fallback thread is running. */
    @Volatile
    private var polling: Boolean = false

    /** Background daemon thread used only when no [InputPin] is available. */
    private var pollThread: Thread? = null

    /**
     * Fixed edge-handler instance, reused across onEdge()/offEdge() calls.
     *
     * A method reference is not guaranteed by the JLS to evaluate to the same
     * object each time, so `offEdge()` could silently fail to remove the
     * handler `onEdge()` registered.
     */
    private val edgeHandler = EdgeHandler { handleEdge() }

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
    fun setRange(rangeG: Int) {
        if (rangeG != 2 && rangeG != 4 && rangeG != 8) {
            throw IllegalArgumentException("range must be one of 2, 4, 8 g")
        }
        if (rangeG == 8 && lowNoiseEnabled) {
            throw IllegalArgumentException("+/-8 g is not available in low-noise mode")
        }
        var xyzCfg = readReg(REG_XYZ_DATA_CFG)
        val fsCode = when (rangeG) {
            4 -> FS_CODES[1]
            8 -> FS_CODES[2]
            else -> FS_CODES[0]
        }
        xyzCfg = (xyzCfg and 0xFC) or fsCode
        standbyWrite(REG_XYZ_DATA_CFG, xyzCfg)
        this.rangeG = rangeG
    }

    /**
     * Set the output data rate.
     *
     * @param rateHz one of 800, 400, 200, 100, 50, 12.5, 6.25 or 1.56 (Hz)
     * @throws IllegalArgumentException if the rate is not supported
     */
    fun setDataRate(rateHz: Double) {
        val idx = odrIndex(rateHz)
        if (idx < 0) {
            throw IllegalArgumentException("rate must be one of 800, 400, 200, 100, 50, 12.5, 6.25, 1.56 Hz")
        }
        var ctrl = readReg(REG_CTRL_REG1)
        ctrl = (ctrl and 0xC7) or (DR_CODES[idx] shl 3)
        standbyWrite(REG_CTRL_REG1, ctrl)
        odrHz = ODR_ORDER[idx]
    }

    /**
     * Set the oversampling mode.
     *
     * @param mode 0 normal, 1 low-noise low-power, 2 high resolution, 3 low
     *        power — applies in WAKE / ACTIVE mode
     * @param sleepMode oversampling mode in SLEEP; negative means "same as mode"
     * @throws IllegalArgumentException if a mode is out of range
     */
    fun setOversampling(mode: Int, sleepMode: Int = -1) {
        if (mode < 0 || mode > 3) {
            throw IllegalArgumentException("mode must be 0, 1, 2 or 3")
        }
        val smods = if (sleepMode < 0) mode else sleepMode
        if (smods < 0 || smods > 3) {
            throw IllegalArgumentException("sleepMode must be 0, 1, 2 or 3")
        }
        var ctrl2 = readReg(REG_CTRL_REG2)
        ctrl2 = (ctrl2 and 0xE7) or (MODS_CODES[smods] shl 3) or MODS_CODES[mode]
        standbyWrite(REG_CTRL_REG2, ctrl2)
        oversampling = mode
        sleepOversampling = smods
    }

    /**
     * Enable or disable the reduced-noise (low-noise) mode.
     *
     * Low-noise mode limits the dynamic range to ±4 *g*.
     *
     * @param enabled `true` to enable low-noise mode
     * @throws IllegalArgumentException if low-noise mode is requested while the
     *         range is ±8 *g*
     */
    fun setLowNoise(enabled: Boolean) {
        if (enabled && rangeG == 8) {
            throw IllegalArgumentException("low-noise mode limits the range to +/-4 g")
        }
        var ctrl = readReg(REG_CTRL_REG1)
        ctrl = if (enabled) ctrl or CTRL1_LNOISE else ctrl and CTRL1_LNOISE.inv() and 0xFF
        standbyWrite(REG_CTRL_REG1, ctrl)
        lowNoiseEnabled = enabled
    }

    /**
     * Select the output resolution.
     *
     * @param bits 14 for the normal two-byte output, 8 for fast read (MSB
     *        register only)
     * @throws IllegalArgumentException if the resolution is not 14 or 8 bits, or
     *         if the change is attempted while the FIFO is enabled
     */
    fun setResolution(bits: Int) {
        if (bits != 8 && bits != 14) {
            throw IllegalArgumentException("bits must be 8 or 14")
        }
        if (fifoMode != 0) {
            throw IllegalArgumentException("resolution cannot change while the FIFO is enabled")
        }
        var ctrl = readReg(REG_CTRL_REG1)
        ctrl = if (bits == 8) ctrl or CTRL1_F_READ else ctrl and CTRL1_F_READ.inv() and 0xFF
        standbyWrite(REG_CTRL_REG1, ctrl)
        this.bits = bits
    }

    /**
     * Switch the chip to STANDBY mode.
     */
    fun standby() {
        val ctrl = readReg(REG_CTRL_REG1)
        if ((ctrl and CTRL1_ACTIVE) == 0) {
            return
        }
        writeReg(REG_CTRL_REG1, ctrl and CTRL1_ACTIVE.inv() and 0xFF)
    }

    /**
     * Switch the chip to ACTIVE mode and wait for the turn-on time.
     */
    fun activate() {
        val ctrl = readReg(REG_CTRL_REG1)
        if ((ctrl and CTRL1_ACTIVE) != 0) {
            return
        }
        writeReg(REG_CTRL_REG1, ctrl or CTRL1_ACTIVE)
        waitTurnOn()
    }

    /**
     * Perform a software reset.
     *
     * All registers return to their reset values and the chip ends up in
     * STANDBY. Re-run the initialization (by constructing a new driver)
     * afterwards.
     */
    fun reset() {
        writeReg(REG_CTRL_REG2, CTRL2_RST)
        delay(1)
        waitResetDone()
        rangeG = DEFAULT_RANGE_G
        odrHz = DEFAULT_ODR_HZ
        bits = DEFAULT_BITS
        lowNoiseEnabled = false
        oversampling = 0
        sleepOversampling = 0
        fifoMode = 0
    }

    /**
     * Enable or disable the high-pass-filtered output and select its cutoff.
     *
     * @param enabled `true` to route high-pass-filtered data to the output
     *        registers and the FIFO
     * @param cutoff high-pass cutoff selector 0–3 (the actual frequency depends
     *        on the ODR and oversampling mode; datasheet Table 23)
     * @throws IllegalArgumentException if the cutoff is out of range
     */
    fun setHighPassOutput(enabled: Boolean, cutoff: Int = 0) {
        if (cutoff < 0 || cutoff > 3) {
            throw IllegalArgumentException("cutoff must be 0, 1, 2 or 3")
        }
        var xyzCfg = readReg(REG_XYZ_DATA_CFG)
        xyzCfg = if (enabled) xyzCfg or 0x10 else xyzCfg and 0x10.inv() and 0xFF
        var hpFilter = readReg(REG_HP_FILTER)
        hpFilter = (hpFilter and 0xFC) or cutoff
        standbyWriteMulti(REG_XYZ_DATA_CFG, xyzCfg, arrayOf(intArrayOf(REG_HP_FILTER, hpFilter)))
    }

    /**
     * Set the per-axis zero-*g* offset correction.
     *
     * @param xG X offset in *g*
     * @param yG Y offset in *g*
     * @param zG Z offset in *g*
     */
    fun setOffset(xG: Double, yG: Double, zG: Double) {
        val ox = offsetCode(xG)
        val oy = offsetCode(yG)
        val oz = offsetCode(zG)
        standbyWriteMulti(REG_OFF_X, ox, arrayOf(
                intArrayOf(REG_OFF_Y, oy), intArrayOf(REG_OFF_Z, oz)))
    }

    /**
     * Read the per-axis offset correction.
     *
     * @return Triple of (x, y, z) offsets in *g*.
     */
    fun getOffset(): DoubleArray {
        val ox = Register.toSigned(readReg(REG_OFF_X), 8)
        val oy = Register.toSigned(readReg(REG_OFF_Y), 8)
        val oz = Register.toSigned(readReg(REG_OFF_Z), 8)
        return doubleArrayOf(ox * OFFSET_LSB_G, oy * OFFSET_LSB_G, oz * OFFSET_LSB_G)
    }

    // =========================================================================
    // Status
    // =========================================================================

    /**
     * Read raw signed acceleration counts.
     *
     * @return Triple of (x, y, z) signed counts — 14-bit right-aligned, or 8-bit
     *         in fast-read mode.
     */
    fun readRaw(): IntArray {
        val raw = readBurst(REG_OUT_X_MSB, 6)
        if (bits == 8) {
            return intArrayOf(
                Register.toSigned(raw[0].toInt() and 0xFF, 8),
                Register.toSigned(raw[2].toInt() and 0xFF, 8),
                Register.toSigned(raw[4].toInt() and 0xFF, 8))
        }
        val rx = (((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)) shr 2
        val ry = (((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)) shr 2
        val rz = (((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)) shr 2
        return intArrayOf(
            Register.toSigned(rx, 14),
            Register.toSigned(ry, 14),
            Register.toSigned(rz, 14))
    }

    /**
     * Check whether a new sample set is ready.
     *
     * @return `true` when `STATUS.ZYXDR` is set — not valid while the FIFO is
     *         enabled, use [fifoLevel] instead.
     */
    fun newDataAvailable(): Boolean {
        val status = readReg(REG_STATUS)
        return (status and STATUS_ZYXDR) != 0
    }

    /**
     * Read the STATUS register.
     *
     * @return the STATUS byte (`ZYXOW` bit 7 … `XDR` bit 0).
     */
    fun readStatus(): Int = readReg(REG_STATUS)

    /**
     * Read the current power mode.
     *
     * @return `SYSMOD` bits 1:0 — 0 standby, 1 wake, 2 sleep.
     */
    fun readSystemMode(): Int = readReg(REG_SYSMOD) and 0x03

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
    fun setFifo(mode: Int, watermark: Int = 0) {
        if (mode < 0 || mode > 3) {
            throw IllegalArgumentException("mode must be 0, 1, 2 or 3")
        }
        if (watermark < 0 || watermark > 32) {
            throw IllegalArgumentException("watermark must be 0..32")
        }
        if (mode != 0 && fifoMode != 0 && fifoMode != mode) {
            // The mode must go through 00 to switch between enabled modes.
            writeFifoSetup(0, 0)
        }
        writeFifoSetup(mode, watermark)
        fifoMode = mode
    }

    /**
     * Select the FIFO trigger sources.
     *
     * @param transientEvent enable the transient detector as a trigger source
     * @param orientation enable the orientation detector as a trigger source
     * @param pulse enable the pulse (tap) detector as a trigger source
     * @param motion enable the freefall / motion detector as a trigger source
     */
    fun setFifoTrigger(transientEvent: Boolean = false, orientation: Boolean = false,
                       pulse: Boolean = false, motion: Boolean = false) {
        var trig = 0
        if (transientEvent) trig = trig or 0x20
        if (orientation) trig = trig or 0x10
        if (pulse) trig = trig or 0x08
        if (motion) trig = trig or 0x04
        standbyWrite(REG_TRIG_CFG, trig)
    }

    /**
     * Read the number of samples currently in the FIFO.
     *
     * @return the sample count (0–32).
     */
    fun fifoLevel(): Int = readReg(REG_STATUS) and 0x3F

    /**
     * Read and decode the FIFO status.
     *
     * Reading F_STATUS clears the FIFO interrupt source.
     *
     * @return the decoded [FifoStatus].
     */
    fun readFifoStatus(): FifoStatus {
        val status = readReg(REG_STATUS)
        return FifoStatus((status and 0x80) != 0, (status and 0x40) != 0, status and 0x3F)
    }

    /**
     * Drain samples from the FIFO.
     *
     * One sample is read per transaction starting at 0x01 (6 bytes, or 3 in
     * 8-bit mode).
     *
     * @param count maximum number of samples to read; negative reads all
     *        samples currently in the FIFO
     * @return array of (x, y, z) acceleration triples in *g*.
     */
    fun readFifo(count: Int = -1): Array<DoubleArray> {
        val n = if (count < 0) fifoLevel() else count
        val samples = Array(n) { DoubleArray(3) }
        for (i in 0 until n) {
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
     * @param motion `true` for motion detection (any enabled axis above the
     *        threshold), `false` for freefall (all enabled axes below the
     *        threshold)
     * @param axes axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG threshold in *g* (0.063 g/LSB, valid to 8 *g*)
     * @param debounceMs debounce time in ms
     * @param latch `true` to latch the event flags until they are read
     * @param debounceClear `true` to clear the debounce counter when the
     *        condition is lost (`DBCNTM`)
     * @throws IllegalArgumentException if the axis mask is out of range
     */
    fun setFreefallMotion(motion: Boolean, axes: Int = 7, thresholdG: Double = 0.0,
                          debounceMs: Double = 0.0, latch: Boolean = false,
                          debounceClear: Boolean = false) {
        if (axes < 0 || axes > 7) {
            throw IllegalArgumentException("axes must be a 3-bit mask (0..7)")
        }
        var cfg = 0
        if (latch) cfg = cfg or 0x80
        if (motion) cfg = cfg or 0x40
        if ((axes and 0x04) != 0) cfg = cfg or 0x20
        if ((axes and 0x02) != 0) cfg = cfg or 0x10
        if ((axes and 0x01) != 0) cfg = cfg or 0x08
        var ths = thresholdCode(thresholdG)
        if (debounceClear) {
            ths = ths or 0x80
        }
        val count = debounceCode(debounceMs)
        standbyWriteMulti(REG_FF_MT_CFG, cfg, arrayOf(
                intArrayOf(REG_FF_MT_THS, ths), intArrayOf(REG_FF_MT_COUNT, count)))
    }

    /**
     * Read the freefall / motion event flags.
     *
     * @return the `FF_MT_SRC` byte — cleared when the events are latched.
     */
    fun readMotionSource(): Int = readReg(REG_FF_MT_SRC)

    /**
     * Configure the transient detector.
     *
     * @param axes axis mask — bit 0 = X, bit 1 = Y, bit 2 = Z
     * @param thresholdG threshold in *g* (0.063 g/LSB)
     * @param debounceMs debounce time in ms
     * @param bypassHpf `true` to bypass the high-pass filter (the detector then
     *        behaves like motion detection)
     * @param latch `true` to latch the event flags until they are read
     * @throws IllegalArgumentException if the axis mask is out of range
     */
    fun setTransient(axes: Int = 7, thresholdG: Double = 0.0, debounceMs: Double = 0.0,
                     bypassHpf: Boolean = false, latch: Boolean = false) {
        if (axes < 0 || axes > 7) {
            throw IllegalArgumentException("axes must be a 3-bit mask (0..7)")
        }
        var cfg = 0
        if (latch) cfg = cfg or 0x10
        if ((axes and 0x04) != 0) cfg = cfg or 0x08
        if ((axes and 0x02) != 0) cfg = cfg or 0x04
        if ((axes and 0x01) != 0) cfg = cfg or 0x02
        if (bypassHpf) cfg = cfg or 0x01
        val ths = thresholdCode(thresholdG)
        val count = debounceCode(debounceMs)
        standbyWriteMulti(REG_TRANSIENT_CFG, cfg, arrayOf(
                intArrayOf(REG_TRANSIENT_THS, ths), intArrayOf(REG_TRANSIENT_CNT, count)))
    }

    /**
     * Read the transient event flags.
     *
     * Reading clears the flags and the interrupt source.
     *
     * @return the `TRANSIENT_SRC` byte.
     */
    fun readTransientSource(): Int = readReg(REG_TRANSIENT_SRC)

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
     * @param latch `true` to latch the event flags until they are read
     * @param doubleAbort `true` to abort double-tap detection when a pulse
     *        starts during the latency window
     * @param lpf `true` to enable the pulse low-pass filter
     * @param hpf `true` to keep the pulse high-pass filter enabled
     * @throws IllegalArgumentException if an axis mask is out of range
     */
    fun setPulse(single: Int = 0, doubleTap: Int = 0, thresholdX: Double = 0.5,
                 thresholdY: Double = 0.5, thresholdZ: Double = 0.5, timeLimitMs: Double = 30.0,
                 latencyMs: Double = 100.0, windowMs: Double = 300.0, latch: Boolean = false,
                 doubleAbort: Boolean = false, lpf: Boolean = true, hpf: Boolean = true) {
        if (single < 0 || single > 7 || doubleTap < 0 || doubleTap > 7) {
            throw IllegalArgumentException("single and doubleTap must be 3-bit axis masks (0..7)")
        }
        var cfg = 0
        if (doubleAbort) cfg = cfg or 0x80
        if (latch) cfg = cfg or 0x40
        if ((doubleTap and 0x04) != 0) cfg = cfg or 0x20
        if ((doubleTap and 0x02) != 0) cfg = cfg or 0x10
        if ((doubleTap and 0x01) != 0) cfg = cfg or 0x08
        if ((single and 0x04) != 0) cfg = cfg or 0x04
        if ((single and 0x02) != 0) cfg = cfg or 0x02
        if ((single and 0x01) != 0) cfg = cfg or 0x01
        val thsx = thresholdCode(thresholdX)
        val thsy = thresholdCode(thresholdY)
        val thsz = thresholdCode(thresholdZ)
        val tmlt = pulseTimeCode(timeLimitMs, lpf, true)
        val ltcy = pulseTimeCode(latencyMs, lpf, false)
        val wind = pulseTimeCode(windowMs, lpf, false)
        var hpFilter = readReg(REG_HP_FILTER)
        hpFilter = if (hpf) hpFilter and 0x20.inv() and 0xFF else hpFilter or 0x20
        hpFilter = if (lpf) hpFilter or 0x10 else hpFilter and 0x10.inv() and 0xFF
        standbyWriteMulti(REG_PULSE_CFG, cfg, arrayOf(
                intArrayOf(REG_PULSE_THSX, thsx), intArrayOf(REG_PULSE_THSY, thsy),
                intArrayOf(REG_PULSE_THSZ, thsz), intArrayOf(REG_PULSE_TMLT, tmlt),
                intArrayOf(REG_PULSE_LTCY, ltcy), intArrayOf(REG_PULSE_WIND, wind),
                intArrayOf(REG_HP_FILTER, hpFilter)))
    }

    /**
     * Read the tap event flags.
     *
     * Reading clears the flags and the interrupt source.
     *
     * @return the `PULSE_SRC` byte.
     */
    fun readPulseSource(): Int = readReg(REG_PULSE_SRC)

    /**
     * Configure the portrait / landscape orientation detector.
     *
     * @param enabled `true` to enable the detector
     * @param debounceMs debounce time in ms
     * @param thresholdDeg trip angle — one of 15, 20, 30, 35, 40, 45, 55, 60,
     *        70 or 75 degrees
     * @param hysteresis hysteresis code 0–7 (adds 0/±4/±7/±11/±14/±17/±21/±24 degrees)
     * @param zLock Z-lock angle code 0–7 (14/18/21/25/29/33/37/42 degrees)
     * @param backFront back/front threshold code 0–3
     * @throws IllegalArgumentException if the trip angle is not supported, a code
     *         is out of range, or the register constraint `THS + HYS` in 1…31 is
     *         violated
     */
    fun setOrientation(enabled: Boolean = true, debounceMs: Double = 0.0, thresholdDeg: Int = 45,
                       hysteresis: Int = 4, zLock: Int = 4, backFront: Int = 1) {
        val ths = orientationThreshold(thresholdDeg)
        if (hysteresis < 0 || hysteresis > 7) {
            throw IllegalArgumentException("hysteresis must be 0..7")
        }
        if (zLock < 0 || zLock > 7) {
            throw IllegalArgumentException("zLock must be 0..7")
        }
        if (backFront < 0 || backFront > 3) {
            throw IllegalArgumentException("backFront must be 0..3")
        }
        if (ths + hysteresis < 1 || ths + hysteresis > 31) {
            throw IllegalArgumentException("thresholdDeg + hysteresis must give a register value in 1..31")
        }
        // PL_CFG: DBCNTM (bit 7) stays at its reset value 1, PL_EN (bit 6)
        // enables the detector.
        val plCfg = 0x80 or (if (enabled) 0x40 else 0x00)
        val plThs = (ths shl 3) or hysteresis
        val plBf = ((backFront and 0x03) shl 6) or (zLock and 0x07)
        val count = debounceCode(debounceMs)
        standbyWriteMulti(REG_PL_CFG, plCfg, arrayOf(
                intArrayOf(REG_PL_COUNT, count), intArrayOf(REG_P_L_THS, plThs),
                intArrayOf(REG_PL_BF_ZCOMP, plBf)))
    }

    /**
     * Read the orientation status.
     *
     * Reading clears the `NEWLP` flag.
     *
     * @return the decoded [Orientation].
     */
    fun readOrientation(): Orientation {
        val status = readReg(REG_PL_STATUS)
        return Orientation((status and 0x80) != 0, (status and 0x40) != 0,
                (status shr 1) and 0x03, (status and 0x01) != 0)
    }

    /**
     * Configure Auto-SLEEP / Auto-WAKE.
     *
     * @param enabled `true` to enable Auto-SLEEP
     * @param sleepRateHz output rate while asleep — 50, 12.5, 6.25 or 1.56 Hz
     * @param timeoutS inactivity timeout in seconds (0–81 s)
     * @param wakeSources mask of wake sources — any combination of
     *        [SOURCE_TRANSIENT], [SOURCE_ORIENTATION], [SOURCE_PULSE] and
     *        [SOURCE_FF_MT]
     * @throws IllegalArgumentException if the sleep rate is not supported
     */
    fun setAutoSleep(enabled: Boolean, sleepRateHz: Double = 50.0, timeoutS: Double = 10.0,
                      wakeSources: Int = 0) {
        val rateIdx = aslpRateIndex(sleepRateHz)
        if (rateIdx < 0) {
            throw IllegalArgumentException("sleepRateHz must be one of 50, 12.5, 6.25, 1.56")
        }
        var ctrl1 = readReg(REG_CTRL_REG1)
        ctrl1 = (ctrl1 and 0x3F) or (ASLP_RATE_CODES[rateIdx] shl 6)
        var ctrl2 = readReg(REG_CTRL_REG2)
        ctrl2 = if (enabled) ctrl2 or 0x04 else ctrl2 and 0x04.inv() and 0xFF
        var ctrl3 = readReg(REG_CTRL_REG3) and 0x07
        if ((wakeSources and SOURCE_TRANSIENT) != 0) ctrl3 = ctrl3 or 0x40
        if ((wakeSources and SOURCE_ORIENTATION) != 0) ctrl3 = ctrl3 or 0x20
        if ((wakeSources and SOURCE_PULSE) != 0) ctrl3 = ctrl3 or 0x10
        if ((wakeSources and SOURCE_FF_MT) != 0) ctrl3 = ctrl3 or 0x08
        val stepS = if (odrHz <= 1.56) 0.64 else 0.32
        val aslpCount = clamp(Math.round(timeoutS / stepS).toInt(), 0, 255)
        standbyWriteMulti(REG_CTRL_REG1, ctrl1, arrayOf(
                intArrayOf(REG_CTRL_REG2, ctrl2), intArrayOf(REG_CTRL_REG3, ctrl3),
                intArrayOf(REG_ASLP_COUNT, aslpCount)))
    }

    /**
     * Enable or disable the FIFO gate.
     *
     * With the gate enabled the FIFO blocks new input around WAKE / SLEEP
     * transitions until it has been emptied.
     *
     * @param enabled `true` to enable the FIFO gate
     */
    fun setFifoGate(enabled: Boolean) {
        var ctrl3 = readReg(REG_CTRL_REG3)
        ctrl3 = if (enabled) ctrl3 or 0x80 else ctrl3 and 0x80.inv() and 0xFF
        standbyWrite(REG_CTRL_REG3, ctrl3)
    }

    /**
     * Configure the interrupt pin polarity and drive.
     *
     * Both INT pins share these settings.
     *
     * @param activeHigh `true` for active-high interrupts (the default is
     *        active-low)
     * @param openDrain `true` for open-drain drive (the default is push-pull)
     */
    fun setInterruptPins(activeHigh: Boolean = false, openDrain: Boolean = false) {
        var ctrl3 = readReg(REG_CTRL_REG3)
        ctrl3 = if (activeHigh) ctrl3 or 0x02 else ctrl3 and 0x02.inv() and 0xFF
        ctrl3 = if (openDrain) ctrl3 or 0x01 else ctrl3 and 0x01.inv() and 0xFF
        standbyWrite(REG_CTRL_REG3, ctrl3)
    }

    // =========================================================================
    // Interrupts
    // =========================================================================

    /**
     * Enable one interrupt source and route it to INT1 or INT2.
     *
     * @param source one of the `SOURCE_*` constants
     * @param pin 1 for INT1, 2 for INT2
     * @throws IllegalArgumentException if the source or the pin is invalid
     */
    fun enableInterrupt(source: Int, pin: Int = 1) {
        checkSource(source)
        if (pin != 1 && pin != 2) {
            throw IllegalArgumentException("pin must be 1 or 2")
        }
        val ctrl4 = readReg(REG_CTRL_REG4) or source
        var ctrl5 = readReg(REG_CTRL_REG5)
        ctrl5 = if (pin == 1) ctrl5 or source else ctrl5 and source.inv() and 0xFF
        standbyWriteMulti(REG_CTRL_REG4, ctrl4, arrayOf(intArrayOf(REG_CTRL_REG5, ctrl5)))
    }

    /**
     * Disable one interrupt source.
     *
     * @param source one of the `SOURCE_*` constants
     * @throws IllegalArgumentException if the source is invalid
     */
    fun disableInterrupt(source: Int) {
        checkSource(source)
        val ctrl4 = readReg(REG_CTRL_REG4) and source.inv() and 0xFF
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
    fun onInterrupt(callback: (Int) -> Unit, pin: Int = 1) {
        offInterrupt()
        this.callback = IntConsumer { callback(it) }
        val intPin = conn.intPin()
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
    fun offInterrupt(pin: Int = 1) {
        callback = null
        subscribedPin?.let {
            it.offEdge(edgeHandler)
            subscribedPin = null
        }
        stopPolling()
    }

    /**
     * Read the interrupt status and clear the per-source flags.
     *
     * Every set bit except [SOURCE_DATA_READY] additionally reads the matching
     * source register so the flag (and the interrupt pin) is cleared.
     * [SOURCE_DATA_READY] stays set — clear it by reading the data.
     *
     * @return the `INT_SOURCE` byte.
     */
    fun pollInterrupt(): Int {
        val status = readReg(REG_INT_SOURCE)
        if ((status and SOURCE_FIFO) != 0) {
            readReg(REG_STATUS)
        }
        if ((status and SOURCE_FF_MT) != 0) {
            readReg(REG_FF_MT_SRC)
        }
        if ((status and SOURCE_PULSE) != 0) {
            readReg(REG_PULSE_SRC)
        }
        if ((status and SOURCE_ORIENTATION) != 0) {
            readReg(REG_PL_STATUS)
        }
        if ((status and SOURCE_TRANSIENT) != 0) {
            readReg(REG_TRANSIENT_SRC)
        }
        if ((status and SOURCE_AUTO_SLEEP) != 0) {
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
     * @return the [SelfTestResult].
     */
    fun selfTest(): SelfTestResult {
        val savedRange = rangeG
        val savedBits = bits
        val savedMods = oversampling
        val savedSmods = sleepOversampling
        val savedLnoise = lowNoiseEnabled
        val savedFifo = fifoMode
        val savedOdr = odrHz
        val before: IntArray
        val after: IntArray
        try {
            setFifo(0, 0)
            standby()
            setRange(4)
            setDataRate(100.0)
            setOversampling(0, -1)
            setResolution(14)
            activate()
            before = averageCounts()
            val ctrl2 = readReg(REG_CTRL_REG2)
            writeReg(REG_CTRL_REG2, ctrl2 or CTRL2_ST)
            delay(kotlin.math.ceil(turnOnTimeMs()).toLong())
            after = averageCounts()
            writeReg(REG_CTRL_REG2, ctrl2 and CTRL2_ST.inv() and 0xFF)
        } finally {
            // Restore the previous configuration.
            standby()
            setResolution(if (savedFifo == 0) savedBits else 14)
            setOversampling(savedMods, savedSmods)
            setDataRate(savedOdr)
            setLowNoise(savedLnoise)
            setRange(savedRange)
            if (savedFifo != 0) {
                setFifo(savedFifo, 0)
            }
            activate()
        }
        val dx = after[0] - before[0]
        val dy = after[1] - before[1]
        val dz = after[2] - before[2]
        val ok = dx > SELF_TEST_TYPICAL[0] / 2 &&
                dy > SELF_TEST_TYPICAL[1] / 2 &&
                dz > SELF_TEST_TYPICAL[2] / 2
        return SelfTestResult(ok, dx, dy, dz)
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Average a few raw samples and return the mean counts.
     *
     * @return Triple of the mean X, Y, Z counts.
     */
    protected fun averageCounts(): IntArray {
        val total = IntArray(3)
        for (i in 0 until 4) {
            val sample = readRaw()
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
    protected fun thresholdCode(thresholdG: Double): Int =
        clamp(Math.round(thresholdG / THRESHOLD_LSB_G).toInt(), 0, 127)

    /**
     * Convert an offset in *g* to the signed 8-bit register code.
     *
     * @param offsetG offset in *g*
     * @return the register code (-128…127)
     */
    protected fun offsetCode(offsetG: Double): Int =
        clamp(Math.round(offsetG / OFFSET_LSB_G).toInt(), -128, 127)

    /**
     * Convert a debounce time in ms to the 8-bit counter code.
     *
     * @param debounceMs debounce time in ms
     * @return the counter code (0–255)
     */
    protected fun debounceCode(debounceMs: Double): Int {
        var idx = odrIndex(odrHz)
        if (idx < 0) idx = 3
        val step = DEBOUNCE_STEPS[idx][oversampling and 3]
        return clamp(Math.round(debounceMs / step).toInt(), 0, 255)
    }

    /**
     * Convert a pulse timing in ms to the 8-bit register code.
     *
     * @param timeMs the timing in ms
     * @param lpf `true` when the pulse low-pass filter is enabled
     * @param isTimeLimit `true` for the time limit register, `false` for the
     *        latency and window registers
     * @return the register code (0–255)
     */
    protected fun pulseTimeCode(timeMs: Double, lpf: Boolean, isTimeLimit: Boolean): Int {
        var idx = odrIndex(odrHz)
        if (idx < 0) idx = 3
        val step = if (lpf) {
            (if (isTimeLimit) PULSE_TMLT_LPF else PULSE_LTCY_LPF)[idx][oversampling and 3]
        } else {
            (if (isTimeLimit) PULSE_TMLT_NO_LPF else PULSE_LTCY_NO_LPF)[idx][oversampling and 3]
        }
        return clamp(Math.round(timeMs / step).toInt(), 0, 255)
    }

    /**
     * Write `F_SETUP` (mode bits 7:6, watermark bits 5:0).
     *
     * @param mode FIFO mode (0–3)
     * @param watermark FIFO watermark (0–32)
     */
    protected fun writeFifoSetup(mode: Int, watermark: Int) {
        val value = ((mode and 0x03) shl 6) or (watermark and 0x3F)
        standbyWrite(REG_F_SETUP, value)
    }

    /**
     * Look up the index of an output data rate in [ODR_ORDER].
     *
     * @param rateHz the rate in Hz
     * @return the index, or -1 when the rate is not supported
     */
    protected fun odrIndex(rateHz: Double): Int {
        for (i in ODR_ORDER.indices) {
            if (Math.abs(ODR_ORDER[i] - rateHz) < 1e-6) {
                return i
            }
        }
        return -1
    }

    /**
     * Look up the index of a sleep rate in [ASLP_RATE_ORDER].
     *
     * @param rateHz the rate in Hz
     * @return the index, or -1 when the rate is not supported
     */
    protected fun aslpRateIndex(rateHz: Double): Int {
        for (i in ASLP_RATE_ORDER.indices) {
            if (Math.abs(ASLP_RATE_ORDER[i] - rateHz) < 1e-6) {
                return i
            }
        }
        return -1
    }

    /**
     * Look up the `P_L_THS` code of a trip angle.
     *
     * @param thresholdDeg the trip angle in degrees
     * @return the register code
     * @throws IllegalArgumentException if the angle is not one of the supported values
     */
    protected fun orientationThreshold(thresholdDeg: Int): Int {
        for (i in ORIENTATION_ANGLES.indices) {
            if (ORIENTATION_ANGLES[i] == thresholdDeg) {
                return ORIENTATION_THRESHOLDS[i]
            }
        }
        throw IllegalArgumentException("thresholdDeg must be one of 15, 20, 30, 35, 40, 45, 55, 60, 70, 75")
    }

    /**
     * Verify that an interrupt source is valid.
     *
     * @param source the source value to check
     * @throws IllegalArgumentException if the source is not a valid `SOURCE_*` value
     */
    protected fun checkSource(source: Int) {
        if (source == 0 || (source and SOURCE_ALL.inv()) != 0) {
            throw IllegalArgumentException("invalid interrupt source 0x${Integer.toHexString(source)}")
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
    protected fun clamp(value: Int, low: Int, high: Int): Int = when {
        value < low -> low
        value > high -> high
        else -> value
    }

    private fun startPolling() {
        polling = true
        val thread = Thread {
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }
        thread.isDaemon = true
        thread.name = "mma8451q-poll"
        pollThread = thread
        thread.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread?.let {
            it.interrupt()
            pollThread = null
        }
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            val cb = callback
            if (status != 0 && cb != null) {
                cb.accept(status)
            }
        } catch (e: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
