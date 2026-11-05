package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import kotlin.math.floor

/**
 * AD5243 — dual 256-position I²C digital potentiometer (Analog Devices) —
 * minimal interface.
 *
 * Two independent 3-terminal potentiometers (A, W, B) usable as voltage
 * dividers or 2-terminal rheostats. The chip has no register map: a write is
 * `[instruction, data]` where the instruction byte carries the channel select
 * (`A0`, bit 7) and the shutdown flag (`SD`, bit 6); it is passed as the
 * "register" of the [RegisterConnection]. The I²C address is fixed at 0x2F.
 * Wiper values are volatile (power-up midscale 0x80).
 *
 * Resistances follow `R_WB = D/256 · RAB + 2·RW`. Construction performs no
 * bus traffic.
 *
 * @param connection configured I²C connection bound to the device (0x2F)
 * @param rAb end-to-end resistance RAB in Ω
 * @param rWiper wiper resistance RW in Ω
 */
open class AD5243Minimal(
    protected val connection: RegisterConnection,
    rAb: Double = DEFAULT_RAB,
    rWiper: Double = DEFAULT_RW,
) {

    companion object {
        /** Fixed 7-bit I²C address (no address pins). */
        const val DEFAULT_ADDRESS = 0x2F

        /** Default end-to-end resistance RAB in Ω (10 kΩ part). */
        const val DEFAULT_RAB = 10000.0

        /** Default wiper resistance RW in Ω (typical, 5 V). */
        const val DEFAULT_RW = 160.0

        const val INSTR_A0 = 0x80
        const val INSTR_SD = 0x40
        const val MIDSCALE = 0x80

        internal fun checkChannel(channel: Int) {
            require(channel == 0 || channel == 1) { "channel must be 0 or 1" }
        }

        internal fun clampCode(code: Int): Int = maxOf(0, minOf(255, code))
    }

    protected var rAb: Double = rAb
    protected var rW: Double = rWiper
    protected val code = intArrayOf(MIDSCALE, MIDSCALE)
    protected val sd = booleanArrayOf(false, false)

    protected fun instr(channel: Int): Int {
        checkChannel(channel)
        return (if (channel == 1) INSTR_A0 else 0) or (if (sd[channel]) INSTR_SD else 0)
    }

    protected fun writeCode(channel: Int, value: Int) {
        val c = clampCode(value)
        connection.write(instr(channel), byteArrayOf(c.toByte()))
        code[channel] = c
    }

    protected fun readCode(channel: Int): Int {
        val c = connection.read(instr(channel), 1)[0].toInt() and 0xFF
        code[channel] = c
        return c
    }

    /**
     * Set the wiper position of a channel.
     *
     * @param channel 0 or 1
     * @param value wiper position 0–255 (clamped)
     */
    fun setWiper(channel: Int, value: Int) {
        writeCode(channel, value)
    }

    /**
     * Read back the wiper position (instruction byte alone, then one byte).
     *
     * @param channel 0 or 1
     * @return wiper position 0–255
     */
    fun getWiper(channel: Int): Int = readCode(channel)

    /**
     * Set the W–B resistance to the nearest reachable code.
     *
     * @param channel 0 or 1
     * @param ohms target W–B resistance in Ω (clamped to the reachable range)
     */
    fun setResistance(channel: Int, ohms: Double) {
        writeCode(channel, floor((ohms - 2.0 * rW) / rAb * 256.0 + 0.5).toInt())
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, `D/256 · RAB + 2·RW`
     */
    fun getResistance(channel: Int): Double = readCode(channel) / 256.0 * rAb + 2.0 * rW
}
