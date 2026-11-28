package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.IntConsumer

/**
 * TSL2561 full interface — extends {@link TSL2561Minimal} with raw counts, gain
 * and integration time, package coefficients, auto-ranging, power control,
 * manual integration, the interrupt window and interrupt delivery.
 */
@CompileStatic
class TSL2561Full extends TSL2561Minimal {

    // (gain, INTEG) from most to least sensitive, used by autoRange().
    private static final int[][] RANGES = [[16, 2], [1, 2], [1, 1], [1, 0]] as int[][]
    private static final int AUTO_MIN_COUNTS = 100

    /** Lux plus the channel counts of one sample. */
    public static final class Reading {
        /** Illuminance in lux. */
        public final double lux
        /** Channel 0 count (visible + IR). */
        public final int ch0
        /** Channel 1 count (IR). */
        public final int ch1

        Reading(double lux, int ch0, int ch1) {
            this.lux = lux
            this.ch0 = ch0
            this.ch1 = ch1
        }
    }

    private volatile IntConsumer callback
    private InputPin intPinUsed
    private Thread pollThread
    private volatile boolean polling

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the device (0x29, 0x39 or 0x49)
     * @throws IOException on bus error, or if CONTROL does not read back as powered up
     */
    public TSL2561Full(RegisterConnection connection) throws IOException {
        super(connection)
    }

    /**
     * Read both ADC channels in one block read.
     *
     * @return {@code {ch0, ch1}} — channel 0 (visible + IR) and channel 1 (IR) counts
     * @throws IOException on bus error
     */
    public int[] readRaw() throws IOException {
        return readChannels()
    }

    /**
     * Set the ADC gain (TIMING.GAIN); wait one integration before trusting the next reading.
     *
     * @param gain 1 or 16
     * @throws IllegalArgumentException on any other value
     * @throws IOException              on bus error
     */
    public void setGain(int gain) throws IOException {
        if (gain != 1 && gain != 16) throw new IllegalArgumentException("gain must be 1 or 16")
        curGain = gain
        writeTiming(false)
    }

    /** @return the current ADC gain (1 or 16) */
    public int getGain() { return curGain }

    /**
     * Set the integration time (TIMING.INTEG); wait one integration before trusting the next reading.
     *
     * @param ms 14 (13.7 ms), 101 or 402
     * @throws IllegalArgumentException on any other value
     * @throws IOException              on bus error
     */
    public void setIntegrationTime(int ms) throws IOException {
        if (ms == 14) integ = 0
        else if (ms == 101) integ = 1
        else if (ms == 402) integ = 2
        else throw new IllegalArgumentException("integration time must be 14, 101 or 402 ms")
        writeTiming(false)
    }

    /** @return the current integration time in ms (14, 101 or 402; 0 in manual timing mode) */
    public int getIntegrationTime() { return integrationMs() }

    /**
     * Select the lux coefficient set.
     *
     * @param pkg "T", "FN", "CL" (shared coefficients) or "CS"
     * @throws IllegalArgumentException on any other value
     */
    public void setPackage(String pkg) {
        switch (pkg) {
            case "T":
            case "FN":
            case "CL":
            case "CS":
                this.pkg = pkg
                return
            default:
                throw new IllegalArgumentException("package must be T, FN, CL or CS")
        }
    }

    /**
     * Read lux and both channel counts from one sample.
     *
     * @return the reading
     * @throws SaturatedException if a channel is saturated
     * @throws IOException        on bus error
     */
    public Reading readLuxRaw() throws IOException {
        int[] c = readChannels()
        return new Reading(luxFrom(c[0], c[1]), c[0], c[1])
    }

    private void applyRange(int index) throws IOException {
        int oldWait = settleMs()
        curGain = RANGES[index][0]
        integ = RANGES[index][1]
        writeTiming(false)
        sleepMs(oldWait + settleMs())
    }

    /**
     * Pick gain and integration time for the current light level and read lux.
     * Steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms until neither
     * channel saturates and channel 0 is above 100 counts, waiting for a fresh
     * integration after every change; the chosen setting stays active.
     *
     * @return lux
     * @throws SaturatedException if a channel still saturates at 1×/13.7 ms
     * @throws IOException        on bus error
     */
    public double autoRange() throws IOException {
        int index = -1
        for (int i = 0; i < RANGES.length; i++) {
            if (RANGES[i][0] == curGain && RANGES[i][1] == integ) index = i
        }
        if (index < 0) {
            index = 1
            applyRange(index)
        }
        for (int step = 0; step < 2 * RANGES.length; step++) {
            int[] c = readChannels()
            int limit = fullScale()
            boolean saturated = c[0] >= limit || c[1] >= limit
            if (saturated && index < RANGES.length - 1) index++
            else if (!saturated && c[0] < AUTO_MIN_COUNTS && index > 0) index--
            else return luxFrom(c[0], c[1])
            applyRange(index)
        }
        return readLux()
    }

    /**
     * Power up (CONTROL ← 0x03); integration restarts.
     *
     * @throws IOException on bus error
     */
    public void powerUp() throws IOException { writeByte(REG_CONTROL, POWER_ON) }

