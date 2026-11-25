package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * MPL3115A2 — full driver: altimeter mode, oversampling, continuous acquisition, offsets,
 * sea-level pressure, targets and windows, min/max capture, the 32-sample FIFO and interrupts.
 * Extends [Mpl3115a2Minimal].
 */
class Mpl3115a2Full(connection: RegisterConnection) : Mpl3115a2Minimal(connection) {
    companion object {
        const val SOURCE_DRDY = 0x80
        const val SOURCE_FIFO = 0x40
        const val SOURCE_PW   = 0x20
        const val SOURCE_TW   = 0x10
        const val SOURCE_PTH  = 0x08
        const val SOURCE_TTH  = 0x04
        const val SOURCE_PCHG = 0x02
        const val SOURCE_TCHG = 0x01

        const val FIFO_OFF      = 0
        const val FIFO_CIRCULAR = 1
        const val FIFO_STOP     = 2
    }

    private var fifoMode = 0
    @Volatile private var callback: IntConsumer? = null
    @Volatile private var intPinNo = 1
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun standby(): Boolean {
        val c1 = readReg(REG_CTRL_REG1)
        val was = c1 and CTRL1_SBYB != 0
        if (was) writeReg(REG_CTRL_REG1, c1 and CTRL1_SBYB.inv())
        return was
    }

    private fun restore(was: Boolean) {
        if (was) writeReg(REG_CTRL_REG1, ctrl1() or CTRL1_SBYB)
    }

    /** Select barometer (false) or altimeter (true) mode; the chip is dropped to STANDBY and restored. */
    @Throws(IOException::class)
    fun setMode(altimeter: Boolean) {
        val was = standby()
        alt = altimeter
        writeReg(REG_CTRL_REG1, ctrl1())
        restore(was)
    }

    /** Run one barometer conversion (switching back from altimeter mode if needed). */
    @Throws(IOException::class)
    override fun read(): DoubleArray {
        if (alt) setMode(false)
        return super.read()
    }

    /**
     * Switch to altimeter mode if needed and run one conversion.
     *
     * @return altitude in metres relative to the sea-level pressure (BAR_IN)
     */
    @Throws(IOException::class)
    fun readAltitude(): Double {
        if (!alt) setMode(true)
        return altitudeFrom(oneShot(), 0)
    }

    /**
     * Set the oversample ratio.
     *
     * @param ratio 1, 2, 4, 8, 16, 32, 64 or 128
     * @throws IllegalArgumentException for any other value
     */
    @Throws(IOException::class)
    fun setOversample(ratio: Int) {
        require(ratio in 1..128 && Integer.bitCount(ratio) == 1) { "ratio must be 1, 2, 4, ..., 128" }
        val was = standby()
        os = Integer.numberOfTrailingZeros(ratio)
        writeReg(REG_CTRL_REG1, ctrl1())
        restore(was)
    }

    /** The current oversample ratio (1..128). */
    fun getOversample(): Int = 1 shl os

    /**
     * Start periodic acquisition (ACTIVE mode).
     *
     * @param stepExp auto-acquisition step exponent 0-15 (2^n seconds)
     * @throws IllegalArgumentException if stepExp is outside 0-15
     */
    @Throws(IOException::class)
    fun startContinuous(stepExp: Int) {
        require(stepExp in 0..15) { "stepExp must be 0-15" }
        standby()
        writeReg(REG_CTRL_REG2, (readReg(REG_CTRL_REG2) and 0xF0) or stepExp)
        writeReg(REG_CTRL_REG1, ctrl1() or CTRL1_SBYB)
    }

    /** Stop periodic acquisition (back to STANDBY). */
    @Throws(IOException::class)
    fun stopContinuous() = writeReg(REG_CTRL_REG1, ctrl1())

    /** True if new pressure/altitude or temperature data is ready (PTDR). */
    @Throws(IOException::class)
    fun dataReady(): Boolean = readReg(REG_DR_STATUS) and DR_PTDR != 0

    /**
     * Wait for PTDR and read the latest sample.
     *
     * @return `[value (Pa or m per mode), temperature in °C]`
     */
    @Throws(IOException::class)
    fun readContinuous(): DoubleArray {
        waitPtdr()
        val raw = readRegs(REG_OUT_P_MSB, 5)
        val v = if (alt) altitudeFrom(raw, 0) else pressureFrom(raw, 0)
        return doubleArrayOf(v, temperatureFrom(raw, 3))
    }

