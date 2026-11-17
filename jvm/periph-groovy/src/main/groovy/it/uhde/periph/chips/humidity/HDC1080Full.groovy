package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HDC1080 full driver — single values, resolution, heater, battery flag, serial ID and the
 * manufacturer/device ID registers. Extends {@link HDC1080Minimal}.
 */
@CompileStatic
class HDC1080Full extends HDC1080Minimal {

    /**
     * Create the driver; does no bus access.
     *
     * @param connection plain I²C connection bound to 0x40
     */
    HDC1080Full(Connection connection) {
        super(connection)
    }

    /**
     * Measure and return the raw 16-bit words.
     *
     * @return {raw temperature, raw humidity}, bits 1:0 zero
     * @throws IOException on bus error or timeout
     */
    int[] readRaw() {
        return measurePair()
    }

    /**
     * Measure temperature only (single mode).
     *
     * @return degrees Celsius
     * @throws IOException on bus error or timeout
     */
    double readTemperature() {
        setMode(false)
        byte[] d = triggerRead(REG_TEMPERATURE, 2)
        return toTemperature(((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
    }

    /**
     * Measure humidity only (single mode).
     *
     * @return %RH
     * @throws IOException on bus error or timeout
     */
    double readHumidity() {
        setMode(false)
        byte[] d = triggerRead(REG_HUMIDITY, 2)
        return toHumidity(((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
    }

    /**
     * Select the conversion resolutions.
     *
     * @param temperatureBits 14 or 11
     * @param humidityBits    14, 11 or 8
     * @throws IllegalArgumentException for other values
     * @throws IOException on bus error
     */
    void setResolution(int temperatureBits, int humidityBits) {
        if (temperatureBits != 14 && temperatureBits != 11) throw new IllegalArgumentException('temperatureBits must be 14 or 11')
        if (humidityBits != 14 && humidityBits != 11 && humidityBits != 8) throw new IllegalArgumentException('humidityBits must be 14, 11 or 8')
        int c = cfg & ~(CFG_TRES | CFG_HRES)
        if (temperatureBits == 11) c |= CFG_TRES
        if (humidityBits == 11) c |= 0x0100
        else if (humidityBits == 8) c |= 0x0200
        cfg = c
        writeConfig()
    }

    /**
     * Enable or disable the heater.
     *
     * @param on true to enable
     * @throws IOException on bus error
     */
    void setHeater(boolean on) {
        cfg = on ? (cfg | CFG_HEAT) : (cfg & ~CFG_HEAT)
        writeConfig()
    }

    /**
     * Read the configuration register.
     *
     * @return the 16-bit configuration word
     * @throws IOException on bus error
     */
    int readConfig() {
        connection.write([(byte) REG_CONFIG] as byte[])
        byte[] d = connection.read(2)
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF)
    }

    /**
     * Whether the heater bit is set in the configuration register.
     *
     * @return true if the heater is enabled
     * @throws IOException on bus error
     */
    boolean isHeaterOn() {
        return (readConfig() & CFG_HEAT) != 0
    }

    /**
     * Whether VDD is below 2.8 V (BTST).
     *
     * @return true if the battery flag is set
     * @throws IOException on bus error
     */
    boolean batteryLow() {
        return (readConfig() & CFG_BTST) != 0
    }

    /**
     * Set RST, wait 15 ms, then rewrite the stored configuration.
     *
     * @throws IOException on bus error
     */
    void softwareReset() {
        connection.write([(byte) REG_CONFIG, (byte) (CFG_RST >> 8), (byte) 0x00] as byte[])
        sleepMs(15)
        writeConfig()
    }

    /**
     * Read the 40-bit serial number from registers 0xFB–0xFD.
     *
     * @return the serial ID
     * @throws IOException on bus error
     */
    long serialId() {
        long[] w = new long[3]
        for (int i = 0; i < 3; i++) {
            connection.write([(byte) (REG_SERIAL1 + i)] as byte[])
            byte[] d = connection.read(2)
            w[i] = (long) (((d[0] & 0xFF) << 8) | (d[1] & 0xFF))
        }
        return (w[0] << 24) | (w[1] << 8) | (w[2] >> 7)
    }

    /**
     * Typical conversion time for the current resolutions (temperature + humidity).
     *
     * @return seconds
     */
    double conversionTime() {
        double t = (cfg & CFG_TRES) != 0 ? 0.00365d : 0.00635d
        int h = cfg & CFG_HRES
        return t + (h == 0x0100 ? 0.00385d : (h == 0x0200 ? 0.0025d : 0.0065d))
    }

    private int readWord(int reg) {
        connection.write([(byte) reg] as byte[])
        byte[] d = connection.read(2)
        return ((d[0] & 0xFF) << 8) | (d[1] & 0xFF)
    }

    /**
     * Read the manufacturer ID register 0xFE.
     *
     * @return 0x5449 for Texas Instruments
     * @throws IOException on bus error
     */
    int manufacturerId() {
        return readWord(REG_MANUFACTURER_ID)
    }

    /**
     * Read the device ID register 0xFF.
     *
     * @return 0x1050 for the HDC1080
     * @throws IOException on bus error
     */
    int deviceId() {
        return readWord(REG_DEVICE_ID)
    }
}
