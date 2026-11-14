package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.log10
import kotlin.math.pow

/**
 * HTU21D-F — full driver extending [HTU21DFMinimal]: single values and raw codes, the four
 * resolution settings, the on-chip heater, the end-of-battery flag, the raw user register, soft
 * reset, hold-master mode, temperature-compensated humidity and the dew point.
 */
open class HTU21DFFull(connection: Connection) : HTU21DFMinimal(connection) {

    private val userHeater = 0x04
    private val userVddLow = 0x40
    private val userResMask = 0x81

    /** Run one temperature measurement in °C. */
    fun readTemperature(): Double = toTemperature(measure(CMD_T_HOLD, CMD_T_POLL))

    /** Run one humidity measurement in %RH (not clamped). */
    fun readHumidity(): Double = toHumidity(measure(CMD_RH_HOLD, CMD_RH_POLL))

    /** Measure and correct the humidity for temperature (−0.15 %RH/°C relative to 25 °C). */
    fun readHumidityCompensated(): Double {
        val v = read()
        return v[1] + (25.0 - v[0]) * (-0.15)
    }

    /** Measure and return the raw 16-bit codes {temperature, humidity} with the status bits cleared. */
    fun readRaw(): IntArray {
        val t = measure(CMD_T_HOLD, CMD_T_POLL)
        val rh = measure(CMD_RH_HOLD, CMD_RH_POLL)
        return intArrayOf(t, rh)
    }

    /** Read the user register (command 0xE7); 0x02 after reset. */
    fun readUserRegister(): Int = connection.writeRead(byteArrayOf(CMD_READ_USER.toByte()), 1)[0].toInt() and 0xFF

    private fun writeUser(value: Int) = connection.write(byteArrayOf(CMD_WRITE_USER.toByte(), value.toByte()))

    /**
     * Select the resolution (read-modify-write of the RES bits 7 and 0).
     *
     * @param res 0 = 12-bit RH / 14-bit T, 1 = 8 / 12, 2 = 10 / 13, 3 = 11 / 11
     * @throws IllegalArgumentException if res is not 0–3
     */
    fun setResolution(res: Int) {
        require(res in 0..3) { "res must be 0-3" }
        val bits = ((res shr 1) shl 7) or (res and 1)
        writeUser((readUserRegister() and userResMask.inv() and 0xFF) or bits)
    }

    /** Read the resolution setting: 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11. */
    fun getResolution(): Int {
        val reg = readUserRegister()
        return (((reg shr 7) and 1) shl 1) or (reg and 1)
    }

    /** Switch the on-chip heater on or off (read-modify-write). */
    fun setHeater(on: Boolean) {
        val reg = readUserRegister()
        writeUser(if (on) reg or userHeater else reg and userHeater.inv() and 0xFF)
    }

    /** Whether the on-chip heater is enabled. */
    fun heater(): Boolean = (readUserRegister() and userHeater) != 0

    /** End-of-battery flag (VDD below about 2.25 V). */
    fun batteryLow(): Boolean = (readUserRegister() and userVddLow) != 0

    /** Soft reset (0xFE) and wait 15 ms; the heater bit survives. */
    fun softReset() {
        command(CMD_RESET)
        sleepMs(15)
    }

    /** Use the hold-master commands (0xE3 / 0xE5) instead of polling; the bus layer must support clock stretching. */
    fun setHoldMaster(on: Boolean) {
        hold = on
    }

    /** Run one measurement and compute the dew point in °C. */
    fun dewPoint(): Double {
        val v = read()
        val t = v[0]
        val rh = if (v[1] <= 0.0) 0.01 else v[1]
        val pp = 10.0.pow(8.1332 - 1762.39 / (t + 235.66))
        return -1762.39 / (log10(rh * pp / 100.0) - 8.1332) - 235.66
    }
}
