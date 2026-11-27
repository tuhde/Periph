package it.uhde.periph.chips.gas;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;

/**
 * RRH46410 indoor air quality gas sensor module (Renesas) — minimal interface: one call returns
 * the IAQ index, TVOC, ethanol equivalent, estimated CO₂ and relative IAQ of the IAQ 2nd Gen
 * algorithm.
 *
 * <p>The host sends a request {@code [command, params…, checksum]} in one I²C write and reads the
 * response {@code [status, params…, checksum]} in a separate read, where
 * {@code checksum = ~(sum of the other bytes)}. The module stretches the I²C clock; its address is
 * fixed at {@code 0x38}.
 *
 * <p>{@link #init()} retries Get Product ID for up to 1 s, verifies {@code 0x2310} and selects
 * the IAQ 2nd Gen mode (one sample every 3 s; the first 100 samples, about 5 minutes, are the
 * warm-up during which the values stay fixed). The module's default humidity (50 %RH) is kept
 * and the GPIOs are unused.
 */
public class Rrh46410Minimal {

    /** RRH46410 protocol or status error; {@link #status()} is the status byte or -1. */
    public static class Rrh46410Exception extends IOException {
        private final int status;

        /**
         * @param message description
         * @param status  status byte of the failing response, or -1 for checksum / timeout /
         *                identity errors
         */
        public Rrh46410Exception(String message, int status) {
            super(message);
            this.status = status;
        }

        /** @return the status byte, or -1 for checksum / timeout / identity errors */
        public int status() { return status; }
    }

    /**
     * A decoded measurement (Get Measurement Results).
     *
     * @param sample      sample counter (0–255, wraps)
     * @param iaq         IAQ index, UBA level 1.0–5.0 (0 in PBAQ mode)
     * @param tvocMgM3    TVOC in mg/m³
     * @param etohPpm     ethanol equivalent in ppm
     * @param eco2Ppm     estimated CO₂ in ppm (0 in PBAQ mode)
     * @param relativeIaq relative IAQ (0 in PBAQ mode)
     * @param warmedUp    true after the warm-up samples of the mode
     * @param pbaq        true when decoded in PBAQ mode (only TVOC and ethanol valid)
     */
    public record Measurement(int sample, double iaq, double tvocMgM3, double etohPpm, int eco2Ppm,
                              int relativeIaq, boolean warmedUp, boolean pbaq) {}

    /** Fixed 7-bit I²C address. */
    public static final int ADDRESS = 0x38;
    /** Expected Get Product ID value. */
    public static final int PRODUCT_ID = 0x2310;

    /** No measurement (default after power-up and reset). */
    public static final int MODE_SUSPEND = 0x00;
    /** IAQ 2nd Gen: one sample every 3 s, 100 warm-up samples. */
    public static final int MODE_IAQ_2ND_GEN = 0x01;
    /** Ultra-low power: one sample every 90 s, 10 warm-up samples. */
    public static final int MODE_ULP = 0x02;
    /** Public-building air quality: TVOC and ethanol every 5 s, 60 warm-up samples. */
    public static final int MODE_PBAQ = 0x05;
    /** One-time sensor cleaning (1 minute). */
    public static final int MODE_CLEANING = 0x80;

    protected static final int CMD_GET_PRODUCT_ID = 0x00;
    protected static final int CMD_GET_TRACKING_NUMBER = 0x01;
    protected static final int CMD_GET_OPERATION_MODE = 0x10;
    protected static final int CMD_SET_OPERATION_MODE = 0x11;
    protected static final int CMD_SET_HUMIDITY = 0x12;
    protected static final int CMD_GET_MEASUREMENT_RESULTS = 0x18;
    protected static final int CMD_CONFIG_GPIO = 0x20;
    protected static final int CMD_GET_GPIO = 0x21;
    protected static final int CMD_SET_GPIO = 0x22;
    protected static final int CMD_CLEAR_GPIO = 0x23;
    protected static final int CMD_RESET = 0x8F;

    protected static final int STATUS_NOT_COMPLETE = 0x10;

    protected final Connection connection;
    protected int mode = MODE_SUSPEND;
    protected int lastStatus;
    protected int lastCounter = -1;
    protected int samples;
    protected Measurement last;
    /** Result polling interval in ms while waiting for a sample. */
    protected long pollMs = 200;

    /**
     * Creates the driver; no bus access.
     *
     * @param connection plain I²C connection bound to address 0x38
     */
    public Rrh46410Minimal(Connection connection) {
        this.connection = connection;
    }

    /**
     * Waits for the module (up to 1 s), verifies the product ID and starts IAQ 2nd Gen mode.
     *
     * @throws IOException if the module does not answer or the product ID is not 0x2310
     */
    public synchronized void init() throws IOException {
        waitProductId();
        setModeRaw(MODE_IAQ_2ND_GEN);
    }

