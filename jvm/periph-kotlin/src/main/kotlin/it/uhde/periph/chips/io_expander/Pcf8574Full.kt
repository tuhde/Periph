package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * PCF8574 full driver — extends [Pcf8574Minimal] with interrupt-on-change support.
 *
 * [onInterrupt] subscribes to the chip's INT line; delivery uses `connection.intPin()`
 * (or an explicit override) if wired, otherwise falls back to a 5 ms polling thread
 * automatically. [pollInterrupt] reads current pin states and returns the bitmask of
 * changed pins. Per-pin [Pin.watch] is also available.
 */
class Pcf8574Full(connection: Connection) : Pcf8574Minimal(connection) {

    /** Port value from the previous read — used to detect changes. */
    private var prev: Int = readPort()

    /** Callback invoked with the changed-pin bitmask on any input change. */
    @Volatile private var callback: ((Int) -> Unit)? = null

    /** INT-line pin currently armed, or `null` if polling. */
    private var intPin: InputPin? = null

    /** `true` while the polling-fallback thread is running. */
    @Volatile private var polling: Boolean = false

    /** Background daemon thread used only when no [InputPin] is available. */
    private var pollThread: Thread? = null

    /** Per-pin watchers, indexed by pin number (0–7). */
    private val watches = arrayOfNulls<PinWatch>(8)

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls — a
     * fresh lambda per call would not be guaranteed by the JLS to evaluate to
     * the same object each time, so offEdge() could silently fail to remove it.
     */
    private val edgeHandler = EdgeHandler { handleEdge() }

    private class PinWatch {
        var handler: ((Pin) -> Unit)? = null
        var trigger: EdgeTrigger = EdgeTrigger.CHANGE
        var lastState: Boolean = false
    }

    /**
     * Return a Full [Pin] proxy for pin [n] (0–7).
     *
     * @param n pin index (0 = P0, 7 = P7)
     * @return Full Pin proxy with [Pin.watch]/[Pin.unwatch] support
     */
    override fun pin(n: Int): Pin = Pin(this, n)

    // -------------------------------------------------------------------------
    // Interrupt API
    // -------------------------------------------------------------------------

    /**
     * Subscribe to INT assertions using `connection.intPin()` (or a 5 ms polling
     * thread if none is wired), unless [intPin] overrides which pin delivers edges.
     *
     * @param callback called with the changed-pin bitmask on any input change
     * @param intPin   INT-line pin to arm, or `null` to force the 5 ms polling fallback
     */
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
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

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                handleEdge()
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "pcf8574-poll").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        try {
            val changed = pollInterrupt()
            if (changed == 0) return
            callback?.invoke(changed)
            for (n in 0 until 8) {
                if (changed and (1 shl n) == 0) continue
                val w = watches[n] ?: continue
                val handler = w.handler ?: continue
                val current = (prev shr n) and 1 == 1
                val rising = current && !w.lastState
                val falling = !current && w.lastState
                w.lastState = current
                val fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && rising) ||
                    (w.trigger == EdgeTrigger.FALLING && falling)
                if (fire) handler(Pin(this, n))
            }
        } catch (e: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read current pin states and return the bitmask of pins that changed since last read.
     *
     * Reading the port also clears the chip's INT output. The previous-read value
     * is updated each time this method is called.
     *
     * @return 8-bit bitmask; bit n = 1 if pin n changed since the previous read
     */
    fun pollInterrupt(): Int {
        val current = readPort()
        val changed = current xor prev
        prev = current
        return changed
    }

    // =========================================================================
    // Pin — extends Pcf8574Minimal.Pin with watch/unwatch support
    // =========================================================================

    /** GPIO proxy for a single PCF8574 pin — full interface, adds [watch]/[unwatch]. */
    class Pin(private val full: Pcf8574Full, n: Int) : Pcf8574Minimal.Pin(full, n) {

        /**
         * Subscribe to this pin's edge events.
         *
         * At most one handler per pin at a time; a second call replaces the
         * first. Call [Pcf8574Full.onInterrupt] first to arm delivery.
         *
         * @param handler called with this pin when its state matches [trigger]
         * @param trigger which edge(s) to fire on (default: [EdgeTrigger.CHANGE])
         */
        fun watch(handler: (Pin) -> Unit, trigger: EdgeTrigger = EdgeTrigger.CHANGE) {
            val w = PinWatch()
            w.handler = handler
            w.trigger = trigger
            w.lastState = (full.prev shr n and 1) == 1
            full.watches[n] = w
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        fun unwatch() {
            full.watches[n] = null
        }
    }
}
