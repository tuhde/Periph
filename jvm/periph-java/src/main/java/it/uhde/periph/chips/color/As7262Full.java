package it.uhde.periph.chips.color;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * AS7262 — full driver: raw counts, gain, integration time, bank modes, LEDs, interrupt, versions.
 *
 * <p>Extends {@link As7262Minimal}. {@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()}
 * if wired (falling edge), otherwise a polling thread at the integration time; the callback receives
 * {@code 1} when data is ready.
 */
public class As7262Full extends As7262Minimal {

    private static final double[] GAINS = {1.0, 3.7, 16.0, 64.0};
    private static final double[] DRV_CURRENTS = {12.5, 25.0, 50.0, 100.0};
    private static final double[] IND_CURRENTS = {1.0, 2.0, 4.0, 8.0};

    private volatile IntConsumer callback;
    private InputPin intPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection register-capable I²C connection bound to 0x49
     */
    public As7262Full(RegisterConnection connection) {
        super(connection);
    }

    private RawChannels readRawRegs() throws IOException {
        int[] v = new int[6];
        for (int ch = 0; ch < 6; ch++) {
            v[ch] = (readVirtual(REG_RAW + 2 * ch) << 8) | readVirtual(REG_RAW + 2 * ch + 1);
        }
        return new RawChannels(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /**
     * Run one one-shot conversion and return the raw 16-bit counts.
     *
     * @return raw counts
     * @throws IOException on a bus error or a conversion time-out
     */
    public RawChannels readRaw() throws IOException {
        startOneShot();
        waitData();
        return readRawRegs();
    }

    /**
     * Start a one-shot conversion without waiting for it ({@code BANK = 3}).
     *
     * @throws IOException on bus error
     */
    public void measure() throws IOException {
        startOneShot();
    }

    /**
     * @return true if a conversion result is waiting ({@code CONTROL_SETUP.DATA_RDY})
     * @throws IOException on bus error
     */
    public boolean dataReady() throws IOException {
        return (readVirtual(REG_CONTROL) & CTRL_DATA_RDY) != 0;
    }

    /**
     * Read the last calibrated results without starting a conversion.
     *
     * @return calibrated channel values
     * @throws IOException on bus error
     */
    public Channels readCalibratedLast() throws IOException {
        return readCalibrated();
    }

    /**
     * Read the last raw counts without starting a conversion.
     *
     * @return raw counts
     * @throws IOException on bus error
     */
    public RawChannels readRawLast() throws IOException {
        return readRawRegs();
    }

    /**
     * Read one calibrated channel of the last conversion.
     *
     * @param index channel 0–5 (V, B, G, Y, O, R)
     * @return calibrated value
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if index is outside 0–5
     */
    public float readChannel(int index) throws IOException {
        if (index < 0 || index > 5) throw new IllegalArgumentException("channel index must be 0-5");
        return readFloat(REG_CAL + 4 * index);
    }

    /**
     * Read one raw channel of the last conversion.
     *
     * @param index channel 0–5
     * @return raw count (0–65535)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if index is outside 0–5
     */
    public int readChannelRaw(int index) throws IOException {
        if (index < 0 || index > 5) throw new IllegalArgumentException("channel index must be 0-5");
        return (readVirtual(REG_RAW + 2 * index) << 8) | readVirtual(REG_RAW + 2 * index + 1);
    }

    private void updateControl(int mask, int bits) throws IOException {
        int ctrl = readVirtual(REG_CONTROL);
        writeVirtual(REG_CONTROL, (ctrl & 0x7C & ~mask) | bits);
    }

    /**
     * Set the analog gain.
     *
     * @param gain 0–3 (index), or the gain 1, 3.7, 16 or 64 (1 means 1x)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException for an unsupported gain
     */
    public void setGain(double gain) throws IOException {
        int index = -1;
        for (int i = 0; i < 4; i++) if (GAINS[i] == gain) index = i;
        if (index < 0 && (gain == 0 || gain == 2 || gain == 3)) index = (int) gain;
        if (index < 0) throw new IllegalArgumentException("gain must be 0-3 or one of 1, 3.7, 16, 64");
        updateControl(0x30, index << 4);
    }

    /**
     * @return the analog gain multiplier (1, 3.7, 16 or 64)
     * @throws IOException on bus error
     */
    public double getGain() throws IOException {
        return GAINS[(readVirtual(REG_CONTROL) >> 4) & 3];
    }

    /**
     * Set the integration time per bank.
     *
     * @param ms 2.8–714 ms (rounded to a multiple of 2.8 ms)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if ms is outside 2.8–714
     */
    public void setIntegrationTime(double ms) throws IOException {
        long n = Math.round(ms / STEP_MS);
        if (n < 1 || n > 255) throw new IllegalArgumentException("integration time must be 2.8-714 ms");
        writeVirtual(REG_INT_T, (int) n);
    }

    /**
     * @return the integration time per bank in milliseconds
     * @throws IOException on bus error
     */
    public double getIntegrationTime() throws IOException {
        return readVirtual(REG_INT_T) * STEP_MS;
    }

    /**
     * Set the bank mode.
     *
     * @param mode 0 (V,B,G,Y continuous), 1 (G,Y,O,R continuous), 2 (all six continuous) or 3 (all six, one-shot)
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if mode is outside 0–3
     */
    public void setMode(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("mode must be 0-3");
        updateControl(0x0C, mode << 2);
    }

    /**
     * @return the bank mode (0–3)
     * @throws IOException on bus error
     */
    public int getMode() throws IOException {
        return (readVirtual(REG_CONTROL) >> 2) & 3;
    }

    /**
     * Switch to continuous conversions.
     *
     * @param mode bank mode 0, 1 or 2
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if mode is not 0, 1 or 2
     */
    public void setContinuous(int mode) throws IOException {
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("continuous mode must be 0, 1 or 2");
        setMode(mode);
    }

    /**
     * Enable or disable the INT pin output ({@code CONTROL_SETUP.INT}).
     *
     * @param enabled true to enable
     * @throws IOException on bus error
     */
    public void setInterrupt(boolean enabled) throws IOException {
        updateControl(CTRL_INT, enabled ? CTRL_INT : 0);
    }

    private static int nearest(double[] options, double value) {
        if (value > options[3]) throw new IllegalArgumentException("current above maximum " + options[3] + " mA");
        int best = 0;
        for (int i = 1; i < 4; i++) if (Math.abs(options[i] - value) < Math.abs(options[best] - value)) best = i;
        return best;
    }

    /**
     * Control the driver LED output.
     *
     * @param on        true to enable the output
     * @param currentMa current limit; nearest of 12.5, 25, 50, 100 mA
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if currentMa is above 100
     */
    public void setLed(boolean on, double currentMa) throws IOException {
        int index = nearest(DRV_CURRENTS, currentMa);
        int led = readVirtual(REG_LED) & 0x07;
        writeVirtual(REG_LED, led | (index << 4) | (on ? 0x08 : 0));
    }

    /**
     * Control the indicator LED output.
     *
     * @param on        true to enable the output
     * @param currentMa current limit; nearest of 1, 2, 4, 8 mA
     * @throws IOException              on bus error
     * @throws IllegalArgumentException if currentMa is above 8
     */
    public void setIndicatorLed(boolean on, double currentMa) throws IOException {
        int index = nearest(IND_CURRENTS, currentMa);
        int led = readVirtual(REG_LED) & 0x38;
        writeVirtual(REG_LED, led | (index << 1) | (on ? 0x01 : 0));
    }

    /**
     * Soft-reset the chip (all registers return to their defaults).
     *
     * @throws IOException on a bus error or time-out
     */
    public void softReset() throws IOException {
        reset();
    }

    /**
     * @return {@code {device_type, hardware_version}} — expect {@code {0x40, 0x3E}}
     * @throws IOException on bus error
     */
    public int[] hardwareVersion() throws IOException {
        return new int[]{readVirtual(REG_HW_VERSION), readVirtual(REG_HW_VERSION + 1)};
    }

    /**
     * @return the firmware version as {@code {major, minor, sub}}
     * @throws IOException on bus error
     */
    public int[] firmwareVersion() throws IOException {
        int r2 = readVirtual(REG_FW_VERSION);
        int r3 = readVirtual(REG_FW_VERSION + 1);
        return new int[]{r3 >> 4, ((r3 & 0x0F) << 2) | (r2 >> 6), r2 & 0x3F};
    }

    /**
     * @return the device type byte (virtual register 0x00, expect 0x40)
     * @throws IOException on bus error
     */
    public int deviceType() throws IOException {
        return readVirtual(REG_HW_VERSION);
    }

    /**
     * Read {@code CONTROL_SETUP} (releases INT) and return {@code DATA_RDY}.
     *
     * @return 1 if data is ready, else 0
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return (readVirtual(REG_CONTROL) & CTRL_DATA_RDY) != 0 ? 1 : 0;
    }

    /**
     * Enable the INT output and subscribe to the INT pin ({@code connection.intPin()}, falling
     * edge), or poll every integration time when no pin is wired.
     *
     * @param callback receives 1 when data is ready
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to the INT pin, overriding which {@link InputPin} delivers edges.
     *
     * @param callback receives 1 when data is ready
     * @param pin      INT pin to arm, or {@code null} to force the polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin pin) throws IOException {
        offInterrupt();
        this.callback = callback;
        setInterrupt(true);
        if (pin != null) {
            this.intPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            long periodMs = Math.max((long) getIntegrationTime(), 10);
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(periodMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "as7262-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /**
     * Unsubscribe, stop delivery and disable the INT output.
     *
     * @throws IOException on bus error
     */
    public void offInterrupt() throws IOException {
        boolean was = callback != null;
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        if (was) setInterrupt(false);
    }

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException | IllegalStateException e) {
            // a failed poll delivers nothing
        }
    }
}
