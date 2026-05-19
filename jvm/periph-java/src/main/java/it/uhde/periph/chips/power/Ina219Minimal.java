package it.uhde.periph.chips.power;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * INA219 — zero-drift, bidirectional current/power monitor with I²C interface
 * (minimal driver).
 *
 * <p>Measures bus voltage (0–32 V), shunt voltage, current, and power via a
 * sense resistor on the high or low side of the load. The calibration register
 * is computed and written once during construction; the configuration register
 * is left at its hardware reset value (0x399F: ±32 V bus, PGA /8, 12-bit,
 * continuous shunt+bus mode).
 *
 * <p>Default I²C address: 0x40 (A1=GND, A0=GND).
 */
public class Ina219Minimal {

    // Register map
    protected static final int REG_CONFIG    = 0x00;
    protected static final int REG_SHUNT_V   = 0x01;
    protected static final int REG_BUS_V     = 0x02;
    protected static final int REG_POWER     = 0x03;
    protected static final int REG_CURRENT   = 0x04;
    protected static final int REG_CALIBRATE = 0x05;

    protected final Transport transport;

    /** Shunt resistance in Ω — retained for calibration register recalculation. */
    protected final double rShunt;

    /** Current register LSB in A/LSB. */
    protected final double currentLsb;

    /** Power register LSB in W/LSB (= 20 × currentLsb). */
    protected final double powerLsb;

    /**
     * Construct the driver and program the calibration register.
     *
     * <p>Computes {@code Current_LSB = maxCurrent / 32768} and
     * {@code CAL = floor(0.04096 / (Current_LSB × rShunt)) & 0xFFFE}, then
     * writes CAL to register 0x05. The configuration register is not touched.
     *
     * @param transport  I²C transport bound to the INA219 device address
     * @param rShunt     shunt resistance in Ω (e.g. 0.1)
     * @param maxCurrent maximum expected current in A (e.g. 2.0)
     * @throws IOException on I²C error
     */
    public Ina219Minimal(Transport transport, double rShunt, double maxCurrent) throws IOException {
        this.transport  = transport;
        this.rShunt     = rShunt;
        this.currentLsb = maxCurrent / 32768.0;
        this.powerLsb   = 20.0 * currentLsb;
        int cal = (int) (0.04096 / (currentLsb * rShunt)) & 0xFFFE;
        writeReg(REG_CALIBRATE, cal);
    }

    /**
     * Construct the driver with default parameters (0.1 Ω shunt, 2.0 A max).
     *
     * @param transport I²C transport bound to the INA219 device address
     * @throws IOException on I²C error
     */
    public Ina219Minimal(Transport transport) throws IOException {
        this(transport, 0.1, 2.0);
    }

    /**
     * Read the bus voltage.
     *
     * <p>Reads the Bus Voltage register (0x02), right-shifts by 3, and
     * multiplies by the 4 mV LSB. Range: 0–32 V.
     *
     * @return bus voltage in V
     * @throws IOException on I²C error
     */
    public double voltage() throws IOException {
        int raw = readReg(REG_BUS_V);
        return ((raw >> 3) & 0x1FFF) * 4e-3;
    }

    /**
     * Read the shunt (differential) voltage.
     *
     * <p>Reads the Shunt Voltage register (0x01) as a signed 16-bit value and
     * multiplies by the 10 µV LSB.
     *
     * @return shunt voltage in V (negative for reverse current flow)
     * @throws IOException on I²C error
     */
    public double shuntVoltage() throws IOException {
        int raw = readReg(REG_SHUNT_V);
        return (short) raw * 10e-6;
    }

    /**
     * Read the current through the shunt resistor.
     *
     * <p>Reads the Current register (0x04) as a signed 16-bit value and
     * multiplies by the current LSB computed during construction.
     *
     * @return current in A (negative for reverse flow)
     * @throws IOException on I²C error
     */
    public double current() throws IOException {
        int raw = readReg(REG_CURRENT);
        return (short) raw * currentLsb;
    }

    /**
     * Read the calculated power.
     *
     * <p>Reads the Power register (0x03) as an unsigned 16-bit value and
     * multiplies by the power LSB (= 20 × current LSB).
     *
     * @return power in W (always non-negative)
     * @throws IOException on I²C error
     */
    public double power() throws IOException {
        int raw = readReg(REG_POWER);
        return (raw & 0xFFFF) * powerLsb;
    }

    // -------------------------------------------------------------------------
    // Protected register I/O helpers (shared with Ina219Full)
    // -------------------------------------------------------------------------

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address (0x00–0x05)
     * @return raw unsigned 16-bit value
     * @throws IOException on I²C error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) reg}, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg register address (0x00–0x05)
     * @param val 16-bit value to write
     * @throws IOException on I²C error
     */
    protected void writeReg(int reg, int val) throws IOException {
        transport.write(new byte[]{
                (byte) reg,
                (byte) ((val >> 8) & 0xFF),
                (byte) (val & 0xFF)
        });
    }
}
