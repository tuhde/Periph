package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * PCA9536 full interface — polarity inversion, whole-port direction, polling-based
 * change detection and raw register access on top of the minimal GPIO API.
 *
 * The chip has no interrupt output, so change detection is by polling:
 * [pollChanges] derives the changed mask by comparing INPUT reads, and
 * [watchChanges] runs a polling thread (5 ms by default) that reports it.
 * Per-pin [Pin.watch] starts the thread on first use.
 *
 * @param connection I²C or SMBus (no PEC) register connection bound to the device address
 * @param addr       7-bit I²C address (fixed `0x41`)
 */
class PCA9536Full(connection: RegisterConnection, addr: Int = 0x41) : PCA9536Minimal(connection, addr) {

    @Volatile private var callback: ((Int) -> Unit)? = null
    private var pollThread: Thread? = null
    @Volatile private var polling = false
    @Volatile private var intervalMs = 5L
    @Volatile private var level = lastInput
    private val watches = arrayOfNulls<PinWatch>(4)

    private class PinWatch(val handler: (Pin) -> Unit, val trigger: EdgeTrigger)

    /**
     * Return a Full [Pin] proxy for pin [n] (0–3).
     *
     * @param n pin index (0–3)
     * @return Pin proxy with [Pin.watch]/[Pin.unwatch] support
     */
    override fun pin(n: Int): Pin = Pin(this, n and 3)

    /**
     * Configure input polarity inversion (POLARITY ← mask). The cached input value is
     * refreshed, so the polarity change itself is not reported by [pollChanges].
     *
     * @param mask 4-bit mask; bit n = 1 inverts the INPUT bit of Pn
     */
    fun configurePolarity(mask: Int) {
        polarity = mask and MASK
        writeReg(REG_POLARITY, polarity)
        lastInput = readReg(REG_INPUT) and MASK
    }

    /**
     * Write CONFIG in one transaction (1 = input, 0 = output; the shadow is readable
     * as [direction]). OUTPUT is written
     * first so pins turning into outputs never glitch; the cached input value is
     * refreshed afterwards.
     *
     * @param mask 4-bit direction mask; bit n = 1 makes Pn an input
     */
    fun setDirection(mask: Int) {
        writeReg(REG_OUTPUT, shadow)
        direction = (mask and MASK) or 0xF0
        writeReg(REG_CONFIG, direction)
        lastInput = readReg(REG_INPUT) and MASK
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
     * the input cache. The chip has no RESET pin; only a power cycle clears a lock-up.
     */
    fun reset() {
        unwatchChanges()
        writeReg(REG_OUTPUT, 0xFF)
        writeReg(REG_POLARITY, 0x00)
        writeReg(REG_CONFIG, 0xFF)
        shadow = 0xFF
        polarity = 0x00
        direction = 0xFF
        lastInput = readReg(REG_INPUT) and MASK
        level = lastInput
    }

    /**
     * Start a polling thread that calls [pollChanges] every [intervalS] seconds
     * and invokes [callback] when the mask is non-zero. [unwatchChanges] stops it.
     *
     * @param callback  called with the mask of input pins whose level changed
     * @param intervalS polling interval in seconds (default 5 ms)
     */
    @Synchronized
    fun watchChanges(callback: ((Int) -> Unit)?, intervalS: Double = 0.005) {
        stopPolling()
        this.callback = callback
        intervalMs = maxOf(1L, Math.round(intervalS * 1000))
        startPolling()
    }

    private fun startPolling() {
        polling = true
        pollThread = Thread({
            while (polling) {
                dispatch()
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "pca9536-poll").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling() {
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** Stop the polling thread started by [watchChanges] (per-pin watchers stay registered). */
    @Synchronized
    fun unwatchChanges() {
        stopPolling()
        callback = null
    }

    private fun dispatch() {
        try {
            val changed = pollChanges()
            if (changed == 0) return
            callback?.invoke(changed)
            for (n in 0 until 4) {
                if (changed and (1 shl n) == 0) continue
                val w = watches[n] ?: continue
                val high = ((level shr n) and 1) == 1
                val fire = w.trigger == EdgeTrigger.CHANGE ||
                    (w.trigger == EdgeTrigger.RISING && high) ||
                    (w.trigger == EdgeTrigger.FALLING && !high)
                if (fire) w.handler(Pin(this, n))
            }
        } catch (ignored: IOException) {
            // bus error; wait for the next tick rather than propagating
        }
    }

    /**
     * Read INPUT and return the changed-pin mask (software edge detection; the
     * chip has no interrupt output): the XOR of the new and the cached INPUT
     * value, limited to pins configured as inputs. The cache is updated.
     *
     * @return mask; bit n = 1 if input Pn changed
     */
    @Synchronized
    fun pollChanges(): Int {
        val now = readReg(REG_INPUT) and MASK
        val changed = (now xor lastInput) and direction and MASK
        lastInput = now
        level = now
        return changed
    }

    // =========================================================================
    // Pin — extends PCA9536Minimal.Pin with watch/unwatch support
    // =========================================================================

    /**
     * GPIO proxy for a single pin — full interface. Adds per-pin change watchers.
     * The weak pull-up is fixed in hardware, so [setPullUp] always rejects.
     */
    class Pin(private val full: PCA9536Full, n: Int) : PCA9536Minimal.Pin(full, n) {

        /**
         * Pull-up control is not supported: the PCA9536's weak pull-up is fixed in hardware.
         *
         * @throws UnsupportedOperationException always
         */
        @Suppress("UNUSED_PARAMETER")
        fun setPullUp(pullUp: Boolean): Unit =
            throw UnsupportedOperationException("PCA9536 pull-up is fixed in hardware")

        /**
         * Subscribe to this pin's changes. Starts the 5 ms polling thread
         * if none is running. The chip reports changes only; the
         * rising/falling filter is applied in software. At most one handler per
         * pin; a second call replaces the first.
         *
         * @param handler called with this pin when its state matches [trigger]
         * @param trigger which edge(s) to fire on
         * @throws IllegalStateException if the pin is an output (outputs never report changes)
         */
        fun watch(handler: (Pin) -> Unit, trigger: EdgeTrigger = EdgeTrigger.CHANGE) {
            check((full.direction shr n) and 1 == 1) { "cannot watch an output pin" }
            full.watches[n] = PinWatch(handler, trigger)
            synchronized(full) {
                if (full.pollThread == null) {
                    full.startPolling()
                }
            }
        }

        /** Unsubscribe this pin's handler. No-op if not registered. */
        fun unwatch() {
            full.watches[n] = null
        }
    }
}
