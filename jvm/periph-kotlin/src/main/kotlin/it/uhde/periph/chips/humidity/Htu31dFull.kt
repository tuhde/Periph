package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.ln

/**
 * HTU31D — full driver extending [Htu31dMinimal]: single values and raw codes, separate
 * oversampling for humidity and temperature, non-blocking conversion, heater, diagnostic byte,
 * serial number, soft reset and the dew point.
 */
open class Htu31dFull(connection: Connection) : Htu31dMinimal(connection) {

    companion object {
        const val DIAG_NVM_ERR     = 0x80
        const val DIAG_RH_OVERRUN  = 0x40
        const val DIAG_RH_HIGH_ERR = 0x20
        const val DIAG_RH_LOW_ERR  = 0x10
        const val DIAG_T_OVERRUN   = 0x08
        const val DIAG_T_HIGH_ERR  = 0x04
        const val DIAG_T_LOW_ERR   = 0x02
        const val DIAG_HEATER_ON   = 0x01
    }

    /** Run one humidity measurement (read command 0x10) in %RH, clamped to 0–100. */
    fun readHumidity(): Double = toHumidity(measure(CMD_READ_RH, 1)[0])

    /** Run one temperature measurement (the read stops after the temperature word) in °C. */
    fun readTemperature(): Double = toTemperature(measure(CMD_READ_TRH, 1)[0])

    /** Measure and return the raw 16-bit codes {t_raw, rh_raw}. */
    fun readRaw(): IntArray = measure(CMD_READ_TRH, 2)

    /**
     * Select the oversampling of the following measurements.
     *
     * @param osrRh humidity oversampling 0–3
     * @param osrT temperature oversampling 0–3
     * @throws IllegalArgumentException if either value is not 0–3
     */
    fun setResolution(osrRh: Int, osrT: Int) {
        require(osrRh in 0..3 && osrT in 0..3) { "osr must be 0-3" }
        this.osrRh = osrRh
        this.osrT = osrT
    }

    /** Return the oversampling setting {osrRh, osrT}. */
    fun getResolution(): IntArray = intArrayOf(osrRh, osrT)

    /** Typical conversion time of one measurement at the current oversampling, in seconds. */
    fun conversionTime(): Double = T_RH[osrRh] + T_T[osrT]

    /** Write the conversion command only (non-blocking use). */
    fun startConversion() = start()

    /** Read the result of the last conversion (command 0x00): {temperature °C, humidity %RH}. */
    fun readResult(): DoubleArray {
        val w = readWords(CMD_READ_TRH, 2)
        return doubleArrayOf(toTemperature(w[0]), toHumidity(w[1]))
    }

    /** Switch the on-chip heater on (0x04) or off (0x02). */
    fun setHeater(on: Boolean) = command(if (on) CMD_HEATER_ON else CMD_HEATER_OFF)

    /** Read the diagnostic byte (command 0x08); test it against the `DIAG_*` masks. */
    fun diagnostic(): Int {
        command(CMD_READ_DIAG)
        return connection.read(1)[0].toInt() and 0xFF
    }

    /** Read the diagnostic byte and name its bits. */
    fun diagnosticFlags(): Map<String, Boolean> {
        val v = diagnostic()
        return linkedMapOf(
            "nvm_err" to ((v and DIAG_NVM_ERR) != 0),
            "rh_overrun" to ((v and DIAG_RH_OVERRUN) != 0),
            "rh_high_err" to ((v and DIAG_RH_HIGH_ERR) != 0),
            "rh_low_err" to ((v and DIAG_RH_LOW_ERR) != 0),
            "t_overrun" to ((v and DIAG_T_OVERRUN) != 0),
            "t_high_err" to ((v and DIAG_T_HIGH_ERR) != 0),
            "t_low_err" to ((v and DIAG_T_LOW_ERR) != 0),
            "heater_on" to ((v and DIAG_HEATER_ON) != 0),
        )
    }

    /** Whether the diagnostic byte reports the heater on. */
    fun isHeaterOn(): Boolean = (diagnostic() and DIAG_HEATER_ON) != 0

    /** Read the 24-bit serial number (command 0x0A, CRC verified). */
    fun serialNumber(): Int {
        command(CMD_READ_SERIAL)
        val b = connection.read(4)
        if (crc8(b, 0, 3) != (b[3].toInt() and 0xFF)) throw IOException("HTU31D CRC mismatch")
        return ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
    }

    /** Soft reset (0x1E) and wait 15 ms; heater off, oversampling back to OSR 3 / 3. */
    fun softReset() {
        command(CMD_RESET)
        sleepMs(15)
        osrRh = 3
        osrT = 3
    }

    /** Run one measurement and compute the dew point in °C (Magnus formula over water). */
    fun dewPoint(): Double {
        val m = read()
        val t = m[0]
        val rh = if (m[1] <= 0.0) 0.01 else m[1]
        val g = ln(rh / 100.0) + 17.62 * t / (243.12 + t)
        return 243.12 * g / (17.62 - g)
    }
}
