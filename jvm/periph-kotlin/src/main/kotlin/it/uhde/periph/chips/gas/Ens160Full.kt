package it.uhde.periph.chips.gas

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * ENS160 full interface — extends Ens160Minimal with compensation, raw readings, and power control.
 *
 * Adds external temperature/humidity compensation, individual gas readings,
 * raw sensor resistance, firmware version query, interrupt configuration,
 * and sleep/wake control.
 */
class Ens160Full(transport: Transport) : Ens160Minimal(transport) {

    companion object {
        /** Validity flag: OK. */
        const val VALIDITY_OK              = 0
        /** Validity flag: Warm-up. */
        const val VALIDITY_WARMUP          = 1
        /** Validity flag: Initial Start-up. */
        const val VALIDITY_INITIAL_STARTUP = 2
        /** Validity flag: Invalid. */
        const val VALIDITY_INVALID         = 3
    }

    /**
     * Write external temperature and humidity for compensation.
     *
     * @param tempCelsius Ambient temperature in degrees Celsius.
     * @param rhPercent   Ambient relative humidity in percent (0–100).
     */
    fun setCompensation(tempCelsius: Double, rhPercent: Double) {
        val tempRaw = ((tempCelsius + 273.15) * 64).toInt()
        val rhRaw = (rhPercent * 512).toInt()
        writeRegLE16(REG_TEMP_IN, tempRaw)
        writeRegLE16(REG_RH_IN, rhRaw)
    }

    /**
     * Read TVOC concentration.
     *
     * @return TVOC in ppb.
     */
    fun readTvoc(): Double {
        waitForNewData(5000)
        return readRegLE16(REG_DATA_TVOC).toDouble()
    }

    /**
     * Read equivalent CO2 concentration.
     *
     * @return eCO2 in ppm.
     */
    fun readEco2(): Double {
        waitForNewData(5000)
        return readRegLE16(REG_DATA_ECO2).toDouble()
    }

    /**
     * Read Air Quality Index (UBA scale).
     *
     * @return AQI value 1–5 (1=Excellent, 5=Unhealthy).
     */
    fun readAqi(): Int {
        waitForNewData(5000)
        val data = readReg(REG_DATA_AQI, 1)
        return data[0].toInt() and 0x07
    }

    /**
     * Read ethanol concentration estimate.
     *
     * @return Ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
     */
    fun readEthanol(): Double {
        waitForNewData(5000)
        return readRegLE16(REG_DATA_TVOC).toDouble()
    }

    /**
     * Read raw sensor resistance from GPR_READ registers.
     *
     * @param sensor Sensor number (1 or 4).
     * @return Resistance in Ohms.
     */
    fun readRawResistance(sensor: Int): Double {
        val offset = when (sensor) {
            1 -> 0
            4 -> 6
            else -> throw IllegalArgumentException("sensor must be 1 or 4, got $sensor")
        }
        val raw = readRegLE16(REG_GPR_READ + offset)
        return Math.pow(2.0, raw / 2048.0)
    }

    /**
     * Read the temperature and humidity values used by the sensor.
     *
     * @return DoubleArray: [tempCelsius, rhPercent].
     */
    fun readCompensationActuals(): DoubleArray {
        val data = readReg(REG_DATA_T, 4)
        val tempRaw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val rhRaw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val tempCelsius = (tempRaw / 64.0) - 273.15
        val rhPercent = rhRaw / 512.0
        return doubleArrayOf(tempCelsius, rhPercent)
    }

    /**
     * Query firmware version (requires IDLE mode).
     *
     * Switches to IDLE, issues GET_APPVER command, reads GPR_READ, then
     * returns to STANDARD mode.
     *
     * @return IntArray: [major, minor, release].
     */
    fun getFirmwareVersion(): IntArray {
        writeReg(REG_OPMODE, OPMODE_IDLE)
        Thread.sleep(1)
        writeReg(REG_COMMAND, 0x0E)
        Thread.sleep(1)
        val data = readReg(REG_GPR_READ + 4, 3)
        val major = data[0].toInt() and 0xFF
        val minor = data[1].toInt() and 0xFF
        val release = data[2].toInt() and 0xFF
        writeReg(REG_OPMODE, OPMODE_STANDARD)
        return intArrayOf(major, minor, release)
    }

    /**
     * Configure the INTn interrupt pin.
     *
     * @param enabled     Enable interrupt pin.
     * @param activeHigh  True for active-high polarity, false for active-low.
     * @param pushPull    True for push-pull drive, false for open-drain.
     * @param onData      Assert on new DATA_xxx data.
     * @param onGpr       Assert on new GPR_READ data.
     */
    fun configureInterrupt(enabled: Boolean, activeHigh: Boolean, pushPull: Boolean, onData: Boolean, onGpr: Boolean) {
        var config = 0
        if (enabled) config = config or 0x01
        if (onData) config = config or 0x02
        if (onGpr) config = config or 0x08
        if (pushPull) config = config or 0x20
        if (activeHigh) config = config or 0x40
        writeReg(REG_CONFIG, config)
    }

    /**
     * Enter DEEP SLEEP mode for power saving.
     */
    fun sleep() {
        writeReg(REG_OPMODE, OPMODE_DEEP_SLEEP)
    }

    /**
     * Wake from DEEP SLEEP and resume STANDARD gas sensing.
     */
    fun wake() {
        writeReg(REG_OPMODE, OPMODE_IDLE)
        Thread.sleep(1)
        writeReg(REG_OPMODE, OPMODE_STANDARD)
    }
}
