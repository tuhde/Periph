package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * APDS-9250 full interface — extends {@link Apds9250Minimal} with
 * single-channel reads, mode, resolution, rate and gain configuration,
 * thresholds, variation and persistence, and interrupt delivery.
 *
 * <p>Setters with a restricted value throw {@link IllegalArgumentException}
 * and write nothing for an unsupported argument.
 */
@CompileStatic
class Apds9250Full extends Apds9250Minimal {

    /** Interrupt source: selected channel outside the threshold window. */
    public static final int SOURCE_THRESHOLD = 0x01
    /** Interrupt source: selected channel changed by more than the variance threshold. */
    public static final int SOURCE_VARIATION = 0x02

    /** Interrupt channel: infrared. */
    public static final int CHANNEL_IR = 0
    /** Interrupt channel: green / ambient light. */
    public static final int CHANNEL_GREEN = 1
    /** Interrupt channel: red. */
    public static final int CHANNEL_RED = 2
    /** Interrupt channel: blue. */
    public static final int CHANNEL_BLUE = 3

    private static final int[] RESOLUTION_BITS = [20, 19, 18, 17, 16, 13] as int[]
    private static final double[] INTEGRATION_S = [0.4d, 0.2d, 0.1d, 0.05d, 0.025d, 0.003125d] as double[]
    private static final int[] RATE_MS = [25, 50, 100, 200, 500, 1000, 2000, 2000] as int[]
    private static final int[] GAINS = [1, 3, 6, 9, 18] as int[]

    protected volatile IntConsumer callback
    private InputPin intPinUsed
    private Thread pollThread
    private volatile boolean polling

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    protected final EdgeHandler edgeHandler = { -> handleEdge() } as EdgeHandler

    /**
     * Construct the driver and start the sensor.
     *
     * @param connection I²C register connection bound to the device (0x52)
     */
    Apds9250Full(RegisterConnection connection) {
        super(connection)
    }

    /** @return red count (3-byte read) */
    int readRed() { return readChannel(REG_DATA_RED) }

    /** @return green / ambient-light count */
    int readGreen() { return readChannel(REG_DATA_GREEN) }

    /** @return blue count */
    int readBlue() { return readChannel(REG_DATA_BLUE) }

    /** @return infrared count */
    int readIr() { return readChannel(REG_DATA_IR) }

    /**
     * Block-read all four channels without waiting (call after {@link #dataReady()}).
     *
     * @return the four counts
     */
    Counts readChannels() { return readBlock() }

    /**
     * Report whether a new result is available. Reads MAIN_STATUS, which clears
     * its flags; the interrupt and power-on bits are kept for
     * {@link #pollInterrupt()} and {@link #status()}.
     *
     * @return true if LS_DATA was set
     */
    boolean dataReady() {
        return (readStatus() & STATUS_DATA_READY) != 0
    }

    /**
     * Select colour-sensing or ALS/IR mode.
     *
     * @param colour true: CS mode (red, green, blue, IR); false: ALS/IR (green and IR only)
     */
    void setMode(boolean colour) {
        updateReg(REG_MAIN_CTRL, colour ? CTRL_CS_MODE : 0, CTRL_CS_MODE)
    }

    /** Activate the light sensor (LS_EN = 1). */
    void enable() { updateReg(REG_MAIN_CTRL, CTRL_LS_EN, 0) }

    /** Put the light sensor in standby (LS_EN = 0). */
    void disable() { updateReg(REG_MAIN_CTRL, 0, CTRL_LS_EN) }

    private static int indexOf(int[] table, int value, int limit) {
        for (int i = 0; i < limit; i++) if (table[i] == value) return i
        return -1
    }

    /**
     * Set the ADC resolution (and integration time); restarts the measurement.
     *
     * @param bits 13, 16, 17, 18, 19 or 20
     * @throws IllegalArgumentException for an unsupported value
     */
    void setResolution(int bits) {
        int code = indexOf(RESOLUTION_BITS, bits, 6)
        if (code < 0) throw new IllegalArgumentException('resolution must be 13, 16, 17, 18, 19 or 20')
        updateReg(REG_LS_MEAS_RATE, code << 4, 0x70)
        this.@integrationS = INTEGRATION_S[code]
    }

    /**
     * Set the measurement rate.
     *
     * @param milliseconds 25, 50, 100, 200, 500, 1000 or 2000
     * @throws IllegalArgumentException for an unsupported value
     */
    void setMeasurementRate(int milliseconds) {
        int code = indexOf(RATE_MS, milliseconds, 7)
        if (code < 0) throw new IllegalArgumentException('rate must be 25, 50, 100, 200, 500, 1000 or 2000 ms')
        updateReg(REG_LS_MEAS_RATE, code, 0x07)
    }

    /**
     * Set the analog gain for all channels; restarts the measurement.
     *
     * @param gain 1, 3, 6, 9 or 18
     * @throws IllegalArgumentException for an unsupported value
     */
    void setGain(int gain) {
        int code = indexOf(GAINS, gain, 5)
        if (code < 0) throw new IllegalArgumentException('gain must be 1, 3, 6, 9 or 18')
        writeReg(REG_LS_GAIN, code)
    }

    /** @return the ADC resolution in bits */
    int getResolution() {
        int code = (readReg(REG_LS_MEAS_RATE) >> 4) & 0x07
        return code < 6 ? RESOLUTION_BITS[code] : 18
    }

    /** @return the measurement rate in milliseconds */
    int getMeasurementRate() {
        return RATE_MS[readReg(REG_LS_MEAS_RATE) & 0x07]
    }

