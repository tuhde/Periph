package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.transport.DHTxxTransport

/**
 * DHT11 — combined temperature and humidity sensor (full driver).
 *
 * Extends {@link Dht11Minimal} with a configurable-retry read, a
 * {@code readRetry} convenience method, and {@code readRaw} access to the
 * unprocessed 5-byte frame.
 */
@CompileStatic
class Dht11Full extends Dht11Minimal {

    private final int maxRetries

    Dht11Full(DHTxxTransport transport) {
        this(transport, 3)
    }

    Dht11Full(DHTxxTransport transport, int maxRetries) {
        super(transport)
        this.maxRetries = maxRetries
    }

    /**
     * Read both values, retrying on checksum error.
     *
     * @param maxRetries Maximum number of read attempts. If 0, the constructor default is used.
     * @return 2-element array: [temperature_C, humidity_RH].
     * @throws Dht11Exception if all attempts fail with a checksum error.
     */
    double[] readRetry(int maxRetries = 0) throws Exception {
        int n = maxRetries > 0 ? maxRetries : this.maxRetries
        Dht11Exception last = null
        for (int i = 0; i < n; i++) {
            try {
                return read()
            } catch (Dht11Exception e) {
                last = e
            }
        }
        throw new Dht11Exception("readRetry exhausted after ${n} attempts: ${last?.message ?: ''}")
    }

    /**
     * Read the raw 5-byte frame (after validating the checksum).
     *
     * @return 5-byte frame: [hum_int, hum_dec, temp_int, temp_dec, checksum].
     */
    byte[] readRaw() throws Exception {
        byte[] frame = transport.read()
        decode(frame)
        return frame
    }
}