    /**
     * Read the change since the previous sample.
     *
     * @return `[delta value (Pa or m), delta temperature in °C]`
     */
    @Throws(IOException::class)
    fun readDelta(): DoubleArray {
        val raw = readRegs(REG_OUT_P_DELTA, 5)
        var dv = if (alt) altitudeFrom(raw, 0) else pressureFrom(raw, 0)
        if (!alt && dv >= 262144.0) dv -= 524288.0
        return doubleArrayOf(dv, temperatureFrom(raw, 3))
    }

    /** Write BAR_IN, the altitude reference (2 Pa resolution, default 101326 Pa). */
    @Throws(IOException::class)
    fun setSeaLevelPressure(pa: Double) {
        val raw = Math.round(pa / 2.0).toInt() and 0xFFFF
        writeRegs(REG_BAR_IN_MSB, raw shr 8, raw and 0xFF)
    }

    /**
     * Write the offset trims: pressure (4 Pa/LSB), temperature (0.0625 °C/LSB), altitude (m).
     *
     * @throws IllegalArgumentException if a value is outside its register range
     */
    @Throws(IOException::class)
    fun setOffsets(pressurePa: Double = 0.0, temperatureC: Double = 0.0, altitudeM: Int = 0) {
        val op = Math.round(pressurePa / 4.0)
        val ot = Math.round(temperatureC / 0.0625)
        require(op in -128..127 && ot in -128..127 && altitudeM in -128..127) { "offset out of range" }
        writeReg(REG_OFF_P, op.toInt() and 0xFF)
        writeReg(REG_OFF_T, ot.toInt() and 0xFF)
        writeReg(REG_OFF_H, altitudeM and 0xFF)
    }

    /**
     * Program the threshold/window targets (pascals or metres per mode; temperature in °C).
     */
    @Throws(IOException::class)
    fun setTarget(value: Double, window: Double, temperatureC: Int = 0, temperatureWindow: Int = 0) {
        val tgt = (if (alt) Math.round(value) else Math.round(value / 2.0)).toInt() and 0xFFFF
        val wnd = (if (alt) Math.round(window) else Math.round(window / 2.0)).toInt() and 0xFFFF
        val was = standby()
        writeRegs(REG_P_TGT_MSB, tgt shr 8, tgt and 0xFF)
        writeReg(REG_T_TGT, temperatureC and 0xFF)
        writeRegs(REG_P_WND_MSB, wnd shr 8, wnd and 0xFF)
        writeReg(REG_T_WND, temperatureWindow and 0xFF)
        restore(was)
    }

