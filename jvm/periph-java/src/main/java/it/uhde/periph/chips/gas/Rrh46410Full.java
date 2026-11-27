package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * RRH46410 — full interface: identity, operation modes (IAQ 2nd Gen, ULP, PBAQ, cleaning),
 * humidity compensation, GPIOs, status, reset, raw commands and the INT interrupt API.
 *
 * <p>INT is active high, push-pull: high when a new result is available, low after Get
 * Measurement Results or Set Operation Mode.
 */
public class Rrh46410Full extends Rrh46410Minimal {

    /**
     * The decoded status byte of the last response.
     *
     * @param raw            raw status byte
     * @param invalidCommand bit 7: invalid command, read without request or bus error
     * @param checksumError  bit 6: request checksum wrong
     * @param sensorNack     bit 5: internal gas-sensor I²C NACK
     * @param notComplete    bit 4: asynchronous command not complete
     * @param modeError      bits 3:0: 0 OK, 0xA sensor damaged
     */
    public record Status(int raw, boolean invalidCommand, boolean checksumError, boolean sensorNack,
                         boolean notComplete, int modeError) {}

    /**
     * Product ID and firmware version.
     *
     * @param productId 0x2310
     * @param major     firmware major version
     * @param minor     firmware minor version
     * @param patch     firmware patch version
     */
    public record ProductId(int productId, int major, int minor, int patch) {}

    private volatile Consumer<Measurement> callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::dispatch;

    /**
     * Creates the driver; no bus access.
     *
     * @param connection plain I²C connection bound to address 0x38
     */
    public Rrh46410Full(Connection connection) {
        super(connection);
    }

    /**
     * Get Product ID.
     *
     * @return product ID (0x2310) and firmware version
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized ProductId productId() throws IOException {
        byte[] d = transact(CMD_GET_PRODUCT_ID, new byte[0], 5, true);
        return new ProductId(((d[1] & 0xFF) << 8) | (d[2] & 0xFF), d[3] & 0xFF, d[4] & 0xFF, d[5] & 0xFF);
    }

    /**
     * Get Tracking Number.
     *
     * @return the 6-byte ASIC tracking number
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized byte[] trackingNumber() throws IOException {
        byte[] d = transact(CMD_GET_TRACKING_NUMBER, new byte[0], 6, true);
        byte[] out = new byte[6];
        System.arraycopy(d, 1, out, 0, 6);
        return out;
    }

    /**
     * Get Operation Mode.
     *
     * @return one of the MODE_* constants (other values behave as SUSPEND)
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized int getMode() throws IOException {
        mode = transact(CMD_GET_OPERATION_MODE, new byte[0], 1, true)[1] & 0xFF;
        return mode;
    }

    /**
     * Set Operation Mode; stops a running measurement, restarts the warm-up and sets INT low.
     * Use {@link #cleanSensor(boolean)} for the cleaning mode.
     *
     * @param newMode MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ
     * @throws IllegalArgumentException for another mode
     * @throws IOException              on a bus, checksum or status error
     */
    public synchronized void setMode(int newMode) throws IOException {
        if (newMode != MODE_SUSPEND && newMode != MODE_IAQ_2ND_GEN && newMode != MODE_ULP && newMode != MODE_PBAQ) {
            throw new IllegalArgumentException("mode must be MODE_SUSPEND, MODE_IAQ_2ND_GEN, MODE_ULP or MODE_PBAQ");
        }
        setModeRaw(newMode);
    }

    /**
     * Stops measuring (SUSPEND, about 0.7 µA).
     *
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized void suspend() throws IOException {
        setModeRaw(MODE_SUSPEND);
    }

    /**
     * Reads and decodes the latest result without waiting.
     *
     * @return the decoded measurement, or null before the first sample of the mode
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized Measurement readResults() throws IOException {
        byte[] d = fetch();
        return d == null ? null : decode(d);
    }

    /**
     * Waits until the sample counter changes (or INT goes high); the new sample is returned by
     * {@link #lastResult()}.
     *
     * @param timeout maximum wait in s
     * @return true when a new sample arrived
     * @throws IOException on a bus, checksum or status error
     */
    public boolean waitForSample(double timeout) throws IOException {
        return waitSample(timeout);
    }

