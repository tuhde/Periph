package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer
import kotlin.math.pow

/**
 * SPL06-007 — full driver: oversampling and rate configuration, background (continuous) mode,
 * the 32-entry FIFO, raw values and compensation, altitude, soft reset and the data-ready /
 * FIFO-full interrupt. Extends [Spl06Minimal].
 *
 * The interrupt output shares the SDO pin. INT_HL stays at its reset value 0 (active low), whose
 * idle-high level matches the default address 0x77.
 *
 * @param connection I²C register connection bound to address 0x77 or 0x76
 */
class Spl06Full(connection: RegisterConnection) : Spl06Minimal(connection) {
    companion object {
        /** Interrupt source: a pressure result is ready (INT_STS.INT_PRS). */
        const val SOURCE_PRESSURE    = 0x01
        /** Interrupt source: a temperature result is ready (INT_STS.INT_TMP). */
        const val SOURCE_TEMPERATURE = 0x02
        /** Interrupt source: the FIFO is full (INT_STS.INT_FIFO_FULL). */
        const val SOURCE_FIFO_FULL   = 0x04

        /** FIFO entry kind: pressure (value in Pa). */
        const val KIND_PRESSURE    = "pressure"
        /** FIFO entry kind: temperature (value in °C). */
        const val KIND_TEMPERATURE = "temperature"

        private const val CFG_INT_HL   = 0x80
        private const val CFG_INT_FIFO = 0x40
        private const val CFG_INT_PRS  = 0x20
        private const val CFG_INT_TMP  = 0x10
        private const val CFG_FIFO_EN  = 0x02

        private fun log2(value: Int, name: String): Int {
            for (i in 0 until 8) if (value == 1 shl i) return i
            throw IllegalArgumentException("$name must be 1, 2, 4, 8, 16, 32, 64 or 128")
        }

        private fun intBits(source: Int): Int {
            var bits = 0
            if (source and SOURCE_PRESSURE != 0) bits = bits or CFG_INT_PRS
            if (source and SOURCE_TEMPERATURE != 0) bits = bits or CFG_INT_TMP
            if (source and SOURCE_FIFO_FULL != 0) bits = bits or CFG_INT_FIFO
            return bits
        }
    }

    /** One FIFO entry: [kind] is [KIND_PRESSURE] (value in Pa) or [KIND_TEMPERATURE] (value in °C). */
    data class FifoEntry(val kind: String, val value: Double)

    private var pRate = 0
    private var tRate = 0
    private var continuous = 0
    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    private fun writeCfg() = writeReg(REG_CFG_REG, cfg)

    /**
     * Measure temperature, then pressure; in background mode return the latest results instead.
     *
     * @return temperature in °C and pressure in Pa
     */
    override fun read(): Measurement = if (continuous != 0) readContinuous() else super.read()

    /**
     * Measure temperature and pressure and return the compensated pressure.
     *
     * @return pressure in Pa
     */
    fun readPressure(): Double = read().pressurePa

    /**
     * Measure and return the temperature.
     *
     * @return temperature in °C
     */
    fun readTemperature(): Double {
        if (continuous != 0) return readContinuous().temperatureC
        val traw = measure(CMD_TEMPERATURE, MEAS_TMP_RDY, tOs)
        lastTraw = traw
        return temperature(traw)
    }

    /**
     * Raw 24-bit two's-complement results. Command mode measures temperature, then pressure;
     * background mode reads the latest results without starting a measurement.
     *
     * @return `[praw, traw]`
     */
    fun readRaw(): IntArray {
        if (continuous != 0) {
            val b = connection.read(REG_PRS_B2, 6)
            val traw = raw24(b, 3)
            lastTraw = traw
            return intArrayOf(raw24(b, 0), traw)
        }
        return measureRaw()
    }

