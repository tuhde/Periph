package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * PCF8575 full driver — extends [Pcf8575Minimal] with interrupt-on-change support.
 *
 * [onInterrupt] subscribes to the chip's INT line; delivery uses `connection.intPin()`
 * (or an explicit override) if wired, otherwise falls back to a 5 ms polling thread
 * automatically. [pollInterrupt] reads current pin states and returns the 16-bit
 * bitmask of changed pins (bits 0–7 = Port 0, bits 8–15 = Port 1). Per-pin
 * [Pin.watch] is also available.
 */
class Pcf8575Full(connection: Connection) : Pcf8575Minimal(connection) {

    private var prev = intArrayOf(0xFF, 0xFF)

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPin: InputPin? = null
    @Volatile private var polling: Boolean = false
    private var pollThread: Thread? = null
    private val watches = arrayOfNulls<PinWatch>(16)

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls — a
     * fresh lambda per call would not be guaranteed by the JLS to evaluate to
     * the same object each time.
     */
    private val edgeHandler = EdgeHandler { handleEdge() }

    private class PinWatch {
        var handler: ((Pin) -> Unit)? = null
        var trigger: EdgeTrigger = EdgeTrigger.CHANGE
        var lastState: Boolean = false
    }

    init {
        val buf = connection.read(2)
        prev[0] = buf[0].toInt() and 0xFF
        prev[1] = buf[1].toInt() and 0xFF
    }

    override fun pin(n: Int): Pin = Pin(this, n)

    /**
     * Subscribe to INT assertions using `connection.intPin()` (or a 5 ms polling
     * thread if none is wired), unless [intPin] overrides which pin delivers edges.
     *
     * @param callback called with the 16-bit changed-pin bitmask on any input change
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
        }, "pcf8575-poll").also {
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
            for (n in 0 until 16) {
                if (changed and (1 shl n) == 0) continue
                val w = watches[n] ?: continue
                val handler = w.handler ?: continue
                val port = n / 8
                val bit = n % 8
                val current = (prev[port] shr bit) and 1 == 1
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
     * Read current pin states and return the 16-bit bitmask of pins that changed
     * since last read. Also clears the chip's INT output.
     *
     * @return 16-bit bitmask; bits 0–7 = Port 0 changed, bits 8–15 = Port 1 changed
     */
    fun pollInterrupt(): Int {
        val current = connection.read(2)
        val ch0 = (current[0].toInt() xor prev[0]) and 0xFF
        val ch1 = (current[1].toInt() xor prev[1]) and 0xFF
        prev[0] = current[0].toInt() and 0xFF
        prev[1] = current[1].toInt() and 0xFF
        return ch0 or (ch1 shl 8)
    }

    /** GPIO proxy for a single PCF8575 pin — full interface, adds [watch]/[unwatch]. */
    class Pin(private val full: Pcf8575Full, n: Int) : Pcf8575Minimal.Pin(full, n) {

        /**
         * Subscribe to this pin's edge events.
         *
         * At most one handler per pin at a time; a second call replaces the
         * first. Call [Pcf8575Full.onInterrupt] first to arm delivery.
         *
         * @param handler called with this pin when its state matches [trigger]
         * @param trigger which edge(s) to fire on (default: [EdgeTrigger.CHANGE])
         */
        fun watch(handler: (Pin) -> Unit, trigger: EdgeTrigger = EdgeTrigger.CHANGE) {
            val w = PinWatch()
            w.handler = handler
            w.trigger = trigger
            val port = n / 8
            val bit = n % 8
            w.lastState = ((full.prev[port] shr bit) and 1) == 1
            full.watches[n] = w
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        fun unwatch() {
            full.watches[n] = null
        }
    }
}
