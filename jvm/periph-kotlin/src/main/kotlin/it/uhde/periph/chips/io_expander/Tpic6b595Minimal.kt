package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.OutputPin
import it.uhde.periph.connection.SiPoConnection

/**
 * TPIC6B595 8-bit power SIPO shift register — minimal interface.
 *
 * Drives up to `numDevices` cascaded TPIC6B595s through a SiPo
 * (serial-in/parallel-out) connection. Each device exposes 8 open-drain
 * outputs (DRAIN0–DRAIN7); every write shifts the entire cascade MSB-first
 * and pulses RCK to latch all outputs atomically. Outputs only sink current
 * — they never source it; an external pull-up or load supply is required
 * for the "off"/high state.
 *
 * The driver owns a `numDevices`-byte shadow register so single-pin updates
 * work without re-reading the bus. Every write (pin, port, fill) rebuilds
 * and retransmits the entire reversed cascade — see
 * `specs/io_expander/tpic6b595.md` for the wire-order reversal that
 * cascading requires.
 *
 * Initialises every output to OFF at construction (shadow zero, latched
 * once). If the SiPo connection has SRCLR wired, the constructor pulses
 * it to clear the shift register before the all-zero latch.
 */
open class Tpic6b595Minimal @JvmOverloads constructor(
    protected val connection: SiPoConnection,
    val numDevices: Int = 1,
) {
    companion object {
        const val MAX_DEVICES = 8
    }

    init {
        require(numDevices in 1..MAX_DEVICES) {
            "numDevices must be in [1, $MAX_DEVICES], got $numDevices"
        }
    }

    /** Shadow register — one byte per cascaded device. */
    val shadow: IntArray = IntArray(numDevices)

    init {
        try { connection.clear() } catch (_: IllegalStateException) { /* SRCLR optional */ }
        flush()
    }

    internal fun flush() {
        val wire = ByteArray(numDevices)
        for (i in 0 until numDevices) {
            wire[i] = (shadow[numDevices - 1 - i] and 0xFF).toByte()
        }
        connection.write(wire)
    }

    /**
     * Write all 8 outputs of cascaded device `port` from `mask`.
     *
     * @param port Cascaded device index (0 = nearest the controller).
     * @param mask 8-bit output mask. Bit 0 = DRAIN0, bit 7 = DRAIN7.
     *              1 = ON (DMOS conducting, sinks current); 0 = OFF (high-impedance).
     */
    fun writePort(port: Int, mask: Int) {
        shadow[port] = mask and 0xFF
        flush()
    }

    /** Set every pin on every cascaded device to `value`. */
    fun fill(value: Boolean) {
        val b = if (value) 0xFF else 0x00
        for (i in 0 until numDevices) shadow[i] = b
        flush()
    }

    /** Turn every output off (equivalent to `fill(false)`). */
    fun off() { fill(false) }

    /** Return a [Pin] proxy for global pin `n`. */
    open fun pin(n: Int): Pin = Pin(this, n)

    internal fun setPin(n: Int, high: Boolean) {
        val port = n / 8
        val bit = n % 8
        if (high) shadow[port] = shadow[port] or (1 shl bit)
        else      shadow[port] = shadow[port] and (1 shl bit).inv()
        flush()
    }

    /** GPIO proxy for a single TPIC6B595 pin — output-only. */
    open class Pin(val chip: Tpic6b595Minimal, val n: Int) : OutputPin {
        @Throws(java.io.IOException::class)
        override fun set(high: Boolean) = chip.setPin(n, high)

        /** Set the DMOS output ON (sink current through the external load). */
        fun setHigh() = set(true)

        /** Set the DMOS output OFF (high-impedance). */
        fun setLow() = set(false)

        /** Invert the shadow bit for this pin. */
        fun toggle() {
            val port = n / 8
            val bit = n % 8
            set(((chip.shadow[port] shr bit) and 1) == 0)
        }

        /**
         * Read pin shadow bit (NOT a bus read — SiPo is write-only).
         * @return true if ON, false if OFF.
         */
        fun read(): Boolean {
            val port = n / 8
            val bit = n % 8
            return ((chip.shadow[port] shr bit) and 1) == 1
        }

        @Throws(java.io.IOException::class)
        override fun close() { /* no-op for virtual pins */ }
    }
}
