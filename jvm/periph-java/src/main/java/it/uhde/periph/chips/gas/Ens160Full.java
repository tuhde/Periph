package it.uhde.periph.chips.gas;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * ENS160 full interface — extends Ens160Minimal with compensation, raw readings, and power control.
 *
 * <p>Adds external temperature/humidity compensation, individual gas readings,
 * raw sensor resistance, firmware version query, interrupt configuration,
 * and sleep/wake control.
 */
public class Ens160Full extends Ens160Minimal {

    /** Validity flag: OK. */
    public static final int VALIDITY_OK              = 0;
    /** Validity flag: Warm-up. */
    public static final int VALIDITY_WARMUP          = 1;
    /** Validity flag: Initial Start-up. */
    public static final int VALIDITY_INITIAL_STARTUP = 2;
    /** Validity flag: Invalid. */
    public static final int VALIDITY_INVALID         = 3;

    /**
     * Construct the full driver.
     *
     * @param transport I²C or SPI transport bound to the device.
     * @throws IOException on I²C error or wrong PART_ID.
     */
    public Ens160Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Write external temperature and humidity for compensation.
     *
     * @param tempCelsius Ambient temperature in degrees Celsius.
     * @param rhPercent   Ambient relative humidity in percent (0–100).
     * @throws IOException on I²C error.
     */
    public void setCompensation(double tempCelsius, double rhPercent) throws IOException {
        int tempRaw = (int) Math.round((tempCelsius + 273.15) * 64);
        int rhRaw = (int) Math.round(rhPercent * 512);
        writeRegLE16(REG_TEMP_IN, tempRaw);
        writeRegLE16(REG_RH_IN, rhRaw);
    }

    /**
     * Read TVOC concentration.
     *
     * @return TVOC in ppb.
     * @throws IOException on I²C error.
     */
    public double readTvoc() throws IOException {
        waitForNewData(5000);
        return readRegLE16(REG_DATA_TVOC);
    }

    /**
     * Read equivalent CO2 concentration.
     *
     * @return eCO2 in ppm.
     * @throws IOException on I²C error.
     */
    public double readEco2() throws IOException {
        waitForNewData(5000);
        return readRegLE16(REG_DATA_ECO2);
    }

    /**
     * Read Air Quality Index (UBA scale).
     *
     * @return AQI value 1–5 (1=Excellent, 5=Unhealthy).
     * @throws IOException on I²C error.
     */
    public int readAqi() throws IOException {
        waitForNewData(5000);
        byte[] data = readReg(REG_DATA_AQI, 1);
        return data[0] & 0x07;
    }

    /**
     * Read ethanol concentration estimate.
     *
     * @return Ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
     * @throws IOException on I²C error.
     */
    public double readEthanol() throws IOException {
        waitForNewData(5000);
        return readRegLE16(REG_DATA_TVOC);
    }

    /**
     * Read raw sensor resistance from GPR_READ registers.
     *
     * @param sensor Sensor number (1 or 4).
     * @return Resistance in Ohms.
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException if sensor is not 1 or 4.
     */
    public double readRawResistance(int sensor) throws IOException {
        int offset;
        if (sensor == 1) {
            offset = 0;
        } else if (sensor == 4) {
            offset = 6;
        } else {
            throw new IllegalArgumentException("sensor must be 1 or 4, got " + sensor);
        }
        int raw = readRegLE16(REG_GPR_READ + offset);
        return Math.pow(2.0, raw / 2048.0);
    }

    /**
     * Read the temperature and humidity values used by the sensor.
     *
     * @return array: [tempCelsius, rhPercent].
     * @throws IOException on I²C error.
     */
    public double[] readCompensationActuals() throws IOException {
        byte[] data = readReg(REG_DATA_T, 4);
        int tempRaw = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        int rhRaw = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);
        double tempCelsius = (tempRaw / 64.0) - 273.15;
        double rhPercent = rhRaw / 512.0;
        return new double[]{tempCelsius, rhPercent};
    }

    /**
     * Query firmware version (requires IDLE mode).
     *
     * <p>Switches to IDLE, issues GET_APPVER command, reads GPR_READ, then
     * returns to STANDARD mode.
     *
     * @return array: [major, minor, release].
     * @throws IOException on I²C error.
     */
    public int[] getFirmwareVersion() throws IOException {
        writeReg(REG_OPMODE, OPMODE_IDLE);
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        writeReg(REG_COMMAND, 0x0E);
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        byte[] data = readReg(REG_GPR_READ + 4, 3);
        int major = data[0] & 0xFF;
        int minor = data[1] & 0xFF;
        int release = data[2] & 0xFF;
        writeReg(REG_OPMODE, OPMODE_STANDARD);
        return new int[]{major, minor, release};
    }

    /**
     * Configure the INTn interrupt pin.
     *
     * @param enabled     Enable interrupt pin.
     * @param activeHigh  True for active-high polarity, false for active-low.
     * @param pushPull    True for push-pull drive, false for open-drain.
     * @param onData      Assert on new DATA_xxx data.
     * @param onGpr       Assert on new GPR_READ data.
     * @throws IOException on I²C error.
     */
    public void configureInterrupt(boolean enabled, boolean activeHigh, boolean pushPull, boolean onData, boolean onGpr) throws IOException {
        int config = 0;
        if (enabled) config |= 0x01;
        if (onData) config |= 0x02;
        if (onGpr) config |= 0x08;
        if (pushPull) config |= 0x20;
        if (activeHigh) config |= 0x40;
        writeReg(REG_CONFIG, config);
    }

    /**
     * Enter DEEP SLEEP mode for power saving.
     *
     * @throws IOException on I²C error.
     */
    public void sleep() throws IOException {
        writeReg(REG_OPMODE, OPMODE_DEEP_SLEEP);
    }

    /**
     * Wake from DEEP SLEEP and resume STANDARD gas sensing.
     *
     * @throws IOException on I²C error.
     */
    public void wake() throws IOException {
        writeReg(REG_OPMODE, OPMODE_IDLE);
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        writeReg(REG_OPMODE, OPMODE_STANDARD);
    }
}
