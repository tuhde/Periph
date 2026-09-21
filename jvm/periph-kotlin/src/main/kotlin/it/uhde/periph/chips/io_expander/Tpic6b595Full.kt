package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.SiPoConnection

/**
 * TPIC6B595 full driver — extends [Tpic6b595Minimal] with hardware features.
 *
 * Adds `clear()`, `setOutputEnable()`, and `writeAll()` for the
 * shift-register-clear and output-enable hardware lines, plus a bulk
 * multi-device write. The [Pin] API surface stays exactly Minimal's
 * — every TPIC6B595 pin is a fixed, capability-less output.
 */
class Tpic6b595Full @JvmOverloads constructor(
    connection: SiPoConnection,
    numDevices: Int = 1,
) : Tpic6b595Minimal(connection, numDevices) {

    /** Return a Full [Pin] proxy for global pin `n`. */
    override fun pin(n: Int): Pin = Pin(this, n)

    /**
     * Pulse SRCLR to clear the shift register only.
     *
     * The storage register (and therefore the DRAIN outputs) keeps its
     * last-latched value until the next RCK pulse.
     */
    fun clear() { connection.clear() }

    /**
     * Drive G LOW (`enabled = true`) or HIGH (`enabled = false`).
     */
    fun setOutputEnable(enabled: Boolean) { connection.setOutputEnable(enabled) }

    /**
     * Write every cascaded device's byte in one call.
     *
     * `values` is length-truncated or zero-extended to `numDevices` as needed.
     */
    fun writeAll(values: IntArray) {
        for (i in 0 until numDevices) {
            val v = if (i < values.size) values[i] else 0
            shadow[i] = v and 0xFF
        }
        flush()
    }

    /** Full GPIO proxy for a single TPIC6B595 pin. */
    class Pin(chip: Tpic6b595Full, n: Int) : Tpic6b595Minimal.Pin(chip, n)
}
