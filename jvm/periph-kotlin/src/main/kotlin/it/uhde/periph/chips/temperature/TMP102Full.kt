package it.uhde.periph.chips.temperature

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * TMP102 full interface — extends [TMP102Minimal] with conversion rate,
 * extended mode, the `T_LOW`/`T_HIGH` limits, fault queue, polarity,
 * thermostat mode, shutdown/one-shot, alert status, general-call reset and the
 * ALERT interrupt API.
 *
 * [onInterrupt] uses `connection.intPin()` if wired (edge direction follows
 * `POL`), otherwise a 1 s polling thread that reports the temperature while
 * [pollInterrupt] indicates an alert.
 *
 * @param connection configured I²C connection bound to the device (0x48–0x4B)
 */
class TMP102Full(connection: RegisterConnection) : TMP102Minimal(connection) {

    companion object {
        // CONFIGURATION (0x01) bits.
        private const val CFG_OS = 0x8000
        private const val CFG_F_SHIFT = 11
        private const val CFG_F_MASK = 0x1800
        private const val CFG_POL = 0x0400
        private const val CFG_TM = 0x0200
        private const val CFG_SD = 0x0100
        private const val CFG_CR_SHIFT = 6
        private const val CFG_CR_MASK = 0x00C0
        private const val CFG_AL = 0x0020
        private const val CFG_EM = 0x0010
        private const val CFG_WRITABLE = 0x9FD0
        private const val CFG_RESERVED = 0x6000 // R1:R0, read-only, always 11

        private const val LIMIT_MIN = -55.0
        private const val LIMIT_MAX = 150.0
        private val RATES = doubleArrayOf(0.25, 1.0, 4.0, 8.0)
        private val FAULTS = intArrayOf(1, 2, 4, 6)
    }

