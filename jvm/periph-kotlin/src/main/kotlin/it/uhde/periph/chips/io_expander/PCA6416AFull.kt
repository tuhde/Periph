package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * PCA6416A full interface — polarity inversion, whole-device direction,
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
class PCA6416AFull(connection: RegisterConnection, addr: Int = 0x20) : PCA6416AMinimal(connection, addr) {

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    private var active = false
    @Volatile private var level = lastInput
    private val watches = arrayOfNulls<PinWatch>(16)

    /**
     * Fixed edge handler instance, reused across onEdge()/offEdge() calls so
     * offEdge() can remove the exact instance that was registered.
     */
    private val edgeHandler = EdgeHandler { handleEdge() }

    private class PinWatch(val handler: (Pin) -> Unit, val trigger: EdgeTrigger)

    /**
     * Return a Full [Pin] proxy for pin [n] (0–15).
     *
     * @param n pin index (0–15)
     * @return Pin proxy with [Pin.watch]/[Pin.unwatch] support
     */
    override fun pin(n: Int): Pin = Pin(this, n and 15)

    /**
     * Read both INPUT registers in one two-byte transaction.
     *
     * @return 16-bit value; bit n = pin n (bits 0–7 port 0, 8–15 port 1)
     */
    fun readAll(): Int = read16(REG_INPUT)

    /**
     * Write both OUTPUT registers in one two-byte transaction and update the shadow.
     *
     * @param value 16-bit output value; bit n = pin n
     */
    fun writeAll(value: Int) {
        shadow = value and 0xFFFF
        write16(REG_OUTPUT, shadow)
    }

    /**
     * Configure input polarity inversion (POLARITY0/1 ← mask).
     *
     * @param mask 16-bit mask; bit n = 1 inverts the INPUT bit of pin n
     */
    fun configurePolarity(mask: Int) {
        polarity = mask and 0xFFFF
        write16(REG_POLARITY, polarity)
        lastInput = read16(REG_INPUT)
    }

    /**
     * Write CONFIG0/1 in one two-byte transaction (1 = input, 0 = output; the shadow
     * is readable as [direction]). OUTPUT is written first so pins turning into
     * outputs never glitch; the cached input value is refreshed afterwards to drop
     * the false interrupt an output→input change can raise.
     *
     * @param mask 16-bit direction mask; bit n = 1 makes pin n an input
     */
    fun setDirection(mask: Int) {
        write16(REG_OUTPUT, shadow)
        direction = mask and 0xFFFF
        write16(REG_CONFIG, direction)
        lastInput = read16(REG_INPUT)
    }

    /**
     * Raw register read (debug / escape hatch).
     *
     * @param reg register address 0–7
     * @throws IllegalArgumentException if reg is outside 0–7
     */
    fun readRegister(reg: Int): Int {
        require(reg in 0..7) { "register must be 0-7" }
        return readReg(reg)
    }

    /**
     * Raw register write (2–7); the OUTPUT / POLARITY / CONFIG shadows are kept in sync.
     *
     * @param reg   register address 2–7 (INPUT0/1 are read-only)
     * @param value 8-bit value
     * @throws IllegalArgumentException if reg is 0 or 1, or outside 2–7
     */
    fun writeRegister(reg: Int, value: Int) {
        require(reg in 2..7) { "register must be 2-7 (INPUT is read-only)" }
        val v = value and 0xFF
        val shift = 8 * (reg and 1)
        val keep = (0xFF shl shift).inv() and 0xFFFF
        when (reg shr 1) {
            1    -> shadow = (shadow and keep) or (v shl shift)
            2    -> polarity = (polarity and keep) or (v shl shift)
            else -> direction = (direction and keep) or (v shl shift)
        }
        writeReg(reg, v)
    }

    /**
     * Write the reset values (OUTPUT 0xFF, POLARITY 0x00, CONFIG 0xFF, both ports) and
     * refresh the input cache. The RESET pin is external and not driven by the driver.
     */
    fun reset() {
        offInterrupt()
        write16(REG_OUTPUT, 0xFFFF)
        write16(REG_POLARITY, 0x0000)
        write16(REG_CONFIG, 0xFFFF)
        shadow = 0xFFFF
        polarity = 0x0000
        direction = 0xFFFF
        lastInput = read16(REG_INPUT)
        level = lastInput
    }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 5 ms polling
     * thread if no [InputPin] is available.
     *
     * @param callback called with the 16-bit mask of input pins whose level changed
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
            }, "pca6416a-poll").also {
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
            for (n in 0 until 16) {
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
     * Read both INPUT registers (clearing INT) and return the changed-pin mask: the XOR of the
     * new and the cached INPUT value, limited to pins configured as inputs. The
     * cache is updated.
     *
     * @return 16-bit mask; bit n = 1 if input pin n changed
     */
    @Synchronized
    fun pollInterrupt(): Int {
        val now = read16(REG_INPUT)
        val changed = (now xor lastInput) and direction
        lastInput = now
        level = now
        return changed
    }

    // =========================================================================
    // Pin — extends PCA6416AMinimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds per-pin change watchers.
     * The chip has no pull-up resistors, so [setPullUp] always rejects.
     */
    class Pin(private val full: PCA6416AFull, n: Int) : PCA6416AMinimal.Pin(full, n) {

        /**
         * Pull-up control is not supported: the PCA6416A has no pull-up resistors.
         *
         * @throws UnsupportedOperationException always
         */
        @Suppress("UNUSED_PARAMETER")
        fun setPullUp(pullUp: Boolean): Unit =
            throw UnsupportedOperationException("PCA6416A has no pull-up resistors")

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
