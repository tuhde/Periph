package it.uhde.periph.chips.power;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * INA260 — 36 V current/voltage/power monitor with integrated 2 mΩ shunt (minimal driver).
 *
 * <p>The shunt is integrated and the Current, Bus Voltage and Power registers have
 * fixed LSBs (1.25 mA, 1.25 mV, 10 mW), so no calibration is needed and the
 * constructor makes no bus access.
 *
 * <p>Default I²C address: 0x40 (A0=GND, A1=GND); range 0x40–0x4F.
 *
 * <h2>Configuration (chip power-on state, not rewritten)</h2>
 * <ul>
 *   <li>Mode: continuous shunt+bus (7)</li>
 *   <li>Bus voltage conversion time: 1.1 ms (4)</li>
 *   <li>Shunt current conversion time: 1.1 ms (4)</li>
 *   <li>Averaging: 1 sample (0)</li>
 * </ul>
 */
public class Ina260Minimal {

    // --- Register addresses ---
    protected static final int REG_CONFIG    = 0x00;
    protected static final int REG_CURRENT   = 0x01;
    protected static final int REG_BUS       = 0x02;
    protected static final int REG_POWER     = 0x03;
    protected static final int REG_MASK_EN   = 0x06;
    protected static final int REG_ALERT_LIM = 0x07;
    protected static final int REG_MFR_ID    = 0xFE;
    protected static final int REG_DIE_ID    = 0xFF;

    protected final RegisterConnection connection;

    /**
     * Construct the driver. Makes no bus access.
     *
     * @param connection I²C connection bound to the INA260 device address
     * @throws IOException on I²C error
     */
    public Ina260Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
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
     * Read the current through the integrated shunt.
     *
     * @return current in A (1.25 mA LSB, signed; positive flows IN+ to IN−)
     * @throws IOException on I²C error
     */
    public double current() throws IOException {
        return readRegSigned(REG_CURRENT) * 1.25e-3;
    }

    /**
     * Read the power.
     *
     * @return power in W (10 mW LSB, unsigned)
     * @throws IOException on I²C error
     */
    public double power() throws IOException {
        return readReg(REG_POWER) * 10e-3;
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
        connection.write(reg, new byte[]{(byte) (val >> 8), (byte) (val & 0xFF)});
    }

    /**
     * Read an unsigned 16-bit value from a register.
     *
     * @param reg register address
     * @return unsigned 16-bit value (0–65535)
     * @throws IOException on I²C error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.read(reg, 2);
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
        byte[] b = connection.read(reg, 2);
        return (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
    }
}