    /**
     * Waits for the next sample (sample period plus 2 s) and decodes it.
     *
     * @return the decoded measurement
     * @throws IOException on a bus, checksum or status error, or when no new sample arrives
     */
    public Measurement read() throws IOException {
        if (!waitSample(period(mode) + 2.0)) throw new Rrh46410Exception("RRH46410 sample timeout", -1);
        synchronized (this) {
            return last;
        }
    }

    protected static int checksum(byte[] data, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xFF;
        return ~sum & 0xFF;
    }

    protected static double period(int mode) {
        return switch (mode) {
            case MODE_IAQ_2ND_GEN -> 3.0;
            case MODE_ULP -> 90.0;
            case MODE_PBAQ -> 5.0;
            default -> 0.0;
        };
    }

    protected static int warmup(int mode) {
        return switch (mode) {
            case MODE_IAQ_2ND_GEN -> 100;
            case MODE_ULP -> 10;
            case MODE_PBAQ -> 60;
            default -> 0;
        };
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends a request and reads the status byte and {@code n} parameter bytes.
     *
     * @return {@code [status, params…]}
     */
    protected byte[] transact(int command, byte[] params, int n, boolean check) throws IOException {
        byte[] req = new byte[params.length + 2];
        req[0] = (byte) command;
        System.arraycopy(params, 0, req, 1, params.length);
        req[req.length - 1] = (byte) checksum(req, req.length - 1);
        connection.write(req);
        byte[] resp = connection.read(n + 2);
        if (resp.length < n + 2 || checksum(resp, n + 1) != (resp[n + 1] & 0xFF)) {
            throw new Rrh46410Exception(String.format("RRH46410 response checksum error (command 0x%02X)", command), -1);
        }
        lastStatus = resp[0] & 0xFF;
        if (check && lastStatus != 0) {
            throw new Rrh46410Exception(String.format("RRH46410 status 0x%02X (command 0x%02X)", lastStatus, command), lastStatus);
        }
        byte[] out = new byte[n + 1];
        System.arraycopy(resp, 0, out, 0, n + 1);
        return out;
    }

    protected void waitProductId() throws IOException {
        long deadline = System.currentTimeMillis() + 1000;
        byte[] d;
        while (true) {
            try {
                d = transact(CMD_GET_PRODUCT_ID, new byte[0], 5, true);
                break;
            } catch (IOException e) {
                if (System.currentTimeMillis() > deadline) throw new Rrh46410Exception("RRH46410 not responding", -1);
                sleep(50);
            }
        }
        int pid = ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
        if (pid != PRODUCT_ID) {
            throw new Rrh46410Exception(String.format("Unexpected RRH46410 product ID 0x%04X, expected 0x2310", pid), -1);
        }
    }

    protected void setModeRaw(int newMode) throws IOException {
        transact(CMD_SET_OPERATION_MODE, new byte[] {(byte) newMode}, 0, true);
        mode = newMode;
        lastCounter = -1;
        samples = 0;
    }

    /** @return the raw result {@code [status, params…]}, or null before the first sample */
    protected byte[] fetch() throws IOException {
        int n = mode == MODE_PBAQ ? 5 : 9;
        byte[] d = transact(CMD_GET_MEASUREMENT_RESULTS, new byte[0], n, false);
        int status = d[0] & 0xFF;
        if (status == STATUS_NOT_COMPLETE) return null;
        if (status != 0) throw new Rrh46410Exception(String.format("RRH46410 status 0x%02X (command 0x18)", status), status);
        return d;
    }

    protected Measurement decode(byte[] d) {
        int counter = d[1] & 0xFF;
        if (lastCounter < 0) samples = counter;
        else samples += (counter - lastCounter) & 0xFF;
        lastCounter = counter;
        boolean warmedUp = samples >= warmup(mode);
        if (mode == MODE_PBAQ) {
            last = new Measurement(counter, 0, u16(d, 2) / 1000.0, u16(d, 4) / 1000.0, 0, 0, warmedUp, true);
        } else {
            last = new Measurement(counter, (d[2] & 0xFF) / 10.0, u16(d, 3) / 100.0, u16(d, 5) / 100.0,
                    u16(d, 7), (d[9] & 0xFF) * 10, warmedUp, false);
        }
        return last;
    }

    private static int u16(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    /** Polls until the sample counter changes; stores the decoded result. Timeout in s. */
    protected boolean waitSample(double timeout) throws IOException {
        long deadline = System.currentTimeMillis() + (long) (timeout * 1000);
        InputPin pin = connection.intPin();
        int prev;
        synchronized (this) {
            prev = lastCounter;
        }
        while (true) {
            if (pin == null || pin.isHigh()) {
                synchronized (this) {
                    byte[] d = fetch();
                    if (d != null && (prev < 0 || (d[1] & 0xFF) != prev)) {
                        decode(d);
                        return true;
                    }
                }
            }
            if (System.currentTimeMillis() > deadline) return false;
            sleep(pollMs);
        }
    }
}