    /**
     * Power down (CONTROL ← 0x00); the registers keep their values.
     *
     * @throws IOException on bus error
     */
    public void powerDown() throws IOException { writeByte(REG_CONTROL, 0x00) }

    /**
     * @return true if CONTROL reads 0x03
     * @throws IOException on bus error
     */
    public boolean isPowered() throws IOException { return (readByte(REG_CONTROL) & 0x03) == POWER_ON }

    /**
     * Start a host-timed integration (INTEG = 11, MANUAL = 1); {@link #readLux()}
     * then uses the 402 ms scale.
     *
     * @throws IOException on bus error
     */
    public void startManual() throws IOException {
        integ = INTEG_MANUAL
        writeTiming(true)
    }

    /**
     * Stop the host-timed integration (INTEG = 11, MANUAL = 0); read the counts next.
     *
     * @throws IOException on bus error
     */
    public void stopManual() throws IOException {
        integ = INTEG_MANUAL
        writeTiming(false)
    }

    /**
     * Set the channel-0 interrupt window (word writes of 0x2–0x3 and 0x4–0x5).
     *
     * @param low  low threshold, channel-0 counts 0–65535
     * @param high high threshold, channel-0 counts 0–65535
     * @throws IllegalArgumentException if a value is outside 0–65535
     * @throws IOException              on bus error
     */
    public void setThresholds(int low, int high) throws IOException {
        if (low < 0 || low > 0xFFFF || high < 0 || high > 0xFFFF) {
            throw new IllegalArgumentException("threshold must be 0-65535")
        }
        writeWord(REG_THRESH_LOW, low)
        writeWord(REG_THRESH_HIGH, high)
    }

    /**
     * Read the channel-0 interrupt window.
     *
     * @return {@code {low, high}} in channel-0 counts
     * @throws IOException on bus error
     */
    public int[] getThresholds() throws IOException {
        return [readWord(REG_THRESH_LOW), readWord(REG_THRESH_HIGH)] as int[]
    }

    /**
     * Configure the INTERRUPT register.
     *
     * @param mode    0 disabled, 1 level interrupt, 2 SMBAlert style
     * @param persist 0 every cycle, 1 any value outside, N (2–15) N consecutive cycles outside
     * @throws IllegalArgumentException if mode is not 0–2 or persist not 0–15
     * @throws IOException              on bus error
     */
    public void configureInterrupt(int mode, int persist) throws IOException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("mode must be 0, 1 or 2")
        if (persist < 0 || persist > 15) throw new IllegalArgumentException("persist must be 0-15")
        writeByte(REG_INTERRUPT, (mode << 4) | persist)
    }

    /**
     * Read the ID register.
     *
     * @return ID byte; {@code (id >> 4) == 1} for the TSL2561, bits 3:0 the revision
     * @throws IOException on bus error
     */
    public int readId() throws IOException { return readByte(REG_ID) }

    /**
     * Check channel 0 against the programmed window (the persistence filter is
     * not modelled and the chip's interrupt is not cleared).
     *
     * @return true if channel 0 is ≤ the low or > the high threshold
     * @throws IOException on bus error
     */
    public boolean pollInterrupt() throws IOException {
        int ch0 = readChannels()[0]
        int[] th = getThresholds()
        return ch0 <= th[0] || ch0 > th[1]
    }

    /**
     * Send the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt.
     *
     * @throws IOException on bus error
     */
    public void clearInterrupt() throws IOException {
        connection.write([(byte) CMD_CLEAR] as byte[])
    }

    /**
     * Subscribe to INT assertions using {@code connection.intPin()}.
     *
     * @param callback called with the current channel-0 count
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, null)
    }

    /**
     * Subscribe to INT assertions (falling edge). With a pin the driver reads
     * channel 0, calls {@code callback(ch0)} and clears the level interrupt
     * without one a polling thread calls {@link #pollInterrupt()} once per
     * integration time.
     *
     * @param callback called with the current channel-0 count
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    public synchronized void onInterrupt(IntConsumer callback, InputPin intPin) {
        this.callback = callback
        startDelivery(intPin != null ? intPin : connection.intPin())
    }

    private void startDelivery(InputPin pin) {
        stopDelivery()
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            final long period = Math.max(14, integrationMs())
            pollThread = new Thread({
                while (polling) {
                    handlePoll()
                    try {
                        Thread.sleep(period)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } as Runnable, "tsl2561-poll")
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    private void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler)
            intPinUsed = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    /** Unsubscribe and stop delivery. */
    public synchronized void offInterrupt() {
        stopDelivery()
        callback = null
    }

    private void handleEdge() {
        try {
            int ch0 = readChannels()[0]
            IntConsumer cb = callback
            if (cb != null) cb.accept(ch0)
            clearInterrupt()
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }

    private void handlePoll() {
        try {
            if (!pollInterrupt()) return
            int ch0 = readChannels()[0]
            IntConsumer cb = callback
            if (cb != null) cb.accept(ch0)
        } catch (IOException ignored) {
            // bus error; wait for the next tick rather than propagating
        }
    }
}