    /** @return the result decoded by the last read, readResults or waitForSample (null before) */
    public synchronized Measurement lastResult() {
        return last;
    }

    /**
     * Set Humidity: the ambient relative humidity, sent as round(percent / 100 × 255).
     *
     * @param percent relative humidity in % (0–100)
     * @throws IllegalArgumentException if percent is out of range
     * @throws IOException              on a bus, checksum or status error
     */
    public synchronized void setHumidity(double percent) throws IOException {
        if (!(percent >= 0 && percent <= 100)) throw new IllegalArgumentException("percent must be 0..100");
        transact(CMD_SET_HUMIDITY, new byte[] {(byte) Math.round(percent / 100 * 255)}, 0, true);
    }

    /** @return the sample period of the current mode in s (3, 90, 5, 0 otherwise) */
    public double samplePeriod() {
        return period(mode);
    }

    /**
     * @param m operation mode
     * @return the sample period in s: 3 (IAQ 2nd Gen), 90 (ULP), 5 (PBAQ), 0 otherwise
     */
    public double samplePeriod(int m) {
        return period(m);
    }

    /** @return the warm-up samples of the current mode (100, 10, 60, 0 otherwise) */
    public int warmupSamples() {
        return warmup(mode);
    }

    /**
     * @param m operation mode
     * @return the warm-up samples: 100 (IAQ 2nd Gen), 10 (ULP), 60 (PBAQ), 0 otherwise
     */
    public int warmupSamples(int m) {
        return warmup(m);
    }

    /**
     * UBA rating text for an IAQ index.
     *
     * @param iaq IAQ index (1.0–5.0)
     * @return "very good" (&lt; 2.0), "good", "medium", "poor" or "bad" (≥ 5.0)
     */
    public static String airQualityLabel(double iaq) {
        if (iaq < 2.0) return "very good";
        if (iaq < 3.0) return "good";
        if (iaq < 4.0) return "medium";
        if (iaq < 5.0) return "poor";
        return "bad";
    }

    /**
     * Runs the one-time 1-minute sensor cleaning and waits (up to 90 s) for SUSPEND. Execute it
     * at most once in the sensor's lifetime and do not interrupt it.
     *
     * @param confirm must be true
     * @throws IllegalArgumentException if confirm is false
     * @throws IOException              on an error or when cleaning does not finish
     */
    public synchronized void cleanSensor(boolean confirm) throws IOException {
        if (!confirm) throw new IllegalArgumentException("cleanSensor runs once per sensor lifetime; pass confirm=true");
        setModeRaw(MODE_CLEANING);
        long deadline = System.currentTimeMillis() + 90_000;
        while (getMode() == MODE_CLEANING) {
            if (System.currentTimeMillis() > deadline) throw new Rrh46410Exception("RRH46410 cleaning did not finish", -1);
            sleep(1000);
        }
        mode = MODE_SUSPEND;
    }

    private static byte mask(int value) {
        if (value < 0 || value > 3) throw new IllegalArgumentException("GPIO mask must be 0..3");
        return (byte) value;
    }

