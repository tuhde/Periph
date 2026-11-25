package it.uhde.periph.chips.light;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * MAX44009 full interface — extends {@link MAX44009Minimal} with raw readings,
 * measurement mode and range configuration, interrupt thresholds and delay,
 * and interrupt delivery.
 */
public class MAX44009Full extends MAX44009Minimal {

    /** Status bit 0: light level outside the threshold window for longer than the delay. */
    public static final int SOURCE_THRESHOLD = 0x01;

    private static final double[] INTEGRATION_MS = {800.0, 400.0, 200.0, 100.0, 50.0, 25.0, 12.5, 6.25};

    /** One consistent raw lux sample. */
    public static final class Raw {
        /** Exponent field 0–15 (15 = overrange). */
        public final int exponent;
        /** 8-bit mantissa. */
        public final int mantissa;
        /** True if the exponent field was 15. */
        public final boolean overrange;

        Raw(int exponent, int mantissa) {
            this.exponent = exponent;
            this.mantissa = mantissa;
            this.overrange = exponent >= 15;
        }
    }

    /** Decoded CONFIG register. */
    public static final class Config {
        public final boolean continuous;
        public final boolean manual;
        public final boolean divideBy8;
        public final double integrationMs;

        Config(boolean continuous, boolean manual, boolean divideBy8, double integrationMs) {
            this.continuous = continuous;
            this.manual = manual;
            this.divideBy8 = divideBy8;
            this.integrationMs = integrationMs;
        }
    }

    private volatile IntConsumer callback;
    private InputPin intPinUsed;
    private Thread pollThread;
    private volatile boolean polling;

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and run the initialization sequence.
     *
     * @param connection I²C register connection bound to the device (0x4A or 0x4B)
     * @throws IOException on bus error
     */
    public MAX44009Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /**
     * Read the lux registers as one consistent sample.
     *
     * @return exponent, mantissa and the overrange flag
     * @throws IOException on bus error
     */
    public Raw readRaw() throws IOException {
        int[] r = readLuxRegs();
        return new Raw(r[0], r[1]);
    }

    /**
     * Whether the last reading was overrange (E = 15).
     *
     * @return true if the last exponent field was 15
     */
    public boolean isOverrange() {
        return lastExponent >= 15;
    }

    /**
     * Lux per mantissa LSB for the last reading.
     *
     * @return {@code 2^E · 0.045}
     */
    public double resolution() {
        return (double) (1 << Math.min(lastExponent, 14)) * LUX_PER_LSB;
    }

    private void updateConfig(int setBits, int clearBits) throws IOException {
        int cfg = readReg(REG_CONFIG);
        writeReg(REG_CONFIG, (cfg & ~clearBits) | setBits);
    }

    /**
     * Select continuous or low-power measurement mode.
     *
     * @param continuous true: a new measurement as soon as one finishes; false: one every 800 ms
     * @throws IOException on bus error
     */
    public void setContinuous(boolean continuous) throws IOException {
        updateConfig(continuous ? CFG_CONT : 0, CFG_CONT);
    }

    /**
     * Select automatic mode (the chip chooses current divider and integration time).
     *
     * @throws IOException on bus error
     */
    public void setAutomatic() throws IOException {
        updateConfig(0, CFG_MANUAL);
    }

    /**
     * Select manual mode.
     *
     * @param integrationMs integration time; rounded to the nearest of 800, 400, 200, 100, 50, 25, 12.5, 6.25 ms
     * @param divideBy8     true to divide the photodiode current by 8 (high brightness)
     * @throws IOException on bus error
     */
    public void setManual(double integrationMs, boolean divideBy8) throws IOException {
        int best = 0;
        double bestErr = Math.abs(INTEGRATION_MS[0] - integrationMs);
        for (int tim = 1; tim < 8; tim++) {
            double err = Math.abs(INTEGRATION_MS[tim] - integrationMs);
            if (err < bestErr) {
                best = tim;
                bestErr = err;
            }
        }
        int cfg = readReg(REG_CONFIG);
        writeReg(REG_CONFIG, (cfg & CFG_CONT) | CFG_MANUAL | (divideBy8 ? CFG_CDR : 0) | best);
    }

    /**
     * Read the CONFIG register.
     *
     * @return the decoded configuration
     * @throws IOException on bus error
     */
    public Config getConfig() throws IOException {
        int cfg = readReg(REG_CONFIG);
        return new Config((cfg & CFG_CONT) != 0, (cfg & CFG_MANUAL) != 0, (cfg & CFG_CDR) != 0,
            INTEGRATION_MS[cfg & CFG_TIM_MASK]);
    }