    /**
     * Write PRS_CFG and P_SHIFT (set above 8×).
     *
     * @param oversampling 1, 2, 4, 8, 16, 32, 64 or 128
     * @param rate background measurements per second: 1, 2, 4, …, 128
     * @throws IllegalArgumentException for values other than powers of two 1-128
     */
    fun setPressureConfig(oversampling: Int = 16, rate: Int = 1) {
        val os = log2(oversampling, "oversampling")
        val r = log2(rate, "rate")
        writeReg(REG_PRS_CFG, (r shl 4) or os)
        pOs = os
        pRate = r
        cfg = if (oversampling > 8) cfg or CFG_P_SHIFT else cfg and CFG_P_SHIFT.inv() and 0xFF
        writeCfg()
    }

    /**
     * Write TMP_CFG (always TMP_EXT = 1) and T_SHIFT (set above 8×).
     *
     * @param oversampling 1, 2, 4, 8, 16, 32, 64 or 128
     * @param rate background measurements per second: 1, 2, 4, …, 128
     * @throws IllegalArgumentException for values other than powers of two 1-128
     */
    fun setTemperatureConfig(oversampling: Int = 1, rate: Int = 1) {
        val os = log2(oversampling, "oversampling")
        val r = log2(rate, "rate")
        writeReg(REG_TMP_CFG, TMP_EXT or (r shl 4) or os)
        tOs = os
        tRate = r
        cfg = if (oversampling > 8) cfg or CFG_T_SHIFT else cfg and CFG_T_SHIFT.inv() and 0xFF
        writeCfg()
    }

    /**
     * Start background mode (MEAS_CTRL = 101 / 110 / 111).
     *
     * @param pressure measure pressure continuously
     * @param temperature measure temperature continuously
     * @throws IllegalArgumentException if both are false
     */
    fun startContinuous(pressure: Boolean = true, temperature: Boolean = true) {
        require(pressure || temperature) { "select pressure and/or temperature" }
        val mode = 0x04 or (if (pressure) 0x01 else 0) or (if (temperature) 0x02 else 0)
        writeReg(REG_MEAS_CFG, mode)
        continuous = mode
    }

    /** Stop background mode and return to standby (MEAS_CTRL = 000). */
    fun stop() {
        writeReg(REG_MEAS_CFG, CMD_IDLE)
        continuous = 0
    }

    /**
     * Wait for the background ready flags and return the latest results (no new measurement).
     *
     * @return temperature in °C and pressure in Pa
     */
    fun readContinuous(): Measurement {
        var flags = 0
        if (continuous and 0x01 != 0) flags = flags or MEAS_PRS_RDY
        if (continuous and 0x02 != 0) flags = flags or MEAS_TMP_RDY
        if (flags != 0) {
            val deadline = System.currentTimeMillis() + 2000L / (1 shl minOf(pRate, tRate)) + 500
            while (readReg(REG_MEAS_CFG) and flags != flags) {
                if (System.currentTimeMillis() > deadline) throw IOException("SPL06 background data not ready")
                Thread.sleep(2)
            }
        }
        val b = connection.read(REG_PRS_B2, 6)
        val praw = raw24(b, 0)
        val traw = raw24(b, 3)
        lastTraw = traw
        return Measurement(temperature(traw), pressure(praw, traw))
    }

    /**
     * Read the MEAS_CFG ready flags.
     *
     * @return `(pressureReady, temperatureReady)`
     */
    fun dataReady(): Pair<Boolean, Boolean> {
        val m = readReg(REG_MEAS_CFG)
        return Pair(m and MEAS_PRS_RDY != 0, m and MEAS_TMP_RDY != 0)
    }

    /** Enable the 32-entry result FIFO (CFG_REG.FIFO_EN). */
    fun enableFifo() {
        cfg = cfg or CFG_FIFO_EN
        writeCfg()
    }

    /** Disable the result FIFO. */
    fun disableFifo() {
        cfg = cfg and CFG_FIFO_EN.inv() and 0xFF
        writeCfg()
    }

