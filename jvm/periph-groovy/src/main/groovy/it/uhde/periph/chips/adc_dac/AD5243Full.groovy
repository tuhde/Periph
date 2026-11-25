package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * AD5243 — full interface: extends {@link AD5243Minimal} with ratio and W–A
 * helpers, increment/decrement, per-channel shutdown and reset. The shutdown
 * state is write-only on the chip, so the driver tracks it.
 */
@CompileStatic
class AD5243Full extends AD5243Minimal {

    /**
     * Construct the driver with the default 10 kΩ / 160 Ω resistance model.
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     */
    AD5243Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Construct the driver.
     *
     * @param connection configured I²C connection bound to the device (0x2F)
     * @param rAb        end-to-end resistance RAB in Ω
     * @param rWiper     wiper resistance RW in Ω
     */
    AD5243Full(RegisterConnection connection, double rAb, double rWiper) {
        super(connection, rAb, rWiper)
    }

    /**
     * Write both wipers (two transactions).
     *
     * @param code1 channel 0 wiper position 0–255
     * @param code2 channel 1 wiper position 0–255
     */
    void setBoth(int code1, int code2) {
        writeCode(0, code1)
        writeCode(1, code2)
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param channel 0 or 1
     * @param ratio   0.0–1.0; the code is {@code round(ratio × 256)} clamped to 255
     */
    void setRatio(int channel, double ratio) {
        writeCode(channel, (int) Math.floor(ratio * 256.0d + 0.5d))
    }

    /**
     * Read the divider ratio of a channel.
     *
     * @param channel 0 or 1
     * @return {@code code / 256}
     */
    double getRatio(int channel) {
        return readCode(channel) / 256.0d
    }

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–A resistance in Ω
     */
    void setResistanceWa(int channel, double ohms) {
        int steps = (int) Math.floor((ohms - 2.0d * rWOhms) / rAbOhms * 256.0d + 0.5d)
        writeCode(channel, 256 - steps)
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, {@code (256 − D)/256 · RAB + 2·RW}
     */
    double getResistanceWa(int channel) {
        return (256 - readCode(channel)) / 256.0d * rAbOhms + 2.0d * rWOhms
    }

    /**
     * Move the wiper relative to its current position.
     *
     * @param channel 0 or 1
     * @param steps   signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–255
     */
    int increment(int channel, int steps) {
        int c = clampCode(readCode(channel) + steps)
        writeCode(channel, c)
        return c
    }

    /**
     * Shut a channel down: terminal A opens and the wiper shorts to B. The
     * RDAC contents are kept (the tracked code is rewritten with SD = 1).
     *
     * @param channel 0 or 1
     */
    void shutdown(int channel) {
        checkChannel(channel)
        shut[channel] = true
        writeCode(channel, codes[channel])
    }

    /**
     * Leave shutdown (SD = 0), rewriting the tracked code.
     *
     * @param channel 0 or 1
     */
    void resume(int channel) {
        checkChannel(channel)
        shut[channel] = false
        writeCode(channel, codes[channel])
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     *
     * @param channel 0 or 1
     * @return true if the channel was shut down by this driver
     */
    boolean isShutdown(int channel) {
        checkChannel(channel)
        return shut[channel]
    }

    /** Restore the power-up preset: both wipers 0x80, SD = 0. */
    void reset() {
        shut[0] = false
        shut[1] = false
        writeCode(0, MIDSCALE)
        writeCode(1, MIDSCALE)
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb    end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω
     */
    void setRab(double rAb, double rWiper) {
        this.rAbOhms = rAb
        this.rWOhms = rWiper
    }

    /**
     * Change RAB only; RW is kept.
     *
     * @param rAb end-to-end resistance RAB in Ω
     */
    void setRab(double rAb) {
        this.rAbOhms = rAb
    }
}
