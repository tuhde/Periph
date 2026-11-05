package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * AD5243 — full interface: extends {@link AD5243Minimal} with ratio and W–A
 * helpers, increment/decrement, per-channel shutdown and reset. The shutdown
 * state is write-only on the chip, so the driver tracks it.
 */
public class AD5243Full extends AD5243Minimal {

    /**
     * Construct the driver with the default 10 kΩ / 160 Ω resistance model.
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     */
    public AD5243Full(RegisterConnection connection) {
        super(connection);
    }

    /**
     * Construct the driver.
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    public AD5243Full(RegisterConnection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper);
    }

    /**
     * Write both wipers (two transactions).
     *
     * @param code1 channel 0 wiper position 0–255
     * @param code2 channel 1 wiper position 0–255
     * @throws IOException on bus error
     */
    public void setBoth(int code1, int code2) throws IOException {
        writeCode(0, code1);
        writeCode(1, code2);
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param channel 0 or 1
     * @param ratio   0.0–1.0; the code is {@code round(ratio × 256)} clamped to 255
     * @throws IOException on bus error
     */
    public void setRatio(int channel, double ratio) throws IOException {
        writeCode(channel, (int) Math.floor(ratio * 256.0 + 0.5));
    }

    /**
     * Read the divider ratio of a channel.
     *
     * @param channel 0 or 1
     * @return {@code code / 256}
     * @throws IOException on bus error
     */
    public double getRatio(int channel) throws IOException {
        return readCode(channel) / 256.0;
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–A resistance in Ω
     * @throws IOException on bus error
     */
    public void setResistanceWa(int channel, double ohms) throws IOException {
        int steps = (int) Math.floor((ohms - 2.0 * rW) / rAb * 256.0 + 0.5);
        writeCode(channel, 256 - steps);
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code (256 − D)/256 · RAB + 2·RW}
     * @throws IOException on bus error
     */
    public double getResistanceWa(int channel) throws IOException {
        return (256 - readCode(channel)) / 256.0 * rAb + 2.0 * rW;
    }

    /**
     * Move the wiper relative to its current position.
     *
     * @param channel 0 or 1
     * @param steps   signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–255
     * @throws IOException on bus error
     */
    public int increment(int channel, int steps) throws IOException {
        int c = clampCode(readCode(channel) + steps);
        writeCode(channel, c);
        return c;
    }

    /**
     * Shut a channel down: terminal A opens and the wiper shorts to B. The
     * RDAC contents are kept (the tracked code is rewritten with SD = 1).
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void shutdown(int channel) throws IOException {
        checkChannel(channel);
        sd[channel] = true;
        writeCode(channel, code[channel]);
    }

    /**
     * Leave shutdown (SD = 0), rewriting the tracked code.
     *
     * @param channel 0 or 1
     * @throws IOException on bus error
     */
    public void resume(int channel) throws IOException {
        checkChannel(channel);
        sd[channel] = false;
        writeCode(channel, code[channel]);
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     *
     * @param channel 0 or 1
     * @return true if the channel was shut down by this driver
     */
    public boolean isShutdown(int channel) {
        checkChannel(channel);
        return sd[channel];
    }

    /**
     * Restore the power-up preset: both wipers 0x80, SD = 0.
     *
     * @throws IOException on bus error
     */
    public void reset() throws IOException {
        sd[0] = false;
        sd[1] = false;
        writeCode(0, MIDSCALE);
        writeCode(1, MIDSCALE);
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb    end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω
     */
    public void setRab(double rAb, double rWiper) {
        this.rAb = rAb;
        this.rW = rWiper;
    }

    /**
     * Change RAB only; RW is kept.
     *
     * @param rAb end-to-end resistance RAB in Ω
     */
    public void setRab(double rAb) {
        this.rAb = rAb;
    }
}
