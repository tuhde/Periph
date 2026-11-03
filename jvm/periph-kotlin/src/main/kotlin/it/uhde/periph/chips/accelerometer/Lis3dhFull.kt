package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.abs
import kotlin.math.ceil

/**
 * LIS3DH full interface — extends [Lis3dhMinimal] with the complete chip
 * functionality.
 *
 * Adds range / data-rate / mode selection, raw counts, status and
 * temperature reads, the auxiliary ADC, the high-pass filter, the 32-level
 * FIFO, both inertial interrupt generators (free-fall, 6D / 4D orientation),
 * single / double click detection, the sleep-to-wake activity function,
 * interrupt routing and delivery on INT1 / INT2, memory reboot, SDO
 * pull-up control and the electrostatic self-test.
 */
open class Lis3dhFull @JvmOverloads constructor(conn: RegisterConnection) : Lis3dhMinimal(conn) {

    companion object {
        /** Bit position of the source inside the enabled-source bookkeeping. */
        private const val SOURCE_BIT_DATA_READY = 0
        private const val SOURCE_BIT_AUX_DATA_READY = 1
        private const val SOURCE_BIT_WATERMARK = 2
        private const val SOURCE_BIT_OVERRUN = 3
        private const val SOURCE_BIT_IA1 = 4
        private const val SOURCE_BIT_IA2 = 5
        private const val SOURCE_BIT_CLICK = 6
    }

    /** Sources routed to INT1 only (through `CTRL_REG3`). */
    private var enabled1: Int = 0

    /** Sources routed to INT2 only (through `CTRL_REG6`). */
    private var enabled2: Int = 0

    @Volatile
    private var callback: IntConsumer? = null
    private var intPin: InputPin? = null
    private var pollThread: Thread? = null

    @Volatile
    private var polling: Boolean = false

