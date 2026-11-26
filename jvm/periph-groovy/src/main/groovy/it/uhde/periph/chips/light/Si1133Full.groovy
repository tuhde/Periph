package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.IntConsumer

/**
 * Si1133 full interface — extends {@link Si1133Minimal} with per-channel configuration,
 * single-photodiode reads, autonomous and burst modes, thresholds, direct parameter access, the
 * I²C address and the INT interrupt (active low, open-drain, released when IRQ_STATUS is read).
 */
@CompileStatic
public class Si1133Full extends Si1133Minimal {

    /** Interrupt source: channel 0 result ready. */
    public static final int SOURCE_CHANNEL0 = 0x01
    /** Interrupt source: channel 1 result ready. */
    public static final int SOURCE_CHANNEL1 = 0x02
    /** Interrupt source: channel 2 result ready. */
    public static final int SOURCE_CHANNEL2 = 0x04
    /** Interrupt source: channel 3 result ready. */
    public static final int SOURCE_CHANNEL3 = 0x08
    /** Interrupt source: channel 4 result ready. */
    public static final int SOURCE_CHANNEL4 = 0x10
    /** Interrupt source: channel 5 result ready. */
    public static final int SOURCE_CHANNEL5 = 0x20

    private static final int SCRATCH = 5

    /**
     * Identification registers.
     *
     * @param partId PART_ID (0x33)
     * @param hwId   HW_ID (bits 4:0 implementation, 0x03 = Si1133-AA00; bits 7:5 silicon revision)
     * @param revId  REV_ID (product revision)
     */
    static record Id(int partId, int hwId, int revId) {}

    /**
     * Decoded RESPONSE0.
     *
     * @param state   RESP_RUNNING / RESP_SUSPEND / RESP_SLEEP bits
     * @param error   CMD_ERR flag
     * @param counter command counter (the error code when {@code error} is set)
     */
    static record Response(int state, boolean error, int counter) {}

    private final int[] counterIndex = new int[6]
    private volatile IntConsumer callback
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = this::handleEdge

    /**
     * Creates the driver; no bus access.
     *
     * @param connection register-addressed I²C connection bound to 0x55 or 0x52
     */
    public Si1133Full(RegisterConnection connection) {
        super(connection)
    }

    private static void check(String name, long value, long lo, long hi) {
        if (value < lo || value > hi) throw new IllegalArgumentException(name + " must be " + lo + ".." + hi)
    }

    /**
     * Reads PART_ID, HW_ID and REV_ID.
     *
     * @return the identification registers
     * @throws IOException on a bus error
     */
    public Id readId() throws IOException {
        byte[] b = connection.read(REG_PART_ID, 3)
        return new Id(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF)
    }

    /**
     * Configures one channel (ADCCONFIG, ADCSENS, ADCPOST) with 24-bit output and no shift.
     *
     * @param channel channel 0–5
     * @param adcmux  photodiode (MUX_* constant)
     * @param hwGain  integration time code 0–11 (48.8 µs × 2^n at decimation 0)
     * @throws IOException on a failed command or bus error
     */
    public void configureChannel(int channel, int adcmux, int hwGain) throws IOException {
        configureChannel(channel, adcmux, hwGain, 0, 0, false, true, 0)
    }

    /**
     * Configures one channel (ADCCONFIG, ADCSENS, ADCPOST); clears its threshold select.
     *
     * @param channel    channel 0–5
     * @param adcmux     photodiode (MUX_* constant)
     * @param hwGain     integration time code 0–11 (48.8 µs × 2^n at decimation 0)
     * @param swGain     accumulate 2^n samples, n = 0–7
     * @param decimRate  decimation rate 0–3
     * @param highSignal divide the sensitivity by 14.5 (direct sunlight)
     * @param bits24Out  24-bit signed output, otherwise 16-bit unsigned
     * @param postshift  right shift 0–7 of the accumulated result
     * @throws IOException on a failed command or bus error
     * @throws IllegalArgumentException if a value is out of range
     */
    public void configureChannel(int channel, int adcmux, int hwGain, int swGain, int decimRate,
                                 boolean highSignal, boolean bits24Out, int postshift) throws IOException {
        check("channel", channel, 0, 5)
        check("adcmux", adcmux, 0, 0x1F)
        check("hwGain", hwGain, 0, 11)
        check("swGain", swGain, 0, 7)
        check("decimRate", decimRate, 0, 3)
        check("postshift", postshift, 0, 7)
        setChannel(channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24Out, postshift)
    }

    /**
     * Selects the channels that run (CHAN_LIST).
     *
     * @param mask bit c enables channel c (0x00–0x3F)
     * @throws IOException on a failed command or bus error
     */
    public void enableChannels(int mask) throws IOException {
        check("mask", mask, 0, 0x3F)
        paramSet(PARAM_CHAN_LIST, mask)
        chanList = mask
    }

    /**
     * Runs a forced measurement and returns the results of all enabled channels.
     *
     * @return one value per enabled channel in channel order
     * @throws IOException on a failed command, timeout or bus error
     */
    public int[] readChannels() throws IOException {
        return readChannels(true)
    }

