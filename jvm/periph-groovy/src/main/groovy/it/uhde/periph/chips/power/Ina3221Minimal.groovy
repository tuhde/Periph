package it.uhde.periph.chips.power

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * INA3221 — 3-channel, high-side measurement, shunt and bus voltage monitor
 * with I²C interface (minimal driver).
 *
 * <p>Measures bus voltage and shunt voltage on up to three independent channels.
 * Current and power are computed in software from the shunt voltage and the
 * per-channel shunt resistance supplied at construction time. There is no
 * calibration register; all scaling is done in the driver.
 *
 * <p>The constructor does not write any registers — the chip's hardware reset
 * default (configuration register 0x7127: all channels enabled, 1-sample
 * averaging, 1.1 ms conversion, continuous shunt+bus mode) is suitable for
 * immediate use.
 *
 * <p>Default I²C address: 0x40 (A0=GND, A1=GND).
 */
@CompileStatic
class Ina3221Minimal {

    protected static final int[] SHUNT_BASE = [0, 0x01, 0x03, 0x05] as int[]
    protected static final int[] BUS_BASE   = [0, 0x02, 0x04, 0x06] as int[]

    protected final Transport transport
    /** Per-channel shunt resistances in Ω (index 0 = channel 1). */
    protected final double[] rShunts

    /**
     * Construct the driver with a uniform shunt resistance for all three channels.
     *
     * <p>No registers are written; the hardware reset defaults are used.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunt    shunt resistance in Ω applied to all three channels (e.g. 0.1)
     */
    Ina3221Minimal(Transport transport, double rShunt = 0.1) {
        this.transport = transport
        this.rShunts   = [rShunt, rShunt, rShunt] as double[]
    }

    /**
     * Construct the driver with per-channel shunt resistances.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunts   shunt resistances in Ω for channels 1, 2, and 3 (length must be 3)
     */
    Ina3221Minimal(Transport transport, double[] rShunts) {
        if (rShunts == null || rShunts.length != 3)
            throw new IllegalArgumentException('rShunts must have exactly 3 elements')
        this.transport = transport
        this.rShunts   = rShunts.clone() as double[]
    }

    /**
     * Read the bus voltage for a channel.
     *
     * <p>Reads the left-aligned 12-bit unsigned bus voltage register, right-shifts
     * by 3, and multiplies by the 8 mV LSB.
     *
     * @param channel channel number (1, 2, or 3)
     * @return bus voltage in V
     */
    double voltage(int channel) {
        checkChannel(channel)
        int raw = readReg(BUS_BASE[channel])
        (raw >> 3) * 8e-3
    }

    /**
     * Read the shunt voltage for a channel.
     *
     * <p>Reads the left-aligned 13-bit signed shunt voltage register, right-shifts
     * by 3 (arithmetic), and multiplies by the 40 µV LSB.
     *
     * @param channel channel number (1, 2, or 3)
     * @return shunt voltage in V (may be negative)
     */
    double shuntVoltage(int channel) {
        checkChannel(channel)
        def raw = readReg(SHUNT_BASE[channel])
        def signed = (short) raw
        (signed >> 3) * 40e-6
    }

    /**
     * Compute the current through the shunt resistor for a channel.
     *
     * <p>Current = shuntVoltage / rShunt[channel].
     *
     * @param channel channel number (1, 2, or 3)
     * @return current in A (may be negative for reverse flow)
     */
    double current(int channel) {
        checkChannel(channel)
        shuntVoltage(channel) / rShunts[channel - 1]
    }

    /**
     * Compute the power consumed on a channel.
     *
     * <p>Power = busVoltage × current.
     *
     * @param channel channel number (1, 2, or 3)
     * @return power in W
     */
    double power(int channel) {
        checkChannel(channel)
        voltage(channel) * current(channel)
    }

    // -------------------------------------------------------------------------
    // Protected helpers (shared with Ina3221Full)
    // -------------------------------------------------------------------------

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address
     * @return raw unsigned 16-bit value
     */
    protected int readReg(int reg) {
        byte[] b = transport.writeRead([(byte) reg] as byte[], 2)
        ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg register address
     * @param val 16-bit value to write
     */
    protected void writeReg(int reg, int val) {
        transport.write([(byte) reg, (byte) ((val >> 8) & 0xFF), (byte) (val & 0xFF)] as byte[])
    }

    /**
     * Validate that a channel number is 1, 2, or 3.
     *
     * @param channel channel number to validate
     */
    protected static void checkChannel(int channel) {
        if (channel < 1 || channel > 3)
            throw new IllegalArgumentException("channel must be 1-3, got: ${channel}")
    }
}