    private val edgeHandler = EdgeHandler { handleEdge() }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Set the full-scale measurement range.
     *
     * Read-modify-writes the `FS` field of `CTRL_REG4` and updates the cached
     * sensitivity used by [read] and [readRaw].
     *
     * @param rangeG full-scale range in *g*: 2, 4, 8 or 16.
     * @throws IllegalArgumentException if [rangeG] is not 2, 4, 8 or 16.
     */
    fun setRange(rangeG: Int) {
        val index = rangeIndex(rangeG)
        require(index >= 0) {
            "LIS3DH setRange: range must be one of 2, 4, 8, 16, got $rangeG"
        }
        val ctrl4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (ctrl4 and 0x30.inv()) or (index shl 4))
        this.rangeG = rangeG
    }

    /**
     * Set the output data rate, or power the chip down.
     *
     * Read-modify-writes the `ODR` field of `CTRL_REG1` and waits for the
     * turn-on time before returning.
     *
     * @param rateHz output data rate in Hz: 0 (power-down), 1, 10, 25, 50,
     * 100, 200, 400, 1344, 1620 or 5376. 1620 Hz and 5376 Hz exist in
     * low-power mode only, 1344 Hz in normal / high-resolution mode only.
     * @throws IllegalArgumentException if the rate is unknown or not allowed
     * in the current mode.
     */
    fun setDataRate(rateHz: Int) {
        val code = rateCode(rateHz)
        require(code >= 0) {
            "LIS3DH setDataRate: rate must be one of 0, 1, 10, 25, 50, 100, 200, " +
                "400, 1344, 1620, 5376, got $rateHz"
        }
        require(!((rateHz == 1620 || rateHz == 5376) && resolutionMode != MODE_LOW_POWER)) {
            "LIS3DH setDataRate: rate $rateHz Hz is low-power mode only"
        }
        require(!(rateHz == 1344 && resolutionMode == MODE_LOW_POWER)) {
            "LIS3DH setDataRate: rate 1344 Hz is not available in low-power mode"
        }
        val ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, (ctrl1 and 0x0F) or code)
        this.rateHz = rateHz
        sleepMs(settleMs().toLong())
    }

    /**
     * Set the operating mode (the resolution / power trade-off).
     *
     * Read-modify-writes `LPen` in `CTRL_REG1` and `HR` in `CTRL_REG4`,
     * updates the cached resolution and waits for the mode's turn-on time. A
     * rate that does not exist in the requested mode is rejected, so `LPen` =
     * 1 with `HR` = 1 is never written.
     *
     * @param mode 0 = low-power (8-bit), 1 = normal (10-bit), 2 =
     * high-resolution (12-bit).
     * @throws IllegalArgumentException if the mode is unknown or the current
     * data rate is illegal in that mode.
     */
    fun setMode(mode: Int) {
        require(mode == MODE_LOW_POWER || mode == MODE_NORMAL || mode == MODE_HIGH_RESOLUTION) {
            "LIS3DH setMode: mode must be 0 (low-power), 1 (normal) or 2 (high-resolution), got $mode"
        }
        require(!(mode == MODE_LOW_POWER && rateHz == 1344)) {
            "LIS3DH setMode: rate 1344 Hz is not available in low-power mode"
        }
        require(!(mode != MODE_LOW_POWER && (rateHz == 1620 || rateHz == 5376))) {
            "LIS3DH setMode: rate $rateHz Hz is low-power mode only"
        }
        var ctrl1 = readReg(REG_CTRL_REG1)
        var ctrl4 = readReg(REG_CTRL_REG4)
        if (mode == MODE_LOW_POWER) {
            ctrl1 = ctrl1 or 0x08       // LPen = 1
            ctrl4 = ctrl4 and 0x08.inv() // HR = 0 (LPen = 1 with HR = 1 is not allowed)
        } else {
            ctrl1 = ctrl1 and 0x08.inv() // LPen = 0
            ctrl4 = if (mode == MODE_HIGH_RESOLUTION) {
                ctrl4 or 0x08            // HR = 1
            } else {
                ctrl4 and 0x08.inv()     // HR = 0
            }
        }
        writeReg(REG_CTRL_REG1, ctrl1)
        writeReg(REG_CTRL_REG4, ctrl4)
        this.resolutionMode = mode
        sleepMs(settleMs().toLong())
    }

    /**
     * Enable or disable the individual measurement axes.
     *
     * @param x enable the X axis.
     * @param y enable the Y axis.
     * @param z enable the Z axis.
     */
    fun setAxes(x: Boolean = true, y: Boolean = true, z: Boolean = true) {
        val ctrl1 = readReg(REG_CTRL_REG1)
        val axes = (if (x) 0x01 else 0x00) or (if (y) 0x02 else 0x00) or (if (z) 0x04 else 0x00)
        writeReg(REG_CTRL_REG1, (ctrl1 and 0xF8) or axes)
    }

    /**
     * Stop the conversions by setting `ODR` = 0000; the register contents are
     * retained.
     */
    fun powerDown() {
        val ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, ctrl1 and 0x0F)
        rateHz = 0
    }

    // -------------------------------------------------------------------------
    // Data reads
    // -------------------------------------------------------------------------

    /**
     * Read the raw acceleration counts at the current resolution.
     *
     * The counts are signed and right-shifted for the current resolution:
     * 12-bit in high-resolution mode, 10-bit in normal mode, 8-bit in
     * low-power mode.
     *
     * @return array of three ints — X, Y, Z signed counts.
     */
    fun readRaw(): IntArray {
        val raw = readBurst(REG_OUT_X_L, 6)
        return intArrayOf(
            decodeAxis(raw[0].toInt(), raw[1].toInt()),
            decodeAxis(raw[2].toInt(), raw[3].toInt()),
            decodeAxis(raw[4].toInt(), raw[5].toInt())
        )
    }

    /**
     * Check whether a complete X/Y/Z sample set is ready.
     *
     * @return `STATUS_REG.ZYXDA` — true when all three axes have new data.
     */
    fun newDataAvailable(): Boolean = (readReg(REG_STATUS) and 0x08) != 0

    /**
     * Read the `STATUS_REG` byte.
     *
     * @return the overrun (`ZYXOR`..`XOR`) and data-available (`ZYXDA`..`XDA`)
     * flags.
     */
    fun readStatus(): Int = readReg(REG_STATUS)

    /**
     * Read the relative on-chip temperature.
     *
     * Enables `ADC_EN` and `TEMP_EN` in `TEMP_CFG_REG` on first use — the
     * channel-3 conversions run through the same ADC — and waits one ODR
     * period for the first conversion.
     *
     * The datasheet specifies only 1 digit/°C with no zero-degree reference,
     * so the value is a relative delta, never an absolute temperature.
     *
     * @return relative temperature in °C (8-bit two's complement, 1 digit/°C).
     */
    fun readTemperatureDelta(): Int {
        val cfg = readReg(REG_TEMP_CFG)
        if (cfg and 0xC0 != 0xC0) {
            writeReg(REG_TEMP_CFG, cfg or 0xC0)
            if (rateHz > 0) {
                sleepMs(ceil(1000.0 / rateHz).toLong())
            }
        }
        return Register.toSigned(readReg(REG_OUT_ADC3_H), 8)
    }

    /**
     * Read one auxiliary ADC channel.
     *
     * The mapping is derived from the stated input range (1.200 V ± 400 mV) —
     * the datasheet gives no explicit voltage formula.
     *
     * @param channel 1, 2 or 3. Channel 3 is the temperature-sensor input
     * while `TEMP_EN` = 1.
     * @return the input voltage in V.
     * @throws IllegalArgumentException if the channel is not 1, 2 or 3, or if
     * channel 3 is requested while `TEMP_EN` = 1.
     */
    fun readAdc(channel: Int): Double {
        require(channel in 1..3) {
            "LIS3DH readAdc: channel must be 1, 2 or 3, got $channel"
        }
        if (channel == 3) {
            val cfg = readReg(REG_TEMP_CFG)
            require(cfg and 0x40 == 0) {
                "LIS3DH readAdc: channel 3 is the temperature sensor while TEMP_EN = 1"
            }
        }
        val base = REG_OUT_ADC1_L + (channel - 1) * 2
        val raw = readBurst(base, 2)
        val raw16 = (((raw[1].toInt() and 0xFF) shl 8) or (raw[0].toInt() and 0xFF)).toShort().toInt()
        val counts = raw16 shr (if (resolutionMode == MODE_LOW_POWER) 8 else 6)
        return 1.200 + counts * (0.400 / 512.0)
    }

    /**
     * Enable or disable the auxiliary ADC (`ADC_EN` in `TEMP_CFG_REG`).
     *
     * @param enabled true to enable the ADC, false to disable it.
     */
    fun enableAdc(enabled: Boolean) {
        var cfg = readReg(REG_TEMP_CFG)
        cfg = if (enabled) cfg or 0x80 else cfg and 0x80.inv() and 0xFF
        writeReg(REG_TEMP_CFG, cfg)
    }

    // -------------------------------------------------------------------------
    // High-pass filter
    // -------------------------------------------------------------------------

    /**
     * Configure the high-pass filter (`CTRL_REG2`).
     *
     * @param mode `HPM`: 0 normal (reset by reading `REFERENCE`), 1 reference
     * signal for filtering, 2 normal, 3 auto-reset on interrupt event.
     * @param cutoff `HPCF<2:1>` code 0–3 (the datasheet tabulates no Hz
     * values; they scale with ODR).
     * @param filterOutput route the filtered data to the output registers and
     * FIFO (`FDS`).
     * @param onIa1 apply the filter to interrupt generator 1.
     * @param onIa2 apply the filter to interrupt generator 2.
     * @param onClick apply the filter to the click function.
     * @throws IllegalArgumentException if [mode] or [cutoff] is outside 0–3.
     */
    fun setHighPass(
        mode: Int = 0,
        cutoff: Int = 0,
        filterOutput: Boolean = false,
        onIa1: Boolean = false,
        onIa2: Boolean = false,
        onClick: Boolean = false
    ) {
        require(mode in 0..3) { "LIS3DH setHighPass: mode must be 0-3" }
        require(cutoff in 0..3) { "LIS3DH setHighPass: cutoff must be 0-3" }
        var value = (mode shl 6) or (cutoff shl 4)
        if (filterOutput) value = value or 0x08
        if (onClick) value = value or 0x04
        if (onIa2) value = value or 0x02
        if (onIa1) value = value or 0x01
        writeReg(REG_CTRL_REG2, value)
    }

    /**
     * Reset the high-pass filter by reading `REFERENCE` (`HPM` = 00).
     *
     * @return the `REFERENCE` register value that was read.
     */
    fun resetHighPass(): Int = readReg(REG_REFERENCE)

    /**
     * Write the high-pass reference value (`HPM` = 01).
     *
     * @param value 0–255.
     * @throws IllegalArgumentException if [value] is outside 0–255.
     */
    fun setReference(value: Int) {
        require(value in 0..255) { "LIS3DH setReference: value must be 0-255" }
        writeReg(REG_REFERENCE, value)
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    /**
     * Configure the 32-level FIFO (`FIFO_CTRL_REG`).
     *
     * Sets `FIFO_EN` in `CTRL_REG5` for modes 1–3; returning to bypass
     * (mode 0) clears the buffer. Only bypass mode empties the buffer —
     * restart a FIFO mode through bypass after a full drain.
     *
     * @param mode 0 = bypass, 1 = FIFO, 2 = stream, 3 = stream-to-FIFO.
     * @param watermark watermark level `FTH`, 0–31.
     * @param triggerInt2 trigger source for stream-to-FIFO: false = INT1, true
     * = INT2 (`TR`).
     * @throws IllegalArgumentException if [mode] is not 0–3 or [watermark] is
     * outside 0–31.
     */
    fun setFifo(mode: Int, watermark: Int = 0, triggerInt2: Boolean = false) {
        require(mode in 0..3) {
            "LIS3DH setFifo: mode must be 0 (bypass), 1 (FIFO), 2 (stream) or 3 (stream-to-FIFO)"
        }
        require(watermark in 0..31) { "LIS3DH setFifo: watermark must be 0-31" }
        val value = (mode shl 6) or (if (triggerInt2) 0x20 else 0x00) or watermark
        writeReg(REG_FIFO_CTRL, value)
        var ctrl5 = readReg(REG_CTRL_REG5)
        ctrl5 = if (mode == 0) ctrl5 and 0x40.inv() and 0xFF else ctrl5 or 0x40
        writeReg(REG_CTRL_REG5, ctrl5)
    }

    /**
     * Read the number of unread sample sets in the FIFO.
     *
     * @return `FSS<4:0>` — 0–32 unread sample sets.
     */
    fun fifoLevel(): Int = readReg(REG_FIFO_SRC) and 0x1F

    /**
     * Drain sample sets from the FIFO.
     *
     * Each sample set is one 6-byte burst of 0x28–0x2D; the address
     * auto-increment wraps back to 0x28, so a full drain is 32 reads. Never
     * reads more than the FIFO actually holds.
     *
     * @param count maximum number of sample sets to read; `null` reads all
     * `FSS` entries currently in the FIFO.
     * @return the sample sets in *g*, oldest sample first.
     */
    fun readFifo(count: Int? = null): List<DoubleArray> {
        val level = fifoLevel()
        val n = if (count == null) level else minOf(count, level)
        val samples = ArrayList<DoubleArray>(maxOf(0, n))
        for (i in 0 until n) {
            val raw = readBurst(REG_OUT_X_L, 6)
            samples.add(
                doubleArrayOf(
                    scale(decodeAxis(raw[0].toInt(), raw[1].toInt())),
                    scale(decodeAxis(raw[2].toInt(), raw[3].toInt())),
                    scale(decodeAxis(raw[4].toInt(), raw[5].toInt()))
                )
            )
        }
        return samples
    }

    /**
     * Decode `FIFO_SRC_REG`.
     *
     * @return the watermark, overrun and empty flags plus the number of
     * unread sample sets.
     */
    fun readFifoStatus(): FifoStatus {
        val raw = readReg(REG_FIFO_SRC)
        return FifoStatus(
            raw and 0x80 != 0,
            raw and 0x40 != 0,
            raw and 0x20 != 0,
            raw and 0x1F
        )
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators
    // -------------------------------------------------------------------------

    /**
     * Configure an inertial interrupt generator (motion / threshold).
     *
     * @param generator 1 or 2 — the `INT1` or `INT2` register block.
     * @param events 6-bit `ZHIE`..`XLIE` event-enable mask.
     * @param thresholdG threshold in *g*, scaled with the current full-scale
     * range (16 / 32 / 62 / 186 mg per LSb).
     * @param durationMs minimum duration the events must hold, in ms
     * (`INTx_DURATION`, 1 LSb = 1/ODR).
     * @param andMode true for an AND combination of the enabled events
     * (`AOI` = 1), false for OR.
     * @throws IllegalArgumentException if the generator is not 1 or 2, or the
     * event mask is outside 0–0x3F.
     */
    fun setMotionInterrupt(
        generator: Int,
        events: Int,
        thresholdG: Double,
        durationMs: Double = 0.0,
        andMode: Boolean = false
    ) {
        require(events in 0..0x3F) {
            "LIS3DH setMotionInterrupt: events must be a 6-bit mask (0-0x3F)"
        }
        val regs = generatorRegs(generator)
        val cfg = (if (andMode) 0x80 else 0x00) or (events and 0x3F)
        writeReg(regs[0], cfg)
        writeReg(regs[1], thresholdCode(thresholdG))
        writeReg(regs[2], durationCode(durationMs, 127))
    }

    /**
     * Configure the free-fall detector: all three axes low, AND mode.
     *
     * Convenience wrapper around the generator's `INTx_CFG` = 0x95 (`AOI` +
     * `XLIE`/`YLIE`/`ZLIE`).
     *
     * @param generator 1 or 2.
     * @param thresholdG free-fall threshold in *g*.
     * @param durationMs minimum time below the threshold, in ms.
     * @throws IllegalArgumentException if the generator is not 1 or 2.
     */
    fun setFreeFall(generator: Int = 1, thresholdG: Double = 0.35, durationMs: Double = 30.0) {
        val regs = generatorRegs(generator)
        writeReg(regs[0], 0x95)
        writeReg(regs[1], thresholdCode(thresholdG))
        writeReg(regs[2], durationCode(durationMs, 127))
    }

    /**
     * Configure 6D / 4D orientation detection.
     *
     * `AOI` / `6D` = 01 is *movement* recognition (a one-ODR pulse), 11 is
     * *position* recognition (level, active while the orientation is inside a
     * known zone).
     *
     * @param generator 1 or 2.
     * @param position false = 6D movement recognition, true = 6D position
     * recognition.
     * @param fourD enable 4D detection (`D4D_INTx`) — disables the Z-axis
     * zones.
     * @throws IllegalArgumentException if the generator is not 1 or 2.
     */
    fun setOrientation(generator: Int = 1, position: Boolean = false, fourD: Boolean = false) {
        val regs = generatorRegs(generator)
        val axisZones = if (fourD) 0x0F else 0x3F
        val cfg = (if (position) 0xC0 else 0x40) or axisZones
        writeReg(regs[0], cfg)
        var ctrl5 = readReg(REG_CTRL_REG5)
        val d4dBit = if (generator == 1) 0x04 else 0x01
        ctrl5 = if (fourD) ctrl5 or d4dBit else ctrl5 and d4dBit.inv() and 0xFF
        writeReg(REG_CTRL_REG5, ctrl5)
    }

    /**
     * Latch a generator's interrupt until its `INTx_SRC` is read.
     *
     * @param generator 1 or 2.
     * @param enabled true to latch (`LIR_INTx`), false for a self-resetting
     * interrupt.
     * @throws IllegalArgumentException if the generator is not 1 or 2.
     */
    fun setLatch(generator: Int, enabled: Boolean) {
        require(generator == 1 || generator == 2) {
            "LIS3DH setLatch: generator must be 1 or 2"
        }
        val bit = if (generator == 1) 0x08 else 0x02
        var ctrl5 = readReg(REG_CTRL_REG5)
        ctrl5 = if (enabled) ctrl5 or bit else ctrl5 and bit.inv() and 0xFF
        writeReg(REG_CTRL_REG5, ctrl5)
    }

    /**
     * Read a generator's `INTx_SRC` — **clears** the interrupt.
     *
     * @param generator 1 or 2.
     * @return the `INTx_SRC` byte (`IA` = bit 6, per-axis event flags in
     * bits 5–0).
     * @throws IllegalArgumentException if the generator is not 1 or 2.
     */
    fun readInterruptSource(generator: Int): Int {
        require(generator == 1 || generator == 2) {
            "LIS3DH readInterruptSource: generator must be 1 or 2"
        }
        return readReg(if (generator == 1) REG_INT1_SRC else REG_INT2_SRC)
    }

    // -------------------------------------------------------------------------
    // Click detection
    // -------------------------------------------------------------------------

    /**
     * Configure single / double click detection (`CLICK_*`).
     *
     * @param single axis mask of the single-click enables (X = 1, Y = 2, Z = 4).
     * @param doubleClick axis mask of the double-click enables (X = 1, Y = 2,
     * Z = 4).
     * @param thresholdG click threshold in *g* (same scale as `INTx_THS`).
     * @param timeLimitMs maximum time between the two clicks of a double click.
     * @param latencyMs minimum time between two consecutive clicks.
     * @param windowMs maximum time after the first click of a double click.
     * @param latch keep the interrupt high until `CLICK_SRC` is read
     * (`LIR_Click`).
     * @throws IllegalArgumentException if either axis mask is outside 0–7.
     */
    fun setClick(
        single: Int = 0,
        doubleClick: Int = 0,
        thresholdG: Double = 0.5,
        timeLimitMs: Double = 10.0,
        latencyMs: Double = 20.0,
        windowMs: Double = 50.0,
        latch: Boolean = false
    ) {
        require(single in 0..7 && doubleClick in 0..7) {
            "LIS3DH setClick: single / double must be axis masks (0-7: X=1, Y=2, Z=4)"
        }
        val cfg = (single and 0x01) or
            ((single and 0x02) shl 1) or
            ((single and 0x04) shl 2) or
            ((doubleClick and 0x01) shl 1) or
            ((doubleClick and 0x02) shl 2) or
            ((doubleClick and 0x04) shl 3)
        val ths = thresholdCode(thresholdG)
        writeReg(REG_CLICK_CFG, cfg)
        writeReg(REG_CLICK_THS, ths or (if (latch) 0x80 else 0x00))
        writeReg(REG_TIME_LIMIT, durationCode(timeLimitMs, 127))
        writeReg(REG_TIME_LATENCY, durationCode(latencyMs, 255))
        writeReg(REG_TIME_WINDOW, durationCode(windowMs, 255))
    }

    /**
     * Read `CLICK_SRC` — **clears** the click interrupt.
     *
     * @return the `CLICK_SRC` byte (`IA` = bit 6, `DCLICK` = bit 5, `SCLICK`
     * = bit 4, `Sign` = bit 3, axis flags in bits 2–0).
     */
    fun readClickSource(): Int = readReg(REG_CLICK_SRC)

    // -------------------------------------------------------------------------
    // Sleep-to-wake activity
    // -------------------------------------------------------------------------

    /**
     * Configure the sleep-to-wake / return-to-sleep activity function.
     *
     * While the function has dropped the chip into its 10 Hz low-power state
     * the `ODR`, `LPen` and `HR` settings are ignored; the chip restores
     * them on activity. Pair with [Lis3dhSource.SOURCE_ACTIVITY].
     *
     * @param thresholdG activation threshold in *g* (`ACT_THS`).
     * @param durationMs activation duration in ms (`ACT_DUR`, where 1 LSb =
     * (8·`ACT_DUR` + 1)/ODR).
     * @throws IllegalArgumentException if the threshold rounds to 0 counts
     * (`ACT_THS` must be non-zero).
     */
    fun setActivity(thresholdG: Double, durationMs: Double) {
        val code = thresholdCode(thresholdG)
        require(code != 0) {
            "LIS3DH setActivity: threshold_g is below 1 LSb — ACT_THS must be non-zero"
        }
        var dur = roundHalfAwayFromZero((durationMs * rateHz / 1000.0 - 1) / 8.0)
        dur = dur.coerceIn(0, 255)
        writeReg(REG_ACT_THS, code)
        writeReg(REG_ACT_DUR, dur)
    }

    // -------------------------------------------------------------------------
    // Interrupt routing and delivery
    // -------------------------------------------------------------------------

    /**
     * Set the shared INT1 / INT2 output polarity (`INT_POLARITY`).
     *
     * @param activeLow true for active-low, false for active-high (the reset
     * default).
     */
    fun setInterruptPolarity(activeLow: Boolean) {
        var ctrl6 = readReg(REG_CTRL_REG6)
        ctrl6 = if (activeLow) ctrl6 or 0x02 else ctrl6 and 0x02.inv() and 0xFF
        writeReg(REG_CTRL_REG6, ctrl6)
    }

    /**
     * Route one interrupt source to INT`pin`.
     *
     * Only routes the source — the generator or function behind it is
     * configured with the `set*` methods. `pin` is ignored for the INT1-only
     * (data-ready, aux data-ready, watermark, overrun) and INT2-only
     * (activity, boot) sources.
     *
     * @param source one of the [Lis3dhSource] constants.
     * @param pin 1 or 2.
     * @throws IllegalArgumentException if the source is unknown or the pin is
     * not 1 or 2.
     */
    fun enableInterrupt(source: Int, pin: Int = 1) {
        val route = route(source, pin)
        val value = readReg(route[0])
        writeReg(route[0], value or route[1])
        if (route[2] == 1) {
            enabled1 = enabled1 or (1 shl sourceBit(source))
        } else {
            enabled2 = enabled2 or (1 shl sourceBit(source))
        }
    }

    /**
     * Remove one interrupt source from INT`pin`.
     *
     * @param source one of the [Lis3dhSource] constants.
     * @param pin 1 or 2.
     * @throws IllegalArgumentException if the source is unknown or the pin is
     * not 1 or 2.
     */
    fun disableInterrupt(source: Int, pin: Int = 1) {
        val route = route(source, pin)
        val value = readReg(route[0])
        writeReg(route[0], value and route[1].inv() and 0xFF)
        if (route[2] == 1) {
            enabled1 = enabled1 and (1 shl sourceBit(source)).inv()
        } else {
            enabled2 = enabled2 and (1 shl sourceBit(source)).inv()
        }
    }

    /**
     * Subscribe to the chip's INT line.
     *
     * The callback receives the combined status returned by [pollInterrupt]
     * and is only invoked for a non-zero status. Delivery uses the
     * connection's `intPin()` when one is wired and falls back to a 5 ms
     * polling thread otherwise.
     *
     * @param callback called with the combined interrupt status.
     */
    fun onInterrupt(callback: IntConsumer) {
        this.callback = callback
        val pin = conn.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop the interrupt delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.let {
            it.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
    }

    /**
     * Read the source registers of every currently enabled source and return
     * the combined status.
     *
     * **Reading `INT1_SRC` / `INT2_SRC` / `CLICK_SRC` clears their latched
     * flags** (and the INT pin).
     *
     * @return the combined status — bit 0 [Lis3dhSource.SOURCE_DATA_READY],
     * bit 1 [Lis3dhSource.SOURCE_AUX_DATA_READY], bit 2
     * [Lis3dhSource.SOURCE_WATERMARK], bit 3 [Lis3dhSource.SOURCE_OVERRUN],
     * bit 4 [Lis3dhSource.SOURCE_IA1], bit 5 [Lis3dhSource.SOURCE_IA2], bit 6
     * [Lis3dhSource.SOURCE_CLICK]. Sources that are not enabled read as 0.
     */
    fun pollInterrupt(): Int {
        val e1 = enabled1
        val e2 = enabled2
        val enabled = e1 or e2
        var status = 0
        // The INT1-only sources are routed through CTRL_REG3.
        if (e1 and (1 shl SOURCE_BIT_DATA_READY) != 0 && readReg(REG_STATUS) and 0x08 != 0) {
            status = status or Lis3dhSource.SOURCE_DATA_READY
        }
        if (e1 and (1 shl SOURCE_BIT_AUX_DATA_READY) != 0 && readReg(REG_STATUS_AUX) and 0x08 != 0) {
            status = status or Lis3dhSource.SOURCE_AUX_DATA_READY
        }
        if (e1 and (1 shl SOURCE_BIT_WATERMARK) != 0 || e1 and (1 shl SOURCE_BIT_OVERRUN) != 0) {
            val fifo = readReg(REG_FIFO_SRC)
            if (e1 and (1 shl SOURCE_BIT_WATERMARK) != 0 && fifo and 0x80 != 0) {
                status = status or Lis3dhSource.SOURCE_WATERMARK
            }
            if (e1 and (1 shl SOURCE_BIT_OVERRUN) != 0 && fifo and 0x40 != 0) {
                status = status or Lis3dhSource.SOURCE_OVERRUN
            }
        }
        // The generator flags live in their own source register, no matter
        // which pin the generator is routed to.
        if (enabled and (1 shl SOURCE_BIT_IA1) != 0 && readReg(REG_INT1_SRC) and 0x40 != 0) {
            status = status or Lis3dhSource.SOURCE_IA1
        }
        if (enabled and (1 shl SOURCE_BIT_IA2) != 0 && readReg(REG_INT2_SRC) and 0x40 != 0) {
            status = status or Lis3dhSource.SOURCE_IA2
        }
        if (enabled and (1 shl SOURCE_BIT_CLICK) != 0 && readReg(REG_CLICK_SRC) and 0x40 != 0) {
            status = status or Lis3dhSource.SOURCE_CLICK
        }
        return status
    }

    // -------------------------------------------------------------------------
    // System
    // -------------------------------------------------------------------------

    /**
     * Reboot the memory content (`BOOT` = 1) and re-run the Initialization
     * Sequence.
     *
     * Reloads the factory trim and waits 5 ms. The datasheet does not say
     * whether the control registers survive, so the Initialization Sequence is
     * re-run afterwards and the configuration returns to the Minimal
     * defaults.
     */
    fun reboot() {
        val ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, ctrl5 or 0x80)
        sleepMs(BOOT_DELAY_MS.toLong())
        rangeG = RANGE_2G
        resolutionMode = MODE_HIGH_RESOLUTION
        rateHz = 100
        enabled1 = 0
        enabled2 = 0
        runInitSequence()
    }

    /**
     * Connect or disconnect the pull-up on SDO/SA0 (`CTRL_REG0`).
     *
     * `CTRL_REG0` is not a normal register: bit 4 must stay 1 and the other
     * bits except `SDO_PU_DISC` must stay 0, so the register is written with
     * the forced values rather than read-modify-written.
     *
     * @param connected true to connect the pull-up (`SDO_PU_DISC` = 0), false
     * to disconnect it.
     */
    fun setSdoPullup(connected: Boolean) {
        val value = 0x10 or (if (connected) 0x00 else 0x80)
        writeReg(REG_CTRL_REG0, value)
    }

    /**
     * Run the datasheet electrostatic self-test.
     *
     * Configures ±2 *g*, normal (10-bit) mode, 50 Hz with `BDU` on, averages
     * a few samples with `ST` = 00, enables `ST` (01 = self-test 0, 10 =
     * self-test 1), waits 2 samples and averages again. Every axis must show
     * a 17–360 LSb change (1 LSb = 4 mg at 10-bit / ±2 *g*). The previous
     * `CTRL_REG1` / `CTRL_REG4` configuration is restored afterwards.
     *
     * @param mode 1 = self-test 0, 2 = self-test 1.
     * @return true if every axis showed a change inside 17–360 LSb.
     * @throws IllegalArgumentException if [mode] is not 1 or 2.
     */
    fun selfTest(mode: Int = 1): Boolean {
        require(mode == 1 || mode == 2) {
            "LIS3DH selfTest: mode must be 1 (self-test 0) or 2 (self-test 1), got $mode"
        }
        val savedCtrl1 = readReg(REG_CTRL_REG1)
        val savedCtrl4 = readReg(REG_CTRL_REG4)
        val savedRange = this.rangeG
        val savedMode = this.resolutionMode
        val savedRate = this.rateHz
        try {
            configureSelfTest(0x00)
            sleepMs(10)
            val before = averageRaw(5, 20)
            configureSelfTest(if (mode == 1) 0x02 else 0x04)
            sleepMs(40)  // 2 samples at 50 Hz
            val after = averageRaw(5, 20)
            for (i in 0..2) {
                val delta = abs(after[i] - before[i])
                if (delta < 17 || delta > 360) return false
            }
            return true
        } finally {
            writeReg(REG_CTRL_REG1, savedCtrl1)
            writeReg(REG_CTRL_REG4, savedCtrl4)
            this.rangeG = savedRange
            this.resolutionMode = savedMode
            this.rateHz = savedRate
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun configureSelfTest(stBits: Int) {
        writeReg(REG_CTRL_REG4, 0x80 or stBits)
        writeReg(REG_CTRL_REG1, 0x47)  // 50 Hz, LPen = 0, X/Y/Z enabled
        rangeG = RANGE_2G
        resolutionMode = MODE_NORMAL
        rateHz = 50
    }

    private fun averageRaw(samples: Int, delayMs: Int): DoubleArray {
        val total = DoubleArray(3)
        for (i in 0 until samples) {
            val raw = readRaw()
            total[0] += raw[0]
            total[1] += raw[1]
            total[2] += raw[2]
            sleepMs(delayMs.toLong())
        }
        for (i in 0..2) {
            total[i] /= samples
        }
        return total
    }

    private fun generatorRegs(generator: Int): IntArray = when (generator) {
        1 -> intArrayOf(REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR)
        2 -> intArrayOf(REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR)
        else -> throw IllegalArgumentException("LIS3DH: generator must be 1 or 2, got $generator")
    }

    /**
     * Interrupt-threshold LSB in mg for the current full-scale range
     * (datasheet scale, full-scale / 128).
     */
    private fun thresholdLsbMg(): Int = when (rangeG) {
        RANGE_4G -> 32
        RANGE_8G -> 62
        RANGE_16G -> 186
        else -> 16
    }

    private fun thresholdCode(thresholdG: Double): Int {
        val code = roundHalfAwayFromZero(thresholdG * 1000.0 / thresholdLsbMg())
        return code.coerceIn(0, 127)
    }

    private fun durationCode(durationMs: Double, limit: Int): Int {
        // 1 LSb = 1/ODR; while powered down there is no ODR to convert with,
        // so the code is 0.
        if (rateHz <= 0) return 0
        val code = roundHalfAwayFromZero(durationMs * rateHz / 1000.0)
        return code.coerceIn(0, limit)
    }

    private fun rangeIndex(rangeG: Int): Int = when (rangeG) {
        RANGE_2G -> 0
        RANGE_4G -> 1
        RANGE_8G -> 2
        RANGE_16G -> 3
        else -> -1
    }

    private fun rateCode(rateHz: Int): Int = when (rateHz) {
        0 -> 0x00
        1 -> 0x10
        10 -> 0x20
        25 -> 0x30
        50 -> 0x40
        100 -> 0x50
        200 -> 0x60
        400 -> 0x70
        1620 -> 0x80
        1344 -> 0x90
        5376 -> 0x90
        else -> -1
    }

    private fun sourceBit(source: Int): Int {
        var bit = 0
        var value = source
        while (value > 1) {
            value = value shr 1
            bit++
        }
        return bit
    }

    /**
     * Return the routing of a source: the register, the mask inside it and
     * the pin whose bookkeeping tracks it (1 = INT1, 2 = INT2).
     */
    private fun route(source: Int, pin: Int): IntArray {
        require(pin == 1 || pin == 2) { "LIS3DH: pin must be 1 or 2, got $pin" }
        return when (source) {
            Lis3dhSource.SOURCE_DATA_READY -> intArrayOf(REG_CTRL_REG3, 0x10, 1)
            Lis3dhSource.SOURCE_AUX_DATA_READY -> intArrayOf(REG_CTRL_REG3, 0x08, 1)
            Lis3dhSource.SOURCE_WATERMARK -> intArrayOf(REG_CTRL_REG3, 0x04, 1)
            Lis3dhSource.SOURCE_OVERRUN -> intArrayOf(REG_CTRL_REG3, 0x02, 1)
            Lis3dhSource.SOURCE_IA1 -> intArrayOf(if (pin == 1) REG_CTRL_REG3 else REG_CTRL_REG6, 0x40, pin)
            Lis3dhSource.SOURCE_IA2 -> intArrayOf(if (pin == 1) REG_CTRL_REG3 else REG_CTRL_REG6, 0x20, pin)
            Lis3dhSource.SOURCE_CLICK -> intArrayOf(if (pin == 1) REG_CTRL_REG3 else REG_CTRL_REG6, 0x80, pin)
            Lis3dhSource.SOURCE_ACTIVITY -> intArrayOf(REG_CTRL_REG6, 0x08, 2)
            Lis3dhSource.SOURCE_BOOT -> intArrayOf(REG_CTRL_REG6, 0x10, 2)
            else -> throw IllegalArgumentException(
                "LIS3DH: unknown interrupt source 0x${Integer.toHexString(source)}"
            )
        }
    }

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "lis3dh-poll")
        pollThread!!.isDaemon = true
        pollThread!!.start()
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status == 0) return
            callback?.accept(status)
        } catch (e: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Decoded `FIFO_SRC_REG`.
     *
     * @param watermark true when the FIFO content exceeds the watermark (`WTM`).
     * @param overrun true when the FIFO is full and overwrites the oldest
     * sample (`OVRN_FIFO`).
     * @param empty true when all samples have been read (`EMPTY`).
     * @param level the number of unread sample sets (`FSS<4:0>`), 0–32.
     */
    data class FifoStatus(
        val watermark: Boolean,
        val overrun: Boolean,
        val empty: Boolean,
        val level: Int
    )
}
