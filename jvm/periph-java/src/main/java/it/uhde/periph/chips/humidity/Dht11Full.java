package it.uhde.periph.chips.humidity;

import it.uhde.periph.transport.DHTxxTransport;

import java.io.IOException;

/**
 * DHT11 — combined temperature and humidity sensor (full driver).
 *
 * <p>Extends {@link Dht11Minimal} with a configurable-retry read, a
 * {@code read_retry(maxRetries)} convenience method, and {@code readRaw()}
 * access to the unprocessed 5-byte frame.
 */
public class Dht11Full extends Dht11Minimal {

    private final int maxRetries;

    /**
     * Construct the driver.
     *
     * @param transport   Configured DHTxx transport bound to the chip's DATA pin.
     * @param maxRetries  Default retry count for {@link #readRetry(int)} (default 3).
     */
    public Dht11Full(DHTxxTransport transport) {
        this(transport, 3);
    }

    public Dht11Full(DHTxxTransport transport, int maxRetries) {
        super(transport);
        this.maxRetries = maxRetries;
    }

    /**
     * Read both values, retrying on checksum error.
     *
     * @param maxRetries Maximum number of read attempts. If 0, the constructor default is used.
     * @return 2-element array: [temperature_C, humidity_RH].
     * @throws IOException    on transport error.
     * @throws Dht11Exception if all attempts fail with a checksum error.
     */
    public double[] readRetry(int maxRetries) throws IOException, Dht11Exception {
        int n = maxRetries > 0 ? maxRetries : this.maxRetries;
        Dht11Exception last = null;
        for (int i = 0; i < n; i++) {
            try {
                return read();
            } catch (Dht11Exception e) {
                last = e;
            }
        }
        throw new Dht11Exception("readRetry exhausted after " + n + " attempts: " + (last != null ? last.getMessage() : ""));
    }

    /**
     * Read the raw 5-byte frame (after validating the checksum).
     *
     * @return 5-byte frame: [hum_int, hum_dec, temp_int, temp_dec, checksum].
     * @throws IOException    on transport error.
     * @throws Dht11Exception on checksum mismatch.
     */
    public byte[] readRaw() throws IOException, Dht11Exception {
        byte[] frame = transport.read();
        decode(frame);
        return frame;
    }
}