    /** Use the current output as the threshold target (ALARM_SEL = 1, pulse LOAD_OUTPUT). */
    @Throws(IOException::class)
    fun loadCurrentAsTarget() {
        val c2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, (c2 or 0x10) and 0x20.inv())
        writeReg(REG_CTRL_REG2, c2 or 0x30)
    }

    /**
     * Read the captured extremes.
     *
     * @return `[pMin, pMax, tMin, tMax]` — pascals (metres in altimeter mode) and °C
     */
    @Throws(IOException::class)
    fun readMinMax(): DoubleArray {
        val pm = readRegs(REG_P_MIN, 3)
        val px = readRegs(REG_P_MAX, 3)
        val tm = readRegs(REG_T_MIN, 2)
        val tx = readRegs(REG_T_MAX, 2)
        return doubleArrayOf(
            if (alt) altitudeFrom(pm, 0) else pressureFrom(pm, 0),
            if (alt) altitudeFrom(px, 0) else pressureFrom(px, 0),
            temperatureFrom(tm, 0), temperatureFrom(tx, 0))
    }

    /** Clear the min/max registers. */
    @Throws(IOException::class)
    fun clearMinMax() {
        writeRegs(REG_P_MIN, 0, 0, 0)
        writeRegs(REG_T_MIN, 0, 0)
        writeRegs(REG_P_MAX, 0, 0, 0)
        writeRegs(REG_T_MAX, 0, 0)
    }

    /**
     * Configure the FIFO.
     *
     * @param mode 0 off, 1 circular, 2 stop when full
     * @param watermark watermark sample count 0-63 (0 disables the flag)
     * @throws IllegalArgumentException for an invalid mode or a direct switch between modes 1 and 2
     */
    @Throws(IOException::class)
    fun fifoSetup(mode: Int, watermark: Int = 0) {
        require(mode in 0..2 && !(fifoMode != 0 && mode != 0 && mode != fifoMode)) {
            "invalid FIFO mode or direct switch between modes"
        }
        val was = standby()
        writeReg(REG_F_SETUP, (mode shl 6) or (watermark and 0x3F))
        fifoMode = mode
        restore(was)
    }

    /** Read F_STATUS: `[overflow (0/1), watermark (0/1), count]`. */
    @Throws(IOException::class)
    fun fifoStatus(): IntArray {
        val s = readReg(REG_F_STATUS)
        return intArrayOf((s shr 7) and 1, (s shr 6) and 1, s and 0x3F)
    }

    /** Drain the FIFO; one `[value (Pa or m), temperature in °C]` per sample. */
    @Throws(IOException::class)
    fun fifoRead(): List<DoubleArray> {
        val count = fifoStatus()[2]
        val out = ArrayList<DoubleArray>()
        repeat(count) {
            val raw = readRegs(REG_F_DATA, 5)
            val v = if (alt) altitudeFrom(raw, 0) else pressureFrom(raw, 0)
            out.add(doubleArrayOf(v, temperatureFrom(raw, 3)))
        }
        return out
    }

    /** Software reset, then restore the default configuration (as [init]). */
    @Throws(IOException::class)
    fun reset() {
        softReset()
        os = 7
        alt = false
        fifoMode = 0
        applyConfig()
    }

    /** The WHO_AM_I register (0xC4). */
    @Throws(IOException::class)
    fun whoAmI(): Int = readReg(REG_WHO_AM_I)

    /** True if SYSMOD reports ACTIVE mode. */
    @Throws(IOException::class)
    fun isActive(): Boolean = readReg(REG_SYSMOD) and 0x01 != 0

    /** Write CTRL_REG3: interrupt polarity (true = active high) and open-drain per pin. */
    @Throws(IOException::class)
    fun setInterruptPins(polarity1: Boolean = false, openDrain1: Boolean = false,
                         polarity2: Boolean = false, openDrain2: Boolean = false) {
        val was = standby()
        writeReg(REG_CTRL_REG3, (if (polarity1) 0x20 else 0) or (if (openDrain1) 0x10 else 0) or
            (if (polarity2) 0x02 else 0) or (if (openDrain2) 0x01 else 0))
        restore(was)
    }

    /**
     * Enable an interrupt source and route it to INT1 or INT2.
     *
     * @param source a SOURCE_* constant (or an OR of them)
     * @param pin 1 or 2
     * @throws IllegalArgumentException if pin is not 1 or 2
     */
    @Throws(IOException::class)
    fun enableInterrupt(source: Int, pin: Int = 1) {
        require(pin == 1 || pin == 2) { "pin must be 1 or 2" }
        val was = standby()
        val c4 = readReg(REG_CTRL_REG4)
        val c5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG4, c4 or source)
        writeReg(REG_CTRL_REG5, if (pin == 1) c5 or source else c5 and source.inv())
        restore(was)
    }

    /** Disable an interrupt source (clears it in CTRL_REG4). */
    @Throws(IOException::class)
    fun disableInterrupt(source: Int) {
        val was = standby()
        writeReg(REG_CTRL_REG4, readReg(REG_CTRL_REG4) and source.inv())
        restore(was)
    }

    /** Read INT_SOURCE (bitmask of SOURCE_* flags). */
    @Throws(IOException::class)
    fun pollInterrupt(): Int = readReg(REG_INT_SOURCE)

    /**
     * Subscribe to INT1 or INT2 on [intPin] (falling edge; defaults to `connection.intPin()`), or
     * a 100 ms polling thread if no pin is wired. The callback receives INT_SOURCE masked to the
     * sources routed to that pin.
     *
     * @param callback receives the status bitmask
     * @param pin 1 or 2
     * @param intPin pin to arm, or null to force the polling fallback
     */
    fun onInterrupt(callback: IntConsumer, pin: Int, intPin: InputPin?) {
        require(pin == 1 || pin == 2) { "pin must be 1 or 2" }
        offInterrupt()
        this.callback = callback
        intPinNo = pin
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "mpl3115a2-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Subscribe to INT1 or INT2 using `connection.intPin()` (falling edge), or a polling thread if none is wired. */
    fun onInterrupt(callback: IntConsumer, pin: Int = 1) = onInterrupt(callback, pin, connection.intPin())

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            var status = pollInterrupt()
            val c5 = readReg(REG_CTRL_REG5)
            status = status and (if (intPinNo == 1) c5 else c5.inv() and 0xFF)
            if (status != 0) cb.accept(status)
        } catch (e: IOException) {
            // transient bus error: the next edge or tick retries
        }
    }
}
