package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.ln

/**
 * Si7021 — full driver extending [Si7021Minimal]: single values and raw codes, the four
 * resolution pairs, the on-chip heater, the raw user register, clock stretching (hold master),
 * software reset, electronic serial number, firmware revision, conversion time and the dew point.
 */
open class Si7021Full(connection: Connection) : Si7021Minimal(connection) {

    private val userReserved = 0x3A
    private val userVdds = 0x40
    private val userHtre = 0x04
    private val userResMask = 0x81

    /** {rhBits, temperatureBits} indexed by RES1:RES0. */
    private val resolutions = arrayOf(intArrayOf(12, 14), intArrayOf(8, 12), intArrayOf(10, 13), intArrayOf(11, 11))
    private val rhTime = doubleArrayOf(0.010, 0.0026, 0.0037, 0.0058)
    private val tTime = doubleArrayOf(0.007, 0.0024, 0.004, 0.0015)

    /** Run one RH measurement and return the raw 16-bit code (status bits included). */
    fun readRawHumidity(): Int = measure(CMD_RH_HOLD, CMD_RH_POLL)

    /** Run one temperature measurement and return the raw 16-bit code. */
    fun readRawTemperature(): Int = measure(CMD_T_HOLD, CMD_T_POLL)

    /** Run one RH measurement and return the relative humidity in %RH (0–100). */
    fun readHumidity(): Double = toHumidity(readRawHumidity())

    /** Run a fresh temperature measurement (0xF3, or 0xE3 with clock stretching) in °C. */
    fun readTemperature(): Double = toTemperature(readRawTemperature())

    /** Read the temperature of the last RH measurement (0xE0, no new conversion, no CRC) in °C. */
    fun readTemperaturePrevious(): Double = toTemperature(previousCode())

    /** Read USER_REG_1 (command 0xE7); 0x3A after reset. */
    fun readUserRegister(): Int = connection.writeRead(byteArrayOf(CMD_READ_USER.toByte()), 1)[0].toInt() and 0xFF

    /** Write USER_REG_1 (command 0xE6); the reserved bits and VDDS keep their current value. */
    fun writeUserRegister(value: Int) {
        val keep = userReserved or userVdds
        val merged = (readUserRegister() and keep) or (value and keep.inv() and 0xFF)
        connection.write(byteArrayOf(CMD_WRITE_USER.toByte(), merged.toByte()))
    }

    /**
     * Select the measurement resolution (read-modify-write of RES1:RES0).
     *
     * @param rhBits RH resolution in bits
     * @param temperatureBits temperature resolution in bits; allowed pairs are (12, 14), (8, 12), (10, 13), (11, 11)
     * @throws IllegalArgumentException if the pair is not one of the four allowed ones
     */
    fun setResolution(rhBits: Int, temperatureBits: Int) {
        for (res in 0 until 4) {
            if (resolutions[res][0] == rhBits && resolutions[res][1] == temperatureBits) {
                val bits = ((res shr 1) shl 7) or (res and 1)
                writeUserRegister((readUserRegister() and userResMask.inv() and 0xFF) or bits)
                return
            }
        }
        throw IllegalArgumentException("resolution must be (12, 14), (8, 12), (10, 13) or (11, 11)")
    }

    /** Return the configured resolution as {rhBits, temperatureBits}. */
    fun getResolution(): IntArray {
        val reg = readUserRegister()
        return resolutions[(((reg shr 7) and 1) shl 1) or (reg and 1)].clone()
    }

    /** Switch the on-chip heater (HTRE, about 3.1 mA) on or off. */
    fun setHeater(on: Boolean) {
        val reg = readUserRegister()
        writeUserRegister(if (on) reg or userHtre else reg and userHtre.inv() and 0xFF)
    }

    /** Whether the on-chip heater is enabled. */
    fun isHeaterOn(): Boolean = (readUserRegister() and userHtre) != 0

    /** Whether the supply voltage is low (VDDS set, VDD below about 1.9 V). */
    fun vddLow(): Boolean = (readUserRegister() and userVdds) != 0

    /** Use the hold-master commands (0xE5 / 0xE3) instead of polling; the bus layer must support clock stretching. */
    fun setClockStretching(on: Boolean) {
        stretch = on
    }

    /** Software reset (0xFE) and wait 15 ms; the user register returns to 0x3A. */
    fun softReset() {
        command(CMD_RESET)
        sleepMs(15)
    }

    /** Read the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0). */
    fun serialNumber(): Long {
        val a = connection.writeRead(byteArrayOf(0xFA.toByte(), 0x0F), 8)
        val b = connection.writeRead(byteArrayOf(0xFC.toByte(), 0xC9.toByte()), 6)
        val parts = intArrayOf(a[0].toInt(), a[2].toInt(), a[4].toInt(), a[6].toInt(),
            b[0].toInt(), b[1].toInt(), b[3].toInt(), b[4].toInt())
        var sn = 0L
        for (p in parts) sn = (sn shl 8) or (p and 0xFF).toLong()
        return sn
    }

    /** Read SNB_3, the device type byte (0x15 = Si7021, 0x14 = Si7020, 0x0D = Si7013). */
    fun deviceType(): Int = connection.writeRead(byteArrayOf(0xFC.toByte(), 0xC9.toByte()), 6)[0].toInt() and 0xFF

    /** Read the firmware revision (command 0x84 0xB8); 0xFF = firmware 1.0. */
    fun firmwareRevision(): Int = connection.writeRead(byteArrayOf(0x84.toByte(), 0xB8.toByte()), 1)[0].toInt() and 0xFF

    /** Typical RH + temperature conversion time at the current resolution, in seconds. */
    fun conversionTime(): Double {
        val reg = readUserRegister()
        val res = (((reg shr 7) and 1) shl 1) or (reg and 1)
        return rhTime[res] + tTime[res]
    }

    /** Run one measurement and compute the dew point in °C (Magnus formula). */
    fun dewPoint(): Double {
        val v = read()
        val t = v[0]
        val rh = if (v[1] <= 0.0) 0.01 else v[1]
        val gamma = ln(rh / 100.0) + (17.62 * t) / (243.12 + t)
        return 243.12 * gamma / (17.62 - gamma)
    }
}
