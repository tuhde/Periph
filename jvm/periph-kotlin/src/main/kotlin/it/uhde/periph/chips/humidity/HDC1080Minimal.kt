package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HDC1080 — low-power digital humidity and temperature sensor (Texas Instruments), minimal driver.
 *
 * [init] writes the configuration 0x1000 (14-bit T and RH, sequence mode, heater off). Each
 * [read] triggers one sequence measurement (pointer 0x00), polls the NACKed read header (timeout
 * 50 ms) and converts the temperature and humidity words. I²C address 0x40 (fixed).
 *
 * ## Configuration defaults
 * - Nothing is written at construction
 * - 14-bit temperature and humidity, sequence mode (temperature first), heater off
 *
 * @param connection plain I²C connection bound to 0x40
 */
open class HDC1080Minimal(protected val connection: Connection) {
    companion object {
        const val REG_TEMPERATURE = 0x00
        const val REG_HUMIDITY    = 0x01
        const val REG_CONFIG      = 0x02
        const val REG_SERIAL1     = 0xFB
        const val REG_MANUFACTURER_ID = 0xFE
        const val REG_DEVICE_ID   = 0xFF
        const val CFG_RST  = 0x8000
        const val CFG_HEAT = 0x2000
        const val CFG_MODE = 0x1000
        const val CFG_BTST = 0x0800
        const val CFG_TRES = 0x0400
        const val CFG_HRES = 0x0300
        const val CONFIG_DEFAULT = 0x1000
        const val POLL_ATTEMPTS = 50

        fun toTemperature(raw: Int): Double = raw / 65536.0 * 165.0 - 40.0

        fun toHumidity(raw: Int): Double = raw / 65536.0 * 100.0
    }

    protected var config = CONFIG_DEFAULT

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun writeConfig() =
        connection.write(byteArrayOf(REG_CONFIG.toByte(), (config shr 8).toByte(), config.toByte()))

    /** Write the default configuration 0x1000 (14-bit, sequence mode, heater off). */
    fun init() {
        config = CONFIG_DEFAULT
        writeConfig()
    }

    protected fun triggerRead(pointer: Int, n: Int): ByteArray {
        connection.write(byteArrayOf(pointer.toByte()))
        repeat(POLL_ATTEMPTS) {
            try {
                return connection.read(n)
            } catch (nack: IOException) {
                sleepMs(1)                          // read header NACKed: not ready yet
            }
        }
        throw IOException("HDC1080 measurement did not complete")
    }

    protected fun setMode(sequence: Boolean) {
        val want = if (sequence) config or CFG_MODE else config and CFG_MODE.inv()
        if (want != config) {
            config = want
            writeConfig()
        }
    }

    protected fun measurePair(): IntArray {
        setMode(true)
        val d = triggerRead(REG_TEMPERATURE, 4)
        return intArrayOf(
            ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF),
            ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF))
    }

    /**
     * Measure temperature and humidity (sequence mode, temperature first).
     *
     * @return {temperature in °C, relative humidity in %RH}
     * @throws IOException on bus error or timeout (50 ms)
     */
    fun read(): DoubleArray {
        val raw = measurePair()
        return doubleArrayOf(toTemperature(raw[0]), toHumidity(raw[1]))
    }
}
