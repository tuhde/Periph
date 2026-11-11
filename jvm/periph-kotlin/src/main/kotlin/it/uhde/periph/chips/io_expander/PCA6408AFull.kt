package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * PCA6408A full interface — polarity inversion, whole-port direction,
 * change-based interrupts and raw register access on top of the minimal GPIO API.
 *
 * Interrupt delivery uses the [InputPin] given to [onInterrupt] (or
 * `connection.intPin()`, falling edge); without one a 5 ms polling thread is
 * used. Per-pin [Pin.watch] starts delivery on first use. The chip has no
 * interrupt-status register: the driver derives the changed mask by comparing
 * INPUT reads.
 *
 * @param connection I²C or SMBus (no PEC) register connection bound to the device address
 * @param addr       7-bit I²C address (`0x20` or `0x21`, default `0x20`)
 */
class PCA6408AFull(connection: RegisterConnection, addr: Int = 0x20) : PCA6408AMinimal(connection, addr) {

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private var active = false
    @Volatile private var level = lastInput
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
     * @return Pin proxy with [Pin.watch]/[Pin.unwatch] support
     */
    override fun pin(n: Int): Pin = Pin(this, n and 7)

    /**
     * Configure input polarity inversion (POLARITY ← mask).
     *
     * @param mask 8-bit mask; bit n = 1 inverts the INPUT bit of Pn
     */
    fun configurePolarity(mask: Int) {
        polarity = mask and 0xFF
        writeReg(REG_POLARITY, polarity)
        lastInput = readReg(REG_INPUT)
    }

    /**
     * Write CONFIG in one transaction (1 = input, 0 = output; the shadow is readable
     * as [direction]). OUTPUT is written
     * first so pins turning into outputs never glitch; the cached input value is
     * refreshed afterwards to drop the false interrupt an output→input change can
     * raise.
     *
     * @param mask 8-bit direction mask; bit n = 1 makes Pn an input
     */
    fun setDirection(mask: Int) {
        writeReg(REG_OUTPUT, shadow)
        direction = mask and 0xFF
        writeReg(REG_CONFIG, direction)
        lastInput = readReg(REG_INPUT)
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0–3
     * @throws IllegalArgumentException if reg is outside 0–3
     */
    fun readRegister(reg: Int): Int {
        require(reg in 0..3) { "register must be 0-3" }
        return readReg(reg)
    }

    /**
     * Raw register write (1–3); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     *
     * @param reg   register address 1–3 (INPUT is read-only)
     * @param value 8-bit value
     * @throws IllegalArgumentException if reg is 0 or outside 1–3
     */
    fun writeRegister(reg: Int, value: Int) {
        require(reg in 1..3) { "register must be 1-3 (INPUT is read-only)" }
        val v = value and 0xFF
        when (reg) {
            REG_OUTPUT   -> shadow = v
            REG_POLARITY -> polarity = v
            else         -> direction = v
        }
        writeReg(reg, v)
    }

    /**
     * Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF) and refresh
     * the input cache. The RESET pin is external and not driven by the driver.
     */
    fun reset() {
        offInterrupt()
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        shadow = 0xFF
        polarity = 0x00
        direction = 0xFF
        lastInput = readReg(REG_INPUT)
        level = lastInput
    }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 5 ms polling
     * thread if no [InputPin] is available.
     *
     * @param callback called with the mask of input pins whose level changed
     * @param intPin   overrides `connection.intPin()`, or `null`
     */
    @Synchronized
    fun onInterrupt(callback: (Int) -> Unit, intPin: InputPin? = null) {
        this.callback = callback
        startDelivery(intPin ?: connection.intPin())
    }

    private fun startDelivery(pin: InputPin?) {
        stopDelivery()
        active = true
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
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
            }, "pca6408a-poll").also {
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

    /** Unsubscribe and stop delivery. */
    @Synchronized
    fun offInterrupt() {
        stopDelivery()
        callback = null
    }

    private fun handleEdge() {
        try {
            val changed = pollInterrupt()
            if (changed == 0) return
            callback?.invoke(changed)
            for (n in 0 until 8) {
                if (changed and (1 shl n) == 0) continue
                val w = watches[n] ?: continue
                val high = ((level shr n) and 1) == 1
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
     * Read INPUT (clearing INT) and return the changed-pin mask: the XOR of the
     * new and the cached INPUT value, limited to pins configured as inputs. The
     * cache is updated.
     *
     * @return mask; bit n = 1 if input Pn changed
     */
    @Synchronized
    fun pollInterrupt(): Int {
        val now = readReg(REG_INPUT)
        val changed = (now xor lastInput) and direction
        lastInput = now
        level = now
        return changed
    }

    // =========================================================================
    // Pin — extends PCA6408AMinimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds per-pin change watchers.
     * The chip has no pull-up resistors, so [setPullUp] always rejects.
     */
    class Pin(private val full: PCA6408AFull, n: Int) : PCA6408AMinimal.Pin(full, n) {

        /**
         * Pull-up control is not supported: the PCA6408A has no pull-up resistors.
         *
         * @throws UnsupportedOperationException always
         */
        @Suppress("UNUSED_PARAMETER")
        fun setPullUp(pullUp: Boolean): Unit =
            throw UnsupportedOperationException("PCA6408A has no pull-up resistors")

        /**
         * Subscribe to this pin's changes. Starts delivery (INT line or 5 ms
         * polling) if none is running. The chip reports changes only; the
         * rising/falling filter is applied in software. At most one handler per
         * pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches [trigger]
         * @param trigger which edge(s) to fire on
         * @throws IllegalStateException if the pin is an output (outputs never raise INT)
         */
        fun watch(handler: (Pin) -> Unit, trigger: EdgeTrigger = EdgeTrigger.CHANGE) {
            check((full.direction shr n) and 1 == 1) { "cannot watch an output pin" }
            full.watches[n] = PinWatch(handler, trigger)
            synchronized(full) {
                if (!full.active) {
                    full.callback = null
                    full.startDelivery(full.connection.intPin())
                }
            }
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        fun unwatch() {
            full.watches[n] = null
        }
    }
}
