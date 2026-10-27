package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.function.IntConsumer

/**
 * LSM303DLHC full interface: extends [LSM303Minimal] with range, data-rate and mode
 * selection, high-pass filter, 32-level FIFO, two inertial interrupt generators, single and
 * double click detection, interrupt routing (INT1 / INT2 / DRDY), the magnetometer
 * configuration, relative temperature, and software compass heading.
 *
 * Interrupts are delivered per INT line. [onInterrupt] uses the accelerometer connection's
 * [InputPin] for INT1; INT2 needs an explicit pin passed as `intPin`. With no pin wired the
 * driver falls back to a 5 ms polling thread. The callback receives the status value from
 * [pollInterrupt] and only fires when that value is non-zero.
 *
 * The chip's `INTx` lines are active-high by default; [setInterruptPolarity] with
 * `activeLow = true` makes the driver trigger on falling edges.
 */
class LSM303Full @Throws(IOException::class) constructor(
    accelConn: RegisterConnection,
    magConn: RegisterConnection
) : LSM303Minimal(accelConn, magConn) {

    /** Bitmask of sources routed to an INT line by [enableInterrupt]. */
    private var enabledSources: Int = 0

    /** Mirrors `H_LACTIVE`; selects the edge trigger used by [onInterrupt]. */
    private var activeLow: Boolean = false

    private val callbacks = ConcurrentHashMap<Int, IntConsumer>()
    private val edgeHandlers = arrayOfNulls<EdgeHandler>(3)
    private val armedPins = arrayOfNulls<InputPin>(3)
    private val pollThreads = arrayOfNulls<Thread>(3)

    // =====================================================================
    // Accelerometer configuration
    // =====================================================================

    /**
     * Set the full-scale range.
     *
     * @param rangeG full scale in *g*: 2, 4, 8 or 16
     * @throws IllegalArgumentException if [rangeG] is not one of the allowed values
     */
    @Throws(IOException::class)
    fun setRange(rangeG: Int) {
        val code = when (rangeG) {
            2 -> 0
            4 -> 1
            8 -> 2
            16 -> 3
            else -> throw IllegalArgumentException("range must be 2, 4, 8 or 16 g")
        }
        accelUpdate(REG_CTRL_REG4_A, CTRL4_FS_MASK, code shl 4)
        accelFs = code
    }

    /**
     * Set the output data rate.
     *
     * 1620 Hz and 5376 Hz are valid only in low-power mode; 1344 Hz is valid only in normal
     * or high-resolution mode (they share the ODR code).
     *
     * @param rateHz 0 (power-down), 1, 10, 25, 50, 100, 200, 400, 1344, 1620 or 5376
     * @throws IllegalArgumentException if the rate is unknown or not allowed in the current mode
     */
    @Throws(IOException::class)
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
            1620 -> {
                requireMode(0, "1620 Hz is low-power only")
                0x8
            }
            1344 -> {
                if (accelMode == 0) throw IllegalArgumentException("1344 Hz is normal / high-resolution only")
                0x9
            }
            5376 -> {
                requireMode(0, "5376 Hz is low-power only")
                0x9
            }
            else -> throw IllegalArgumentException("unsupported data rate: $rateHz")
        }
        accelUpdate(REG_CTRL_REG1_A, 0xF0, code shl 4)
        odrHz = rateHz
    }

    private fun requireMode(mode: Int, message: String) {
        if (accelMode != mode) throw IllegalArgumentException(message)
    }

    /**
     * Select the accelerometer operating mode.
     *
     * Maps to `LPen` / `HR`: 0 low-power (`LPen`=1, `HR`=0), 1 normal (`LPen`=0, `HR`=0),
     * 2 high-resolution (`LPen`=0, `HR`=1). Changes the resolution used by [readAcceleration]
     * and [readFifo].
     *
     * @param mode 0 low-power, 1 normal, 2 high-resolution
     * @throws IllegalArgumentException if [mode] is out of range
     */
    @Throws(IOException::class)
    fun setMode(mode: Int) {
        require(mode in 0..2) { "mode must be 0, 1 or 2" }
        accelUpdate(REG_CTRL_REG1_A, CTRL1_LPEN, if (mode == 0) CTRL1_LPEN else 0)
        accelUpdate(REG_CTRL_REG4_A, CTRL4_HR, if (mode == 2) CTRL4_HR else 0)
        accelMode = mode
    }

    /**
     * Enable or disable individual accelerometer axes.
     *
     * @param x enable the X axis
     * @param y enable the Y axis
     * @param z enable the Z axis
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setAxes(x: Boolean = true, y: Boolean = true, z: Boolean = true) {
        val bits = (if (x) 0x01 else 0) or (if (y) 0x02 else 0) or (if (z) 0x04 else 0)
        accelUpdate(REG_CTRL_REG1_A, 0x07, bits)
    }

    /** Put the accelerometer into power-down (`ODR` = 0000). Register contents are retained. */
    @Throws(IOException::class)
    fun powerDown() {
        accelUpdate(REG_CTRL_REG1_A, 0xF0, 0)
        odrHz = 0
    }

    /**
     * Read the raw accelerometer counts at the current resolution.
     *
     * @return `[x, y, z]` signed counts (right-shifted by the current mode)
     */
    @Throws(IOException::class)
    fun readAccelerationRaw(): IntArray = accelCounts(accelConnection.read(REG_OUT_X_L_A or ACCEL_AUTO_INC, 6))

    /** @return `true` if a new accelerometer sample is available (`STATUS_REG_A.ZYXDA`) */
    @Throws(IOException::class)
    fun newDataAvailable(): Boolean = (accelRead(REG_STATUS_REG_A) and 0x08) != 0

    /**
     * @return `STATUS_REG_A` byte: `ZYXOR` 7, `ZOR` 6, `YOR` 5, `XOR` 4, `ZYXDA` 3, `ZDA` 2,
     *         `YDA` 1, `XDA` 0
     */
    @Throws(IOException::class)
    fun readStatus(): Int = accelRead(REG_STATUS_REG_A)

    // =====================================================================
    // High-pass filter
    // =====================================================================

    /**
     * Configure the high-pass filter (`CTRL_REG2_A`).
     *
     * @param mode         `HPM` 0–3: 0 normal (reset by reading [resetHighPass]), 1 reference
     *                     filtering, 2 normal, 3 autoreset on interrupt
     * @param cutoff       `HPCF<2:1>` 0–3 (no Hz values are tabulated in this datasheet revision)
     * @param filterOutput route filtered data to the output registers and FIFO (`FDS`)
     * @param onIa1        filter generator 1 (`HPIS1`)
     * @param onIa2        filter generator 2 (`HPIS2`)
     * @param onClick      filter click detection (`HPCLICK`)
     * @throws IllegalArgumentException if [mode] or [cutoff] is outside 0–3
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setHighPass(
        mode: Int = 0,
        cutoff: Int = 0,
        filterOutput: Boolean = false,
        onIa1: Boolean = false,
        onIa2: Boolean = false,
        onClick: Boolean = false
    ) {
        require(mode in 0..3) { "HPM must be 0..3" }
        require(cutoff in 0..3) { "HPCF must be 0..3" }
        val v = ((mode and 0x03) shl 6) or ((cutoff and 0x03) shl 4) or
                (if (filterOutput) 0x08 else 0) or (if (onClick) 0x04 else 0) or
                (if (onIa2) 0x02 else 0) or (if (onIa1) 0x01 else 0)
        accelWrite(REG_CTRL_REG2_A, v)
    }

    /**
     * Read `REFERENCE_A`; in `HPM` = 00 this resets the high-pass filter.
     *
     * @return the reference register value
     */
    @Throws(IOException::class)
    fun resetHighPass(): Int = accelRead(REG_REFERENCE_A)

    /**
     * Write the high-pass reference value (used when `HPM` = 01).
     *
     * @param value reference value, 0–255
     * @throws IllegalArgumentException if [value] is outside 0–255
     */
    @Throws(IOException::class)
    fun setReference(value: Int) {
        require(value in 0..255) { "reference must be 0..255" }
        accelWrite(REG_REFERENCE_A, value)
    }

    // =====================================================================
    // FIFO
    // =====================================================================

    /**
     * Configure the FIFO. `FIFO_EN` in `CTRL_REG5_A` is set for modes 1–3. Returning to bypass
     * clears the buffer (the chip does this); to restart FIFO mode after a drain go through bypass.
     *
     * @param mode        0 bypass, 1 FIFO, 2 stream, 3 trigger (stream-to-FIFO)
     * @param watermark   watermark level, 0–31
     * @param triggerInt2 trigger source for trigger mode: `false` = INT1, `true` = INT2
     * @throws IllegalArgumentException if a value is out of range
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setFifo(mode: Int, watermark: Int = 0, triggerInt2: Boolean = false) {
        require(mode in 0..3) { "FIFO mode must be 0..3" }
        require(watermark in 0..31) { "watermark must be 0..31" }
        accelWrite(REG_FIFO_CTRL_REG_A, (mode shl 6) or (if (triggerInt2) 0x20 else 0) or watermark)
        accelUpdate(REG_CTRL_REG5_A, CTRL5_FIFO_EN, if (mode != 0) CTRL5_FIFO_EN else 0)
    }

    /** @return number of unread FIFO sample sets (`FSS<4:0>`, 0–32) */
    @Throws(IOException::class)
    fun fifoLevel(): Int = accelRead(REG_FIFO_SRC_REG_A) and 0x1F

    /**
     * Read up to [count] FIFO sample sets, one 6-byte burst per sample.
     *
     * @param count maximum number of samples to read (capped at the current `FSS`)
     * @return list of `[x, y, z]` samples in *g*, possibly empty
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun readFifo(count: Int = Int.MAX_VALUE): Array<DoubleArray> {
        val n = minOf(count, fifoLevel())
        return Array(maxOf(n, 0)) {
            convertAccel(accelConnection.read(REG_OUT_X_L_A or ACCEL_AUTO_INC, 6))
        }
    }

    /**
     * Decode `FIFO_SRC_REG_A`.
     *
     * @return `[watermark, overrun, empty, level]`: flags are 0 or 1, level is `FSS`
     */
    @Throws(IOException::class)
    fun readFifoStatus(): IntArray {
        val v = accelRead(REG_FIFO_SRC_REG_A)
        return intArrayOf((v shr 7) and 1, (v shr 6) and 1, (v shr 5) and 1, v and 0x1F)
    }

    // =====================================================================
    // Inertial interrupt generators
    // =====================================================================

    /**
     * Configure an inertial interrupt generator (threshold, duration and event mask).
     *
     * Writes `INTx_CFG_A`, `INTx_THS_A` and `INTx_DURATION_A`. The threshold uses
     * 1 LSb = full scale / 128; the duration uses 1 LSb = 1/ODR. The generator's source must
     * still be routed with [enableInterrupt].
     *
     * @param generator  1 or 2
     * @param events     6-bit mask: `ZHIE` (bit 5), `ZLIE` 4, `YHIE` 3, `YLIE` 2, `XHIE` 1, `XLIE` 0
     * @param thresholdG threshold in *g*
     * @param durationMs minimum event duration in ms
     * @param andMode    `true` = AND of the enabled events, `false` = OR
     * @throws IllegalArgumentException if [generator] or [events] is out of range
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setMotionInterrupt(
        generator: Int,
        events: Int,
        thresholdG: Double,
        durationMs: Double = 0.0,
        andMode: Boolean = false
    ) {
        checkGenerator(generator)
        require(events in 0..0x3F) { "events must be a 6-bit mask" }
        val cfg = if (generator == 1) REG_INT1_CFG_A else REG_INT2_CFG_A
        val ths = if (generator == 1) REG_INT1_THS_A else REG_INT2_THS_A
        val dur = if (generator == 1) REG_INT1_DURATION_A else REG_INT2_DURATION_A
        accelWrite(cfg, (if (andMode) 0x80 else 0) or events)
        accelWrite(ths, thresholdCode(thresholdG))
        accelWrite(dur, timeCode(durationMs, 127))
    }

    /**
     * Free-fall detection: all three axes low, AND combination (`INTx_CFG_A` = 0x95 at the defaults).
     *
     * @param generator  1 or 2
     * @param thresholdG threshold in *g*
     * @param durationMs minimum duration in ms
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setFreeFall(generator: Int = 1, thresholdG: Double = 0.35, durationMs: Double = 30.0) {
        setMotionInterrupt(generator, 0x15, thresholdG, durationMs, true)
    }

    /**
     * 6D orientation: movement recognition (default) or position recognition, optionally 4D.
     * All six zone events are enabled; the generator's threshold register is not changed.
     *
     * @param generator 1 or 2
     * @param position  `true` = position recognition (`AOI`=1, level);
     *                  `false` = movement recognition (pulse of one ODR period)
     * @param fourD     `true` = 4D detection (`D4D_INTx` set)
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setOrientation(generator: Int = 1, position: Boolean = false, fourD: Boolean = false) {
        checkGenerator(generator)
        val cfg = if (generator == 1) REG_INT1_CFG_A else REG_INT2_CFG_A
        accelWrite(cfg, (if (position) 0x80 else 0) or 0x40 or 0x3F)
        val mask = if (generator == 1) CTRL5_D4D_INT1 else CTRL5_D4D_INT2
        accelUpdate(REG_CTRL_REG5_A, mask, if (fourD) mask else 0)
    }

    /**
     * Latch an inertial generator's interrupt until its source register is read.
     *
     * @param generator 1 or 2
     * @param enabled   `true` to latch (`LIR_INTx`)
     */
    @Throws(IOException::class)
    fun setLatch(generator: Int, enabled: Boolean) {
        checkGenerator(generator)
        val mask = if (generator == 1) CTRL5_LIR_INT1 else CTRL5_LIR_INT2
        accelUpdate(REG_CTRL_REG5_A, mask, if (enabled) mask else 0)
    }

    /**
     * Read `INTx_SOURCE_A`. **Clears the generator's `IA` flag and INT line.**
     *
     * @param generator 1 or 2
     * @return the source byte: bit 6 `IA`, bits 5–0 `ZH` … `XL`
     */
    @Throws(IOException::class)
    fun readInterruptSource(generator: Int): Int {
        checkGenerator(generator)
        return accelRead(if (generator == 1) REG_INT1_SOURCE_A else REG_INT2_SOURCE_A)
    }

    // =====================================================================
    // Click detection
    // =====================================================================

    /**
     * Configure single and double click detection.
     *
     * The axis masks use X=1, Y=2, Z=4. Writes `CLICK_CFG_A`, `CLICK_THS_A` (1 LSb = full
     * scale / 128) and the `TIME_*` registers (1 LSb = 1/ODR).
     *
     * @param single      axis mask for single click
     * @param doubleClick axis mask for double click
     * @param thresholdG  click threshold in *g*
     * @param timeLimitMs maximum time above threshold in ms
     * @param latencyMs   dead time after the first click before a second is detected, in ms
     * @param windowMs    window in which the second click must occur, in ms
     * @throws IllegalArgumentException if an axis mask is outside 0–7
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun setClick(
        single: Int = 0,
        doubleClick: Int = 0,
        thresholdG: Double = 0.5,
        timeLimitMs: Double = 10.0,
        latencyMs: Double = 20.0,
        windowMs: Double = 50.0
    ) {
        require(single in 0..7 && doubleClick in 0..7) { "click axis masks must be 0..7" }
        val cfg = (single and 0x01) or ((single and 0x02) shl 1) or ((single and 0x04) shl 2) or
                ((doubleClick and 0x01) shl 1) or ((doubleClick and 0x02) shl 2) or ((doubleClick and 0x04) shl 3)
        accelWrite(REG_CLICK_CFG_A, cfg)
        accelWrite(REG_CLICK_THS_A, thresholdCode(thresholdG))
        accelWrite(REG_TIME_LIMIT_A, timeCode(timeLimitMs, 127))
        accelWrite(REG_TIME_LATENCY_A, timeCode(latencyMs, 255))
        accelWrite(REG_TIME_WINDOW_A, timeCode(windowMs, 255))
    }

    /**
     * Read `CLICK_SRC_A`. **Clears the click interrupt.**
     *
     * @return the source byte: bit 6 `IA`, 5 `DCLICK`, 4 `SCLICK`, 3 `Sign`, 2 `Z`, 1 `Y`, 0 `X`
     */
    @Throws(IOException::class)
    fun readClickSource(): Int = accelRead(REG_CLICK_SRC_A)

    // =====================================================================
    // Interrupt routing and polarity
    // =====================================================================

    /**
     * Set the INT1 / INT2 polarity (`H_LACTIVE`). Also changes the edge trigger used by
     * subsequent [onInterrupt] calls.
     *
     * @param activeLow `true` for active-low INT lines
     */
    @Throws(IOException::class)
    fun setInterruptPolarity(activeLow: Boolean) {
        accelUpdate(REG_CTRL_REG6_A, CTRL6_H_LACTIVE, if (activeLow) CTRL6_H_LACTIVE else 0)
        this.activeLow = activeLow
    }

    /**
     * Route an interrupt source to an INT line. The pin is ignored for INT1-only
     * ([SOURCE_DATA_READY], [SOURCE_WATERMARK], [SOURCE_OVERRUN]) and INT2-only
     * ([SOURCE_BOOT]) sources. The generator or function behind the source is configured separately.
     *
     * @param source one of the `SOURCE_*` constants except [SOURCE_MAG_DATA_READY]
     * @param pin    INT line, 1 or 2
     * @throws IllegalArgumentException for an unknown source, a non-routable source or a bad pin
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun enableInterrupt(source: Int, pin: Int = 1) {
        routeSource(source, true, pin)
        enabledSources = enabledSources or source
    }

    /**
     * Remove an interrupt source from an INT line. See [enableInterrupt].
     *
     * @param source one of the `SOURCE_*` constants except [SOURCE_MAG_DATA_READY]
     * @param pin    INT line, 1 or 2
     * @throws IllegalArgumentException for an unknown source, a non-routable source or a bad pin
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun disableInterrupt(source: Int, pin: Int = 1) {
        routeSource(source, false, pin)
        enabledSources = enabledSources and source.inv()
    }

    @Throws(IOException::class)
    private fun routeSource(source: Int, on: Boolean, pin: Int) {
        checkPin(pin)
        val bit = if (on) 1 else 0
        when (source) {
            SOURCE_DATA_READY -> accelUpdate(REG_CTRL_REG3_A, 0x10, bit * 0x10)
            SOURCE_WATERMARK -> accelUpdate(REG_CTRL_REG3_A, 0x04, bit * 0x04)
            SOURCE_OVERRUN -> accelUpdate(REG_CTRL_REG3_A, 0x02, bit * 0x02)
            SOURCE_IA1 -> routeToPin(0x40, pin, on)
            SOURCE_IA2 -> routeToPin(0x20, pin, on)
            SOURCE_CLICK -> routeToPin(0x80, pin, on)
            SOURCE_BOOT -> accelUpdate(REG_CTRL_REG6_A, CTRL6_BOOT_I1, bit * CTRL6_BOOT_I1)
            SOURCE_MAG_DATA_READY ->
                throw IllegalArgumentException("SOURCE_MAG_DATA_READY is on the DRDY pin and cannot be routed")
            else -> throw IllegalArgumentException("unknown interrupt source: 0x" + Integer.toHexString(source))
        }
    }

    /**
     * Route a function to INT1 (`CTRL_REG3_A`) or INT2 (`CTRL_REG6_A`). Both registers use the
     * same bit layout: 0x80 click, 0x40 generator 1, 0x20 generator 2.
     */
    @Throws(IOException::class)
    private fun routeToPin(bit: Int, pin: Int, on: Boolean) {
        val reg = if (pin == 2) REG_CTRL_REG6_A else REG_CTRL_REG3_A
        accelUpdate(reg, bit, if (on) bit else 0)
    }

    // =====================================================================
    // Interrupt subscribe / poll
    // =====================================================================

    /**
     * Subscribe on one INT line. [intPin] is the pin wired to that line; when it is `null`
     * a 5 ms polling thread is used instead. The default uses the accelerometer connection's
     * pin for INT1 only, so INT2 polls unless a pin is passed explicitly.
     *
     * @param callback receives the non-zero [pollInterrupt] status; bus errors inside the
     *                 handler are dropped
     * @param pin      INT line, 1 or 2
     * @param intPin   pin wired to that INT line, or `null` for the polling fallback
     * @throws IllegalArgumentException if [pin] is not 1 or 2
     */
    @JvmOverloads
    fun onInterrupt(
        callback: IntConsumer,
        pin: Int = 1,
        intPin: InputPin? = if (pin == 1) accelConnection.intPin() else null
    ) {
        checkPin(pin)
        offInterrupt(pin)
        callbacks[pin] = callback
        if (intPin != null) {
            val handler = EdgeHandler { dispatch(pin) }
            edgeHandlers[pin] = handler
            armedPins[pin] = intPin
            intPin.onEdge(handler, if (activeLow) EdgeTrigger.FALLING else EdgeTrigger.RISING)
        } else {
            val t = Thread(Runnable {
                while (!Thread.currentThread().isInterrupted) {
                    dispatch(pin)
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "lsm303-int$pin")
            t.isDaemon = true
            pollThreads[pin] = t
            t.start()
        }
    }

    /**
     * Unsubscribe one INT line: removes the edge handler or stops the polling thread.
     *
     * @param pin INT line, 1 or 2
     * @throws IllegalArgumentException if [pin] is not 1 or 2
     */
    @JvmOverloads
    fun offInterrupt(pin: Int = 1) {
        checkPin(pin)
        callbacks.remove(pin)
        val armed = armedPins[pin]
        val handler = edgeHandlers[pin]
        if (armed != null && handler != null) armed.offEdge(handler)
        armedPins[pin] = null
        edgeHandlers[pin] = null
        pollThreads[pin]?.interrupt()
        pollThreads[pin] = null
    }

    private fun dispatch(pin: Int) {
        val cb = callbacks[pin] ?: return
        try {
            val status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (e: IOException) {
            // Bus error inside an interrupt context: nothing to report to; drop this event.
        }
    }

    /**
     * Read the status of every enabled source and return it as one bitmask (bit layout matches
     * the `SOURCE_*` constants). [SOURCE_MAG_DATA_READY] is always checked because it is wired
     * to the DRDY pin. Reading `INTx_SOURCE_A` and `CLICK_SRC_A` **clears** their latched flags.
     *
     * @return combined status bitmask, 0 if nothing is pending
     */
    @Throws(IOException::class)
    fun pollInterrupt(): Int {
        var status = 0
        val en = enabledSources
        if ((en and SOURCE_DATA_READY) != 0 && newDataAvailable()) status = status or SOURCE_DATA_READY
        if ((en and (SOURCE_WATERMARK or SOURCE_OVERRUN)) != 0) {
            val fifo = accelRead(REG_FIFO_SRC_REG_A)
            if ((en and SOURCE_WATERMARK) != 0 && (fifo and 0x80) != 0) status = status or SOURCE_WATERMARK
            if ((en and SOURCE_OVERRUN) != 0 && (fifo and 0x40) != 0) status = status or SOURCE_OVERRUN
        }
        if ((en and SOURCE_IA1) != 0 && (readInterruptSource(1) and 0x40) != 0) status = status or SOURCE_IA1
        if ((en and SOURCE_IA2) != 0 && (readInterruptSource(2) and 0x40) != 0) status = status or SOURCE_IA2
        if ((en and SOURCE_CLICK) != 0 && (readClickSource() and 0x40) != 0) status = status or SOURCE_CLICK
        if (magDataReady()) status = status or SOURCE_MAG_DATA_READY
        return status
    }

    // =====================================================================
    // Magnetometer
    // =====================================================================

    /**
     * Set the magnetometer full-scale range (`CRB_REG_M.GN`).
     *
     * @param rangeUt full scale in µT: 130, 190, 250, 400, 470, 560 or 810 (±1.3 … ±8.1 gauss)
     * @throws IllegalArgumentException if [rangeUt] is not one of the allowed values
     */
    @Throws(IOException::class)
    fun setMagRange(rangeUt: Int) {
        val idx = MAG_RANGE_UT.indexOf(rangeUt)
        if (idx < 0) throw IllegalArgumentException("unsupported magnetometer range: $rangeUt uT")
        magWrite(REG_CRB_REG_M, (idx + 1) shl 5)
        magGainXy = MAG_GAIN_XY[idx].toDouble()
        magGainZ = MAG_GAIN_Z[idx].toDouble()
        magRangeUt = rangeUt
    }

    /**
     * Set the magnetometer output data rate (`CRA_REG_M.DO`); keeps `TEMP_EN`.
     *
     * @param rateHz 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220
     * @throws IllegalArgumentException if the rate is not supported
     */
    @Throws(IOException::class)
    fun setMagDataRate(rateHz: Double) {
        val code = when (rateHz) {
            0.75 -> 0
            1.5 -> 1
            3.0 -> 2
            7.5 -> 3
            15.0 -> 4
            30.0 -> 5
            75.0 -> 6
            220.0 -> 7
            else -> throw IllegalArgumentException("unsupported magnetometer rate: $rateHz Hz")
        }
        magUpdate(REG_CRA_REG_M, 0x1C, code shl 2)
    }

    /**
     * Set the magnetometer operating mode (`MR_REG_M.MD`).
     *
     * @param mode 0 continuous, 1 single conversion (then sleep), 2 sleep
     * @throws IllegalArgumentException if [mode] is out of range
     */
    @Throws(IOException::class)
    fun setMagMode(mode: Int) {
        require(mode in 0..2) { "magnetometer mode must be 0, 1 or 2" }
        magWrite(REG_MR_REG_M, mode)
    }

    /**
     * Read the magnetometer raw counts in X, Y, Z order.
     *
     * @return `[x, y, z]` signed counts
     */
    @Throws(IOException::class)
    fun readMagneticRaw(): IntArray = readMagCounts()

    /** @return `true` if `SR_REG_M.DRDY` is set */
    @Throws(IOException::class)
    fun magDataReady(): Boolean = (magRead(REG_SR_REG_M) and 0x01) != 0

    /**
     * Decode `SR_REG_M`.
     *
     * @return `[drdy, lock]`, each 0 or 1
     */
    @Throws(IOException::class)
    fun readMagStatus(): IntArray {
        val v = magRead(REG_SR_REG_M)
        return intArrayOf(v and 1, (v shr 1) and 1)
    }

    /**
     * Read the magnetometer identification registers.
     *
     * @return three bytes `IRA`, `IRB`, `IRC`; `0x48 0x34 0x33` on a genuine part
     */
    @Throws(IOException::class)
    fun readMagIdentity(): ByteArray = magConnection.read(REG_IRA_REG_M, 3)

    /**
     * Enable or disable the magnetometer temperature sensor (`CRA_REG_M.TEMP_EN`).
     *
     * @param enabled `true` to enable
     */
    @Throws(IOException::class)
    fun enableTemperature(enabled: Boolean) {
        magUpdate(REG_CRA_REG_M, 0x80, if (enabled) 0x80 else 0)
    }

    /**
     * Read the relative temperature. Enables the sensor on first use if it is off. The
     * datasheet gives no absolute reference, so the value is a delta only.
     *
     * @return temperature change in °C (8 LSB/°C, relative to an unspecified reference)
     */
    @Throws(IOException::class)
    fun readTemperatureDelta(): Double {
        if ((magRead(REG_CRA_REG_M) and 0x80) == 0) enableTemperature(true)
        val raw = magConnection.read(REG_TEMP_OUT_H_M, 2)
        val v = Register.toSigned(((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF), 16) shr 4
        return v / 8.0
    }

    /**
     * Compass heading in degrees, 0 to 360, using `atan2(y, x)`.
     *
     * Flat: `atan2(my, mx)` from the magnetometer only. Tilt-compensated: also reads the
     * accelerometer, derives roll and pitch from the normalised gravity vector, and projects
     * the field onto the horizontal plane. Both sensors are assumed to share one body frame;
     * the datasheet does not document their axis alignment. No declination is applied.
     *
     * @param tiltCompensate `true` to read the accelerometer and compensate for tilt
     * @return heading in degrees, 0 to 360
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun heading(tiltCompensate: Boolean = true): Double {
        val m = readMagnetic()
        val mx = m[0]
        val my = m[1]
        val mz = m[2]
        val h: Double = if (!tiltCompensate) {
            Math.atan2(my, mx)
        } else {
            val a = readAcceleration()
            val n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            if (n == 0.0) {
                Math.atan2(my, mx)
            } else {
                val ax = a[0] / n
                val ay = a[1] / n
                val az = a[2] / n
                val roll = Math.atan2(ay, az)
                val pitch = Math.atan2(-ax, ay * Math.sin(roll) + az * Math.cos(roll))
                val xh = mx * Math.cos(pitch) + mz * Math.sin(pitch)
                val yh = mx * Math.sin(roll) * Math.sin(pitch) + my * Math.cos(roll) -
                        mz * Math.sin(roll) * Math.cos(pitch)
                Math.atan2(yh, xh)
            }
        }
        var deg = Math.toDegrees(h)
        if (deg < 0) deg += 360.0
        if (deg >= 360.0) deg -= 360.0
        return deg
    }

    // =====================================================================
    // Reboot
    // =====================================================================

    /**
     * Reboot the accelerometer memory (`CTRL_REG5_A.BOOT`) and wait 5 ms. The datasheet does
     * not say which control registers survive, so construct a new driver to re-run init afterwards.
     */
    @Throws(IOException::class)
    fun reboot() {
        accelUpdate(REG_CTRL_REG5_A, CTRL5_BOOT, CTRL5_BOOT)
        sleepMs(5)
    }

    private fun checkGenerator(generator: Int) {
        require(generator == 1 || generator == 2) { "generator must be 1 or 2" }
    }

    private fun checkPin(pin: Int) {
        require(pin == 1 || pin == 2) { "INT pin must be 1 or 2" }
    }

    companion object {
        /** Status bit: new accelerometer sample (`STATUS_REG_A.ZYXDA`). INT1 only. */
        const val SOURCE_DATA_READY = 0x0001

        /** Status bit: FIFO content exceeds watermark (`FIFO_SRC_REG_A.WTM`). INT1 only. */
        const val SOURCE_WATERMARK = 0x0002

        /** Status bit: FIFO overrun (`FIFO_SRC_REG_A.OVRN_FIFO`). INT1 only. */
        const val SOURCE_OVERRUN = 0x0004

        /** Status bit: inertial generator 1 event (`INT1_SOURCE_A.IA`). INT1 or INT2. */
        const val SOURCE_IA1 = 0x0008

        /** Status bit: inertial generator 2 event (`INT2_SOURCE_A.IA`). INT1 or INT2. */
        const val SOURCE_IA2 = 0x0010

        /** Status bit: single or double click (`CLICK_SRC_A.IA`). INT1 or INT2. */
        const val SOURCE_CLICK = 0x0020

        /** Boot procedure running. INT2 only; pin-only, never reported by [pollInterrupt]. */
        const val SOURCE_BOOT = 0x0040

        /** New magnetometer sample (`SR_REG_M.DRDY`). Always on the DRDY pin; not routable. */
        const val SOURCE_MAG_DATA_READY = 0x0080

        private const val CTRL1_LPEN = 0x08
        private const val CTRL4_HR = 0x08
        private const val CTRL4_FS_MASK = 0x30
        private const val CTRL5_FIFO_EN = 0x40
        private const val CTRL5_BOOT = 0x80
        private const val CTRL5_LIR_INT1 = 0x08
        private const val CTRL5_D4D_INT1 = 0x04
        private const val CTRL5_LIR_INT2 = 0x02
        private const val CTRL5_D4D_INT2 = 0x01
        private const val CTRL6_BOOT_I1 = 0x10
        private const val CTRL6_H_LACTIVE = 0x02
    }
}
