package it.uhde.periph.chips.io_expander

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MCP23017 full interface — extends [Mcp23017Minimal] with pull-up
 * configuration, polarity inversion, and interrupt support.
 *
 * Interrupt delivery uses a background polling thread when no hardware
 * INT line is available.
 *
 * @param transport I²C transport bound to the device address
 * @param addr      7-bit I²C address
 */
open class Mcp23017Full(transport: it.uhde.periph.transport.Transport, addr: Int = 0x20)
    : Mcp23017Minimal(transport, addr) {

    private val prev = intArrayOf(0, 0)
    private var callback: ((Int, Int) -> Unit)? = null
    private var pollThread: Thread? = null
    private volatile var running = false

    private val REG_GPPUA   = 0x0C
    private val REG_GPPUB   = 0x0D
    private val REG_INTFA   = 0x0E
    private val REG_INTFB   = 0x0F
    private val REG_INTCAPA = 0x10
    private val REG_INTCAPB = 0x11

    /**
     * Return a [Pin] proxy for pin [n] (0–15).
     *
     * The returned Pin also supports [Pin.watch] for interrupt-driven change notification.
     *
     * @param n pin index (0–15)
     * @return Pin proxy backed by this driver
     */
    override fun pin(n: Int): Pin = Pin(this, n)

    /**
     * Enable/disable per-pin 100 kΩ pull-ups on a port.
     *
     * Pull-ups are only electrically active on pins configured as inputs.
     *
     * @param port 0 = PORTA (GPPUA), 1 = PORTB (GPPUB)
     * @param mask 8-bit mask; bit n = 1 enables pull-up on pin n
     */
    fun configurePullup(port: Int, mask: Int) {
        writeReg(REG_GPPUA + port, mask and 0xFF)
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param port 0 = PORTA (IPOLA), 1 = PORTB (IPOLB)
     * @param mask 8-bit mask; bit n = 1 inverts the GPIO read for pin n
     */
    fun configurePolarity(port: Int, mask: Int) {
        writeReg(0x02 + port, mask and 0xFF)
    }

    /**
     * Attach an interrupt callback to a port's INT line.
     *
     * @param port     0 = PORTA (INTA), 1 = PORTB (INTB)
     * @param callback called with (port, changedMask) on any input change
     */
    fun configureInterrupt(port: Int, callback: (Int, Int) -> Unit) {
        this.callback = callback
        this.running  = true
        try {
            writeReg(0x04 + port, 0xFF)
            writeReg(0x08 + port, 0x00)
        } catch (_: IOException) { }
        startPolling()
    }

    private fun startPolling() {
        pollThread = Thread {
            while (running) {
                try {
                    val changedA = clearInterrupt(0)
                    val changedB = clearInterrupt(1)
                    callback?.let {
                        if (changedA != 0) it(0, changedA)
                        if (changedB != 0) it(1, changedB)
                    }
                } catch (_: IOException) { }
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }
        pollThread?.start()
    }

    /**
     * Read and clear the interrupt for a port, returning the changed-pin bitmask.
     *
     * @param port 0 = PORTA, 1 = PORTB
     * @return 8-bit changed-pin bitmask for the port
     */
    @Throws(IOException::class)
    fun clearInterrupt(port: Int): Int {
        val captured = readReg(REG_INTCAPA + port)
        val current  = readReg(REG_GPIOA   + port)
        val changed  = (current xor prev[port]) and 0xFF
        prev[port]   = current
        return changed
    }

    /**
     * Read interrupt flags without clearing the interrupt.
     *
     * @param port 0 = INTFA, 1 = INTFB
     * @return 8-bit interrupt-flag bitmask
     */
    @Throws(IOException::class)
    fun readInterruptFlags(port: Int): Int = readReg(REG_INTFA + port)

    /**
     * Disable interrupt generation for a port and stop the polling thread.
     *
     * @param port 0 = PORTA, 1 = PORTB
     */
    fun stopInterrupt(port: Int) {
        running = false
        try { writeReg(0x04 + port, 0x00) } catch (_: IOException) { }
        pollThread?.interrupt()
        pollThread = null
    }

    // =========================================================================
    // Pin — extends Mcp23017Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single MCP23017 pin — full interface.
     *
     * Extends the minimal Pin with pull-up control and per-pin change watchers.
     */
    open class Pin(chip: Mcp23017Full, n: Int) : Mcp23017Minimal.Pin(chip, n) {

        private val watchers = CopyOnWriteArrayList<(Int) -> Unit>()

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
            val reg  = REG_GPPUA + port
            val cur  = chip.readReg(reg)
            chip.writeReg(reg, if (pullUp) cur or (1 shl bit) else cur and (1 shl bit).inv())
        }

        /**
         * Register a handler for pin state changes.
         *
         * Requires [Mcp23017Full.configureInterrupt] to have been called.
         *
         * @param handler called with the new pin value on change
         */
        fun watch(handler: (Int) -> Unit) {
            watchers.add(handler)
        }

        /**
         * Remove a previously registered handler.
         *
         * @param handler the handler to remove
         */
        fun unwatch(handler: (Int) -> Unit) {
            watchers.remove(handler)
        }

        /** Remove all registered handlers for this pin. */
        fun unwatchAll() { watchers.clear() }
    }
}