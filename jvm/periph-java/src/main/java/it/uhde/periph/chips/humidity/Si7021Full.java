package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * Si7021 full driver — resolution, heater, hold master, ID and dew point.
 *
 * <p>Extends {@link Si7021Minimal} with single values and raw codes, the four resolution pairs,
 * the on-chip heater, the raw user register, clock stretching (hold master), software reset,
 * electronic serial number, firmware revision, conversion time and the dew point.
 */
public class Si7021Full extends Si7021Minimal {

    private static final int USER_RESERVED = 0x3A;
    private static final int USER_VDDS     = 0x40;
    private static final int USER_HTRE     = 0x04;
    private static final int USER_RES_MASK = 0x81;

    /** {rhBits, temperatureBits} indexed by RES1:RES0. */
    private static final int[][] RESOLUTIONS = {{12, 14}, {8, 12}, {10, 13}, {11, 11}};
    /** Typical conversion times in seconds, indexed by RES1:RES0. */
    private static final double[] RH_TIME = {0.010, 0.0026, 0.0037, 0.0058};
    private static final double[] T_TIME = {0.007, 0.0024, 0.004, 0.0015};

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C connection bound to 0x40
     */
    public Si7021Full(Connection connection) {
        super(connection);
    }

    /**
     * Run one RH measurement and return the raw 16-bit code (status bits included).
     *
     * @return raw code, 0–65535
     * @throws IOException on bus error, timeout or a CRC mismatch
     */
    public int readRawHumidity() throws IOException {
        return measure(CMD_RH_HOLD, CMD_RH_POLL);
    }

    /**
     * Run one temperature measurement and return the raw 16-bit code.
     *
     * @return raw code, 0–65535
     * @throws IOException on bus error, timeout or a CRC mismatch
     */
    public int readRawTemperature() throws IOException {
        return measure(CMD_T_HOLD, CMD_T_POLL);
    }

    /**
     * Run one RH measurement.
     *
     * @return relative humidity in %RH, clamped to 0–100
     * @throws IOException on bus error, timeout or a CRC mismatch
     */
    public double readHumidity() throws IOException {
        return toHumidity(readRawHumidity());
    }

    /**
     * Run a fresh temperature measurement (0xF3, or 0xE3 with clock stretching).
     *
     * @return temperature in °C
     * @throws IOException on bus error, timeout or a CRC mismatch
     */
    public double readTemperature() throws IOException {
        return toTemperature(readRawTemperature());
    }

    /**
     * Read the temperature of the last RH measurement (0xE0, no new conversion, no CRC).
     *
     * @return temperature in °C
     * @throws IOException on bus error
     */
    public double readTemperaturePrevious() throws IOException {
        return toTemperature(previousCode());
    }

    /**
     * Read USER_REG_1 (command 0xE7).
     *
     * @return raw register value (0x3A after reset)
     * @throws IOException on bus error
     */
    public int readUserRegister() throws IOException {
        return connection.writeRead(new byte[]{(byte) CMD_READ_USER}, 1)[0] & 0xFF;
    }

    /**
     * Write USER_REG_1 (command 0xE6); the reserved bits and VDDS keep their current value.
     *
     * @param value new register value
     * @throws IOException on bus error
     */
    public void writeUserRegister(int value) throws IOException {
        int keep = USER_RESERVED | USER_VDDS;
        int merged = (readUserRegister() & keep) | (value & ~keep & 0xFF);
        connection.write(new byte[]{(byte) CMD_WRITE_USER, (byte) merged});
    }

    /**
     * Select the measurement resolution (read-modify-write of RES1:RES0).
     *
     * @param rhBits          RH resolution in bits
     * @param temperatureBits temperature resolution in bits; allowed pairs are (12, 14), (8, 12),
     *                        (10, 13) and (11, 11)
     * @throws IllegalArgumentException if the pair is not one of the four allowed ones
     * @throws IOException on bus error
     */
    public void setResolution(int rhBits, int temperatureBits) throws IOException {
        for (int res = 0; res < 4; res++) {
            if (RESOLUTIONS[res][0] == rhBits && RESOLUTIONS[res][1] == temperatureBits) {
                int bits = ((res >> 1) << 7) | (res & 1);
                writeUserRegister((readUserRegister() & ~USER_RES_MASK & 0xFF) | bits);
                return;
            }
        }
        throw new IllegalArgumentException("resolution must be (12, 14), (8, 12), (10, 13) or (11, 11)");
    }

