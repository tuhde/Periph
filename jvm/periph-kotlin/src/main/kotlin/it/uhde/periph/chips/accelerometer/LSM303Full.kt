package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * LSM303 full interface — extends [LSM303Minimal] with complete functionality.
 *
 * Adds accelerometer range / data-rate / power-mode configuration, per-axis
 * enable, raw data access, the high-pass filter, the 32-level FIFO, two
 * inertial interrupt generators with free-fall and 6D/4D orientation,
 * single/double click detection, interrupt routing across INT1/INT2, the
 * magnetometer gain / data-rate / operating-mode controls, the relative
 * temperature sensor, the software compass heading, and memory reboot.
 *
 * Interrupt API: [onInterrupt] subscribes to INT1 or INT2 assertions;
 * delivery uses the accelerometer connection's `intPin()` when wired (both
 * INT lines typically share one edge input) and falls back to a 5 ms polling
 * thread otherwise. The callback receives the combined status int from
 * [pollInterrupt].
 */
open class LSM303Full @Throws(IOException::class) constructor(
    accelConnection: RegisterConnection,
    magConnection: RegisterConnection
) : LSM303Minimal(accelConnection, magConnection) {

    private val intCallbacks = arrayOfNulls<IntConsumer>(3)
    private val intSources = HashSet<Int>()
    private var polling = false
    private var pollThread: Thread? = null

    /** The exact handler registered on the shared INT pin, kept for removal. */
    private var lastEdgeHandler: EdgeHandler? = null

    /** Adapter so this driver can run as an [EdgeHandler] callback. */
    private val edgeRunnable = Runnable { dispatchInterrupt() }

    // -------------------------------------------------------------------------
    // Accelerometer configuration
    // -------------------------------------------------------------------------

    /**
     * Set the accelerometer full-scale range.
     *
     * @param rangeG one of 2, 4, 8, 16 (*g*); updates the cached sensitivity
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if `rangeG` is not 2/4/8/16
     */
    @Throws(IOException::class)
    fun setRange(rangeG: Int) {
        val code = when (rangeG) {
            2 -> 0
            4 -> 1
            8 -> 2
            16 -> 3
            else -> throw IllegalArgumentException("rangeG must be one of 2, 4, 8, 16")
        }
        this.rangeBits = code
        var ctrl4 = accelRead(REG_CTRL4_A)
        ctrl4 = (ctrl4 and 0xCF) or (code shl 4)
        accelWrite(REG_CTRL4_A, ctrl4)
    }

    /**
     * Set the accelerometer output data rate.
     *
     * @param rateHz one of 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376 Hz
     *               (use [powerDown] for 0). 1620 Hz and 5376 Hz exist
     *               in low-power mode only, 1344 Hz in normal / high-resolution mode
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if the rate is not allowed in the current mode
     */
    @Throws(IOException::class)
    fun setDataRate(rateHz: Int) {
        val code = accelOdrCode(rateHz)
        if (code == 0x8 && powerMode != 0) {
            throw IllegalArgumentException("1620 Hz requires low-power mode (setMode(0))")
        }
        if (code == 0x9 && rateHz == 5376 && powerMode != 0) {
            throw IllegalArgumentException("5376 Hz requires low-power mode (setMode(0))")
        }
        if (code == 0x9 && rateHz == 1344 && powerMode == 0) {
            throw IllegalArgumentException("1344 Hz requires normal or high-resolution mode")
        }
        var ctrl1 = accelRead(REG_CTRL1_A)
        ctrl1 = (ctrl1 and 0x0F) or (code shl 4)
        accelWrite(REG_CTRL1_A, ctrl1)
        this.odrHz = rateHz.toDouble()
    }

    /**
     * Set the accelerometer operating mode (resolution).
     *
     * `resolution` is the Java/Kotlin spelling of the spec's `mode` argument
     * (renamed in Kotlin only, to avoid the JVM-signature clash with the
     * inherited `mode` property setter).
     *
     * @param resolution 0 = low-power (8-bit), 1 = normal (10-bit), 2 =
     *                   high-resolution (12-bit); updates the cached sensitivity
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if `resolution` is not 0/1/2
     */
    @Throws(IOException::class)
    fun setMode(resolution: Int) {
        if (resolution != 0 && resolution != 1 && resolution != 2) {
            throw IllegalArgumentException("mode must be 0 (low-power), 1 (normal) or 2 (high-resolution)")
        }
        var ctrl1 = accelRead(REG_CTRL1_A)
        var ctrl4 = accelRead(REG_CTRL4_A)
        if (resolution == 0) {
            ctrl1 = ctrl1 or 0x08                         // LPen = 1
            ctrl4 = ctrl4 and 0x08.inv() and 0xFF         // HR = 0
        } else if (resolution == 1) {
            ctrl1 = ctrl1 and 0x08.inv() and 0xFF
            ctrl4 = ctrl4 and 0x08.inv() and 0xFF
        } else {
            ctrl1 = ctrl1 and 0x08.inv() and 0xFF
            ctrl4 = ctrl4 or 0x08
        }
        accelWrite(REG_CTRL1_A, ctrl1)
        accelWrite(REG_CTRL4_A, ctrl4)
        this.powerMode = resolution
    }

    /**
     * Enable or disable the accelerometer axes.
     *
     * @param x enable the X axis
     * @param y enable the Y axis
     * @param z enable the Z axis
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun setAxes(x: Boolean, y: Boolean, z: Boolean) {
        var ctrl1 = accelRead(REG_CTRL1_A)
        ctrl1 = (ctrl1 and 0xF8) or ((if (z) 1 else 0) shl 2) or ((if (y) 1 else 0) shl 1) or (if (x) 1 else 0)
        accelWrite(REG_CTRL1_A, ctrl1)
    }

    /**
     * Enter power-down (ODR = 0000). Register contents are retained.
     *
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun powerDown() {
        val ctrl1 = accelRead(REG_CTRL1_A)
        accelWrite(REG_CTRL1_A, ctrl1 and 0x0F)
        this.odrHz = 0.0
    }

    /**
     * Read raw accelerometer counts at the current resolution.
     *
     * @return array of three ints — X, Y, Z signed counts
     *         (12/10/8-bit right-aligned per mode)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readAccelerationRaw(): IntArray {
        val raw = accelConn.read(REG_OUT_X_L_A or 0x80, 6)
        return convertAccelSample(raw)
    }

    /**
     * Check whether a new accelerometer sample is available.
     *
     * @return true when STATUS_REG_A.ZYXDA (bit 3, 0x08) is set
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun newDataAvailable(): Boolean {
        return (accelRead(REG_STATUS_A) and STATUS_ZYXDA) != 0
    }

    /**
     * Read the accelerometer status register.
     *
     * @return STATUS_REG_A byte (ZYXOR/ZOR/YOR/XOR overrun, ZYXDA and axis data-ready)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readStatus(): Int {
        return accelRead(REG_STATUS_A)
    }

    /**
     * Configure the high-pass filter.
     *
     * @param mode         HPM bits 7:6 — 0 normal (reset by reading REFERENCE_A),
     *                     1 reference signal for filtering, 2 normal, 3 autoreset
     * @param cutoff       HPCF bits 5:4 raw code 0–3 (no Hz values tabulated for this part)
     * @param filterOutput true = filtered data goes to the output registers and FIFO
     * @param onIa1        true = high-pass filter enabled for interrupt generator 1
     * @param onIa2        true = high-pass filter enabled for interrupt generator 2
     * @param onClick      true = high-pass filter enabled for click detection
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if mode or cutoff is out of range
     */
    @Throws(IOException::class)
    fun setHighPass(mode: Int, cutoff: Int, filterOutput: Boolean, onIa1: Boolean,
                    onIa2: Boolean, onClick: Boolean) {
        var ctrl2 = ((mode and 0x03) shl 6) or ((cutoff and 0x03) shl 4)
        if (filterOutput) ctrl2 = ctrl2 or 0x08
        if (onClick)      ctrl2 = ctrl2 or 0x04
        if (onIa2)        ctrl2 = ctrl2 or 0x02
        if (onIa1)        ctrl2 = ctrl2 or 0x01
        accelWrite(REG_CTRL2_A, ctrl2)
    }

    /**
     * Reset the high-pass filter (HPM = 00) by reading REFERENCE_A.
     *
     * @return the REFERENCE_A register value
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun resetHighPass(): Int {
        return accelRead(REG_REFERENCE_A)
    }

    /**
     * Write the high-pass reference register (for HPM = 01).
     *
     * @param value reference value 0–255
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun setReference(value: Int) {
        accelWrite(REG_REFERENCE_A, value and 0xFF)
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    /**
     * Configure the 32-level FIFO.
     *
     * @param mode        0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO);
     *                    returning to bypass clears the buffer
     * @param watermark   watermark level 0–31
     * @param triggerInt2 trigger-mode source — false = INT1, true = INT2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if mode or watermark is out of range
     */
    @Throws(IOException::class)
    fun setFifo(mode: Int, watermark: Int, triggerInt2: Boolean) {
        if (mode < 0 || mode > 3) {
            throw IllegalArgumentException("mode must be 0..3")
        }
        if (watermark < 0 || watermark > 31) {
            throw IllegalArgumentException("watermark must be 0..31")
        }
        val fifoCtrl = ((mode and 0x03) shl 6) or (if (triggerInt2) 0x20 else 0x00) or (watermark and 0x1F)
        accelWrite(REG_FIFO_CTRL_A, fifoCtrl)
        var ctrl5 = accelRead(REG_CTRL5_A)
        if (mode in 1..3) {
            ctrl5 = ctrl5 or 0x40
        } else {
            ctrl5 = ctrl5 and 0x40.inv() and 0xFF
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Return the number of unread sample sets in the FIFO (0–32).
     *
     * @return FSS bits 4:0 from FIFO_SRC_REG_A
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun fifoLevel(): Int {
        return accelRead(REG_FIFO_SRC_A) and 0x1F
    }

    /**
     * Drain up to [count] sample sets from the FIFO. Each sample is
     * popped with one 6-byte burst read of OUT_X_L_A..OUT_Z_H_A.
     *
     * @param count maximum number of sample sets to read, or < 0
     *              to read all unread samples reported by FSS
     * @return list of (x, y, z) `DoubleArray` in *g*
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readFifo(countIn: Int): MutableList<DoubleArray> {
        var count = countIn
        if (count < 0) {
            count = fifoLevel()
        }
        val out = ArrayList<DoubleArray>()
        val scale = accelSensitivityG()
        for (i in 0 until count) {
            val raw = accelConn.read(REG_OUT_X_L_A or 0x80, 6)
            val c = convertAccelSample(raw)
            out.add(doubleArrayOf(c[0] * scale, c[1] * scale, c[2] * scale))
        }
        return out
    }

    /**
     * Read the decoded FIFO status.
     *
     * @return array `(watermark, overrun, empty, level)` — the first three
     *         entries are 1/0 as in boolean, level is the FSS count
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readFifoStatus(): IntArray {
        val src = accelRead(REG_FIFO_SRC_A)
        return intArrayOf(
            if (src and 0x80 != 0) 1 else 0, if (src and 0x40 != 0) 1 else 0,
            if (src and 0x20 != 0) 1 else 0, src and 0x1F
        )
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators and click detection
    // -------------------------------------------------------------------------

    /** Register base (CFG, SRC, THS, DUR) for generator 1 or 2. */
    private fun intRegs(generator: Int): IntArray {
        if (generator == 1) {
            return intArrayOf(REG_INT1_CFG_A, REG_INT1_SRC_A, REG_INT1_THS_A, REG_INT1_DUR_A)
        }
        if (generator == 2) {
            return intArrayOf(REG_INT2_CFG_A, REG_INT2_SRC_A, REG_INT2_THS_A, REG_INT2_DUR_A)
        }
        throw IllegalArgumentException("generator must be 1 or 2")
    }

    /** Convert a threshold in g to the 7-bit INTx_THS / CLICK_THS code. */
    private fun encodeThreshold(thresholdG: Double): Int {
        val code = Math.round(thresholdG * 128.0 / fullScaleG(rangeBits)).toInt()
        return Math.max(0, Math.min(127, code))
    }

    /** Convert a duration in ms to the 1/ODR code, clamped to [maxBits] bits. */
    private fun encodeDuration(durationMs: Double, maxBits: Int): Int {
        val code = Math.round(durationMs * odrHz / 1000.0).toInt()
        return Math.max(0, Math.min((1 shl maxBits) - 1, code))
    }

    /**
     * Configure one inertial interrupt generator.
     *
     * @param generator  1 or 2 (INT1 generator / INT2 generator)
     * @param events     6-bit event mask (bit 5 = Z high, 4 = Z low, 3 = Y high,
     *                   2 = Y low, 1 = X high, 0 = X low; the same bits select
     *                   the direction zones in 6D modes)
     * @param thresholdG event threshold in *g* (1 LSb = full-scale / 128)
     * @param durationMs minimum event duration in ms (1 LSb = 1/ODR)
     * @param andMode    true = AND combination of the enabled events, false = OR
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if generator is not 1 or 2
     */
    @Throws(IOException::class)
    fun setMotionInterrupt(generator: Int, events: Int, thresholdG: Double,
                           durationMs: Double, andMode: Boolean) {
        val regs = intRegs(generator)
        accelWrite(regs[0], (if (andMode) 0x80 else 0x00) or (events and 0x3F))
        accelWrite(regs[2], encodeThreshold(thresholdG))
        accelWrite(regs[3], encodeDuration(durationMs, 7))
    }

    /**
     * Configure free-fall detection on one generator: all three axes' low
     * events, AND-combined (INTx_CFG_A = 0x95).
     *
     * @param generator  1 (default) or 2
     * @param thresholdG free-fall threshold in *g*
     * @param durationMs minimum free-fall duration in ms
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun setFreeFall(generator: Int, thresholdG: Double, durationMs: Double) {
        setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
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
    @Throws(IOException::class)
    fun setOrientation(generator: Int, position: Boolean, fourD: Boolean) {
        val regs = intRegs(generator)
        var value = 0x40 or 0x3F             // 6D + all six direction zones
        if (position) {
            value = value or 0x80
        }
        accelWrite(regs[0], value)
        var ctrl5 = accelRead(REG_CTRL5_A)
        val d4dMask = if (generator == 1) 0x04 else 0x01
        if (fourD) {
            ctrl5 = ctrl5 or d4dMask
        } else {
            ctrl5 = ctrl5 and d4dMask.inv() and 0xFF
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Latch (or unlatch) one generator's interrupt until its source is read.
     *
     * @param generator 1 or 2
     * @param enabled   true = latch INT output until INTx_SOURCE_A is read
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun setLatch(generator: Int, enabled: Boolean) {
        var ctrl5 = accelRead(REG_CTRL5_A)
        val lirMask = if (generator == 1) 0x08 else 0x02
        if (enabled) {
            ctrl5 = ctrl5 or lirMask
        } else {
            ctrl5 = ctrl5 and lirMask.inv() and 0xFF
        }
        accelWrite(REG_CTRL5_A, ctrl5)
    }

    /**
     * Read one generator's source register — clears the interrupt.
     *
     * @param generator 1 or 2
     * @return INTx_SOURCE_A byte (bit 6 = IA, bits 5:0 = axis high/low flags)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readInterruptSource(generator: Int): Int {
        val regs = intRegs(generator)
        return accelRead(regs[1])
    }

    /**
     * Configure single / double click detection.
     *
     * @param single      axis mask for single click (X=1, Y=2, Z=4; 0 = off)
     * @param doubleMask  axis mask for double click (X=1, Y=2, Z=4; 0 = off)
     * @param thresholdG  click threshold in *g* (1 LSb = full-scale / 128)
     * @param timeLimitMs maximum click duration in ms (1 LSb = 1/ODR, 7-bit)
     * @param latencyMs   time after a click in which the click interrupt is not
     *                    re-armed (1 LSb = 1/ODR, 8-bit)
     * @param windowMs    time window for a second click (1 LSb = 1/ODR, 8-bit)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if a mask is not an X/Y/Z axis mask 0..7
     */
    @Throws(IOException::class)
    fun setClick(single: Int, doubleMask: Int, thresholdG: Double, timeLimitMs: Double,
                 latencyMs: Double, windowMs: Double) {
        if (single < 0 || single > 7 || doubleMask < 0 || doubleMask > 7) {
            throw IllegalArgumentException("single / double are axis masks: X=1, Y=2, Z=4")
        }
        // Axis masks X=1/Y=2/Z=4 spread to CLICK_CFG_A bits: XD=0, XS=1, YD=2, YS=3, ZD=4, ZS=5.
        val singleCode = ((single and 1) shl 1) or ((single and 2) shl 2) or ((single and 4) shl 3)
        val doubleCode = (doubleMask and 1) or ((doubleMask and 2) shl 1) or ((doubleMask and 4) shl 2)
        accelWrite(REG_CLICK_CFG_A, singleCode or doubleCode)
        accelWrite(REG_CLICK_THS_A, encodeThreshold(thresholdG))
        accelWrite(REG_TIME_LIMIT_A, encodeDuration(timeLimitMs, 7))
        accelWrite(REG_TIME_LAT_A, encodeDuration(latencyMs, 8))
        accelWrite(REG_TIME_WIN_A, encodeDuration(windowMs, 8))
    }

    /**
     * Read the click source register — clears the click interrupt.
     *
     * @return CLICK_SRC_A byte (bit 6 = IA, bit 5 = DCLICK, bit 4 = SCLICK,
     *         bit 3 = sign, bits 2:0 = Z/Y/X event axis)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readClickSource(): Int {
        return accelRead(REG_CLICK_SRC_A)
    }

    /**
     * Set the INT1 / INT2 output polarity.
     *
     * @param activeLow true = INT pins active-low, false = active-high (default)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun setInterruptPolarity(activeLow: Boolean) {
        var ctrl6 = accelRead(REG_CTRL6_A)
        if (activeLow) {
            ctrl6 = ctrl6 or 0x02
        } else {
            ctrl6 = ctrl6 and 0x02.inv() and 0xFF
        }
        accelWrite(REG_CTRL6_A, ctrl6)
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
     * @throws IllegalArgumentException if `rangeUt` is not a valid range
     */
    @Throws(IOException::class)
    fun setMagRange(rangeUt: Int) {
        val index = when (rangeUt) {
            130 -> 0
            190 -> 1
            250 -> 2
            400 -> 3
            470 -> 4
            560 -> 5
            810 -> 6
            else -> throw IllegalArgumentException("rangeUt must be one of 130, 190, 250, 400, 470, 560, 810")
        }
        this.magGn = index
        magWrite(REG_CRB_M, (index + 1) shl 5)
    }

    /**
     * Set the magnetometer output data rate.
     *
     * @param rateHz one of 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220 Hz; preserves TEMP_EN
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if `rateHz` is not a valid rate
     */
    @Throws(IOException::class)
    fun setMagDataRate(rateHz: Double) {
        val code = magOdrCode(rateHz)
        val cra = magRead(REG_CRA_M)
        magWrite(REG_CRA_M, (cra and 0x80) or (code shl 2))
        this.magOdrHz = rateHz
    }

    /**
     * Set the magnetometer operating mode.
     *
     * @param magMode 0 continuous conversion, 1 single conversion (one
     *                conversion, then back to sleep), 2 sleep
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if `magMode` is not 0/1/2
     */
    @Throws(IOException::class)
    fun setMagMode(magMode: Int) {
        if (magMode != 0 && magMode != 1 && magMode != 2) {
            throw IllegalArgumentException("mode must be 0 (continuous), 1 (single) or 2 (sleep)")
        }
        magWrite(REG_MR_M, magMode)
    }

    /**
     * Read raw magnetometer counts (X, Y, Z order, after reordering).
     *
     * @return array of three ints — X, Y, Z signed 12-bit counts
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readMagneticRaw(): IntArray {
        val raw = magConn.read(REG_OUT_X_H_M, 6)
        val mx = Register.toSigned(((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16)
        val mz = Register.toSigned(((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF), 16)
        val my = Register.toSigned(((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF), 16)
        return intArrayOf(mx, my, mz)
    }

    /**
     * Check whether a new magnetometer sample is available.
     *
     * @return true when SR_REG_M.DRDY is set
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun magDataReady(): Boolean {
        return (magRead(REG_SR_M) and 0x01) != 0
    }

    /**
     * Read the decoded magnetometer status register.
     *
     * @return array `(drdy, lock)` — DRDY = new set of measurements
     *         available (1/0), LOCK = output registers locked mid-read (1/0)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readMagStatus(): IntArray {
        val sr = magRead(REG_SR_M)
        return intArrayOf(if (sr and 0x01 != 0) 1 else 0, if (sr and 0x02 != 0) 1 else 0)
    }

    /**
     * Read the magnetometer identification registers.
     *
     * @return IRA/IRB/IRC bytes (`0x48 0x34 0x33` — identical to HMC5883L)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readMagIdentity(): ByteArray {
        return magConn.read(REG_IRA_M, 3)
    }

    /**
     * Enable or disable the relative temperature sensor.
     *
     * @param enabled true = TEMP_EN set (updates at the magnetometer data rate)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun enableTemperature(enabled: Boolean) {
        var cra = magRead(REG_CRA_M)
        if (enabled) {
            cra = cra or 0x80
        } else {
            cra = cra and 0x80.inv() and 0xFF
        }
        magWrite(REG_CRA_M, cra)
        this.tempEnabled = enabled
    }

    /**
     * Read the relative temperature. The sensor is relative only — the
     * datasheet gives no zero-degree reference — so the value is a delta,
     * never an absolute °C. Enables TEMP_EN on first use (read-modify-write).
     *
     * @return relative temperature in °C (8 LSB/°C)
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun readTemperatureDelta(): Double {
        if (!tempEnabled) {
            enableTemperature(true)
        }
        val raw = magConn.read(REG_TEMP_H_M, 2)
        val value = Register.toSigned(((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16) shr 4
        return value / 8.0
    }

    // -------------------------------------------------------------------------
    // Software compass
    // -------------------------------------------------------------------------

    /**
     * Compute the compass heading from both sensors.
     *
     * Uses the `atan2(y, x)` convention, wrapped to 0–360°. The datasheet
     * documents neither the axis alignment between the two dies nor a
     * declination, so the result assumes both sensors report in the same body
     * frame and is magnetic (not true) north.
     *
     * @param tiltCompensate true (default) = project the magnetic field onto
     *                       the horizontal plane using the current acceleration;
     *                       false = flat compass from the magnetic X/Y only
     * @return heading in degrees, 0–360
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun heading(tiltCompensate: Boolean): Double {
        val m = readMagnetic()
        val fx = m[0]
        val fy = m[1]
        val fz = m[2]
        val angle: Double
        if (!tiltCompensate) {
            angle = Math.toDegrees(Math.atan2(fy, fx))
        } else {
            val a = readAcceleration()
            var ax = a[0]
            var ay = a[1]
            var az = a[2]
            var norm = Math.sqrt(ax * ax + ay * ay + az * az)
            if (norm == 0.0) {
                norm = 1.0
            }
            ax /= norm
            ay /= norm
            az /= norm
            val roll = Math.atan2(ay, az)
            val pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll))
            val xh = fx * Math.cos(pitch) + fz * Math.sin(pitch)
            val yh = fx * Math.sin(roll) * Math.sin(pitch) + fy * Math.cos(roll)
                    - fz * Math.sin(roll) * Math.cos(pitch)
            angle = Math.toDegrees(Math.atan2(yh, xh))
        }
        return if (angle >= 0.0) angle else angle + 360.0
    }

    // -------------------------------------------------------------------------
    // Interrupts (Level 3: INT1 / INT2 routing)
    // -------------------------------------------------------------------------

    /**
     * Route one interrupt source to INT1 or INT2. Only routes the source; the
     * generator or function behind it is configured with the
     * `setMotionInterrupt` / `setClick` / … methods. `pin` is ignored for
     * INT1-only / INT2-only sources. [SOURCE_MAG_DATA_READY] is rejected
     * (it is always on the dedicated magnetometer DRDY pin and is not routable).
     *
     * @param source one of the `SOURCE_*` constants
     * @param pin    1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException on unknown source or bad pin
     */
    @Throws(IOException::class)
    fun enableInterrupt(source: Int, pin: Int) {
        requireRoutable(source, pin)
        var ctrl3 = accelRead(REG_CTRL3_A)
        var ctrl6 = accelRead(REG_CTRL6_A)
        when (source) {
            SOURCE_DATA_READY -> ctrl3 = ctrl3 or I1_DRDY1
            SOURCE_WATERMARK -> ctrl3 = ctrl3 or I1_WTM
            SOURCE_OVERRUN -> ctrl3 = ctrl3 or I1_OVERRUN
            SOURCE_IA1 -> {
                if (pin == 1) ctrl3 = ctrl3 or I1_AOI1 else ctrl6 = ctrl6 or I2_INT1
            }
            SOURCE_IA2 -> {
                if (pin == 1) ctrl3 = ctrl3 or I1_AOI2 else ctrl6 = ctrl6 or I2_INT2
            }
            SOURCE_CLICK -> {
                if (pin == 1) ctrl3 = ctrl3 or I1_CLICK else ctrl6 = ctrl6 or I2_CLICK
            }
            SOURCE_BOOT -> ctrl6 = ctrl6 or BOOT_I1        // must be pin 2 — checked above
        }
        accelWrite(REG_CTRL3_A, ctrl3)
        accelWrite(REG_CTRL6_A, ctrl6)
        intSources.add(source)
    }

    /**
     * Remove one interrupt source from INT1 or INT2.
     *
     * @param source one of the `SOURCE_*` constants
     * @param pin    1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException on unknown source or bad pin
     */
    @Throws(IOException::class)
    fun disableInterrupt(source: Int, pin: Int) {
        requireRoutable(source, pin)
        var ctrl3 = accelRead(REG_CTRL3_A)
        var ctrl6 = accelRead(REG_CTRL6_A)
        when (source) {
            SOURCE_DATA_READY -> ctrl3 = ctrl3 and I1_DRDY1.inv() and 0xFF
            SOURCE_WATERMARK -> ctrl3 = ctrl3 and I1_WTM.inv() and 0xFF
            SOURCE_OVERRUN -> ctrl3 = ctrl3 and I1_OVERRUN.inv() and 0xFF
            SOURCE_IA1 -> {
                if (pin == 1) ctrl3 = ctrl3 and I1_AOI1.inv() and 0xFF else ctrl6 = ctrl6 and I2_INT1.inv() and 0xFF
            }
            SOURCE_IA2 -> {
                if (pin == 1) ctrl3 = ctrl3 and I1_AOI2.inv() and 0xFF else ctrl6 = ctrl6 and I2_INT2.inv() and 0xFF
            }
            SOURCE_CLICK -> {
                if (pin == 1) ctrl3 = ctrl3 and I1_CLICK.inv() and 0xFF else ctrl6 = ctrl6 and I2_CLICK.inv() and 0xFF
            }
            SOURCE_BOOT -> ctrl6 = ctrl6 and BOOT_I1.inv() and 0xFF   // must be pin 2 — checked above
        }
        accelWrite(REG_CTRL3_A, ctrl3)
        accelWrite(REG_CTRL6_A, ctrl6)
        intSources.remove(source)
    }

    /**
     * Read the source registers of every enabled interrupt source.
     *
     * Reading INTx_SOURCE_A / CLICK_SRC_A clears their latched flags, so a
     * poll acknowledges the INT line.
     *
     * @return combined status — bit 0 [SOURCE_DATA_READY], bit 1
     *         [SOURCE_WATERMARK], bit 2 [SOURCE_OVERRUN], bit 3 [SOURCE_IA1],
     *         bit 4 [SOURCE_IA2], bit 5 [SOURCE_CLICK], bit 7
     *         [SOURCE_MAG_DATA_READY]. [SOURCE_BOOT] is pin-only and never
     *         reported.
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun pollInterrupt(): Int {
        var status = 0
        if (intSources.contains(SOURCE_DATA_READY)) {
            if (accelRead(REG_STATUS_A) and STATUS_ZYXDA != 0) {
                status = status or SOURCE_DATA_READY
            }
        }
        if (intSources.contains(SOURCE_WATERMARK) || intSources.contains(SOURCE_OVERRUN)) {
            val fifoSrc = accelRead(REG_FIFO_SRC_A)
            if (fifoSrc and 0x80 != 0) status = status or SOURCE_WATERMARK
            if (fifoSrc and 0x40 != 0) status = status or SOURCE_OVERRUN
        }
        if (intSources.contains(SOURCE_IA1)) {
            if (accelRead(REG_INT1_SRC_A) and 0x40 != 0) {
                status = status or SOURCE_IA1
            }
        }
        if (intSources.contains(SOURCE_IA2)) {
            if (accelRead(REG_INT2_SRC_A) and 0x40 != 0) {
                status = status or SOURCE_IA2
            }
        }
        if (intSources.contains(SOURCE_CLICK)) {
            if (accelRead(REG_CLICK_SRC_A) and 0x40 != 0) {
                status = status or SOURCE_CLICK
            }
        }
        if (magRead(REG_SR_M) and 0x01 != 0) {
            status = status or SOURCE_MAG_DATA_READY
        }
        return status
    }

    /**
     * Subscribe to INT1 (pin 1, default) or INT2 (pin 2) assertions.
     *
     * Delivery uses the accelerometer connection's `intPin()` when wired
     * (both INT lines typically share one edge input); falls back to a 5 ms
     * polling thread otherwise. The callback receives the combined status int
     * from [pollInterrupt].
     *
     * @param callback called with the combined status int on any assertion
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun onInterrupt(callback: IntConsumer) {
        onInterrupt(callback, 1)
    }

    /**
     * Subscribe to INT1 (pin 1) or INT2 (pin 2) assertions.
     *
     * @param callback called with the combined status int on any assertion
     * @param pin      1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if `pin` is not 1 or 2
     * @see onInterrupt
     */
    @Throws(IOException::class)
    fun onInterrupt(callback: IntConsumer, pin: Int) {
        if (pin != 1 && pin != 2) {
            throw IllegalArgumentException("pin must be 1 or 2")
        }
        intCallbacks[pin] = callback
        startInterruptDelivery()
    }

    /**
     * Unsubscribe from INT1 (pin 1) or INT2 (pin 2) assertions.
     *
     * @param pin 1 or 2
     * @throws IllegalArgumentException if `pin` is not 1 or 2
     */
    fun offInterrupt(pin: Int) {
        if (pin != 1 && pin != 2) {
            throw IllegalArgumentException("pin must be 1 or 2")
        }
        intCallbacks[pin] = null
        if (intCallbacks[1] == null && intCallbacks[2] == null) {
            stopInterruptDelivery()
        }
    }

    /** Unsubscribe from all INT assertions and stop delivery. */
    fun offInterrupt() {
        intCallbacks[1] = null
        intCallbacks[2] = null
        stopInterruptDelivery()
    }

    @Throws(IOException::class)
    private fun startInterruptDelivery() {
        val intPin: InputPin? = accelConn.intPin()
        if (intPin != null) {
            lastEdgeHandler = EdgeHandler { edgeRunnable.run() }
            intPin.onEdge(lastEdgeHandler, EdgeTrigger.FALLING)
            return
        }
        if (polling) {
            return
        }
        polling = true
        pollThread = Thread({
            while (polling) {
                edgeRunnable.run()
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "lsm303-poll")
        pollThread!!.isDaemon = true
        pollThread!!.start()
    }

    private fun stopInterruptDelivery() {
        polling = false
        if (pollThread != null) {
            pollThread!!.interrupt()
            pollThread = null
        }
        val intPin: InputPin? = accelConn.intPin()
        // Registered handlers are removed by exact instance — keep the reference.
        if (intPin != null && lastEdgeHandler != null) {
            intPin.offEdge(lastEdgeHandler!!)
            lastEdgeHandler = null
        }
    }

    private fun dispatchInterrupt() {
        val cb1 = intCallbacks[1]
        val cb2 = intCallbacks[2]
        if (cb1 == null && cb2 == null) {
            return
        }
        val status: Int
        try {
            status = pollInterrupt()
        } catch (e: IOException) {
            return                                    // bus error; wait for the next edge/tick
        }
        if (cb1 != null) cb1.accept(status)
        if (cb2 != null) cb2.accept(status)
    }

    private fun requireRoutable(source: Int, pin: Int) {
        if (source == SOURCE_MAG_DATA_READY) {
            throw IllegalArgumentException("SOURCE_MAG_DATA_READY is on the magnetometer DRDY pin and is not routable")
        }
        if (pin != 1 && pin != 2) {
            throw IllegalArgumentException("pin must be 1 or 2")
        }
        val known = source == SOURCE_DATA_READY || source == SOURCE_WATERMARK
                || source == SOURCE_OVERRUN || source == SOURCE_IA1 || source == SOURCE_IA2
                || source == SOURCE_CLICK || source == SOURCE_BOOT
        if (!known) {
            throw IllegalArgumentException("unknown interrupt source 0x" + Integer.toHexString(source))
        }
        if (source == SOURCE_BOOT && pin != 2) {
            throw IllegalArgumentException("SOURCE_BOOT is INT2-only")
        }
    }

    // -------------------------------------------------------------------------
    // Reboot
    // -------------------------------------------------------------------------

    /**
     * Reboot the accelerometer memory content (BOOT = 1).
     *
     * Reloads the factory trim values; waits ≥ 5 ms. The datasheet does not
     * say whether the control registers survive, so re-run the initialisation
     * (construct a fresh driver) afterwards.
     *
     * @throws IOException on bus error
     */
    @Throws(IOException::class)
    fun reboot() {
        val ctrl5 = accelRead(REG_CTRL5_A)
        accelWrite(REG_CTRL5_A, ctrl5 or 0x80)
        sleepMs(5)
    }

    companion object {
        // Interrupt source bits — the pollInterrupt() status layout.
        /** New accelerometer XYZ sample (STATUS_REG_A.ZYXDA) — INT1. */
        const val SOURCE_DATA_READY = 0x0001
        /** FIFO content exceeds the watermark (FIFO_SRC_REG_A.WTM) — INT1. */
        const val SOURCE_WATERMARK = 0x0002
        /** FIFO overrun (FIFO_SRC_REG_A.OVRN_FIFO) — INT1. */
        const val SOURCE_OVERRUN = 0x0004
        /** Inertial generator 1 event (INT1_SOURCE_A.IA) — INT1 or INT2. */
        const val SOURCE_IA1 = 0x0008
        /** Inertial generator 2 event (INT2_SOURCE_A.IA) — INT1 or INT2. */
        const val SOURCE_IA2 = 0x0010
        /** Single / double click (CLICK_SRC_A.IA) — INT1 or INT2. */
        const val SOURCE_CLICK = 0x0020
        /** Boot procedure running — INT2 only, pin-only (never reported by pollInterrupt). */
        const val SOURCE_BOOT = 0x0040
        /** New magnetometer sample (SR_REG_M.DRDY) — dedicated DRDY pin, not routable. */
        const val SOURCE_MAG_DATA_READY = 0x0080

        // CTRL_REG3_A routing bits (INT1 side).
        private const val I1_CLICK   = 0x80
        private const val I1_AOI1    = 0x40
        private const val I1_AOI2    = 0x20
        private const val I1_DRDY1   = 0x10
        private const val I1_WTM     = 0x04
        private const val I1_OVERRUN = 0x01

        // CTRL_REG6_A routing bits (INT2 side).
        private const val I2_CLICK = 0x80
        private const val I2_INT1  = 0x40
        private const val I2_INT2  = 0x20
        private const val BOOT_I1  = 0x10

        /** Map a rate in Hz to the CTRL_REG1_A ODR code; throws when invalid. */
        private fun accelOdrCode(rateHz: Int): Int {
            return when (rateHz) {
                1 -> 0x1
                10 -> 0x2
                25 -> 0x3
                50 -> 0x4
                100 -> 0x5
                200 -> 0x6
                400 -> 0x7
                1620 -> 0x8
                1344, 5376 -> 0x9
                else -> throw IllegalArgumentException(
                    "rateHz must be one of 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376"
                )
            }
        }

        /** Map a rate in Hz to the CRA_REG_M DO code; throws when invalid. */
        private fun magOdrCode(rateHz: Double): Int {
            if (rateHz == 0.75) return 0x0
            if (rateHz == 1.5) return 0x1
            if (rateHz == 3.0) return 0x2
            if (rateHz == 7.5) return 0x3
            if (rateHz == 15.0) return 0x4
            if (rateHz == 30.0) return 0x5
            if (rateHz == 75.0) return 0x6
            if (rateHz == 220.0) return 0x7
            throw IllegalArgumentException("rateHz must be one of 0.75, 1.5, 3, 7.5, 15, 30, 75, 220")
        }
    }
}
