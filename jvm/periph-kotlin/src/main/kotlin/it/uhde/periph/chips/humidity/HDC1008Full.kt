package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * HDC1008 full driver — single values, resolution, heater, battery flag, serial ID and the DRDYn
 * data-ready interrupt. Extends [HDC1008Minimal].
 *
 * [onInterrupt] uses `connection.intPin()` if wired (DRDYn falling edge), otherwise a 5 ms polling
 * thread. The caller triggers the measurement; the callback receives {temperature °C, humidity %RH}
 * (humidity is NaN after a single-mode temperature trigger).
 *
 * @param connection plain I²C connection bound to 0x40–0x43
 */
open class HDC1008Full(connection: Connection) : HDC1008Minimal(connection) {

    private var callback: ((DoubleArray) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Measure and return the raw 16-bit words.
     *
     * @return {raw temperature, raw humidity}, bits 1:0 zero
     */
    fun readRaw(): IntArray = measurePair()

    /** Measure temperature only (single mode) in °C. */
    fun readTemperature(): Double {
        setMode(false)
        val d = triggerRead(REG_TEMPERATURE, 2)
        return toTemperature(((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF))
    }

    /** Measure humidity only (single mode) in %RH. */
    fun readHumidity(): Double {
        setMode(false)
        val d = triggerRead(REG_HUMIDITY, 2)
        return toHumidity(((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF))
    }

    /**
     * Select the conversion resolutions.
     *
     * @param temperatureBits 14 or 11
     * @param humidityBits 14, 11 or 8
     * @throws IllegalArgumentException for other values
     */
    fun setResolution(temperatureBits: Int, humidityBits: Int) {
        require(temperatureBits == 14 || temperatureBits == 11) { "temperatureBits must be 14 or 11" }
        require(humidityBits == 14 || humidityBits == 11 || humidityBits == 8) { "humidityBits must be 14, 11 or 8" }
        var cfg = config and (CFG_TRES or CFG_HRES).inv()
        if (temperatureBits == 11) cfg = cfg or CFG_TRES
        if (humidityBits == 11) cfg = cfg or 0x0100 else if (humidityBits == 8) cfg = cfg or 0x0200
        config = cfg
        writeConfig()
    }

    /** Enable or disable the heater. */
    fun setHeater(on: Boolean) {
        config = if (on) config or CFG_HEAT else config and CFG_HEAT.inv()
        writeConfig()
    }

    /** Read the 16-bit configuration register. */
    fun readConfig(): Int {
        connection.write(byteArrayOf(REG_CONFIG.toByte()))
        val d = connection.read(2)
        return ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
    }

    /** Whether the heater bit is set in the configuration register. */
    fun isHeaterOn(): Boolean = (readConfig() and CFG_HEAT) != 0

    /** Whether VDD is below 2.8 V (BTST). */
    fun batteryLow(): Boolean = (readConfig() and CFG_BTST) != 0

    /** Set RST, wait 15 ms, then rewrite the stored configuration. */
    fun softwareReset() {
        connection.write(byteArrayOf(REG_CONFIG.toByte(), (CFG_RST shr 8).toByte(), 0x00))
        sleepMs(15)
        writeConfig()
    }

    /** Read the 40-bit serial number from registers 0xFB–0xFD. */
    fun serialId(): Long {
        val w = LongArray(3)
        for (i in 0 until 3) {
            connection.write(byteArrayOf((REG_SERIAL1 + i).toByte()))
            val d = connection.read(2)
            w[i] = (((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)).toLong()
        }
        return (w[0] shl 24) or (w[1] shl 8) or (w[2] shr 7)
    }

    /** Typical conversion time in seconds for the current resolutions (temperature + humidity). */
    fun conversionTime(): Double {
        val t = if ((config and CFG_TRES) != 0) 0.00365 else 0.00635
        return t + when (config and CFG_HRES) {
            0x0100 -> 0.00385
            0x0200 -> 0.0025
            else -> 0.0065
        }
    }

    /**
     * Whether a triggered measurement is ready: an ACKed read header means ready (the bytes are
     * read and cached for the callback), a NACK means not ready.
     */
    @Synchronized
    fun pollInterrupt(): Boolean {
        if (pending != null) return true
        try {
            pending = connection.read(if ((config and CFG_MODE) != 0) 4 else 2)
        } catch (nack: IOException) {
            return false
        }
        return true
    }

    /**
     * Subscribe to the data-ready event using `intPin` (default `connection.intPin()`) or, without
     * a pin, a 5 ms polling thread.
     *
     * @param callback receives {temperature °C, humidity %RH}
     * @param intPin DRDYn pin to arm, or null to force the polling fallback
     */
    @JvmOverloads
    fun onInterrupt(callback: (DoubleArray) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "hdc1008-poll").also {
                it.isDaemon = true
                it.start()
            }
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

    private fun handleEdge() {
        val cb = callback ?: return
        if (!pollInterrupt()) return
        val d: ByteArray?
        synchronized(this) {
            d = pending
            pending = null
        }
        if (d == null) return
        val t = toTemperature(((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF))
        val h = if (d.size >= 4) toHumidity(((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)) else Double.NaN
        cb(doubleArrayOf(t, h))
    }
}