    private val edgeHandler = EdgeHandler { handleEdge() }
    @Volatile private var callback: ((Double) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null

    /** Read the raw signed temperature count (temperature = count × 0.0625 °C). */
    fun readRaw(): Int = decodeRaw(readReg(REG_TEMPERATURE))

    /** Read the 16-bit CONFIGURATION register (reset value 0x60A0). */
    fun readConfig(): Int = readReg(REG_CONFIG)

    /**
     * Write the 16-bit CONFIGURATION register; read-only bits are masked out.
     *
     * @param value register value; only OS, F1:F0, POL, TM, SD, CR1:CR0 and EM are applied
     */
    fun writeConfig(value: Int) = writeReg(REG_CONFIG, (value and CFG_WRITABLE) or CFG_RESERVED)

    private fun updateConfig(clear: Int, set: Int) = writeConfig((readConfig() and clear.inv()) or set)

    /**
     * Set the conversion rate.
     *
     * @param hz 0.25, 1, 4 or 8 Hz
     * @throws IllegalArgumentException if [hz] is not a supported rate
     */
    fun setConversionRate(hz: Double) {
        val code = RATES.indexOfFirst { it == hz }
        require(code >= 0) { "hz must be 0.25, 1, 4 or 8" }
        updateConfig(CFG_CR_MASK, code shl CFG_CR_SHIFT)
    }

    /** Enable or disable the 13-bit extended mode (`EM` bit). */
    fun setExtendedMode(on: Boolean) = updateConfig(CFG_EM, if (on) CFG_EM else 0)

    private fun setLimit(reg: Int, celsius: Double) {
        val extended = readConfig() and CFG_EM != 0
        val c = celsius.coerceIn(LIMIT_MIN, LIMIT_MAX)
        var counts = Math.round(c / LSB_C).toInt()
        if (extended) {
            writeReg(reg, (counts shl 3) and 0xFFF8)
        } else {
            if (counts > 2047) counts = 2047
            writeReg(reg, (counts shl 4) and 0xFFF0)
        }
    }

    private fun getLimit(reg: Int): Double {
        val extended = readConfig() and CFG_EM != 0
        val raw16 = readReg(reg)
        val counts = if (extended) Register.toSigned(raw16 shr 3, 13) else Register.toSigned(raw16 shr 4, 12)
        return counts * LSB_C
    }

    /**
     * Set the `T_HIGH` limit (reset 80 °C), rounded to 0.0625 °C and clamped to
     * −55…+150 °C (12-bit mode: up to +127.9375 °C).
     */
    fun setTHigh(celsius: Double) = setLimit(REG_T_HIGH, celsius)

    /** Read the `T_HIGH` limit in °C. */
    fun getTHigh(): Double = getLimit(REG_T_HIGH)

    /** Set the `T_LOW` limit (reset 75 °C); same coding as [setTHigh]. */
    fun setTLow(celsius: Double) = setLimit(REG_T_LOW, celsius)

    /** Read the `T_LOW` limit in °C. */
    fun getTLow(): Double = getLimit(REG_T_LOW)

    /**
     * Set the number of consecutive faults that trigger ALERT.
     *
     * @param count 1, 2, 4 or 6
     * @throws IllegalArgumentException if [count] is not a supported value
     */
    fun setFaultQueue(count: Int) {
        val code = FAULTS.indexOf(count)
        require(code >= 0) { "count must be 1, 2, 4 or 6" }
        updateConfig(CFG_F_MASK, code shl CFG_F_SHIFT)
    }

    /** Set the ALERT polarity (`POL` bit): `true` = active high. */
    fun setPolarity(activeHigh: Boolean) = updateConfig(CFG_POL, if (activeHigh) CFG_POL else 0)

    /** Select the thermostat mode (`TM` bit): `true` = interrupt, `false` = comparator. */
    fun setThermostatMode(interrupt: Boolean) = updateConfig(CFG_TM, if (interrupt) CFG_TM else 0)

    /** Shut the device down after the current conversion (`SD` = 1). */
    fun shutdown() = updateConfig(0, CFG_SD)

    /** Resume continuous conversion (`SD` = 0). */
    fun wake() = updateConfig(CFG_SD, 0)

    /**
     * Take a single conversion and return the temperature. Puts the device in
     * shutdown if needed, sets `OS` = 1, polls `OS` until the conversion is done
     * (30 ms timeout) and restores the previous mode.
     *
     * @return temperature in °C
     * @throws IOException if the conversion does not finish within 30 ms
     */
    fun oneShot(): Double {
        var config = readConfig()
        val wasRunning = config and CFG_SD == 0
        if (wasRunning) {
            config = config or CFG_SD
            writeConfig(config)
        }
        writeConfig(config or CFG_OS)
        var done = false
        for (i in 0 until 30) {
            Thread.sleep(1)
            if (readConfig() and CFG_OS != 0) {
                done = true
                break
            }
        }
        val t = readTemperature()
        if (wasRunning) wake()
        if (!done) throw IOException("TMP102: one-shot conversion timed out")
        return t
    }

    /** `true` while ALERT is active (the `AL` bit corrected for `POL`). */
    fun alertState(): Boolean {
        val config = readConfig()
        val al = config and CFG_AL != 0
        // AL reads 1 while inactive with POL = 0 (inverted); POL = 1 flips it.
        return if (config and CFG_POL != 0) al else !al
    }

    /**
     * Send the general-call reset (`0x00 0x06`). Resets **every** general-call
     * device on the bus to power-up values; needs a connection that transmits to
     * the general-call address.
     */
    fun reset() = connection.write(byteArrayOf(0x00, 0x06))

    /**
     * Read the alert state and clear an interrupt-mode alert (reads
     * CONFIGURATION, then TEMPERATURE).
     *
     * @return `true` while the comparator state is alerting
     */
    fun pollInterrupt(): Boolean {
        val alerting = alertState()
        readReg(REG_TEMPERATURE)
        return alerting
    }

    /**
     * Subscribe to ALERT events. With a pin, the callback runs on every active
     * edge — falling for active-low, rising for active-high. Without one
     * (default: `connection.intPin()`), a 1 s polling thread calls it while an
     * alert is active.
     *
     * @param callback called with the temperature in °C
     * @param intPin ALERT pin to arm, or `null` to force the polling fallback
     */
    @JvmOverloads
    fun onInterrupt(callback: (Double) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            val activeHigh = readConfig() and CFG_POL != 0
            this.intPin = intPin
            intPin.onEdge(edgeHandler, if (activeHigh) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                try {
                    val alerting = pollInterrupt()
                    val cb = callback
                    if (alerting && cb != null) cb(readTemperature())
                } catch (_: IOException) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "tmp102-poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleEdge() {
        try {
            val t = readTemperature()
            callback?.invoke(t)
        } catch (_: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
