package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import kotlin.math.floor

/**
 * AD5243 — full interface: extends [AD5243Minimal] with ratio and W–A
 * helpers, increment/decrement, per-channel shutdown and reset. The shutdown
 * state is write-only on the chip, so the driver tracks it.
 *
 * @param connection configured I²C connection bound to the device (0x2F)
 * @param rAb end-to-end resistance RAB in Ω
 * @param rWiper wiper resistance RW in Ω
 */
class AD5243Full(
    connection: RegisterConnection,
    rAb: Double = DEFAULT_RAB,
    rWiper: Double = DEFAULT_RW,
) : AD5243Minimal(connection, rAb, rWiper) {

    /**
     * Write both wipers (two transactions).
     *
     * @param code1 channel 0 wiper position 0–255
     * @param code2 channel 1 wiper position 0–255
     */
    fun setBoth(code1: Int, code2: Int) {
        writeCode(0, code1)
        writeCode(1, code2)
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param channel 0 or 1
     * @param ratio 0.0–1.0; the code is `round(ratio × 256)` clamped to 255
     */
    fun setRatio(channel: Int, ratio: Double) {
        writeCode(channel, floor(ratio * 256.0 + 0.5).toInt())
    }

    /**
     * Read the divider ratio of a channel.
     *
     * @param channel 0 or 1
     * @return `code / 256`
     */
    fun getRatio(channel: Int): Double = readCode(channel) / 256.0

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param channel 0 or 1
     * @param ohms target W–A resistance in Ω
     */
    fun setResistanceWa(channel: Int, ohms: Double) {
        val steps = floor((ohms - 2.0 * rW) / rAb * 256.0 + 0.5).toInt()
        writeCode(channel, 256 - steps)
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, `(256 − D)/256 · RAB + 2·RW`
     */
    fun getResistanceWa(channel: Int): Double = (256 - readCode(channel)) / 256.0 * rAb + 2.0 * rW

    /**
     * Move the wiper relative to its current position.
     *
     * @param channel 0 or 1
     * @param steps signed step count (negative to decrement)
     * @return the new wiper code, clamped to 0–255
     */
    fun increment(channel: Int, steps: Int = 1): Int {
        val c = clampCode(readCode(channel) + steps)
        writeCode(channel, c)
        return c
    }

    /**
     * Shut a channel down: terminal A opens and the wiper shorts to B. The
     * RDAC contents are kept (the tracked code is rewritten with SD = 1).
     *
     * @param channel 0 or 1
     */
    fun shutdown(channel: Int) {
        checkChannel(channel)
        sd[channel] = true
        writeCode(channel, code[channel])
    }

    /**
     * Leave shutdown (SD = 0), rewriting the tracked code.
     *
     * @param channel 0 or 1
     */
    fun resume(channel: Int) {
        checkChannel(channel)
        sd[channel] = false
        writeCode(channel, code[channel])
    }

    /**
     * Driver-tracked shutdown state (the chip cannot report it).
     *
     * @param channel 0 or 1
     * @return true if the channel was shut down by this driver
     */
    fun isShutdown(channel: Int): Boolean {
        checkChannel(channel)
        return sd[channel]
    }

    /** Restore the power-up preset: both wipers 0x80, SD = 0. */
    fun reset() {
        sd[0] = false
        sd[1] = false
        writeCode(0, MIDSCALE)
        writeCode(1, MIDSCALE)
    }

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω; null keeps the current value
     */
    fun setRab(rAb: Double, rWiper: Double? = null) {
        this.rAb = rAb
        if (rWiper != null) this.rW = rWiper
    }
}
