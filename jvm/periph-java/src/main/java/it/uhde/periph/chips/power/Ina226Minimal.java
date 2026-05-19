package it.uhde.periph.chips.power;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * INA226 — 16-bit current/power monitor with I²C interface (minimal driver).
 *
 * <p>Measures shunt voltage, bus voltage, current, and power. Calibration is
 * computed from the shunt resistance and maximum expected current; the result
 * is written to the Calibration register at construction time.
 *
 * <p>Default I²C address: 0x40 (A0=GND, A1=GND).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Mode: continuous shunt+bus (7)</li>
 *   <li>Bus voltage conversion time: 1.1 ms (4)</li>
 *   <li>Shunt voltage conversion time: 1.1 ms (4)</li>
 *   <li>Averaging: 1 sample (0)</li>
 * </ul>
 */
public class Ina226Minimal {

    // --- Register addresses ---
    protected static final int REG_CONFIG    = 0x00;
    protected static final int REG_SHUNT     = 0x01;
    protected static final int REG_BUS       = 0x02;
    protected static final int REG_POWER     = 0x03;
    protected static final int REG_CURRENT   = 0x04;
    protected static final int REG_CAL       = 0x05;
    protected static final int REG_MASK_EN   = 0x06;
    protected static final int REG_ALERT_LIM = 0x07;
    protected static final int REG_MFR_ID    = 0xFE;
    protected static final int REG_DIE_ID    = 0xFF;

    /** Default configuration: mode=7, VBUSCT=4, VSHCT=4, AVG=0 → 0x4127. */
    protected static final int DEFAULT_CONFIG = 0x4127;

    protected final Transport transport;
    protected final double currentLsb;
    protected final int    cal;
    /** Stored MODE bits (2:0) for wake(). Updated by configure() and shutdown(). */
    protected int lastMode = 7;

    /**
     * Construct the driver with default shunt (0.1 Ω) and max current (2.0 A).
     *
     * @param transport I²C transport bound to the INA226 device address
     * @throws IOException on I²C error
     */
    public Ina226Minimal(Transport transport) throws IOException {
        this(transport, 0.1, 2.0);
    }

    /**
     * Construct the driver.
     *
     * <p>Computes {@code Current_LSB = maxCurrent / 32768} and
     * {@code CAL = int(0.00512 / (Current_LSB × rShunt))}, then writes
     * the default configuration register and calibration register.
     *
     * @param transport  I²C transport bound to the INA226 device address
     * @param rShunt     shunt resistor value in Ω (e.g. 0.1)
     * @param maxCurrent maximum expected current in A (e.g. 2.0)
     * @throws IOException on I²C error
     */
    public Ina226Minimal(Transport transport, double rShunt, double maxCurrent) throws IOException {
        this.transport  = transport;
        this.currentLsb = maxCurrent / 32768.0;
        this.cal        = (int) (0.00512 / (currentLsb * rShunt));
        writeReg(REG_CONFIG, DEFAULT_CONFIG);
        writeReg(REG_CAL, cal);
    }

    /**
     * Read the bus voltage.
     *
     * @return bus voltage in V (1.25 mV LSB, unsigned 16-bit)
     * @throws IOException on I²C error
     */
    public double voltage() throws IOException {
        return readReg(REG_BUS) * 1.25e-3;
    }

    /**
     * Read the shunt voltage.
     *
     * @return shunt voltage in V (2.5 µV LSB, signed 16-bit)
     * @throws IOException on I²C error
     */
    public double shuntVoltage() throws IOException {
        return readRegSigned(REG_SHUNT) * 2.5e-6;
    }

    /**
     * Read the current through the shunt resistor.
     *
     * <p>Requires a valid Calibration register value (written in the constructor).
     *
     * @return current in A (signed, Current_LSB per bit)
     * @throws IOException on I²C error
     */
    public double current() throws IOException {
        return readRegSigned(REG_CURRENT) * currentLsb;
    }

    /**
     * Read the calculated power.
     *
     * <p>Power = 25 × Current_LSB × raw power register (unsigned).
     *
     * @return power in W
     * @throws IOException on I²C error
     */
    public double power() throws IOException {
        return readReg(REG_POWER) * 25.0 * currentLsb;
    }

    // ---- low-level helpers ----

    /**
     * Write a 16-bit value to a register (big-endian, register-pointer protocol).
     *
     * @param reg register address
     * @param val 16-bit value
     * @throws IOException on I²C error
     */
    protected void writeReg(int reg, int val) throws IOException {
        transport.write(new byte[]{
                (byte) reg,
                (byte) (val >> 8),
                (byte) (val & 0xFF)
        });
    }

    /**
     * Read an unsigned 16-bit value from a register.
     *
     * @param reg register address
     * @return unsigned 16-bit value (0–65535)
     * @throws IOException on I²C error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) reg}, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /**
     * Read a signed 16-bit value from a register.
     *
     * @param reg register address
     * @return signed 16-bit value (-32768–32767)
     * @throws IOException on I²C error
     */
    protected int readRegSigned(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) reg}, 2);
        return (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
    }
}
