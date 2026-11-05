package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AD5248 — dual 256-position I²C digital rheostat (minimal driver).
 *
 * <p>The chip has no register map: the instruction byte (channel select A0 in bit 7,
 * shutdown SD in bit 6) plays the role of the register address, so the driver calls
 * {@code read(instruction, 1)} / {@code write(instruction, data)} on the
 * {@link RegisterConnection}. Wiper values are volatile (midscale 0x80 at power-up).
 *
 * <h2>Defaults</h2>
 * <p>RAB = 10 kΩ, RW = 160 Ω; both channels assumed active. Construction performs no
 * I²C traffic. An invalid channel (not 0 or 1) throws {@link IllegalArgumentException}.
 */
public class AD5248Minimal {

    /** Shutdown bit (SD) of the instruction byte. */
    protected static final int SD = 0x40;

    protected final RegisterConnection connection;
    protected double rAb;
    protected double rWiper;
    protected final boolean[] shutdown = new boolean[2];

    /**
     * Construct the driver; performs no I²C traffic.
     *
     * @param connection I²C connection bound to an address in 0x2C–0x2F
     * @param rAb        end-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000)
     * @param rWiper     wiper resistance RW in ohms (typically 160)
     */
    public AD5248Minimal(RegisterConnection connection, double rAb, double rWiper) {
        this.connection = connection;
        this.rAb = rAb;
        this.rWiper = rWiper;
    }

    /**
     * Construct with RAB = 10 kΩ and RW = 160 Ω.
     *
     * @param connection I²C connection bound to an address in 0x2C–0x2F
     */
    public AD5248Minimal(RegisterConnection connection) {
        this(connection, 10000.0, 160.0);
    }

    /** Validate a channel index. */
    protected static void checkChannel(int channel) {
        if (channel != 0 && channel != 1) {
            throw new IllegalArgumentException("AD5248: channel must be 0 or 1");
        }
    }

    /** Build the instruction byte for a channel, including the tracked SD bit. */
    protected int instruction(int channel) {
        return (channel << 7) | (shutdown[channel] ? SD : 0);
    }

    /**
     * Set the wiper position of a channel; the code is clamped to 0–255. On a shut-down
     * channel the RDAC is updated and the channel stays in shutdown.
     *
     * @param channel channel index, 0 or 1
     * @param code    wiper position 0–255 (0 = at B, 255 = at A)
     * @throws IOException on I²C error
     */
    public void setWiper(int channel, int code) throws IOException {
        checkChannel(channel);
        int c = Math.max(0, Math.min(255, code));
        connection.write(instruction(channel), new byte[] {(byte) c});
    }

    /**
     * Read the wiper position of a channel: sends the instruction byte alone, then reads one byte.
     *
     * @param channel channel index, 0 or 1
     * @return wiper position 0–255
     * @throws IOException on I²C error
     */
    public int getWiper(int channel) throws IOException {
        checkChannel(channel);
        return connection.read(instruction(channel), 1)[0] & 0xFF;
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     *
     * @param channel channel index, 0 or 1
     * @param ohms    target wiper-to-B resistance in ohms (clamped to the reachable range)
     * @throws IOException on I²C error
     */
    public void setResistance(int channel, double ohms) throws IOException {
        checkChannel(channel);
        long code = Math.round((ohms - 2 * rWiper) / rAb * 256.0);
        setWiper(channel, (int) Math.max(0, Math.min(255, code)));
    }

    /**
     * Read the W–B resistance: {@code code / 256 * RAB + 2 * RW}.
     *
     * @param channel channel index, 0 or 1
     * @return resistance in ohms
     * @throws IOException on I²C error
     */
    public double getResistance(int channel) throws IOException {
        return getWiper(channel) / 256.0 * rAb + 2 * rWiper;
    }
}
