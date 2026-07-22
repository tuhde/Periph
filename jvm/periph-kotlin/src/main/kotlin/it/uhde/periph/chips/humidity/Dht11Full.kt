package it.uhde.periph.chips.humidity

import it.uhde.periph.transport.DHTxxTransport
import java.io.IOException

/**
 * DHT11 — combined temperature and humidity sensor (full driver).
 *
 * Extends [Dht11Minimal] with a configurable-retry read, a
 * [readRetry] convenience method, and [readRaw] access to the
 * unprocessed 5-byte frame.
 */
class Dht11Full @JvmOverloads constructor(
    transport: DHTxxTransport,
    private val maxRetries: Int = 3
) : Dht11Minimal(transport) {

    /**
     * Read both values, retrying on checksum error.
     *
     * @param maxRetries Maximum number of read attempts. If 0, the constructor default is used.
     * @return Pair of (temperature_C, humidity_RH).
     * @throws IOException    on transport error.
     * @throws Dht11Minimal.Dht11Exception if all attempts fail with a checksum error.
     */
    @Throws(IOException::class, Dht11Minimal.Dht11Exception::class)
    fun readRetry(maxRetries: Int = 0): Pair<Double, Double> {
        val n = if (maxRetries > 0) maxRetries else this.maxRetries
        var last: Dht11Minimal.Dht11Exception? = null
        for (i in 0 until n) {
            try {
                return read()
            } catch (e: Dht11Minimal.Dht11Exception) {
                last = e
            }
        }
        throw Dht11Minimal.Dht11Exception("readRetry exhausted after $n attempts: ${last?.message}")
    }

    /**
     * Read the raw 5-byte frame (after validating the checksum).
     *
     * @return 5-byte frame: [hum_int, hum_dec, temp_int, temp_dec, checksum].
     */
    @Throws(IOException::class, Dht11Minimal.Dht11Exception::class)
    fun readRaw(): ByteArray {
        val frame = transport.read()
        decode(frame)
        return frame
    }
}
