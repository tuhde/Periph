package it.uhde.periph.chips.led

import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import java.io.IOException
import java.util.function.LongConsumer

/**
 * HT16K33 — full driver. Extends [HT16K33Minimal] with display control,
 * standby, key scan and the INT output.
 */
class HT16K33Full @Throws(IOException::class) constructor(connection: Connection) : HT16K33Minimal(connection) {

    private var callback: LongConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** Turn the display output on (keeps the blink setting). */
    @Throws(IOException::class)
    fun displayOn() {
        displayEnabled = true
        applyDisplay()
    }

    /** Turn the display output off (keeps the blink setting). */
    @Throws(IOException::class)
    fun displayOff() {
        displayEnabled = false
        applyDisplay()
    }

    /**
     * Set the blink frequency.
     *
     * @param frequency 0 (off), 2, 1 or 0.5 Hz
     * @throws IllegalArgumentException for any other value
     */
    @Throws(IOException::class)
    fun setBlink(frequency: Double) {
        blinkCode = when (frequency) {
            0.0 -> 0
            2.0 -> 1
            1.0 -> 2
            0.5 -> 3
            else -> throw IllegalArgumentException("frequency must be 0, 2, 1 or 0.5")
        }
        applyDisplay()
    }

    /** Stop the oscillator (display off, key scan stopped). */
    @Throws(IOException::class)
    fun standby() {
        command(CMD_SYSTEM)
    }

    /** Start the oscillator and restore display, dimming, ROW/INT and RAM. */
    @Throws(IOException::class)
    fun wake() {
        command(CMD_SYSTEM or 0x01)
        command(rowInt)
        command(CMD_DIMMING or brightnessLevel)
        show()
        applyDisplay()
    }

    /**
     * Select the function of the ROW15/INT pin.
     *
     * @param enable true makes the pin an interrupt output (ROW15/K13 unusable)
     * @param activeHigh INT polarity when enabled (false = active low)
     */
    @Throws(IOException::class)
    fun setIntOutput(enable: Boolean, activeHigh: Boolean = false) {
        rowInt = if (enable) (if (activeHigh) 0xA3 else 0xA1) else 0xA0
        command(rowInt)
    }

    /**
     * Read and clear the key data RAM.
     *
     * @return key bitmask, bit `k * 13 + (K - 1)` for key input K (1-13) on key source KSk (0-2)
     */
    @Throws(IOException::class)
    fun readKeys(): Long {
        connection.write(byteArrayOf(CMD_KEY_PTR.toByte()))
        val b = connection.read(6)
        var mask = 0L
        for (k in 0 until 3) {
            val word = (((b[2 * k].toInt() and 0xFF) or ((b[2 * k + 1].toInt() and 0xFF) shl 8)) and 0x1FFF).toLong()
            mask = mask or (word shl (13 * k))
        }
        return mask
    }

    /** Read the INT flag; true if a key press is pending. */
    @Throws(IOException::class)
    fun keyPressed(): Boolean {
        connection.write(byteArrayOf(CMD_INT_PTR.toByte()))
        return (connection.read(1)[0].toInt() and 0x01) != 0
    }

    /** Read one LED state from the shadow RAM. */
    fun getPixel(com: Int, row: Int): Boolean {
        require(com in 0..7 && row in 0..15) { "com 0..7, row 0..15" }
        return ((ram[2 * com + (row shr 3)].toInt() shr (row and 7)) and 1) == 1
    }

    /** Check the INT flag and, if set, read (and clear) the key data; 0 if no key is pending. */
    @Throws(IOException::class)
    fun pollInterrupt(): Long = if (keyPressed()) readKeys() else 0L

    /**
     * Subscribe to key-press events on [intPin] (default: the connection's interrupt pin),
     * or a 20 ms polling thread when there is none.
     *
     * @param callback receives the key bitmask
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun onInterrupt(callback: LongConsumer, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, if (rowInt == 0xA3) EdgeTrigger.RISING else EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(20)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "ht16k33-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            val keys = pollInterrupt()
            if (keys != 0L) cb.accept(keys)
        } catch (e: IOException) {
            // transient bus error: the next edge or tick retries
        }
    }
}
