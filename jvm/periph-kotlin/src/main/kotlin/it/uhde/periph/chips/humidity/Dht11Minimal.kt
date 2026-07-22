package it.uhde.periph.chips.humidity

import it.uhde.periph.transport.DHTxxTransport
import java.io.IOException

/**
 * DHT11 — combined temperature and humidity sensor (minimal driver).
 *
 * Reads a 40-bit frame (humidity integer + decimal, temperature integer +
 * decimal, checksum) over the DHTxx single-wire transport. The driver is
 * responsible for checksum validation and data conversion; the transport
 * handles all GPIO direction switching, timing, and bit decoding.
 *
 * Default configuration (baked in at construction):
 *  - Single read attempt; throws on checksum mismatch
 *  - Caller responsible for respecting the ≥ 2 s sampling interval
 */
open class Dht11Minimal @JvmOverloads constructor(
    protected val transport: DHTxxTransport
) {
    /** Raised on checksum error or invalid frame length. */
    class Dht11Exception(detail: String) : IOException(detail)

    /**
     * Read both temperature and humidity in a single transaction.
     *
     * @return Pair of (temperature_C, humidity_RH).
     * @throws IOException    on transport error.
     * @throws Dht11Exception on checksum mismatch.
     */
    @Throws(IOException::class, Dht11Exception::class)
    fun read(): Pair<Double, Double> {
        val frame = transport.read()
        return decode(frame)
    }

    /**
     * Decode a 5-byte frame into a (temperature, humidity) pair.
     */
    @Throws(Dht11Exception::class)
    protected fun decode(frame: ByteArray): Pair<Double, Double> {
        if (frame.size != 5) {
            throw Dht11Exception("frame must be 5 bytes, got ${frame.size}")
        }
        val humInt = frame[0].toInt() and 0xFF
        val humDec = frame[1].toInt() and 0xFF
        val tempInt = frame[2].toInt() and 0xFF
        val tempDec = frame[3].toInt() and 0xFF
        val checksum = frame[4].toInt() and 0xFF
        val expected = (humInt + humDec + tempInt + tempDec) and 0xFF
        if (expected != checksum) {
            throw Dht11Exception("checksum mismatch: expected 0x%02X, got 0x%02X".format(expected, checksum))
        }
        val humidity = humInt + humDec / 10.0
        val sign = if ((tempDec and 0x80) != 0) -1 else 1
        val tempDecValue = tempDec and 0x7F
        val temperature = sign * (tempInt + tempDecValue / 10.0)
        return Pair(temperature, humidity)
    }
}
