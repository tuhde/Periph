package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * HTU21D-F full driver — resolution, heater, hold master, compensation and dew point.
 *
 * <p>Extends {@link HTU21DFMinimal} with single values and raw codes, the four resolution
 * settings, the on-chip heater, the end-of-battery flag, the raw user register, soft reset,
 * hold-master mode, temperature-compensated humidity and the dew point.
 */
public class HTU21DFFull extends HTU21DFMinimal {

    private static final int USER_HEATER   = 0x04;
    private static final int USER_VDD_LOW  = 0x40;
    private static final int USER_RES_MASK = 0x81;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x40
     */
    public HTU21DFFull(Connection connection) {
        super(connection);
    }

    /**
     * Run one temperature measurement.
     *
     * @return temperature in °C
     * @throws IOException on bus error, timeout or CRC mismatch
     */
    public double readTemperature() throws IOException {
        return toTemperature(measure(CMD_T_HOLD, CMD_T_POLL));
    }

    /**
     * Run one humidity measurement.
     *
     * @return relative humidity in %RH (not clamped)
     * @throws IOException on bus error, timeout or CRC mismatch
     */
    public double readHumidity() throws IOException {
        return toHumidity(measure(CMD_RH_HOLD, CMD_RH_POLL));
    }

    /**
     * Measure and correct the humidity for temperature (−0.15 %RH/°C relative to 25 °C).
     *
     * @return compensated relative humidity in %RH
     * @throws IOException on bus error, timeout or CRC mismatch
     */
    public double readHumidityCompensated() throws IOException {
        double[] v = read();
        return v[1] + (25.0 - v[0]) * (-0.15);
    }

    /**
     * Measure and return the raw 16-bit codes (status bits cleared).
     *
     * @return {temperature code, humidity code}
     * @throws IOException on bus error, timeout or CRC mismatch
     */
    public int[] readRaw() throws IOException {
        int t = measure(CMD_T_HOLD, CMD_T_POLL);
        int rh = measure(CMD_RH_HOLD, CMD_RH_POLL);
        return new int[]{t, rh};
    }

    /**
     * Read the user register (command 0xE7).
     *
     * @return raw register value, 0x02 after reset
     * @throws IOException on bus error
     */
    public int readUserRegister() throws IOException {
        return connection.writeRead(new byte[]{(byte) CMD_READ_USER}, 1)[0] & 0xFF;
    }

    private void writeUser(int value) throws IOException {
        connection.write(new byte[]{(byte) CMD_WRITE_USER, (byte) value});
    }

    /**
     * Select the resolution (read-modify-write of the RES bits 7 and 0).
     *
     * @param res 0 = 12-bit RH / 14-bit T, 1 = 8 / 12, 2 = 10 / 13, 3 = 11 / 11
     * @throws IllegalArgumentException if res is not 0–3
     * @throws IOException on bus error
     */
    public void setResolution(int res) throws IOException {
        if (res < 0 || res > 3) throw new IllegalArgumentException("res must be 0-3");
        int bits = ((res >> 1) << 7) | (res & 1);
        writeUser((readUserRegister() & ~USER_RES_MASK & 0xFF) | bits);
    }

    /**
     * Read the resolution setting.
     *
     * @return 0 = 12/14 bit, 1 = 8/12, 2 = 10/13, 3 = 11/11
     * @throws IOException on bus error
     */
    public int getResolution() throws IOException {
        int reg = readUserRegister();
        return (((reg >> 7) & 1) << 1) | (reg & 1);
    }

    /**
     * Switch the on-chip heater on or off (read-modify-write).
     *
     * @param on true for on
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        int reg = readUserRegister();
        writeUser(on ? (reg | USER_HEATER) : (reg & ~USER_HEATER & 0xFF));
    }

    /**
     * Whether the on-chip heater is enabled.
     *
     * @return true if enabled
     * @throws IOException on bus error
     */
    public boolean heater() throws IOException {
        return (readUserRegister() & USER_HEATER) != 0;
    }

    /**
     * End-of-battery flag (VDD below about 2.25 V).
     *
     * @return true if set
     * @throws IOException on bus error
     */
    public boolean batteryLow() throws IOException {
        return (readUserRegister() & USER_VDD_LOW) != 0;
    }

    /**
     * Soft reset (0xFE) and wait 15 ms; the heater bit survives.
     *
     * @throws IOException on bus error
     */
    public void softReset() throws IOException {
        command(CMD_RESET);
        sleepMs(15);
    }

    /**
     * Use the hold-master commands (0xE3 / 0xE5) instead of polling; the bus layer must support
     * clock stretching.
     *
     * @param on true for hold master
     */
    public void setHoldMaster(boolean on) {
        this.hold = on;
    }

    /**
     * Run one measurement and compute the dew point.
     *
     * @return dew point in °C
     * @throws IOException on bus error, timeout or CRC mismatch
     */
    public double dewPoint() throws IOException {
        double[] v = read();
        double t = v[0];
        double rh = v[1] <= 0.0 ? 0.01 : v[1];
        double pp = Math.pow(10.0, 8.1332 - 1762.39 / (t + 235.66));
        return -1762.39 / (Math.log10(rh * pp / 100.0) - 8.1332) - 235.66;
    }
}