    /**
     * Return the configured resolution.
     *
     * @return {rhBits, temperatureBits}
     * @throws IOException on bus error
     */
    public int[] getResolution() throws IOException {
        int reg = readUserRegister();
        return RESOLUTIONS[(((reg >> 7) & 1) << 1) | (reg & 1)].clone();
    }

    /**
     * Switch the on-chip heater (HTRE, about 3.1 mA) on or off.
     *
     * @param on true for on
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        int reg = readUserRegister();
        writeUserRegister(on ? (reg | USER_HTRE) : (reg & ~USER_HTRE & 0xFF));
    }

    /**
     * Whether the on-chip heater is enabled.
     *
     * @return true if on
     * @throws IOException on bus error
     */
    public boolean isHeaterOn() throws IOException {
        return (readUserRegister() & USER_HTRE) != 0;
    }

    /**
     * Whether the supply voltage is low (VDDS set, VDD below about 1.9 V).
     *
     * @return true if VDD is low
     * @throws IOException on bus error
     */
    public boolean vddLow() throws IOException {
        return (readUserRegister() & USER_VDDS) != 0;
    }

    /**
     * Use the hold-master commands (0xE5 / 0xE3) instead of polling; the bus layer must support
     * clock stretching.
     *
     * @param on true for hold master, false for the polling (NACK) variant
     */
    public void setClockStretching(boolean on) {
        this.stretch = on;
    }

    /**
     * Software reset (0xFE) and wait 15 ms; the user register returns to 0x3A.
     *
     * @throws IOException on bus error
     */
    public void softReset() throws IOException {
        command(CMD_RESET);
        sleepMs(15);
    }

    /**
     * Read the 64-bit electronic serial number (SNA_3..SNA_0, SNB_3..SNB_0).
     *
     * @return the serial number
     * @throws IOException on bus error
     */
    public long serialNumber() throws IOException {
        byte[] a = connection.writeRead(new byte[]{(byte) 0xFA, 0x0F}, 8);
        byte[] b = connection.writeRead(new byte[]{(byte) 0xFC, (byte) 0xC9}, 6);
        int[] parts = {a[0], a[2], a[4], a[6], b[0], b[1], b[3], b[4]};
        long sn = 0;
        for (int p : parts) sn = (sn << 8) | (p & 0xFF);
        return sn;
    }

    /**
     * Read SNB_3, the device type byte.
     *
     * @return 0x15 for the Si7021 (0x14 Si7020, 0x0D Si7013)
     * @throws IOException on bus error
     */
    public int deviceType() throws IOException {
        return connection.writeRead(new byte[]{(byte) 0xFC, (byte) 0xC9}, 6)[0] & 0xFF;
    }

    /**
     * Read the firmware revision (command 0x84 0xB8).
     *
     * @return 0xFF for firmware 1.0
     * @throws IOException on bus error
     */
    public int firmwareRevision() throws IOException {
        return connection.writeRead(new byte[]{(byte) 0x84, (byte) 0xB8}, 1)[0] & 0xFF;
    }

    /**
     * Typical RH + temperature conversion time at the current resolution.
     *
     * @return time in seconds
     * @throws IOException on bus error
     */
    public double conversionTime() throws IOException {
        int reg = readUserRegister();
        int res = (((reg >> 7) & 1) << 1) | (reg & 1);
        return RH_TIME[res] + T_TIME[res];
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula).
     *
     * @return dew point in °C
     * @throws IOException on bus error, timeout or a CRC mismatch
     */
    public double dewPoint() throws IOException {
        double[] v = read();
        double t = v[0];
        double rh = v[1] <= 0.0 ? 0.01 : v[1];
        double gamma = Math.log(rh / 100.0) + (17.62 * t) / (243.12 + t);
        return 243.12 * gamma / (17.62 - gamma);
    }
}