    /**
     * Returns the results of all enabled channels in channel order.
     *
     * @param force run a forced measurement first; otherwise only decode HOSTOUT (autonomous mode)
     * @return one value per enabled channel (24-bit signed or 16-bit unsigned)
     * @throws IOException on a failed command, timeout or bus error
     */
    public int[] readChannels(boolean force) throws IOException {
        return force ? measure() : readOutputs()
    }

    private int scratchRead(int adcmux, int hwGain) throws IOException {
        int saved = chanList
        setChannel(SCRATCH, adcmux, hwGain, 0, 0, false, true, 0)
        if (counterIndex[SCRATCH] != 0) {
            paramSet(PARAM_MEASCONFIG0 + 4 * SCRATCH, 0)
            counterIndex[SCRATCH] = 0
        }
        paramSet(PARAM_CHAN_LIST, 1 << SCRATCH)
        chanList = 1 << SCRATCH
        try {
            return measure()[0]
        } finally {
            paramSet(PARAM_CHAN_LIST, saved)
            chanList = saved
        }
    }

    /**
     * Measures the UV photodiode once (channel 5 used as scratch, HW_GAIN 9).
     *
     * @return signed 24-bit raw count
     * @throws IOException on a failed command, timeout or bus error
     */
    public int readUv() throws IOException {
        return scratchRead(MUX_UV, 9)
    }

    /**
     * Measures the white photodiode once (channel 5 used as scratch, HW_GAIN 7).
     *
     * @return signed 24-bit raw count
     * @throws IOException on a failed command, timeout or bus error
     */
    public int readWhite() throws IOException {
        return scratchRead(MUX_WHITE, 7)
    }

    /**
     * Measures the small IR photodiode once.
     *
     * @return signed 24-bit raw count
     * @throws IOException on a failed command, timeout or bus error
     */
    public int readIr() throws IOException {
        return readIr(MUX_SMALL_IR)
    }

    /**
     * Measures an IR photodiode once (channel 5 used as scratch, HW_GAIN 7).
     *
     * @param photodiode MUX_SMALL_IR, MUX_MEDIUM_IR or MUX_LARGE_IR
     * @return signed 24-bit raw count
     * @throws IOException on a failed command, timeout or bus error
     */
    public int readIr(int photodiode) throws IOException {
        check("photodiode", photodiode, 0, 2)
        return scratchRead(photodiode, 7)
    }

    /**
     * Sends FORCE without waiting for the results.
     *
     * @throws IOException on a failed command or bus error
     */
    public void force() throws IOException {
        command(CMD_FORCE)
    }

    /**
     * Assigns a rate counter to a channel (MEASCONFIG.COUNTER_INDEX).
     *
     * @param channel channel 0–5
     * @param index   0 = none (forced only), 1–3 = MEASCOUNT0..2
     * @throws IOException on a failed command or bus error
     */
    public void setChannelCounter(int channel, int index) throws IOException {
        check("channel", channel, 0, 5)
        check("index", index, 0, 3)
        paramSet(PARAM_MEASCONFIG0 + 4 * channel, index << 6)
        counterIndex[channel] = index
    }

    /**
     * Starts autonomous measurements: MEASRATE (800 µs units), MEASCOUNT0..2, counter 1 for every
     * enabled channel without one, then START. Channel period = rate × MEASCOUNTk.
     *
     * @param rateS    main period in seconds (800 µs … 52.4 s)
     * @param counters MEASCOUNT0..2, 1–255 each
     * @throws IOException on a failed command or bus error
     */
    public void startAutonomous(double rateS, int... counters) throws IOException {
        long ticks = Math.round(rateS / 800e-6d)
        check("rate ticks", ticks, 1, 0xFFFF)
        if (counters.length != 3) throw new IllegalArgumentException("counters must have three entries")
        for (int c : counters) check("counter", c, 1, 0xFF)
        paramSet(PARAM_MEASRATE_H, (int) (ticks >> 8))
        paramSet(PARAM_MEASRATE_L, (int) (ticks & 0xFF))
        for (int i = 0; i < 3; i++) paramSet(PARAM_MEASCOUNT0 + i, counters[i])
        for (int ch = 0; ch < 6; ch++) {
            if ((chanList & (1 << ch)) != 0 && counterIndex[ch] == 0) setChannelCounter(ch, 1)
        }
        command(CMD_START)
    }

    /**
     * Pauses autonomous measurements (PAUSE).
     *
     * @throws IOException on a failed command or bus error
     */
    public void pause() throws IOException {
        command(CMD_PAUSE)
    }

    /**
     * Configures burst mode (BURST).
     *
     * @param count readings 1–127; 0 disables burst mode
     * @throws IOException on a failed command or bus error
     */
    public void setBurst(int count) throws IOException {
        check("count", count, 0, 127)
        paramSet(PARAM_BURST, count != 0 ? 0x80 | count : 0)
    }

