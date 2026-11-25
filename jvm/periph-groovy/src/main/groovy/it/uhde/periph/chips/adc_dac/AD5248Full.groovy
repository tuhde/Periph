package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/**
 * AD5248 — full driver, extends {@link AD5248Minimal} with ratio, increment,
 * shutdown and reset.
 */
@CompileStatic
class AD5248Full extends AD5248Minimal {

    /**
     * Construct the driver; performs no I²C traffic.
     *
     * @param connection I²C connection bound to an address in 0x2C–0x2F
     * @param rAb        end-to-end resistance RAB in ohms
     * @param rWiper     wiper resistance RW in ohms
     */
    AD5248Full(RegisterConnection connection, double rAb = 10000.0d, double rWiper = 160.0d) {
        super(connection, rAb, rWiper)
    }

    /**
     * Write both wipers (two transactions).
     *
     * @param code1 channel 0 wiper position 0–255
     * @param code2 channel 1 wiper position 0–255
     */
    void setBoth(int code1, int code2) {
        setWiper(0, code1)
        setWiper(1, code2)
    }

    /**
     * Set the wiper to {@code round(ratio × 256)} clamped to 255. Only meaningful if
     * terminal A is wired externally; the AD5248 does not pin it out, so prefer
     * {@link #setResistance(int, double)}.
     *
     * @param channel channel index, 0 or 1
     * @param ratio   ratio 0.0–1.0
     */
    void setRatio(int channel, double ratio) {
        double r = Math.max(0.0d, Math.min(1.0d, ratio))
        setWiper(channel, (int) Math.min(255L, Math.round(r * 256.0d)))
    }

    /**
     * Read the wiper position as a ratio.
     *
     * @param channel channel index, 0 or 1
     * @return {@code code / 256}
     */
    double getRatio(int channel) {
        return getWiper(channel) / 256.0d
    }

    /**
     * Move the wiper by a number of steps (negative to decrement).
     *
     * @param channel channel index, 0 or 1
     * @param steps   steps to add
     * @return new wiper code, clamped to 0–255
     */
    int increment(int channel, int steps = 1) {
        int code = Math.max(0, Math.min(255, getWiper(channel) + steps))
        setWiper(channel, code)
        return code
    }

    /**
     * Put a channel in shutdown (terminal A open, wiper shorted to B); the RDAC is kept.
     *
     * @param channel channel index, 0 or 1
     */
    void shutdown(int channel) {
        checkChannel(channel)
        int code = getWiper(channel)
        shutdownState[channel] = true
        connection.write(instruction(channel), [(byte) code] as byte[])
    }

    /**
     * Leave shutdown; the RDAC contents are restored to the wiper.
     *
     * @param channel channel index, 0 or 1
     */
    void resume(int channel) {
        checkChannel(channel)
        int code = getWiper(channel)
        shutdownState[channel] = false
        connection.write(instruction(channel), [(byte) code] as byte[])
    }

    /**
     * Driver-tracked shutdown state (the chip cannot be read back).
     *
     * @param channel channel index, 0 or 1
     * @return true if the channel was shut down by this driver
     */
    boolean isShutdown(int channel) {
        checkChannel(channel)
        return shutdownState[channel]
    }

    /** Write midscale (0x80) to both channels with SD = 0 (power-up preset). */
    void reset() {
        shutdownState[0] = false
        shutdownState[1] = false
        setWiper(0, 0x80)
        setWiper(1, 0x80)
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     *
     * @param rAb     end-to-end resistance RAB in ohms
     * @param rWiper  wiper resistance RW in ohms; negative keeps the current value
     */
    void setRab(double rAb, double rWiper = -1.0d) {
        this.resAb = rAb
        if (rWiper >= 0) {
            this.resWiper = rWiper
        }
    }
}
