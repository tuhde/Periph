package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException

/**
 * MCP23017 full interface — extends [Mcp23017Minimal] with pull-up
 * configuration, polarity inversion, and interrupt support.
 *
 * Level 3: the chip has two independent INT lines (INTA/INTB), one per
 * port. [onInterrupt]/[offInterrupt] subscribe/unsubscribe both ports at once
 * (delivered via one shared [InputPin] — wire IOCON.MIRROR or external wiring
 * so a single physical line carries both); [onInterruptPort]/[offInterruptPort]
 * target one port with its own [InputPin]. [pollInterrupt] reads and clears
 * INTF; [readCapture] reads INTCAP (the latched pin state) separately.
 * Per-pin [Pin.watch] is also available.
 *
 * @param connection I²C connection bound to the device address
 * @param addr       7-bit I²C address
 */
class Mcp23017Full(connection: Connection, addr: Int = 0x20) : Mcp23017Minimal(connection, addr) {

    companion object {
        private const val REG_GPINTENA = 0x04
        private const val REG_GPINTENB = 0x05
        private const val REG_DEFVALA  = 0x06
        private const val REG_DEFVALB  = 0x07
        private const val REG_INTCONA  = 0x08
        private const val REG_INTCONB  = 0x09
        private const val REG_IOCON    = 0x0A
        private const val REG_INTFA    = 0x0E
        private const val REG_INTFB    = 0x0F
        private const val REG_INTCAPA  = 0x10
        private const val REG_INTCAPB  = 0x11
    }

    @Volatile private var callbackBoth: ((Int, Int) -> Unit)? = null
    private val callbackPort = arrayOfNulls<(Int) -> Unit>(2)
    private val intPinUsed = arrayOfNulls<InputPin>(2)
    private val pollThread = arrayOfNulls<Thread>(2)
    private val polling = BooleanArray(2)
    private val watches = Array(2) { arrayOfNulls<PinWatch>(8) }

    /**
     * Fixed per-port edge handler instances, reused across onEdge()/offEdge()
     * calls — a fresh lambda per armPort() call would not be guaranteed by
     * the JLS to equal the one passed to onEdge() earlier, so offEdge()
     * could silently fail to remove it.
     */
    private val edgeHandlers = arrayOf(
        EdgeHandler { handleEdge(0) },
        EdgeHandler { handleEdge(1) },
    )

    private class PinWatch {
        var handler: ((Pin) -> Unit)? = null
        var trigger: EdgeTrigger = EdgeTrigger.CHANGE
        var lastState: Boolean = false
    }

    /**
     * Return a Full [Pin] proxy for pin [n] (0–15).
     *
     * @param n pin index (0–15)
     * @return Pin proxy with [Pin.watch]/[Pin.unwatch] support
     */
    override fun pin(n: Int): Pin = Pin(this, n)

