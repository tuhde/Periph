package it.uhde.periph.chips.light

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MAX44009 full interface — extends [MAX44009Minimal] with raw readings,
 * measurement mode and range configuration, interrupt thresholds and delay, and
 * interrupt delivery.
 *
 * @param connection I²C register connection bound to the device (0x4A or 0x4B)
 */
class MAX44009Full(connection: RegisterConnection) : MAX44009Minimal(connection) {

    /** One consistent raw lux sample: exponent field 0–15, 8-bit mantissa and the overrange flag (E = 15). */
    data class Raw(val exponent: Int, val mantissa: Int, val overrange: Boolean)

    /** Decoded CONFIG register. */
    data class Config(val continuous: Boolean, val manual: Boolean, val divideBy8: Boolean, val integrationMs: Double)

    companion object {
        /** Status bit 0: light level outside the threshold window for longer than the delay. */
        const val SOURCE_THRESHOLD = 0x01

        private val INTEGRATION_MS = doubleArrayOf(800.0, 400.0, 200.0, 100.0, 50.0, 25.0, 12.5, 6.25)

        internal fun encodeThreshold(lux: Double): Int {
            if (lux < 11.5) return minOf(15, (lux / (16 * LUX_PER_LSB)).roundToInt())
            var exponent = 1
            var mantissa = 0.0
            while (exponent <= 14) {
                mantissa = lux / ((1 shl exponent).toDouble() * LUX_PER_LSB)
                if (mantissa < 256) break
                exponent++
            }
            if (exponent > 14) exponent = 14
            val m = maxOf(128, minOf(255, mantissa.toInt()))
            return minOf(0xEF, (exponent shl 4) or (m shr 4))
        }

        internal fun decodeThreshold(code: Int, upper: Boolean): Double {
            val exponent = code shr 4
            val nibble = code and 0x0F
            return (1 shl exponent).toDouble() * ((nibble shl 4) or (if (upper) 0x0F else 0x00)) * LUX_PER_LSB
        }
    }

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Read the lux registers as one consistent sample.
     *
     * @return exponent, mantissa and the overrange flag
     */
    fun readRaw(): Raw {
        val (e, m) = readLuxRegs()
        return Raw(e, m, e >= 15)
    }

    /**
     * Whether the last reading was overrange (E = 15).
     */
    fun isOverrange(): Boolean = lastExponent >= 15

    /**
     * Lux per mantissa LSB for the last reading, `2^E · 0.045`.
     */
    fun resolution(): Double = (1 shl minOf(lastExponent, 14)).toDouble() * LUX_PER_LSB

    private fun updateConfig(setBits: Int, clearBits: Int) {
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, (cfg and clearBits.inv()) or setBits)
    }

    /**
     * Select continuous or low-power measurement mode.
     *
     * @param continuous true: a new measurement as soon as one finishes; false: one every 800 ms
     */
    fun setContinuous(continuous: Boolean) = updateConfig(if (continuous) CFG_CONT else 0, CFG_CONT)

    /** Select automatic mode (the chip chooses current divider and integration time). */
    fun setAutomatic() = updateConfig(0, CFG_MANUAL)

    /**
     * Select manual mode.
     *
     * @param integrationMs integration time; rounded to the nearest of 800, 400, 200, 100, 50, 25, 12.5, 6.25 ms
     * @param divideBy8 true to divide the photodiode current by 8 (high brightness)
     */
    fun setManual(integrationMs: Double, divideBy8: Boolean = false) {
        var best = 0
        var bestErr = abs(INTEGRATION_MS[0] - integrationMs)
        for (tim in 1 until 8) {
            val err = abs(INTEGRATION_MS[tim] - integrationMs)
            if (err < bestErr) {
                best = tim
                bestErr = err
            }
        }
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, (cfg and CFG_CONT) or CFG_MANUAL or (if (divideBy8) CFG_CDR else 0) or best)
    }

    /** Read the CONFIG register. */
    fun getConfig(): Config {
        val cfg = readReg(REG_CONFIG)
        return Config(cfg and CFG_CONT != 0, cfg and CFG_MANUAL != 0, cfg and CFG_CDR != 0, INTEGRATION_MS[cfg and CFG_TIM_MASK])
    }

    /**
     * Set the interrupt threshold window.
     *
     * @param lowLux lower threshold in lux
     * @param highLux upper threshold in lux
     * @throws IllegalArgumentException if lowLux is greater than highLux
     */
    fun setThresholds(lowLux: Double, highLux: Double) {
        require(lowLux <= highLux) { "lowLux must not exceed highLux" }
        writeReg(REG_LOWER, encodeThreshold(lowLux))
        writeReg(REG_UPPER, encodeThreshold(highLux))
    }

    /**
     * Read the interrupt threshold window (the upper one carries the implied low nibble 1111).
     *
     * @return (lowLux, highLux)
     */
    fun getThresholds(): Pair<Double, Double> {
        val low = readReg(REG_LOWER)
        val high = readReg(REG_UPPER)
        return Pair(decodeThreshold(low, false), decodeThreshold(high, true))
    }

    /**
     * Set how long the light must stay outside the window before the interrupt.
     *
     * @param seconds delay in seconds, 0–25.5 (0.1 s steps)
     */
    fun setThresholdDelay(seconds: Double) = writeReg(REG_TIMER, maxOf(0, minOf(255, (seconds / 0.1).roundToInt())))

    /** Read the threshold delay in seconds. */
    fun getThresholdDelay(): Double = readReg(REG_TIMER) * 0.1

    /** Enable the interrupt (INT_ENABLE = 1). */
    fun enableInterrupt() = writeReg(REG_INT_ENABLE, 0x01)

    /** Disable the interrupt (INT_ENABLE = 0; also clears a pending status). */
    fun disableInterrupt() = writeReg(REG_INT_ENABLE, 0x00)

    /**
     * Read INT_STATUS (clearing it).
     *
     * @return status; bit 0 ([SOURCE_THRESHOLD]) is set if the light was outside the window for longer than the delay
     */
    fun pollInterrupt(): Int = readReg(REG_INT_STATUS) and 0x01

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 100 ms polling
     * thread if no [InputPin] is available.
     *
     * @param callback called with the status after INT_STATUS has been read and cleared
     * @param intPin overrides `connection.intPin()`, or null
     */
    @Synchronized
    fun onInterrupt(intPin: InputPin? = null, callback: (Int) -> Unit) {
        this.callback = callback
        startDelivery(intPin ?: connection.intPin())
    }

    private fun startDelivery(pin: InputPin?) {
        stopDelivery()
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(100)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "max44009-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun stopDelivery() {
        intPinUsed?.offEdge(edgeHandler)
        intPinUsed = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** Unsubscribe and stop delivery. */
    @Synchronized
    fun offInterrupt() {
        stopDelivery()
        callback = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status != 0) callback?.invoke(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
