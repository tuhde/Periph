package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AD5252 — dual 256-position I²C digital potentiometer with EEMEM (Analog
 * Devices) — minimal interface.
 *
 * <p>Two independent 3-terminal potentiometers (A, W, B) usable as voltage
 * dividers or 2-terminal rheostats. The chip has no register map: the
 * instruction byte works as a one-byte register pointer (RDAC1 = {@code 0x01}
 * for channel 0, RDAC3 = {@code 0x03} for channel 1), so the driver uses a
 * {@link RegisterConnection} with a 1-byte register width. The I²C address is
 * 0x2C–0x2F (AD1, AD0 pins). Wipers power up from EEMEM (midscale 128 until a
 * value is stored).
 *
 * <p>Resistances follow {@code R_WB = D/256 · RAB + RW}.
 */
public class AD5252Minimal {

    /** Default 7-bit I²C address (AD1 = AD0 = GND); 0x2C–0x2F are possible. */
    public static final int DEFAULT_ADDRESS = 0x2C;

    /** Default end-to-end resistance RAB in Ω (10 kΩ part). */
    public static final double DEFAULT_RAB = 10000.0;

    /** Default wiper resistance RW in Ω (typical). */
    public static final double DEFAULT_RW = 75.0;

    protected static final int[] RDAC = {0x01, 0x03};

    protected final RegisterConnection connection;
    protected double rAb;
    protected double rW;

    /**
     * Construct the driver with the default 10 kΩ / 75 Ω resistance model.
     * Performs no bus traffic.
     *
     * @param connection configured I²C connection bound to the device (0x2C–0x2F)
     */
    public AD5252Minimal(RegisterConnection connection) {
        this(connection, DEFAULT_RAB, DEFAULT_RW);
    }

    /**
     * Construct the driver. Performs no bus traffic (the chip has no identity
     * register and needs no initialisation).
     *
     * @param connection configured I²C connection bound to the device (0x2C–0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    public AD5252Minimal(RegisterConnection connection, double rAb, double rWiper) {
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

    protected void writeCode(int channel, int value) throws IOException {
        checkChannel(channel);
        connection.write(RDAC[channel], new byte[]{(byte) clampCode(value)});
    }

    protected int readCode(int channel) throws IOException {
        checkChannel(channel);
        return connection.read(RDAC[channel], 1)[0] & 0xFF;
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
        writeCode(channel, (int) Math.floor((ohms - rW) / rAb * 256.0 + 0.5));
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code D/256 · RAB + RW}
     * @throws IOException on bus error
     */
    public double getResistance(int channel) throws IOException {
        return readCode(channel) / 256.0 * rAb + rW;
    }
}
