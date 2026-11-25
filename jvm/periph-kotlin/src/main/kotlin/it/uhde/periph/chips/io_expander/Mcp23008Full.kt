package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * Shared Full-stage implementation of the MCP23008 / MCP23S08 drivers: pull-ups,
 * polarity inversion, interrupt-on-change and raw register access. Use
 * [Mcp23008Full] (I²C) or [Mcp23S08Full] (SPI).
 *
 * Interrupt delivery uses the [InputPin] given to [onInterrupt] (or
 * `connection.intPin()`); without one a 5 ms polling thread is used. Per-pin
 * [Pin.watch] arms the pin in GPINTEN and starts delivery on first use.
 */
open class Mcp23x08Full(bus: Bus, connection: Connection) : Mcp23x08Minimal(bus, connection) {

    private var pullup = 0x00
    private var gpinten = 0x00
    private var iocon = bus.baseIocon
    private var activeHigh = false

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private var active = false
    private val watches = arrayOfNulls<PinWatch>(8)

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls so
     * offEdge() can remove the exact instance that was registered.
     */
    private val edgeHandler = EdgeHandler { handleEdge() }

    private class PinWatch(val handler: (Pin) -> Unit, val trigger: EdgeTrigger)

    /**
     * Return a Full [Pin] proxy for pin [n] (0–7).
     *
     * @param n pin index (0–7)
     * @return Pin proxy with pull-up and [Pin.watch]/[Pin.unwatch] support
     */
    override fun pin(n: Int): Pin = Pin(this, n and 7)

    /**
     * Enable/disable per-pin 100 kΩ pull-ups (GPPU ← mask). Only effective on inputs.
     *
     * @param mask 8-bit mask; bit n = 1 enables the pull-up on GPn
     */
    fun configurePullup(mask: Int) {
        pullup = mask and 0xFF
        bus.write(REG_GPPU, pullup)
    }

    /**
     * Configure input polarity inversion (IPOL ← mask).
     *
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read of GPn
     */
    fun configurePolarity(mask: Int) = bus.write(REG_IPOL, mask and 0xFF)

    /**
     * Set DEFVAL for default-compare interrupts.
     *
     * @param mask 8-bit default compare value
     */
    fun setDefaultValue(mask: Int) = bus.write(REG_DEFVAL, mask and 0xFF)

    /**
     * Arm interrupt-on-change pins (GPINTEN ← mask). Only inputs can interrupt.
     *
     * @param mask 8-bit mask; bit n = 1 arms GPn
     */
    fun enablePinInterrupt(mask: Int) {
        gpinten = mask and 0xFF
        bus.write(REG_GPINTEN, gpinten)
    }

    /**
     * Configure the INT output (IOCON ODR / INTPOL).
     *
     * @param openDrain  `true` for an open-drain INT (overrides [activeHigh])
     * @param activeHigh `true` for active-high when push-pull
     */
    fun configureIntPin(openDrain: Boolean = false, activeHigh: Boolean = false) {
        this.activeHigh = activeHigh && !openDrain
        iocon = iocon and (IOCON_ODR or IOCON_INTPOL).inv()
        if (openDrain) iocon = iocon or IOCON_ODR else if (activeHigh) iocon = iocon or IOCON_INTPOL
        bus.write(REG_IOCON, iocon)
    }

    /**
     * Enable or disable sequential register access (IOCON SEQOP). The driver
     * itself never relies on a value other than enabled.
     *
     * @param enabled `true` (default, auto-increment) or `false`
     */
    fun setSequential(enabled: Boolean) {
        iocon = iocon and IOCON_SEQOP.inv()
        if (!enabled) iocon = iocon or IOCON_SEQOP
        bus.write(REG_IOCON, iocon)
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0x00–0x0A
     */
    fun readRegister(reg: Int): Int = bus.read(reg)

    /**
     * Raw register write; the IODIR / OLAT / IOCON / GPPU / GPINTEN shadows are kept in sync.
     *
     * @param reg   register address 0x00–0x0A
     * @param value 8-bit value
     */
    fun writeRegister(reg: Int, value: Int) {
        val v = value and 0xFF
        when (reg) {
            REG_IODIR   -> direction = v
            REG_OLAT    -> shadow = v
            REG_IOCON   -> iocon = v
            REG_GPPU    -> pullup = v
            REG_GPINTEN -> gpinten = v
        }
        bus.write(reg, v)
    }

    /**
     * Rewrite every register to its reset value and clear the shadows. The chip
     * has no reset command (RESET is an external pin). On the MCP23S08 IOCON is
     * rewritten to HAEN (not 0) so addressed access keeps working.
     */
    fun reset() {
        offInterrupt()
        for (reg in intArrayOf(REG_IPOL, REG_GPINTEN, REG_DEFVAL, REG_INTCON, REG_GPPU, REG_OLAT)) {
            bus.write(reg, 0x00)
        }
        bus.write(REG_IODIR, 0xFF)
        iocon = bus.baseIocon
        bus.write(REG_IOCON, iocon)
        shadow = 0x00
        direction = 0xFF
        pullup = 0x00
        gpinten = 0x00
        activeHigh = false
    }

    /**
     * Subscribe to INT assertions. Every pin armed via [enablePinInterrupt] or
     * [Pin.watch] is used. Falls back to a 5 ms polling thread if no [InputPin]
     * is available.
     *
     * @param callback         called with the INTF mask of the pins that caused the interrupt
     * @param intPin           overrides `connection.intPin()`, or `null`
     * @param compareToDefault `true` to compare against DEFVAL (INTCON = armed pins),
     *                         `false` to compare against the previous pin value
     * @throws IllegalStateException if no pin has been armed
     */
    @Synchronized
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = null, compareToDefault: Boolean = false) {
        check(gpinten != 0) { "no pins armed: call enablePinInterrupt() first" }
        this.callback = callback
        bus.write(REG_INTCON, if (compareToDefault) gpinten else 0x00)
        startDelivery(intPin ?: connection.intPin())
    }