    /**
     * Enable/disable per-pin 100 kΩ pull-ups on a port.
     *
     * Pull-ups are only electrically active on pins configured as inputs.
     * Enabling them on output pins has no hardware effect.
     *
     * @param port 0 = PORTA (GPPUA), 1 = PORTB (GPPUB)
     * @param mask 8-bit mask; bit n = 1 enables pull-up on pin n
     */
    fun configurePullup(port: Int, mask: Int) {
        writeReg(Mcp23017Minimal.REG_GPPUA + (port and 1), mask and 0xFF)
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param port 0 = PORTA (IPOLA), 1 = PORTB (IPOLB)
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read for pin n
     */
    fun configurePolarity(port: Int, mask: Int) {
        writeReg(Mcp23017Minimal.REG_IPOLA + (port and 1), mask and 0xFF)
    }

    /**
     * Set DEFVAL register for default-compare interrupt mode.
     *
     * @param port 0 = PORTA (DEFVALA), 1 = PORTB (DEFVALB)
     * @param mask 8-bit default compare value
     */
    fun setDefaultValue(port: Int, mask: Int) {
        writeReg(REG_DEFVALA + (port and 1), mask and 0xFF)
    }

    private fun armPort(port: Int, intPin: InputPin?) {
        val p = port and 1
        writeReg(REG_INTCONA + p, 0x00) // interrupt-on-change mode
        writeReg(REG_GPINTENA + p, 0xFF)
        intPinUsed[p] = intPin
        stopPolling(p)
        if (intPin != null) {
            intPin.onEdge(edgeHandlers[p], EdgeTrigger.FALLING)
        } else {
            startPolling(p)
        }
    }

    /**
     * Subscribe to INT assertions on both ports.
     *
     * The common case: both INT lines share one physical GPIO, either via
     * IOCON.MIRROR (`mirror = true`) or external wiring. Wires [intPin]
     * (or `connection.intPin()` if `null`) to poll both ports on every edge.
     * Falls back to two independent 5 ms polling threads if no [InputPin]
     * is available.
     *
     * @param callback called with (port, status) on any input change
     * @param intPin   overrides `connection.intPin()`, or `null`
     * @param mirror   set IOCON.MIRROR so either port's interrupt activates both INTA and INTB
     */
    fun onInterrupt(callback: (Int, Int) -> Unit, intPin: InputPin? = null, mirror: Boolean = false) {
        this.callbackBoth = callback
        val iocon = readReg(REG_IOCON)
        writeReg(REG_IOCON, if (mirror) (iocon or (1 shl 6)) else iocon)
        val pin = intPin ?: connection.intPin()
        armPort(0, pin)
        armPort(1, pin)
    }

    /**
     * Subscribe to INT assertions on a single port.
     *
     * Use this (with a dedicated [InputPin] per call) when INTA and INTB
     * are wired to two separate GPIOs.
     *
     * @param port     0 = PORTA (INTA), 1 = PORTB (INTB)
     * @param callback called with the port's INTF flag mask
     * @param intPin   overrides `connection.intPin()`, or `null`
     */
    fun onInterruptPort(port: Int, callback: (Int) -> Unit, intPin: InputPin? = connection.intPin()) {
        val p = port and 1
        callbackPort[p] = callback
        armPort(p, intPin)
    }

    /** Unsubscribe and stop delivery on both ports. */
    fun offInterrupt() {
        offInterruptPort(0)
        offInterruptPort(1)
        callbackBoth = null
    }

    /**
     * Unsubscribe and stop delivery on a single port.
     *
     * @param port 0 = PORTA, 1 = PORTB
     */
    fun offInterruptPort(port: Int) {
        val p = port and 1
        writeReg(REG_GPINTENA + p, 0x00)
        intPinUsed[p]?.offEdge(edgeHandlers[p])
        intPinUsed[p] = null
        stopPolling(p)
        callbackPort[p] = null
    }

    private fun startPolling(port: Int) {
        polling[port] = true
        pollThread[port] = Thread({
            while (polling[port]) {
                handleEdge(port)
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "mcp23017-poll-$port").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling(port: Int) {
        polling[port] = false
        pollThread[port]?.interrupt()
        pollThread[port] = null
    }

    private fun handleEdge(port: Int) {
        try {
            val status = pollInterrupt(port)
            if (status == 0) return
            callbackBoth?.invoke(port, status)
            callbackPort[port]?.invoke(status)

            val raw = readPort(port)
            for (bit in 0 until 8) {
                if (status and (1 shl bit) == 0) continue
                val w = watches[port][bit] ?: continue
                val handler = w.handler ?: continue
                val current = (raw shr bit) and 1 == 1
                val rising = current && !w.lastState
                val falling = !current && w.lastState
                w.lastState = current
                val fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && rising) ||
                    (w.trigger == EdgeTrigger.FALLING && falling)
                if (fire) handler(Pin(this, port * 8 + bit))
            }
        } catch (e: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read &amp; clear interrupt status; returns the raw INTF flag register.
     *
     * Reads INTFA/INTFB (which pins triggered) then INTCAPA/INTCAPB (which
     * clears the interrupt latch and re-arms it). Note that reading either
     * INTCAP or GPIO clears the interrupt condition on this chip — call
     * [pollInterrupt] or [readCapture] per event, not both.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit interrupt flag mask
     */
    fun pollInterrupt(port: Int): Int {
        val p = port and 1
        val flags = readReg(REG_INTFA + p)
        readReg(REG_INTCAPA + p) // clears and re-arms the interrupt; value discarded
        return flags
    }

    /**
     * Read INTCAP: the port state latched at the moment of the interrupt.
     *
     * Also clears the interrupt latch (see [pollInterrupt] doc) — call this
     * instead of [pollInterrupt] when the captured pin state is wanted rather
     * than the flag register.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit captured port bitmask at the moment of interrupt
     */
    fun readCapture(port: Int): Int = readReg(REG_INTCAPA + (port and 1))

    // =========================================================================
    // Pin — extends Mcp23017Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin — full interface.
     *
     * Extends the minimal Pin with pull-up control and per-pin change watchers.
     */
    class Pin(private val full: Mcp23017Full, n: Int) : Mcp23017Minimal.Pin(full, n) {

        /**
         * Set or clear the pull-up for this pin.
         *
         * Only electrically active when the pin is configured as input.
         *
         * @param pullUp `true` to enable 100 kΩ pull-up; `false` to disable
         */
        fun setPullUp(pullUp: Boolean) {
            val port = n shr 3
            val bit  = n and 7
            val reg  = Mcp23017Minimal.REG_GPPUA + port
            val cur  = full.readReg(reg)
            full.writeReg(reg, if (pullUp) cur or (1 shl bit) else cur and (1 shl bit).inv())
        }

        /**
         * Subscribe to this pin's edge events.
         *
         * At most one handler per pin at a time; a second call replaces the
         * first. Call the chip's [Mcp23017Full.onInterrupt]/[Mcp23017Full.onInterruptPort]
         * (for this pin's port) first to arm delivery.
         *
         * @param handler called with this pin when its state matches [trigger]
         * @param trigger which edge(s) to fire on (default: [EdgeTrigger.CHANGE])
         */
        fun watch(handler: (Pin) -> Unit, trigger: EdgeTrigger = EdgeTrigger.CHANGE) {
            val port = n shr 3
            val bit  = n and 7
            val w = PinWatch()
            w.handler = handler
            w.trigger = trigger
            w.lastState = ((full.readPort(port) shr bit) and 1) == 1
            full.watches[port][bit] = w
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        fun unwatch() {
            full.watches[n shr 3][n and 7] = null
        }
    }
}
