package it.uhde.periph.chips.gas

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

import java.io.IOException

/**
 * ENS160 full interface — extends Ens160Minimal with compensation, raw readings, and power control.
 *
 * <p>Adds external temperature/humidity compensation, individual gas readings,
 * raw sensor resistance, firmware version query, interrupt configuration,
 * and sleep/wake control.
 */
@CompileStatic
class Ens160Full extends Ens160Minimal {

    /** Validity flag: OK. */
    static final int VALIDITY_OK              = 0
    /** Validity flag: Warm-up. */
    static final int VALIDITY_WARMUP          = 1
    /** Validity flag: Initial Start-up. */
    static final int VALIDITY_INITIAL_STARTUP = 2
    /** Validity flag: Invalid. */
    static final int VALIDITY_INVALID         = 3

    /**
     * Construct the full driver.
     *
     * @param transport I²C or SPI transport bound to the device.
     */
    Ens160Full(Transport transport) {
        super(transport)
    }

    /**
     * Write external temperature and humidity for compensation.
     *
     * @param tempCelsius Ambient temperature in degrees Celsius.
     * @param rhPercent   Ambient relative humidity in percent (0–100).
     */
    void setCompensation(double tempCelsius, double rhPercent) {
        int tempRaw = (int) Math.round((tempCelsius + 273.15) * 64)
        int rhRaw = (int) Math.round(rhPercent * 512)
        writeRegLE16(REG_TEMP_IN, tempRaw)
        writeRegLE16(REG_RH_IN, rhRaw)
    }

    /**
     * Read TVOC concentration.
     *
     * @return TVOC in ppb.
     */
    double readTvoc() {
        waitForNewData(5000)
        return readRegLE16(REG_DATA_TVOC) as double
    }

    /**
     * Read equivalent CO2 concentration.
     *
     * @return eCO2 in ppm.
     */
    double readEco2() {
        waitForNewData(5000)
        return readRegLE16(REG_DATA_ECO2) as double
    }

    /**
     * Read Air Quality Index (UBA scale).
     *
     * @return AQI value 1–5 (1=Excellent, 5=Unhealthy).
     */
    int readAqi() {
        waitForNewData(5000)
        byte[] data = readReg(REG_DATA_AQI, 1)
        return data[0] & 0x07
    }

    /**
     * Read ethanol concentration estimate.
     *
     * @return Ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
     */
    double readEthanol() {
        waitForNewData(5000)
        return readRegLE16(REG_DATA_TVOC) as double
    }

    /**
     * Read raw sensor resistance from GPR_READ registers.
     *
     * @param sensor Sensor number (1 or 4).
     * @return Resistance in Ohms.
     */
    double readRawResistance(int sensor) {
        int offset
        if (sensor == 1) {
            offset = 0
        } else if (sensor == 4) {
            offset = 6
        } else {
            throw new IllegalArgumentException("sensor must be 1 or 4, got " + sensor)
        }
        int raw = readRegLE16(REG_GPR_READ + offset)
        return Math.pow(2.0, raw / 2048.0)
    }

    /**
     * Read the temperature and humidity values used by the sensor.
     *
     * @return array: [tempCelsius, rhPercent].
     */
    double[] readCompensationActuals() {
        byte[] data = readReg(REG_DATA_T, 4)
        int tempRaw = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8)
        int rhRaw = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8)
        double tempCelsius = (tempRaw / 64.0) - 273.15
        double rhPercent = rhRaw / 512.0
        return [tempCelsius, rhPercent] as double[]
    }

    /**
     * Query firmware version (requires IDLE mode).
     *
     * <p>Switches to IDLE, issues GET_APPVER command, reads GPR_READ, then
     * returns to STANDARD mode.
     *
     * @return array: [major, minor, release].
     */
    int[] getFirmwareVersion() {
        writeReg(REG_OPMODE, OPMODE_IDLE)
        Thread.sleep(1)
        writeReg(REG_COMMAND, 0x0E)
        Thread.sleep(1)
        byte[] data = readReg(REG_GPR_READ + 4, 3)
        int major = data[0] & 0xFF
        int minor = data[1] & 0xFF
        int release = data[2] & 0xFF
        writeReg(REG_OPMODE, OPMODE_STANDARD)
        return [major, minor, release] as int[]
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
    void configureInterrupt(boolean enabled, boolean activeHigh, boolean pushPull, boolean onData, boolean onGpr) {
        int config = 0
        if (enabled) config |= 0x01
        if (onData) config |= 0x02
        if (onGpr) config |= 0x08
        if (pushPull) config |= 0x20
        if (activeHigh) config |= 0x40
        writeReg(REG_CONFIG, config)
    }

    /**
     * Enter DEEP SLEEP mode for power saving.
     */
    void sleep() {
        writeReg(REG_OPMODE, OPMODE_DEEP_SLEEP)
    }

    /**
     * Wake from DEEP SLEEP and resume STANDARD gas sensing.
     */
    void wake() {
        writeReg(REG_OPMODE, OPMODE_IDLE)
        Thread.sleep(1)
        writeReg(REG_OPMODE, OPMODE_STANDARD)
    }
}
