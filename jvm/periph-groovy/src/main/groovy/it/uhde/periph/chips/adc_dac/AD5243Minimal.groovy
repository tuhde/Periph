package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * AD5243 — dual 256-position I²C digital potentiometer (Analog Devices) —
 * minimal interface.
 *
 * <p>Two independent 3-terminal potentiometers (A, W, B) usable as voltage
 * dividers or 2-terminal rheostats. The chip has no register map: a write is
 * {@code [instruction, data]} where the instruction byte carries the channel
 * select ({@code A0}, bit 7) and the shutdown flag ({@code SD}, bit 6); it is
 * passed as the "register" of the {@link RegisterConnection}. The I²C address
 * is fixed at 0x2F. Wiper values are volatile (power-up midscale 0x80).
 *
 * <p>Resistances follow {@code R_WB = D/256 · RAB + 2·RW}.
 */
@CompileStatic
class AD5243Minimal {

    /** Fixed 7-bit I²C address (no address pins). */
    public static final int DEFAULT_ADDRESS = 0x2F

    /** Default end-to-end resistance RAB in Ω (10 kΩ part). */
    public static final double DEFAULT_RAB = 10000.0d

    /** Default wiper resistance RW in Ω (typical, 5 V). */
    public static final double DEFAULT_RW = 160.0d

    protected static final int INSTR_A0 = 0x80
    protected static final int INSTR_SD = 0x40
    protected static final int MIDSCALE = 0x80

    protected final RegisterConnection connection
    protected double rAbOhms
    protected double rWOhms
    protected final int[] codes = [MIDSCALE, MIDSCALE] as int[]
    protected final boolean[] shut = [false, false] as boolean[]

    /**
     * Construct the driver with the default 10 kΩ / 160 Ω resistance model.
     * Performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     */
    AD5243Minimal(RegisterConnection connection) {
        this(connection, DEFAULT_RAB, DEFAULT_RW)
    }

    /**
     * Construct the driver. Performs no bus traffic (the chip has no identity
     * register and needs no initialisation).
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    AD5243Minimal(RegisterConnection connection, double rAb, double rWiper) {
        this.connection = connection
        this.rAbOhms = rAb
        this.rWOhms = rWiper
    }

    protected static void checkChannel(int channel) {
        if (channel != 0 && channel != 1) {
            throw new IllegalArgumentException('channel must be 0 or 1')
        }
    }

    protected static int clampCode(int code) {
        return Math.max(0, Math.min(255, code))
    }

    protected int instr(int channel) {
        checkChannel(channel)
        return (channel == 1 ? INSTR_A0 : 0) | (shut[channel] ? INSTR_SD : 0)
    }

    protected void writeCode(int channel, int value) {
        int c = clampCode(value)
        connection.write(instr(channel), new byte[]{(byte) c})
        codes[channel] = c
    }

    protected int readCode(int channel) {
        int c = connection.read(instr(channel), 1)[0] & 0xFF
        codes[channel] = c
        return c
    }

    /**
     * Set the wiper position of a channel.
     *
     * @param channel 0 or 1
     * @param value   wiper position 0–255 (clamped)
     */
    void setWiper(int channel, int value) {
        writeCode(channel, value)
    }

    /**
     * Read back the wiper position (instruction byte alone, then one byte).
     *
     * @param channel 0 or 1
     * @return wiper position 0–255
     */
    int getWiper(int channel) {
        return readCode(channel)
    }

    /**
     * Set the W–B resistance to the nearest reachable code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–B resistance in Ω (clamped to the reachable range)
     */
    void setResistance(int channel, double ohms) {
        writeCode(channel, (int) Math.floor((ohms - 2.0d * rWOhms) / rAbOhms * 256.0d + 0.5d))
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code D/256 · RAB + 2·RW}
     */
    double getResistance(int channel) {
        return readCode(channel) / 256.0d * rAbOhms + 2.0d * rWOhms
    }
}
