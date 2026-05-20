package it.uhde.periph.chips.io_expander

import it.uhde.periph.transport.Transport

/**
 * PCF8574 full driver — extends [Pcf8574Minimal] with interrupt-on-change support.
 *
 * Adds [configureInterrupt] to start a 5 ms polling thread that fires a callback
 * whenever any input pin changes state, and [clearInterrupt] to read current pin
 * states and return the bitmask of changed pins.
 *
 * The INT output of the PCF8574 is active-low and open-drain. In this JVM driver,
 * interrupt delivery is always via a background polling thread.
 *
 * @param transport I²C transport bound to the PCF8574 device address
 */
class Pcf8574Full(transport: Transport) : Pcf8574Minimal(transport) {

    /** Port value from the previous read — used to detect changes. */
    private var prev: Int = readPort()

    /** Callback invoked with the changed-pin bitmask on any input change. */
    @Volatile private var callback: ((Int) -> Unit)? = null

    /** `true` while the polling thread is running. */
    @Volatile private var polling: Boolean = false

    /** Background daemon thread that polls the port every 5 ms. */
    private var pollThread: Thread? = null

    // -------------------------------------------------------------------------
    // Interrupt API
    // -------------------------------------------------------------------------

    /**
     * Start a background polling thread that fires [callback] whenever any input
     * pin changes state.
     *
     * The callback receives an 8-bit bitmask of pins that changed since the
     * previous read (bit n = 1 → pin n changed). The thread polls every 5 ms.
     * Call [stopInterrupt] to terminate the thread.
     *
     * @param callback function invoked with the changed-pin bitmask
     */
    fun configureInterrupt(callback: (Int) -> Unit) {
        this.callback = callback
        this.polling  = true
        pollThread = Thread({
            while (polling) {
                try {
                    val changed = clearInterrupt()
                    if (changed != 0) this.callback?.invoke(changed)
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {
                    // transport error; keep polling
                }
            }
        }, "pcf8574-poll").also { it.isDaemon = true; it.start() }
    }

    /**
     * Stop the polling thread started by [configureInterrupt].
     *
     * The thread is a daemon and also stops automatically when the JVM exits.
     */
    fun stopInterrupt() {
        polling = false
        pollThread?.interrupt()
    }

    /**
     * Read current pin states and return the bitmask of pins that changed since last read.
     *
     * Reading the port also clears the chip's INT output.
     *
     * @return 8-bit bitmask; bit n = 1 if pin n changed since the previous read
     */
    fun clearInterrupt(): Int {
        val current = readPort()
        val changed = current xor prev
        prev = current
        return changed
    }
}
