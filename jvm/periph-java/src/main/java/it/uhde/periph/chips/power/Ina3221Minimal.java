package it.uhde.periph.chips.power;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

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
public class Ina3221Minimal {

    /** I²C register addresses. */
    protected static final int REG_CONFIG        = 0x00;
    protected static final int REG_CH1_SHUNT_V   = 0x01;
    protected static final int REG_CH1_BUS_V     = 0x02;
    protected static final int REG_CH2_SHUNT_V   = 0x03;
    protected static final int REG_CH2_BUS_V     = 0x04;
    protected static final int REG_CH3_SHUNT_V   = 0x05;
    protected static final int REG_CH3_BUS_V     = 0x06;
    protected static final int REG_CH1_CRIT      = 0x07;
    protected static final int REG_CH1_WARN      = 0x08;
    protected static final int REG_CH2_CRIT      = 0x09;
    protected static final int REG_CH2_WARN      = 0x0A;
    protected static final int REG_CH3_CRIT      = 0x0B;
    protected static final int REG_CH3_WARN      = 0x0C;
    protected static final int REG_SV_SUM        = 0x0D;
    protected static final int REG_SV_SUM_LIMIT  = 0x0E;
    protected static final int REG_MASK_ENABLE   = 0x0F;
    protected static final int REG_PV_UPPER      = 0x10;
    protected static final int REG_PV_LOWER      = 0x11;
    protected static final int REG_MANUFACTURER  = 0xFE;
    protected static final int REG_DIE_ID        = 0xFF;

    /** Shunt voltage register base addresses indexed by channel (1-based, index 1–3). */
    private static final int[] SHUNT_BASE = {0, 0x01, 0x03, 0x05};

    /** Bus voltage register base addresses indexed by channel (1-based, index 1–3). */
    private static final int[] BUS_BASE   = {0, 0x02, 0x04, 0x06};

    protected final Transport transport;

    /** Per-channel shunt resistances in Ω (index 0 = channel 1). */
    protected final double[] rShunts;

    /**
     * Construct the driver with a uniform shunt resistance for all three channels.
     *
     * <p>No registers are written; the hardware reset defaults are used.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunt    shunt resistance in Ω applied to all three channels (e.g. 0.1)
     */
    public Ina3221Minimal(Transport transport, double rShunt) {
        this.transport = transport;
        this.rShunts   = new double[]{rShunt, rShunt, rShunt};
    }

    /**
     * Construct the driver with per-channel shunt resistances.
     *
     * <p>No registers are written; the hardware reset defaults are used.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunts   shunt resistances in Ω for channels 1, 2, and 3 (array length must be 3)
     * @throws IllegalArgumentException if {@code rShunts} does not have exactly 3 elements
     */
    public Ina3221Minimal(Transport transport, double[] rShunts) {
        if (rShunts == null || rShunts.length != 3)
            throw new IllegalArgumentException("rShunts must have exactly 3 elements");
        this.transport = transport;
        this.rShunts   = rShunts.clone();
    }

    /**
     * Construct the driver with the default shunt resistance (0.1 Ω) for all channels.
     *
     * @param transport I²C transport bound to the INA3221 device address
     */
    public Ina3221Minimal(Transport transport) {
        this(transport, 0.1);
    }

    /**
     * Read the bus voltage for a channel.
     *
     * <p>Reads the left-aligned 12-bit unsigned bus voltage register, right-shifts
     * by 3, and multiplies by the 8 mV LSB.
     *
     * @param channel channel number (1, 2, or 3)
     * @return bus voltage in V
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public double voltage(int channel) throws IOException {
        checkChannel(channel);
        int raw = readReg(BUS_BASE[channel]);
        return (raw >> 3) * 8e-3;
    }

    /**
     * Read the shunt voltage for a channel.
     *
     * <p>Reads the left-aligned 13-bit signed shunt voltage register, right-shifts
     * by 3 (arithmetic), and multiplies by the 40 µV LSB.
     *
     * @param channel channel number (1, 2, or 3)
     * @return shunt voltage in V (may be negative)
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public double shuntVoltage(int channel) throws IOException {
        checkChannel(channel);
        int raw = readReg(SHUNT_BASE[channel]);
        return ((short) raw >> 3) * 40e-6;
    }

    /**
     * Compute the current through the shunt resistor for a channel.
     *
     * <p>Current = shuntVoltage / rShunt[channel]. The shunt resistance is
     * the value supplied at construction time.
     *
     * @param channel channel number (1, 2, or 3)
     * @return current in A (may be negative for reverse flow)
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public double current(int channel) throws IOException {
        checkChannel(channel);
        return shuntVoltage(channel) / rShunts[channel - 1];
    }

    /**
     * Compute the power consumed on a channel.
     *
     * <p>Power = busVoltage × current.
     *
     * @param channel channel number (1, 2, or 3)
     * @return power in W
     * @throws IOException              on I²C error
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    public double power(int channel) throws IOException {
        checkChannel(channel);
        return voltage(channel) * current(channel);
    }

    // -------------------------------------------------------------------------
    // Protected helpers (shared with Ina3221Full)
    // -------------------------------------------------------------------------

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address
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
     * @param reg register address
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

    /**
     * Validate that a channel number is 1, 2, or 3.
     *
     * @param channel channel number to validate
     * @throws IllegalArgumentException if channel is not 1, 2, or 3
     */
    protected static void checkChannel(int channel) {
        if (channel < 1 || channel > 3)
            throw new IllegalArgumentException("channel must be 1-3, got: " + channel);
    }
}