    static int encodeThreshold(double lux) {
        if (lux < 11.5) {
            return Math.min(15, (int) Math.round(lux / (16 * LUX_PER_LSB)));
        }
        int exponent;
        double mantissa = 0;
        for (exponent = 1; exponent <= 14; exponent++) {
            mantissa = lux / ((double) (1 << exponent) * LUX_PER_LSB);
            if (mantissa < 256) break;
        }
        if (exponent > 14) exponent = 14;
        int m = Math.max(128, Math.min(255, (int) mantissa));
        return Math.min(0xEF, (exponent << 4) | (m >> 4));
    }

    static double decodeThreshold(int code, boolean upper) {
        int exponent = code >> 4;
        int nibble = code & 0x0F;
        return (double) (1 << exponent) * ((nibble << 4) | (upper ? 0x0F : 0x00)) * LUX_PER_LSB;
    }

    /**
     * Set the interrupt threshold window.
     *
     * @param lowLux  lower threshold in lux
     * @param highLux upper threshold in lux
     * @throws IllegalArgumentException if lowLux is greater than highLux
     * @throws IOException on bus error
     */
    public void setThresholds(double lowLux, double highLux) throws IOException {
        if (lowLux > highLux) {
            throw new IllegalArgumentException("lowLux must not exceed highLux");
        }
        writeReg(REG_LOWER, encodeThreshold(lowLux));
        writeReg(REG_UPPER, encodeThreshold(highLux));
    }

    /**
     * Read the interrupt threshold window (the upper one carries the implied low nibble 1111).
     *
     * @return {@code {lowLux, highLux}}
     * @throws IOException on bus error
     */
    public double[] getThresholds() throws IOException {
        int low = readReg(REG_LOWER);
        int high = readReg(REG_UPPER);
        return new double[]{decodeThreshold(low, false), decodeThreshold(high, true)};
    }

    /**
     * Set how long the light must stay outside the window before the interrupt.
     *
     * @param seconds delay in seconds, 0–25.5 (0.1 s steps)
     * @throws IOException on bus error
     */
    public void setThresholdDelay(double seconds) throws IOException {
        writeReg(REG_TIMER, Math.max(0, Math.min(255, (int) Math.round(seconds / 0.1))));
    }

    /**
     * Read the threshold delay.
     *
     * @return delay in seconds
     * @throws IOException on bus error
     */
    public double getThresholdDelay() throws IOException {
        return readReg(REG_TIMER) * 0.1;
    }

    /**
     * Enable the interrupt (INT_ENABLE = 1).
     *
     * @throws IOException on bus error
     */
    public void enableInterrupt() throws IOException {
        writeReg(REG_INT_ENABLE, 0x01);
    }

    /**
     * Disable the interrupt (INT_ENABLE = 0; also clears a pending status).
     *
     * @throws IOException on bus error
     */
    public void disableInterrupt() throws IOException {
        writeReg(REG_INT_ENABLE, 0x00);
    }

    /**
     * Read INT_STATUS (clearing it).
     *
     * @return status; bit 0 ({@link #SOURCE_THRESHOLD}) is set if the light was outside the window for longer than the delay
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return readReg(REG_INT_STATUS) & 0x01;
    }

    /**
     * Subscribe to INT assertions using {@code connection.intPin()}.
     *
     * @param callback called with the status after INT_STATUS has been read and cleared
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, null);
    }

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 100&nbsp;ms
     * polling thread if no {@link InputPin} is available.
     *
     * @param callback called with the status after INT_STATUS has been read and cleared
     * @param intPin   overrides {@code connection.intPin()}, or {@code null}
     */
    public synchronized void onInterrupt(IntConsumer callback, InputPin intPin) {
        this.callback = callback;
        startDelivery(intPin != null ? intPin : connection.intPin());
    }

    private void startDelivery(InputPin pin) {
        stopDelivery();
        intPinUsed = pin;
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    handleEdge();
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "max44009-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    private void stopDelivery() {
        if (intPinUsed != null) {
            intPinUsed.offEdge(edgeHandler);
            intPinUsed = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    /** Unsubscribe and stop delivery. */
    public synchronized void offInterrupt() {
        stopDelivery();
        callback = null;
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (status != 0 && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
