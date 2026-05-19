package it.uhde.periph.chips.power

import it.uhde.periph.transport.Transport

/**
 * INA3221 — 3-channel, high-side measurement, shunt and bus voltage monitor
 * with I²C interface (minimal driver).
 *
 * Measures bus voltage and shunt voltage on up to three independent channels.
 * Current and power are computed in software from the shunt voltage and the
 * per-channel shunt resistance supplied at construction time. There is no
 * calibration register; all scaling is done in the driver.
 *
 * The constructor does not write any registers — the chip's hardware reset
 * default (configuration register 0x7127: all channels enabled, 1-sample
 * averaging, 1.1 ms conversion, continuous shunt+bus mode) is suitable for
 * immediate use.
 *
 * Default I²C address: 0x40 (A0=GND, A1=GND).
 */
open class Ina3221Minimal(
    protected val transport: Transport,
    rShunts: DoubleArray = doubleArrayOf(0.1, 0.1, 0.1)
) {
    /** Per-channel shunt resistances in Ω (index 0 = channel 1). */
    protected val rShunts: DoubleArray

    init {
        require(rShunts.size == 3) { "rShunts must have exactly 3 elements" }
        this.rShunts = rShunts.copyOf()
    }

    /**
     * Construct with a single shunt resistance applied to all three channels.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunt    shunt resistance in Ω for all channels (e.g. 0.1)
     */
    constructor(transport: Transport, rShunt: Double) :
        this(transport, doubleArrayOf(rShunt, rShunt, rShunt))

    companion object {
        internal val SHUNT_BASE = intArrayOf(0, 0x01, 0x03, 0x05)
        internal val BUS_BASE   = intArrayOf(0, 0x02, 0x04, 0x06)

        internal fun checkChannel(channel: Int) {
            require(channel in 1..3) { "channel must be 1..3, got: $channel" }
        }
    }

    /**
     * Read the bus voltage for a channel.
     *
     * Reads the left-aligned 12-bit unsigned bus voltage register, right-shifts
     * by 3, and multiplies by the 8 mV LSB.
     *
     * @param channel channel number (1, 2, or 3)
     * @return bus voltage in V
     */
    fun voltage(channel: Int): Double {
        checkChannel(channel)
        val raw = readReg(BUS_BASE[channel])
        return (raw shr 3) * 8e-3
    }

    /**
     * Read the shunt voltage for a channel.
     *
     * Reads the left-aligned 13-bit signed shunt voltage register, right-shifts
     * by 3 (arithmetic), and multiplies by the 40 µV LSB.
     *
     * @param channel channel number (1, 2, or 3)
     * @return shunt voltage in V (may be negative)
     */
    fun shuntVoltage(channel: Int): Double {
        checkChannel(channel)
        val raw = readReg(SHUNT_BASE[channel])
        return (raw.toShort().toInt() shr 3) * 40e-6
    }

    /**
     * Compute the current through the shunt resistor for a channel.
     *
     * Current = shuntVoltage / rShunt[channel].
     *
     * @param channel channel number (1, 2, or 3)
     * @return current in A (may be negative for reverse flow)
     */
    fun current(channel: Int): Double {
        checkChannel(channel)
        return shuntVoltage(channel) / rShunts[channel - 1]
    }

    /**
     * Compute the power consumed on a channel.
     *
     * Power = busVoltage × current.
     *
     * @param channel channel number (1, 2, or 3)
     * @return power in W
     */
    fun power(channel: Int): Double {
        checkChannel(channel)
        return voltage(channel) * current(channel)
    }

    // -------------------------------------------------------------------------
    // Protected helpers (shared with Ina3221Full)
    // -------------------------------------------------------------------------

    /**
     * Read a 16-bit big-endian register.
     *
     * @param reg register address
     * @return raw unsigned 16-bit value
     */
    protected fun readReg(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    /**
     * Write a 16-bit big-endian register.
     *
     * @param reg register address
     * @param val 16-bit value to write
     */
    protected fun writeReg(reg: Int, `val`: Int) {
        transport.write(byteArrayOf(
            reg.toByte(),
            ((`val` shr 8) and 0xFF).toByte(),
            (`val` and 0xFF).toByte()
        ))
    }
}