    /**
     * Sets one of the three 16-bit thresholds.
     *
     * @param index threshold 0–2
     * @param value threshold count 0–65535
     * @throws IOException on a failed command or bus error
     */
    public void setThreshold(int index, int value) throws IOException {
        check("index", index, 0, 2)
        check("value", value, 0, 0xFFFF)
        paramSet(PARAM_THRESHOLD0_H + 2 * index, value >> 8)
        paramSet(PARAM_THRESHOLD0_H + 2 * index + 1, value & 0xFF)
    }

    /**
     * Selects the threshold a channel's interrupt compares against (ADCPOST.THRESH_SEL).
     *
     * @param channel channel 0–5
     * @param index   0 = off, 1–3 = THRESHOLD0..2
     * @throws IOException on a failed command or bus error
     */
    public void setChannelThreshold(int channel, int index) throws IOException {
        check("channel", channel, 0, 5)
        check("index", index, 0, 3)
        int address = PARAM_ADCPOST0 + 4 * channel
        int v = paramQuery(address)
        paramSet(address, (v & 0xFC) | index)
    }

    /**
     * Reads a parameter-table byte (PARAM_QUERY).
     *
     * @param address parameter address 0x00–0x3F
     * @return parameter value
     * @throws IOException on a failed command or bus error
     */
    public int readParameter(int address) throws IOException {
        check("address", address, 0, 0x3F)
        return paramQuery(address)
    }

    /**
     * Writes a parameter-table byte (PARAM_SET). Writing channel parameters directly bypasses the
     * driver's record of the channel layout; prefer {@link #configureChannel}.
     *
     * @param address parameter address 0x00–0x3F
     * @param value   byte value
     * @throws IOException on a failed command or bus error
     */
    public void writeParameter(int address, int value) throws IOException {
        check("address", address, 0, 0x3F)
        check("value", value, 0, 0xFF)
        paramSet(address, value)
    }

    /**
     * Decodes RESPONSE0.
     *
     * @return state bits, CMD_ERR flag and command counter
     * @throws IOException on a bus error
     */
    public Response readResponse() throws IOException {
        int r = readByte(REG_RESPONSE0)
        return new Response(r & 0xE0, (r & RESP_CMD_ERR) != 0, r & 0x0F)
    }

    /**
     * Reads IRQ_STATUS (clears it).
     *
     * @return bit c set when channel c has a new result
     * @throws IOException on a bus error
     */
    public int dataReady() throws IOException {
        return readByte(REG_IRQ_STATUS) & 0x3F
    }

    /**
     * Changes the I²C address (volatile). The chip answers on the new address at once, so no
     * completion check is made; open a new connection afterwards.
     *
     * @param address new 7-bit address 0x08–0x77
     * @throws IOException on a bus error
     */
    public void setI2cAddress(int address) throws IOException {
        check("address", address, 0x08, 0x77)
        readByte(REG_RESPONSE0)
        connection.write(REG_HOSTIN0, [(byte) address, (byte) (CMD_PARAM_SET | PARAM_I2C_ADDR)] as byte[])
    }

    /**
     * Software reset (RESET_SW), waits for SLEEP and re-applies the Minimal defaults.
     *
     * @throws IOException on a failed command or bus error
     */
    public void reset() throws IOException {
        softwareReset()
        Arrays.fill(counterIndex, 0)
        applyDefaults()
    }

    /**
     * Enables INT for channels (IRQ_ENABLE).
     *
     * @param source SOURCE_CHANNEL* mask
     * @throws IOException on a bus error
     */
    public void enableInterrupt(int source) throws IOException {
        irqEnable = (irqEnable | source) & 0x3F
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    /**
     * Disables INT for channels. Forced reads only wait for channels whose interrupt is enabled
     * (none: a fixed 100 ms wait).
     *
     * @param source SOURCE_CHANNEL* mask
     * @throws IOException on a bus error
     */
    public void disableInterrupt(int source) throws IOException {
        irqEnable &= ~source & 0x3F
        writeByte(REG_IRQ_ENABLE, irqEnable)
    }

    /**
     * Reads IRQ_STATUS (clears it, releases INT).
     *
     * @return channel mask, 0 = nothing pending
     * @throws IOException on a bus error
     */
    public int pollInterrupt() throws IOException {
        return dataReady()
    }

    /**
     * Subscribes to channel-ready events on the connection's INT pin, or via a 10 ms polling
     * thread when the connection has no INT pin.
     *
     * @param callback receives the IRQ_STATUS channel mask
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribes to channel-ready events (falling edge on {@code intPin}).
     *
     * @param callback receives the IRQ_STATUS channel mask
     * @param intPin   pin wired to INT, or {@code null} for the polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(10)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }, "si1133-poll")
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    /** Unsubscribes and stops delivery. */
    public void offInterrupt() {
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
        IntConsumer cb = callback
        if (cb == null) return
        try {
            int status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (IOException e) {
            // a failed poll is retried on the next edge / tick
        }
    }
}
