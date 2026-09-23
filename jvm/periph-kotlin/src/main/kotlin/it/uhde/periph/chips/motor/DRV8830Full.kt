package it.uhde.periph.chips.motor

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * DRV8830 full interface — extends [DRV8830Minimal] with raw `CONTROL` access,
 * output read-back, fault reporting/clearing, and the `FAULTn` interrupt API.
 *
 * Faults are never cleared implicitly: a latched OCP/ILIMIT fault also
 * disables the H-bridge, so clearing is always an explicit [clearFault].
 * [onInterrupt] uses `connection.intPin()` if wired, otherwise a 5 ms polling
 * thread that fires once per new fault.
 *
 * @param connection configured I²C connection bound to the device (0x60–0x68)
 */
class DRV8830Full(connection: Connection) : DRV8830Minimal(connection) {

    /** H-bridge state decoded from `IN1`/`IN2`. */
    enum class Direction {
        /** IN1=0, IN2=0 — outputs high-Z (standby). */
        COAST,
        /** IN1=1, IN2=0. */
        FORWARD,
        /** IN1=0, IN2=1. */
        REVERSE,
        /** IN1=1, IN2=1 — both outputs high. */
        BRAKE,
    }

    /**
     * Decoded `CONTROL` register.
     *
     * @property voltage commanded magnitude in V (0 for a reserved `VSET` code)
     * @property direction H-bridge state
     */
    data class Output(val voltage: Double, val direction: Direction)

    /**
     * Decoded `FAULT` register.
     *
     * @property fault any fault condition exists
     * @property ocp overcurrent (short-circuit) event
     * @property uvlo undervoltage lockout
     * @property ots overtemperature shutdown
     * @property ilimit extended current-limit event
     */
    data class Fault(val fault: Boolean, val ocp: Boolean, val uvlo: Boolean, val ots: Boolean, val ilimit: Boolean)

    @Volatile private var callback: ((Fault) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling: Boolean = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /**
     * Write the `CONTROL` register from raw fields.
     *
     * @param vset `VSET` DAC code, 6–63 (0–5 are reserved)
     * @param in1 H-bridge input 1
     * @param in2 H-bridge input 2
     * @throws IllegalArgumentException if [vset] is outside 6–63
     */
    fun setOutput(vset: Int, in1: Boolean, in2: Boolean) {
        require(vset in VSET_MIN..VSET_MAX) { "vset must be 6-63, got $vset" }
        writeReg(REG_CONTROL, (vset shl 2) or (if (in1) CTRL_IN1 else 0) or (if (in2) CTRL_IN2 else 0))
    }

    /** Read back and decode the `CONTROL` register. */
    fun readOutput(): Output {
        val ctrl = readReg(REG_CONTROL)
        return Output(vsetToVoltage(ctrl shr 2), Direction.entries[ctrl and 0x03])
    }

    /** Read the `FAULT` register without clearing it. */
    fun readFault(): Fault {
        val f = readReg(REG_FAULT)
        return Fault(
            (f and FAULT_FAULT) != 0, (f and FAULT_OCP) != 0, (f and FAULT_UVLO) != 0,
            (f and FAULT_OTS) != 0, (f and FAULT_ILIMIT) != 0,
        )
    }

    /** Clear all fault status bits (`CLEAR` = 1); re-enables the H-bridge if an OCP/ILIMIT fault had latched it off. */
    fun clearFault() {
        writeReg(REG_FAULT, FAULT_CLEAR)
    }

    // -------------------------------------------------------------------------
    // Interrupt API (Level 1 — FAULTn)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to fault interrupts. The fault is not cleared.
     *
     * @param callback called with the [readFault] result on each new fault
     * @param intPin FAULTn pin to arm (falling edge), or `null` to force the 5 ms polling fallback
     */
    fun onInterrupt(callback: (Fault) -> Unit, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            this.intPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        intPin?.offEdge(edgeHandler)
        intPin = null
        stopPolling()
    }

    /** Read the fault status — equivalent to [readFault], does not clear. */
    fun pollInterrupt(): Fault = readFault()

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            var wasFault = false
            while (polling) {
                try {
                    val status = pollInterrupt()
                    if (status.fault && !wasFault) callback?.invoke(status)
                    wasFault = status.fault
                } catch (_: IOException) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "drv8830-poll").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if (status.fault) callback?.invoke(status)
        } catch (_: IOException) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
