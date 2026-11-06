package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

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
@CompileStatic
class AD5248Minimal {

    /** Shutdown bit (SD) of the instruction byte. */
    protected static final int SD = 0x40

    protected final RegisterConnection connection
    protected double resAb
    protected double resWiper
    protected final boolean[] shutdownState = new boolean[2]

    /**
     * Construct the driver; performs no I²C traffic.
     *
     * @param connection I²C connection bound to an address in 0x2C–0x2F
     * @param rAb        end-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000)
     * @param rWiper     wiper resistance RW in ohms (typically 160)
     */
    AD5248Minimal(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 160.0d) {
        this.connection = connection
        this.resAb = rAb
        this.resWiper = rWiper
    }

    /** Validate a channel index. */
    protected static void checkChannel(int channel) {
        if (channel != 0 && channel != 1) {
            throw new IllegalArgumentException("AD5248: channel must be 0 or 1")
        }
    }

    /** Build the instruction byte for a channel, including the tracked SD bit. */
    protected int instruction(int channel) {
        return (channel << 7) | (shutdownState[channel] ? SD : 0)
    }

    /**
     * Set the wiper position of a channel; the code is clamped to 0–255. On a shut-down
     * channel the RDAC is updated and the channel stays in shutdown.
     *
     * @param channel channel index, 0 or 1
     * @param code    wiper position 0–255 (0 = at B, 255 = at A)
     */
    void setWiper(int channel, int code) {
        checkChannel(channel)
        int c = Math.max(0, Math.min(255, code))
        connection.write(instruction(channel), [(byte) c] as byte[])
    }

    /**
     * Read the wiper position of a channel: sends the instruction byte alone, then reads one byte.
     *
     * @param channel channel index, 0 or 1
     * @return wiper position 0–255
     */
    int getWiper(int channel) {
        checkChannel(channel)
        return connection.read(instruction(channel), 1)[0] & 0xFF
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     *
     * @param channel channel index, 0 or 1
     * @param ohms    target wiper-to-B resistance in ohms (clamped to the reachable range)
     */
    void setResistance(int channel, double ohms) {
        checkChannel(channel)
        long code = Math.round((ohms - 2 * resWiper) / resAb * 256.0d)
        setWiper(channel, (int) Math.max(0L, Math.min(255L, code)))
    }

    /**
     * Read the W–B resistance: {@code code / 256 * RAB + 2 * RW}.
     *
     * @param channel channel index, 0 or 1
     * @return resistance in ohms
     */
    double getResistance(int channel) {
        return getWiper(channel) / 256.0d * resAb + 2 * resWiper
    }
}
