package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

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
public class AD5243Minimal {

    /** Fixed 7-bit I²C address (no address pins). */
    public static final int DEFAULT_ADDRESS = 0x2F;

    /** Default end-to-end resistance RAB in Ω (10 kΩ part). */
    public static final double DEFAULT_RAB = 10000.0;

    /** Default wiper resistance RW in Ω (typical, 5 V). */
    public static final double DEFAULT_RW = 160.0;

    protected static final int INSTR_A0 = 0x80;
    protected static final int INSTR_SD = 0x40;
    protected static final int MIDSCALE = 0x80;

    protected final RegisterConnection connection;
    protected double rAb;
    protected double rW;
    protected final int[] code = {MIDSCALE, MIDSCALE};
    protected final boolean[] sd = {false, false};

    /**
     * Construct the driver with the default 10 kΩ / 160 Ω resistance model.
     * Performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     */
    public AD5243Minimal(RegisterConnection connection) {
        this(connection, DEFAULT_RAB, DEFAULT_RW);
    }

    /**
     * Construct the driver. Performs no bus traffic (the chip has no identity
     * register and needs no initialisation).
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    public AD5243Minimal(RegisterConnection connection, double rAb, double rWiper) {
        this.connection = connection;
        this.rAb = rAb;
        this.rW = rWiper;
    }

    protected static void checkChannel(int channel) {
        if (channel != 0 && channel != 1) {
            throw new IllegalArgumentException("channel must be 0 or 1");
        }
    }

    protected static int clampCode(int code) {
        return Math.max(0, Math.min(255, code));
    }

    protected int instr(int channel) {
        checkChannel(channel);
        return (channel == 1 ? INSTR_A0 : 0) | (sd[channel] ? INSTR_SD : 0);
    }

    protected void writeCode(int channel, int value) throws IOException {
        int c = clampCode(value);
        connection.write(instr(channel), new byte[]{(byte) c});
        code[channel] = c;
    }

    protected int readCode(int channel) throws IOException {
        int c = connection.read(instr(channel), 1)[0] & 0xFF;
        code[channel] = c;
        return c;
    }

    /**
     * Set the wiper position of a channel.
     *
     * @param channel 0 or 1
     * @param value   wiper position 0–255 (clamped)
     * @throws IllegalArgumentException if channel is not 0 or 1
     * @throws IOException              on bus error
     */
    public void setWiper(int channel, int value) throws IOException {
        writeCode(channel, value);
    }

    /**
     * Read back the wiper position (instruction byte alone, then one byte).
     *
     * @param channel 0 or 1
     * @return wiper position 0–255
     * @throws IOException on bus error
     */
    public int getWiper(int channel) throws IOException {
        return readCode(channel);
    }

    /**
     * Set the W–B resistance to the nearest reachable code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–B resistance in Ω (clamped to the reachable range)
     * @throws IOException on bus error
     */
    public void setResistance(int channel, double ohms) throws IOException {
        writeCode(channel, (int) Math.floor((ohms - 2.0 * rW) / rAb * 256.0 + 0.5));
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code D/256 · RAB + 2·RW}
     * @throws IOException on bus error
     */
    public double getResistance(int channel) throws IOException {
        return readCode(channel) / 256.0 * rAb + 2.0 * rW;
    }
}