    private fun startDelivery(pin: InputPin?) {
        stopDelivery()
        active = true
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, if (activeHigh) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
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
            }, "mcp23x08-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun stopDelivery() {
        intPinUsed?.offEdge(edgeHandler)
        intPinUsed = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
        active = false
    }

    /** Unsubscribe and stop delivery (GPINTEN is left untouched). */
    @Synchronized
    fun offInterrupt() {
        stopDelivery()
        callback = null
    }

    private fun handleEdge() {
        try {
            val status = bus.read(REG_INTF)
            if (status == 0) return
            val captured = bus.read(REG_INTCAP) // clears the interrupt
            callback?.invoke(status)
            for (n in 0 until 8) {
                if (status and (1 shl n) == 0) continue
                val w = watches[n] ?: continue
                val high = ((captured shr n) and 1) == 1
                val fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && high) ||
                    (w.trigger == EdgeTrigger.FALLING && !high)
                if (fire) w.handler(Pin(this, n))
            }
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }

    /**
     * Read INTF, then INTCAP (clearing the interrupt). The captured port value
     * is discarded — use [readCapture].
     *
     * @return INTF mask; bit n = 1 if GPn caused the interrupt
     */
    fun pollInterrupt(): Int {
        val flags = bus.read(REG_INTF)
        bus.read(REG_INTCAP)
        return flags
    }

    /**
     * Read INTCAP: the port state captured at the interrupt. Also clears the interrupt.
     *
     * @return 8-bit captured port value
     */
    fun readCapture(): Int = bus.read(REG_INTCAP)

    // =========================================================================
    // Pin — extends Mcp23x08Minimal.Pin with pull-up and watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds pull-up control and
     * per-pin change watchers.
     */
    class Pin(private val full: Mcp23x08Full, n: Int) : Mcp23x08Minimal.Pin(full, n) {

        /**
         * Set or clear the pull-up for this pin (its GPPU bit). Only
         * electrically active when the pin is an input.
         *
         * @param pullUp `true` to enable the 100 kΩ pull-up
         */
        fun setPullUp(pullUp: Boolean) {
            val mask = if (pullUp) full.pullup or (1 shl n) else full.pullup and (1 shl n).inv()
            full.configurePullup(mask)
        }

        /**
         * Subscribe to this pin's changes. Arms the pin in GPINTEN and starts
         * delivery (INT line or 5 ms polling) if none is running. The chip
         * reports changes only; the rising/falling filter is applied in
         * software. At most one handler per pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches [trigger]
         * @param trigger which edge(s) to fire on
         * @throws IllegalStateException if the pin is an output (outputs never raise INT)
         */
        fun watch(handler: (Pin) -> Unit, trigger: EdgeTrigger = EdgeTrigger.CHANGE) {
            check((full.direction shr n) and 1 == 1) { "cannot watch an output pin" }
            full.watches[n] = PinWatch(handler, trigger)
            full.enablePinInterrupt(full.gpinten or (1 shl n))
            synchronized(full) {
                if (!full.active) {
                    full.callback = null
                    full.startDelivery(full.connection.intPin())
                }
            }
        }

        /** Unsubscribe this pin's handler and clear its GPINTEN bit. No-op if not registered. */
        fun unwatch() {
            full.watches[n] = null
            full.enablePinInterrupt(full.gpinten and (1 shl n).inv())
        }
    }
}

/**
 * MCP23008 full interface (I²C) — pull-ups, polarity, interrupt-on-change and
 * raw register access on top of the minimal GPIO API.
 *
 * @param connection I²C register connection bound to the device address
 * @param addr       7-bit I²C address (`0x20`–`0x27`, default `0x20`)
 */
class Mcp23008Full(connection: RegisterConnection, val addr: Int = 0x20) :
    Mcp23x08Full(Mcp23008I2cBus(connection), connection)

/**
 * MCP23S08 full interface (SPI) — pull-ups, polarity, interrupt-on-change and
 * raw register access on top of the minimal GPIO API.
 *
 * @param connection SPI connection (mode 0, MSB first, CS active low)
 * @param addr       hardware device address 0–3 (A1:A0 pins)
 */
class Mcp23S08Full(connection: Connection, val addr: Int = 0) :
    Mcp23x08Full(Mcp23S08SpiBus(connection, addr), connection)
