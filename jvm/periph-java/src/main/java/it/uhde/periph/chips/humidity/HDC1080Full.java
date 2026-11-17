package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HDC1080 full driver — single values, resolution, heater, battery flag, serial ID and the
 * manufacturer/device ID registers. Extends {@link HDC1080Minimal}.
 */
public class HDC1080Full extends HDC1080Minimal {

    /**
     * Create the driver; does no bus access.
     *
     * @param connection plain I²C connection bound to 0x40
     */
    public HDC1080Full(Connection connection) {
        super(connection);
    }

    /**
     * Measure and return the raw 16-bit words.
     *
     * @return {raw temperature, raw humidity}, bits 1:0 zero
     * @throws IOException on bus error or timeout
     */
    public int[] readRaw() throws IOException {
        return measurePair();
    }

    /**
     * Measure temperature only (single mode).
     *
     * @return degrees Celsius
     * @throws IOException on bus error or timeout
     */
    public double readTemperature() throws IOException {
        setMode(false);
        byte[] d = triggerRead(REG_TEMPERATURE, 2);
        return toTemperature(((d[0] & 0xFF) << 8) | (d[1] & 0xFF));
    }

    /**
     * Measure humidity only (single mode).
     *
     * @return %RH
     * @throws IOException on bus error or timeout
     */
    public double readHumidity() throws IOException {
        setMode(false);
        byte[] d = triggerRead(REG_HUMIDITY, 2);
        return toHumidity(((d[0] & 0xFF) << 8) | (d[1] & 0xFF));
    }

    /**
     * Select the conversion resolutions.
     *
     * @param temperatureBits 14 or 11
     * @param humidityBits    14, 11 or 8
     * @throws IllegalArgumentException for other values
     * @throws IOException on bus error
     */
    public void setResolution(int temperatureBits, int humidityBits) throws IOException {
        if (temperatureBits != 14 && temperatureBits != 11) throw new IllegalArgumentException("temperatureBits must be 14 or 11");
        if (humidityBits != 14 && humidityBits != 11 && humidityBits != 8) throw new IllegalArgumentException("humidityBits must be 14, 11 or 8");
        int cfg = config & ~(CFG_TRES | CFG_HRES);
        if (temperatureBits == 11) cfg |= CFG_TRES;
        if (humidityBits == 11) cfg |= 0x0100;
        else if (humidityBits == 8) cfg |= 0x0200;
        config = cfg;
        writeConfig();
    }

    /**
     * Enable or disable the heater.
     *
     * @param on true to enable
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        config = on ? (config | CFG_HEAT) : (config & ~CFG_HEAT);
        writeConfig();
    }

    /**
     * Read the configuration register.
     *
     * @return the 16-bit configuration word
     * @throws IOException on bus error
     */
    public int readConfig() throws IOException {
        connection.write(new byte[]{(byte) REG_CONFIG});
        byte[] d = connection.read(2);
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
    }

    /**
     * Whether the heater bit is set in the configuration register.
     *
     * @return true if the heater is enabled
     * @throws IOException on bus error
     */
    public boolean isHeaterOn() throws IOException {
        return (readConfig() & CFG_HEAT) != 0;
    }

    /**
     * Whether VDD is below 2.8 V (BTST).
     *
     * @return true if the battery flag is set
     * @throws IOException on bus error
     */
    public boolean batteryLow() throws IOException {
        return (readConfig() & CFG_BTST) != 0;
    }

    /**
     * Set RST, wait 15 ms, then rewrite the stored configuration.
     *
     * @throws IOException on bus error
     */
    public void softwareReset() throws IOException {
        connection.write(new byte[]{(byte) REG_CONFIG, (byte) (CFG_RST >> 8), 0x00});
        sleepMs(15);
        writeConfig();
    }

    /**
     * Read the 40-bit serial number from registers 0xFB–0xFD.
     *
     * @return the serial ID
     * @throws IOException on bus error
     */
    public long serialId() throws IOException {
        long[] w = new long[3];
        for (int i = 0; i < 3; i++) {
            connection.write(new byte[]{(byte) (REG_SERIAL1 + i)});
            byte[] d = connection.read(2);
            w[i] = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
        }
        return (w[0] << 24) | (w[1] << 8) | (w[2] >> 7);
    }

    /**
     * Typical conversion time for the current resolutions (temperature + humidity).
     *
     * @return seconds
     */
    public double conversionTime() {
        double t = (config & CFG_TRES) != 0 ? 0.00365 : 0.00635;
        switch (config & CFG_HRES) {
            case 0x0100: return t + 0.00385;
            case 0x0200: return t + 0.0025;
            default: return t + 0.0065;
        }
    }

    private int readWord(int reg) throws IOException {
        connection.write(new byte[]{(byte) reg});
        byte[] d = connection.read(2);
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
    }

    /**
     * Read the manufacturer ID register 0xFE.
     *
     * @return 0x5449 for Texas Instruments
     * @throws IOException on bus error
     */
    public int manufacturerId() throws IOException {
        return readWord(REG_MANUFACTURER_ID);
    }

    /**
     * Read the device ID register 0xFF.
     *
     * @return 0x1050 for the HDC1080
     * @throws IOException on bus error
     */
    public int deviceId() throws IOException {
        return readWord(REG_DEVICE_ID);
    }
}
