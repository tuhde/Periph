package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * AD5248 — dual 256-position I²C digital rheostat (minimal driver).
 *
 * The chip has no register map: the instruction byte (channel select A0 in bit 7,
 * shutdown SD in bit 6) plays the role of the register address, so the driver calls
 * `read(instruction, 1)` / `write(instruction, data)` on the [RegisterConnection].
 * Wiper values are volatile (midscale 0x80 at power-up).
 *
 * ## Defaults
 * RAB = 10 kΩ, RW = 160 Ω; both channels assumed active. Construction performs no I²C
 * traffic. An invalid channel (not 0 or 1) throws [IllegalArgumentException].
 *
 * @param connection I²C connection bound to an address in 0x2C–0x2F
 * @param rAb end-to-end resistance RAB in ohms (2500, 10000, 50000 or 100000)
 * @param rWiper wiper resistance RW in ohms (typically 160)
 */
open class AD5248Minimal(
    protected val connection: RegisterConnection,
    protected var rAb: Double = 10000.0,
    protected var rWiper: Double = 160.0
) {
    companion object {
        /** Shutdown bit (SD) of the instruction byte. */
        const val SD = 0x40
    }

    /** Driver-tracked shutdown state per channel. */
    protected val shutdownState = BooleanArray(2)

    /** Validate a channel index. */
    protected fun checkChannel(channel: Int) {
        require(channel == 0 || channel == 1) { "AD5248: channel must be 0 or 1" }
    }

    /** Build the instruction byte for a channel, including the tracked SD bit. */
    protected fun instruction(channel: Int): Int =
        (channel shl 7) or (if (shutdownState[channel]) SD else 0)

    /**
     * Set the wiper position of a channel; the code is clamped to 0–255. On a shut-down
     * channel the RDAC is updated and the channel stays in shutdown.
     *
     * @param channel channel index, 0 or 1
     * @param code wiper position 0–255 (0 = at B, 255 = at A)
     */
    fun setWiper(channel: Int, code: Int) {
        checkChannel(channel)
        connection.write(instruction(channel), byteArrayOf(code.coerceIn(0, 255).toByte()))
    }

    /**
     * Read the wiper position of a channel: sends the instruction byte alone, then reads one byte.
     *
     * @param channel channel index, 0 or 1
     * @return wiper position 0–255
     */
    fun getWiper(channel: Int): Int {
        checkChannel(channel)
        return connection.read(instruction(channel), 1)[0].toInt() and 0xFF
    }

    /**
     * Set the W–B resistance to the nearest reachable value.
     *
     * @param channel channel index, 0 or 1
     * @param ohms target wiper-to-B resistance in ohms (clamped to the reachable range)
     */
    fun setResistance(channel: Int, ohms: Double) {
        checkChannel(channel)
        val code = Math.round((ohms - 2 * rWiper) / rAb * 256.0)
        setWiper(channel, code.coerceIn(0L, 255L).toInt())
    }

    /**
     * Read the W–B resistance: `code / 256 * RAB + 2 * RW`.
     *
     * @param channel channel index, 0 or 1
     * @return resistance in ohms
     */
    fun getResistance(channel: Int): Double = getWiper(channel) / 256.0 * rAb + 2 * rWiper
}