    /** @return the analog gain (1, 3, 6, 9 or 18) */
    int getGain() {
        int code = readReg(REG_LS_GAIN) & 0x07
        return code < 5 ? GAINS[code] : 18
    }

    /** @return the integration time of the current resolution in seconds */
    double integrationTime() {
        int code = (readReg(REG_LS_MEAS_RATE) >> 4) & 0x07
        return code < 6 ? INTEGRATION_S[code] : 0.1d
    }

    /**
     * Convert a count to counts per gain-second: {@code counts / (gain · integrationTime)}.
     *
     * @param counts raw channel count
     * @return the normalised count
     */
    double normalized(int counts) {
        return counts / ((double) getGain() * integrationTime())
    }

    /**
     * Read PART_ID.
     *
     * @return {part, revision} — the two nibbles ({0xB, 0x5})
     */
    int[] partId() {
        int value = readReg(REG_PART_ID)
        return [value >> 4, value & 0x0F] as int[]
    }

    /**
     * Read MAIN_STATUS (clearing the flags on the chip).
     *
     * @return the raw status byte with interrupt / power-on bits seen since the last call merged in
     */
    synchronized int status() {
        int value = readStatus() | this.@pending
        this.@pending = 0
        return value
    }

    /** Trigger a software reset (all registers to defaults, sensor in standby). */
    void softReset() {
        writeReg(REG_MAIN_CTRL, CTRL_SW_RESET)
        sleepMs(startWaitMs > 0 ? 10 : 0)
        this.@pending = 0
        this.@integrationS = 0.1d
    }

    /**
     * Set the 20-bit threshold window.
     *
     * @param low  lower threshold (0–1048575)
     * @param high upper threshold (0–1048575)
     * @throws IllegalArgumentException if a value exceeds 20 bit or {@code low > high}
     */
    void setThresholds(int low, int high) {
        if (low < 0 || high < 0 || low > 0xFFFFF || high > 0xFFFFF || low > high) {
            throw new IllegalArgumentException('thresholds must satisfy 0 <= low <= high <= 1048575')
        }
        connection.write(REG_THRES_LOW, [(byte) low, (byte) (low >> 8), (byte) ((low >> 16) & 0x0F)] as byte[])
        connection.write(REG_THRES_UP, [(byte) high, (byte) (high >> 8), (byte) ((high >> 16) & 0x0F)] as byte[])
    }

    /**
     * Read the threshold window.
     *
     * @return {low, high} counts
     */
    int[] getThresholds() {
        return [count(connection.read(REG_THRES_LOW, 3), 0), count(connection.read(REG_THRES_UP, 3), 0)] as int[]
    }

    /**
     * Select the channel the interrupt monitors.
     *
     * @param channel {@link #CHANNEL_IR}, {@link #CHANNEL_GREEN}, {@link #CHANNEL_RED} or {@link #CHANNEL_BLUE}
     * @throws IllegalArgumentException for an unknown channel
     */
    void setInterruptChannel(int channel) {
        if (channel < CHANNEL_IR || channel > CHANNEL_BLUE) throw new IllegalArgumentException('unknown channel')
        updateReg(REG_INT_CFG, channel << 4, INT_SEL_MASK)
    }

    /**
     * Set the number of consecutive events before the interrupt asserts.
     *
     * @param count 1–16
     * @throws IllegalArgumentException if out of range
     */
    void setPersistence(int count) {
        if (count < 1 || count > 16) throw new IllegalArgumentException('count must be 1-16')
        writeReg(REG_INT_PERSISTENCE, (count - 1) << 4)
    }

    /**
     * Set the variation-interrupt threshold.
     *
     * @param counts 8, 16, 32, … 1024
     * @throws IllegalArgumentException if not {@code 8 << n} (n = 0–7)
     */
    void setVariance(int counts) {
        for (int n = 0; n < 8; n++) {
            if (counts == (8 << n)) {
                writeReg(REG_THRES_VAR, n)
                return
            }
        }
        throw new IllegalArgumentException('counts must be 8, 16, 32, ... 1024')
    }

    /**
     * Select threshold or variation mode and enable the interrupt.
     *
     * @param source {@link #SOURCE_THRESHOLD} or {@link #SOURCE_VARIATION}
     */
    void enableInterrupt(int source = SOURCE_THRESHOLD) {
        int mode = source == SOURCE_VARIATION ? INT_VAR_MODE : 0
        updateReg(REG_INT_CFG, INT_EN | mode, INT_EN | INT_VAR_MODE)
    }

    /** Disable the interrupt (clears LS_INT_EN; both sources share the bit). */
    void disableInterrupt() { updateReg(REG_INT_CFG, 0, INT_EN) }

    /**
     * Read MAIN_STATUS (clearing the flags) and return it; same as {@link #status()}.
     *
     * @return status byte; {@link #STATUS_INTERRUPT} is set if the condition was fulfilled
     */
    int pollInterrupt() { return status() }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 50&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the MAIN_STATUS byte after it has been read and cleared
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    synchronized void onInterrupt(IntConsumer callback, InputPin intPin = null) {
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
            pollThread = new Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(50L)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } as Runnable, 'apds9250-poll')
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
    synchronized void offInterrupt() {
        stopDelivery()
        callback = null
    }

    protected void handleEdge() {
        try {
            int status = pollInterrupt()
            IntConsumer cb = callback
            if ((status & STATUS_INTERRUPT) != 0 && cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
