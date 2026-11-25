package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * AD5248 — full driver, extends [AD5248Minimal] with ratio, increment, shutdown and reset.
 *
 * @param connection I²C connection bound to an address in 0x2C–0x2F
 * @param rAb end-to-end resistance RAB in ohms
 * @param rWiper wiper resistance RW in ohms
 */
class AD5248Full(
    connection: RegisterConnection,
    rAb: Double = 10000.0,
    rWiper: Double = 160.0
) : AD5248Minimal(connection, rAb, rWiper) {

    /**
     * Write both wipers (two transactions).
     *
     * @param code1 channel 0 wiper position 0–255
     * @param code2 channel 1 wiper position 0–255
     */
    fun setBoth(code1: Int, code2: Int) {
        setWiper(0, code1)
        setWiper(1, code2)
    }

    /**
     * Set the wiper to `round(ratio × 256)` clamped to 255. Only meaningful if terminal A
     * is wired externally; the AD5248 does not pin it out, so prefer [setResistance].
     *
     * @param channel channel index, 0 or 1
     * @param ratio ratio 0.0–1.0
     */
    fun setRatio(channel: Int, ratio: Double) {
        val r = ratio.coerceIn(0.0, 1.0)
        setWiper(channel, Math.round(r * 256.0).coerceAtMost(255L).toInt())
    }

    /**
     * Read the wiper position as a ratio.
     *
     * @param channel channel index, 0 or 1
     * @return `code / 256`
     */
    fun getRatio(channel: Int): Double = getWiper(channel) / 256.0

    /**
     * Move the wiper by a number of steps (negative to decrement).
     *
     * @param channel channel index, 0 or 1
     * @param steps steps to add
     * @return new wiper code, clamped to 0–255
     */
    fun increment(channel: Int, steps: Int = 1): Int {
        val code = (getWiper(channel) + steps).coerceIn(0, 255)
        setWiper(channel, code)
        return code
    }

    /**
     * Put a channel in shutdown (terminal A open, wiper shorted to B); the RDAC is kept.
     *
     * @param channel channel index, 0 or 1
     */
    fun shutdown(channel: Int) {
        checkChannel(channel)
        val code = getWiper(channel)
        shutdownState[channel] = true
        connection.write(instruction(channel), byteArrayOf(code.toByte()))
    }

    /**
     * Leave shutdown; the RDAC contents are restored to the wiper.
     *
     * @param channel channel index, 0 or 1
     */
    fun resume(channel: Int) {
        checkChannel(channel)
        val code = getWiper(channel)
        shutdownState[channel] = false
        connection.write(instruction(channel), byteArrayOf(code.toByte()))
    }

    /**
     * Driver-tracked shutdown state (the chip cannot be read back).
     *
     * @param channel channel index, 0 or 1
     * @return true if the channel was shut down by this driver
     */
    fun isShutdown(channel: Int): Boolean {
        checkChannel(channel)
        return shutdownState[channel]
    }

    /** Write midscale (0x80) to both channels with SD = 0 (power-up preset). */
    fun reset() {
        shutdownState[0] = false
        shutdownState[1] = false
        setWiper(0, 0x80)
        setWiper(1, 0x80)
    }

    /**
     * Change the resistance model used by the conversions (no I²C traffic).
     *
     * @param rAb end-to-end resistance RAB in ohms
     * @param rWiper wiper resistance RW in ohms; negative keeps the current value
     */
    fun setRab(rAb: Double, rWiper: Double = -1.0) {
        this.rAb = rAb
        if (rWiper >= 0) this.rWiper = rWiper
    }
}
