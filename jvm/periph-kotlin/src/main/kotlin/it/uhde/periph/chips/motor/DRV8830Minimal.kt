package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import kotlin.math.abs

/**
 * DRV8830 — low-voltage motor driver with I²C interface (Texas Instruments) —
 * minimal interface.
 *
 * H-bridge driver for a single brushed DC motor, controlled entirely over I²C.
 * The host commands a target output *voltage*; the chip PWM-regulates the
 * bridge to hold that average voltage regardless of supply sag. Nine
 * selectable addresses (0x60–0x68) via the tri-state A0/A1 straps.
 *
 * Conversion follows the datasheet's Table 1: `voltage = VREF * vset / 16`,
 * usable 0.48 V (`vset` 6) to 5.06 V (`vset` 63).
 *
 * Construction confirms the device answers on the bus by reading `CONTROL`
 * (no identity register); no register writes are made.
 *
 * @param connection configured I²C connection bound to the device (0x60–0x68)
 */
open class DRV8830Minimal(protected val connection: Connection) {

    companion object {
        /** Default I²C address (A0 = A1 = GND). Valid range 0x60–0x68. */
        const val DEFAULT_ADDRESS = 0x60

        /** Internal reference voltage in V, typical (datasheet: 1.235–1.335 V). */
        const val VREF = 1.285

        /** Lowest valid `VSET` code (0x00–0x05 are reserved). */
        const val VSET_MIN = 6

        /** Highest `VSET` code (≈5.06 V). */
        const val VSET_MAX = 63

        // Register map.
        const val REG_CONTROL = 0x00
        const val REG_FAULT = 0x01

        // CONTROL (0x00) bits.
        const val CTRL_IN1 = 0x01
        const val CTRL_IN2 = 0x02

        // FAULT (0x01) bits.
        const val FAULT_FAULT = 0x01
        const val FAULT_OCP = 0x02
        const val FAULT_UVLO = 0x04
        const val FAULT_OTS = 0x08
        const val FAULT_ILIMIT = 0x10
        const val FAULT_CLEAR = 0x80

        /** Map |voltage| to a `VSET` code; 0 means coast (below the floor). */
        internal fun voltageToVset(voltage: Double): Int {
            val vset = (abs(voltage) * 16.0 / VREF + 0.5).toInt()
            if (vset < VSET_MIN) return 0
            return minOf(vset, VSET_MAX)
        }

        /** `VSET` code to volts; 0 for a reserved code. */
        internal fun vsetToVoltage(vset: Int): Double = if (vset < VSET_MIN) 0.0 else VREF * vset / 16.0
    }

    init {
        readReg(REG_CONTROL)
    }

    protected fun readReg(reg: Int): Int = connection.writeRead(byteArrayOf(reg.toByte()), 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    /**
     * Drive the motor at a regulated output voltage. Writes `VSET` and
     * `IN1`/`IN2` together in one `CONTROL` write; a magnitude below the
     * ~0.48 V floor coasts, above ~5.06 V it is clamped.
     *
     * @param voltage signed target voltage in V: positive = forward, negative = reverse, 0 = coast
     */
    fun drive(voltage: Double) {
        val vset = voltageToVset(voltage)
        when {
            vset == 0 -> writeReg(REG_CONTROL, 0x00)
            voltage > 0 -> writeReg(REG_CONTROL, (vset shl 2) or CTRL_IN1)
            else -> writeReg(REG_CONTROL, (vset shl 2) or CTRL_IN2)
        }
    }

    /** Short-brake the motor (`IN1` = `IN2` = 1, both outputs high). */
    fun brake() {
        writeReg(REG_CONTROL, CTRL_IN1 or CTRL_IN2)
    }

    /** Put the bridge in standby/coast (`IN1` = `IN2` = 0) — same as `drive(0.0)`. */
    fun stop() {
        writeReg(REG_CONTROL, 0x00)
    }
}
