package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AD5248 — full driver, extends {@link AD5248Minimal} with ratio, increment,
 * shutdown and reset.
 */
public class AD5248Full extends AD5248Minimal {

    /**
     * Construct the driver; performs no I²C traffic.
     *
     * @param connection I²C connection bound to an address in 0x2C–0x2F
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    public AD5248Full(RegisterConnection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper);
    }

    /**
     * Construct with RAB = 10 kΩ and RW = 160 Ω.
     *
     * @param connection I²C connection bound to an address in 0x2C–0x2F
     */
    public AD5248Full(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Write both wipers (two transactions).
     *
     * @param code1 channel 0 wiper position 0–255
     * @param code2 channel 1 wiper position 0–255
     * @throws IOException on I²C error
     */
    public void setBoth(int code1, int code2) throws IOException {
        setWiper(0, code1);
        setWiper(1, code2);
    }

    /**
     * Set the wiper to {@code round(ratio × 256)} clamped to 255. Only meaningful if
     * terminal A is wired externally; the AD5248 does not pin it out, so prefer
     * {@link #setResistance(int, double)}.
     *
     * @param channel channel index, 0 or 1
     * @param ratio   ratio 0.0–1.0
     * @throws IOException on I²C error
     */
    public void setRatio(int channel, double ratio) throws IOException {
        double r = Math.max(0.0, Math.min(1.0, ratio));
        setWiper(channel, (int) Math.min(255L, Math.round(r * 256.0)));
    }

    /**
     * Read the wiper position as a ratio.
     *
     * @param channel channel index, 0 or 1
     * @return {@code code / 256}
     * @throws IOException on I²C error
     */
    public double getRatio(int channel) throws IOException {
        return getWiper(channel) / 256.0;
    }

    /**
     * Move the wiper by a number of steps (negative to decrement).
     *
     * @param channel channel index, 0 or 1
     * @param steps   steps to add
     * @return new wiper code, clamped to 0–255
     * @throws IOException on I²C error
     */
    public int increment(int channel, int steps) throws IOException {
        int code = Math.max(0, Math.min(255, getWiper(channel) + steps));
        setWiper(channel, code);
        return code;
    }

    /**
     * Put a channel in shutdown (terminal A open, wiper shorted to B); the RDAC is kept.
     *
     * @param channel channel index, 0 or 1
     * @throws IOException on I²C error
     */
    public void shutdown(int channel) throws IOException {
        checkChannel(channel);
        int code = getWiper(channel);
        shutdown[channel] = true;
        connection.write(instruction(channel), new byte[] {(byte) code});
    }

    /**
     * Leave shutdown; the RDAC contents are restored to the wiper.
     *
     * @param channel channel index, 0 or 1
     * @throws IOException on I²C error
     */
    public void resume(int channel) throws IOException {
        checkChannel(channel);
        int code = getWiper(channel);
        shutdown[channel] = false;
        connection.write(instruction(channel), new byte[] {(byte) code});
    }

    /**
     * Driver-tracked shutdown state (the chip cannot be read back).
     *
     * @param channel channel index, 0 or 1
     * @return true if the channel was shut down by this driver
     */
    public boolean isShutdown(int channel) {
        checkChannel(channel);
        return shutdown[channel];
    }

    /**
     * Write midscale (0x80) to both channels with SD = 0 (power-up preset).
     *
     * @throws IOException on I²C error
     */
    public void reset() throws IOException {
        shutdown[0] = false;
        shutdown[1] = false;
        setWiper(0, 0x80);
        setWiper(1, 0x80);
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     *
     * @param rAb     end-to-end resistance RAB in ohms
     * @param rWiper  wiper resistance RW in ohms; negative keeps the current value
     */
    public void setRab(double rAb, double rWiper) {
        this.rAb = rAb;
        if (rWiper >= 0) {
            this.rWiper = rWiper;
        }
    }
}
