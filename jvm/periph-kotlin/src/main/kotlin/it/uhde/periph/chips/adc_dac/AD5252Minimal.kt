package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import kotlin.math.floor

/**
 * AD5252 — dual 256-position I²C digital potentiometer with EEMEM (Analog
 * Devices) — minimal interface.
 *
 * Two independent 3-terminal potentiometers (A, W, B) usable as voltage
 * dividers or 2-terminal rheostats. The chip has no register map: the
 * instruction byte works as a one-byte register pointer (RDAC1 = `0x01` for
 * channel 0, RDAC3 = `0x03` for channel 1), so the driver uses a
 * [RegisterConnection] with a 1-byte register width. The I²C address is
 * 0x2C–0x2F (AD1, AD0 pins). Wipers power up from EEMEM (midscale 128 until a
 * value is stored).
 *
 * Resistances follow `R_WB = D/256 · RAB + RW`.
 *
 * @param connection configured I²C connection bound to the device (0x2C–0x2F)
 * @param rAb        end-to-end resistance RAB in Ω (default 10 kΩ part)
 * @param rWiper     wiper resistance RW in Ω (default 75 Ω, typical)
 */
open class AD5252Minimal(
    protected val connection: RegisterConnection,
    rAb: Double = DEFAULT_RAB,
    rWiper: Double = DEFAULT_RW,
) {

    companion object {
        /** Default 7-bit I²C address (AD1 = AD0 = GND); 0x2C–0x2F are possible. */
        const val DEFAULT_ADDRESS = 0x2C

        /** Default end-to-end resistance RAB in Ω (10 kΩ part). */
        const val DEFAULT_RAB = 10000.0

        /** Default wiper resistance RW in Ω (typical). */
        const val DEFAULT_RW = 75.0

        internal val RDAC = intArrayOf(0x01, 0x03)

        internal fun checkChannel(channel: Int) {
            require(channel == 0 || channel == 1) { "channel must be 0 or 1" }
        }

        internal fun clampCode(code: Int): Int = maxOf(0, minOf(255, code))
    }

    protected var rAb: Double = rAb
    protected var rW: Double = rWiper

    protected fun writeCode(channel: Int, value: Int) {
        checkChannel(channel)
        connection.write(RDAC[channel], byteArrayOf(clampCode(value).toByte()))
    }

    protected fun readCode(channel: Int): Int {
        checkChannel(channel)
        return connection.read(RDAC[channel], 1)[0].toInt() and 0xFF
    }

    /**
     * Set the wiper position of a channel.
     *
     * @param channel 0 or 1
     * @param value   wiper position 0–255 (clamped)
     * @throws IllegalArgumentException if channel is not 0 or 1
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
     * @param ohms    target W–B resistance in Ω (clamped to the reachable range)
     */
    fun setResistance(channel: Int, ohms: Double) {
        writeCode(channel, floor((ohms - rW) / rAb * 256.0 + 0.5).toInt())
    }

    /**
     * Read the W–B resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, `D/256 · RAB + RW`
     */
    fun getResistance(channel: Int): Double = readCode(channel) / 256.0 * rAb + rW
}
