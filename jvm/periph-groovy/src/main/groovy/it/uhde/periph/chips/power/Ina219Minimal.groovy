package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

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
@CompileStatic
class Ina219Minimal {

    protected static final int REG_CONFIG    = 0x00
    protected static final int REG_SHUNT_V   = 0x01
    protected static final int REG_BUS_V     = 0x02
    protected static final int REG_POWER     = 0x03
    protected static final int REG_CURRENT   = 0x04
    protected static final int REG_CALIBRATE = 0x05

    protected final Transport transport
    /** Shunt resistance in Ω — retained for calibration register recalculation. */
    protected final double rShunt
    /** Current register LSB in A/LSB. */
    protected final double currentLsb
    /** Power register LSB in W/LSB (= 20 × currentLsb). */
    protected final double powerLsb

    /**
     * Construct the driver and program the calibration register.
     *
     * @param transport  I²C transport bound to the INA219 device address
     * @param rShunt     shunt resistance in Ω (default 0.1)
     * @param maxCurrent maximum expected current in A (default 2.0)
     */
    Ina219Minimal(Transport transport, double rShunt = 0.1, double maxCurrent = 2.0) {
        this.transport  = transport
        this.rShunt     = rShunt
        this.currentLsb = maxCurrent / 32768.0
        this.powerLsb   = 20.0 * currentLsb
        int cal = (int) (0.04096 / (currentLsb * rShunt)) & 0xFFFE
        writeReg(REG_CALIBRATE, cal)
    }

    /**
     * Read the bus voltage.
     *
     * <p>Reads the Bus Voltage register (0x02), right-shifts by 3, and
     * multiplies by the 4 mV LSB. Range: 0–32 V.
     *
     * @return bus voltage in V
     */
    double voltage() {
        int raw = readReg(REG_BUS_V)
        ((raw >> 3) & 0x1FFF) * 4e-3
    }

    /**
     * Read the shunt (differential) voltage.
     *
     * <p>Reads the Shunt Voltage register (0x01) as a signed 16-bit value and
     * multiplies by the 10 µV LSB.
     *
     * @return shunt voltage in V (negative for reverse current flow)
     */
    double shuntVoltage() {
        int raw = readReg(REG_SHUNT_V)
        int signed = raw > 32767 ? raw - 65536 : raw
        signed * 10e-6
    }

    /**
     * Read the current through the shunt resistor.
     *
     * <p>Reads the Current register (0x04) as a signed 16-bit value and
     * multiplies by the current LSB computed during construction.
     *
     * @return current in A (negative for reverse flow)
     */
    double current() {
        int raw = readReg(REG_CURRENT)
        int signed = raw > 32767 ? raw - 65536 : raw
        signed * currentLsb
    }

    /**
     * Read the calculated power.
     *
     * <p>Reads the Power register (0x03) as an unsigned 16-bit value and
     * multiplies by the power LSB (= 20 × current LSB).
     *
     * @return power in W (always non-negative)
     */
    double power() {
        int raw = readReg(REG_POWER)
        (raw & 0xFFFF) * powerLsb
    }

    // -------------------------------------------------------------------------
    // Protected register I/O helpers (shared with Ina219Full)
    // -------------------------------------------------------------------------

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address (0x00–0x05)
     * @return raw unsigned 16-bit value
     */
    protected int readReg(int reg) {
        byte[] b = transport.writeRead([(byte) reg] as byte[], 2)
        ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg register address (0x00–0x05)
     * @param val 16-bit value to write
     */
    protected void writeReg(int reg, int val) {
        transport.write([(byte) reg, (byte) ((val >> 8) & 0xFF), (byte) (val & 0xFF)] as byte[])
    }
}