    /**
     * Config GPIO.
     *
     * @param directions bit 0 = GPIO1 output, bit 1 = GPIO2 output (0–3)
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized void configGpio(int directions) throws IOException {
        transact(CMD_CONFIG_GPIO, new byte[] {mask(directions)}, 0, true);
    }

    /**
     * Get GPIO.
     *
     * @return bits 0–3 = GPIO1–GPIO4 levels
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized int getGpio() throws IOException {
        return transact(CMD_GET_GPIO, new byte[0], 1, true)[1] & 0xFF;
    }

    /**
     * Set GPIO: drives the outputs in the mask high.
     *
     * @param m bit 0 = GPIO1, bit 1 = GPIO2 (0–3)
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized void setGpio(int m) throws IOException {
        transact(CMD_SET_GPIO, new byte[] {mask(m)}, 0, true);
    }

    /**
     * Clear GPIO: drives the outputs in the mask low.
     *
     * @param m bit 0 = GPIO1, bit 1 = GPIO2 (0–3)
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized void clearGpio(int m) throws IOException {
        transact(CMD_CLEAR_GPIO, new byte[] {mask(m)}, 0, true);
    }

    /** @return the decoded status byte of the last response */
    public synchronized Status status() {
        int s = lastStatus;
        return new Status(s, (s & 0x80) != 0, (s & 0x40) != 0, (s & 0x20) != 0, (s & 0x10) != 0, s & 0x0F);
    }

    /**
     * Reset (0x8F, no response), waits 500 ms and verifies the product ID again; the module
     * restarts in SUSPEND.
     *
     * @throws IOException if the module does not answer afterwards
     */
    public synchronized void reset() throws IOException {
        try {
            connection.write(new byte[] {(byte) CMD_RESET, (byte) (~CMD_RESET & 0xFF)});
        } catch (IOException e) {
            // the module may not acknowledge while resetting
        }
        sleep(500);
        waitProductId();
        mode = MODE_SUSPEND;
        lastCounter = -1;
        samples = 0;
    }

    /**
     * Raw request with checksums; the status byte is available from {@link #status()}. Reset
     * (0x8F) sends the request only.
     *
     * @param command     command byte
     * @param params      request parameter bytes
     * @param responseLen number of response parameter bytes
     * @return the response parameters (empty for Reset)
     * @throws IOException on a bus or checksum error
     */
    public synchronized byte[] sendCommand(int command, byte[] params, int responseLen) throws IOException {
        if (command == CMD_RESET) {
            byte[] req = new byte[params.length + 2];
            req[0] = (byte) command;
            System.arraycopy(params, 0, req, 1, params.length);
            req[req.length - 1] = (byte) checksum(req, req.length - 1);
            connection.write(req);
            return new byte[0];
        }
        byte[] d = transact(command, params, responseLen, false);
        byte[] out = new byte[responseLen];
        System.arraycopy(d, 1, out, 0, responseLen);
        return out;
    }

    /**
     * Checks for a new sample without consuming it.
     *
     * @return true if the INT pin is high (pin wired), otherwise if the sample counter changed
     *         since the last decoded result
     * @throws IOException on a bus, checksum or status error
     */
    public synchronized boolean pollInterrupt() throws IOException {
        InputPin pin = connection.intPin();
        if (pin != null) return pin.isHigh();
        byte[] d = fetch();
        return d != null && (d[1] & 0xFF) != lastCounter;
    }

    private void dispatch() {
        Consumer<Measurement> cb = callback;
        if (cb == null) return;
        Measurement m;
        synchronized (this) {
            try {
                byte[] d = fetch();
                if (d == null || (d[1] & 0xFF) == lastCounter) return;
                m = decode(d);
            } catch (IOException e) {
                return;
            }
        }
        cb.accept(m);
    }

    /**
     * Subscribes to new samples on the connection's INT pin (or the polling fallback).
     *
     * @param callback called with each new decoded measurement
     */
    public void onInterrupt(Consumer<Measurement> callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribes to new samples: rising edge on {@code intPin}, or a 200 ms poll comparing the
     * sample counter when {@code intPin} is null. The driver reads the result (which sets INT
     * low) and passes the decoded measurement.
     *
     * @param callback called with each new decoded measurement
     * @param intPin   pin wired to INT, or {@code null} for the polling fallback
     */
    public void onInterrupt(Consumer<Measurement> callback, InputPin intPin) {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.RISING);
        } else {
            polling = true;
            pollThread = new Thread(() -> {
                while (polling) {
                    dispatch();
                    try {
                        Thread.sleep(pollMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "rrh46410-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribes and stops delivery. */
    public void offInterrupt() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }
}
