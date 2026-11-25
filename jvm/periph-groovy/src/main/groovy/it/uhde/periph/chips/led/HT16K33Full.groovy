package it.uhde.periph.chips.led

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin

import java.util.function.LongConsumer

/**
 * HT16K33 — full driver. Extends {@link HT16K33Minimal} with display control,
 * standby, key scan and the INT output.
 */
@CompileStatic
class HT16K33Full extends HT16K33Minimal {

    private LongConsumer callback
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I2C connection bound to the HT16K33 address
     */
    HT16K33Full(Connection connection) throws IOException {
        super(connection)
    }

    /** Turn the display output on (keeps the blink setting). */
    void displayOn() throws IOException {
        displayEnabled = true
        applyDisplay()
    }

    /** Turn the display output off (keeps the blink setting). */
    void displayOff() throws IOException {
        displayEnabled = false
        applyDisplay()
    }

    /**
     * Set the blink frequency.
     *
     * @param frequency 0 (off), 2, 1 or 0.5 Hz
     */
    void setBlink(double frequency) throws IOException {
        int code
        if (frequency == 0.0d) code = 0
        else if (frequency == 2.0d) code = 1
        else if (frequency == 1.0d) code = 2
        else if (frequency == 0.5d) code = 3
        else throw new IllegalArgumentException('frequency must be 0, 2, 1 or 0.5')
        this.@blinkCode = code
        applyDisplay()
    }

    /** Stop the oscillator (display off, key scan stopped). */
    void standby() throws IOException {
        command(CMD_SYSTEM)
    }

    /** Start the oscillator and restore display, dimming, ROW/INT and RAM. */
    void wake() throws IOException {
        command(CMD_SYSTEM | 0x01)
        command(rowInt)
        command(CMD_DIMMING | brightnessLevel)
        show()
        applyDisplay()
    }

    /**
     * Select the function of the ROW15/INT pin.
     *
     * @param enable true makes the pin an interrupt output (ROW15/K13 unusable)
     * @param activeHigh INT polarity when enabled (false = active low)
     */
    void setIntOutput(boolean enable, boolean activeHigh = false) throws IOException {
        rowInt = enable ? (activeHigh ? 0xA3 : 0xA1) : 0xA0
        command(rowInt)
    }

    /**
     * Read and clear the key data RAM.
     *
     * @return key bitmask, bit {@code k * 13 + (K - 1)} for key input K (1-13) on key source KSk (0-2)
     */
    long readKeys() throws IOException {
        connection.write([(byte) CMD_KEY_PTR] as byte[])
        byte[] b = connection.read(6)
        long mask = 0L
        for (int k = 0; k < 3; k++) {
            long word = (((b[2 * k] & 0xFF) | ((b[2 * k + 1] & 0xFF) << 8)) & 0x1FFF) as long
            mask |= word << (13 * k)
        }
        return mask
    }

    /** Read the INT flag; true if a key press is pending. */
    boolean keyPressed() throws IOException {
        connection.write([(byte) CMD_INT_PTR] as byte[])
        return (connection.read(1)[0] & 0x01) != 0
    }

    /** Read one LED state from the shadow RAM. */
    boolean getPixel(int com, int row) {
        if (com < 0 || com > 7 || row < 0 || row > 15) throw new IllegalArgumentException('com 0..7, row 0..15')
        return ((ram[2 * com + (row >> 3)] >> (row & 7)) & 1) == 1
    }

    /** Check the INT flag and, if set, read (and clear) the key data; 0 if no key is pending. */
    long pollInterrupt() throws IOException {
        return keyPressed() ? readKeys() : 0L
    }

    /**
     * Subscribe to key-press events on the connection's interrupt pin, or a 20 ms polling thread.
     *
     * @param callback receives the key bitmask
     */
    void onInterrupt(LongConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to key-press events on an explicit pin.
     *
     * @param callback receives the key bitmask
     * @param intPin pin to arm, or {@code null} to force the 20 ms polling fallback
     */
    void onInterrupt(LongConsumer callback, InputPin intPin) throws IOException {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, rowInt == 0xA3 ? EdgeTrigger.RISING : EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({ ->
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(20)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            } as Runnable, 'ht16k33-poll')
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        LongConsumer cb = callback
        if (cb == null) return
        try {
            long keys = pollInterrupt()
            if (keys != 0L) cb.accept(keys)
        } catch (IOException ignored) {
            // transient bus error: the next edge or tick retries
        }
    }
}