    /**
     * Drain the FIFO until FIFO_EMPTY (at most 32 entries). The LSB of each entry flags its kind
     * (1 = pressure, 0 = temperature); pressure entries are compensated with the most recent
     * temperature entry (or the last measured temperature).
     *
     * @return entries in measured order
     */
    fun readFifo(): List<FifoEntry> {
        val out = ArrayList<FifoEntry>()
        for (i in 0 until 32) {
            if (readReg(REG_FIFO_STS) and 0x01 != 0) break
            val raw = raw24(connection.read(REG_PRS_B2, 3), 0)
            if (raw == -0x800000) break
            if (raw and 1 != 0) {
                out.add(FifoEntry(KIND_PRESSURE, pressure(raw and 1.inv(), lastTraw)))
            } else {
                lastTraw = raw
                out.add(FifoEntry(KIND_TEMPERATURE, temperature(raw)))
            }
        }
        return out
    }

    /** Discard the FIFO contents (RESET.FIFO_FLUSH). */
    fun flushFifo() = writeReg(REG_RESET, 0x80)

    /**
     * Read FIFO_STS.
     *
     * @return `(empty, full)`
     */
    fun fifoStatus(): Pair<Boolean, Boolean> {
        val s = readReg(REG_FIFO_STS)
        return Pair(s and 0x01 != 0, s and 0x02 != 0)
    }

    /** Soft reset (RESET = 0x09), wait for the ready flags, re-read the coefficients and restore the [init] defaults. */
    fun reset() {
        try {
            writeReg(REG_RESET, 0x09)
        } catch (e: IOException) {
            // the chip may not acknowledge while it resets
        }
        Thread.sleep(12)
        waitReady()
        readCoefficients()
        applyDefaults()
        pRate = 0
        tRate = 0
        continuous = 0
    }

    /**
     * Decoded calibration coefficients.
     *
     * @return c0, c1, c00, c10, c01, c11, c20, c21, c30
     */
    fun coefficients(): Coefficients = coef

    /**
     * Compensate raw values with the stored coefficients (no bus access).
     *
     * @param praw signed raw pressure
     * @param traw signed raw temperature
     * @param pOversampling pressure oversampling of [praw]
     * @param tOversampling temperature oversampling of [traw]
     * @return temperature in °C and pressure in Pa
     * @throws IllegalArgumentException for an oversampling other than a power of two 1-128
     */
    fun compensate(praw: Int, traw: Int, pOversampling: Int, tOversampling: Int): Measurement {
        val p = praw / SCALE[log2(pOversampling, "pOversampling")]
        val t = traw / SCALE[log2(tOversampling, "tOversampling")]
        return Measurement(compT(coef, t), compP(coef, p, t))
    }

    /**
     * Measure the pressure and convert it to altitude (international barometric formula).
     *
     * @param seaLevelPa reference sea-level pressure in Pa
     * @return altitude in m
     */
    fun altitude(seaLevelPa: Double = 101325.0): Double = 44330.0 * (1.0 - (readPressure() / seaLevelPa).pow(1.0 / 5.255))

    /**
     * Enable interrupt sources on SDO (CFG_REG.INT_PRS / INT_TMP / INT_FIFO).
     *
     * @param source SOURCE_* constant or a bitwise OR of them
     */
    fun enableInterrupt(source: Int) {
        cfg = cfg or intBits(source)
        writeCfg()
    }

    /**
     * Disable interrupt sources.
     *
     * @param source SOURCE_* constant or a bitwise OR of them
     */
    fun disableInterrupt(source: Int) {
        cfg = cfg and intBits(source).inv() and 0xFF
        writeCfg()
    }

    /**
     * Read INT_STS (cleared on read).
     *
     * @return bitmask of SOURCE_* flags (0 = none)
     */
    fun pollInterrupt(): Int = readReg(REG_INT_STS) and 0x07

    /**
     * Subscribe to the interrupt on [intPin] (edge matching INT_HL), or a 5 ms polling thread when
     * there is no pin.
     *
     * @param callback receives the SOURCE_* bitmask
     * @param intPin pin wired to SDO; defaults to `connection.intPin()`
     */
    fun onInterrupt(callback: IntConsumer, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, if (cfg and CFG_INT_HL != 0) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "spl06-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

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
            val status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (e: IOException) {
            // a failed poll is retried on the next edge / tick
        }
    }
}
