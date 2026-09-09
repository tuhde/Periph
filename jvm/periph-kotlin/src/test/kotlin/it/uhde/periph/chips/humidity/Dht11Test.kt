package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockDHTxxConnection
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class Dht11Test {

    private val eps = 0.001
    private val goodFrame = byteArrayOf(0x35, 0x00, 0x18, 0x04, 0x51)
    private val negTempFrame = byteArrayOf(0x20, 0x00, 0x0A, 0x81.toByte(), 0xAB.toByte())
    private val badChecksumFrame = byteArrayOf(0x35, 0x00, 0x18, 0x04, 0x00)
    private val shortFrame = byteArrayOf(0x35, 0x00, 0x18)

    @Test
    fun decodeDatasheetExample() {
        val conn = MockDHTxxConnection()
        conn.queueRead(goodFrame)
        val sensor = Dht11Minimal(conn)
        val (temperature, humidity) = sensor.read()
        assertEquals(24.4, temperature, eps)
        assertEquals(53.0, humidity, eps)
    }

    @Test
    fun decodeNegativeTemperature() {
        val conn = MockDHTxxConnection()
        conn.queueRead(negTempFrame)
        val sensor = Dht11Minimal(conn)
        val (temperature, humidity) = sensor.read()
        assertEquals(-10.1, temperature, eps)
        assertEquals(32.0, humidity, eps)
    }

    @Test
    fun checksumErrorThrows() {
        val conn = MockDHTxxConnection()
        conn.queueRead(badChecksumFrame)
        val sensor = Dht11Minimal(conn)
        assertThrows(Dht11Minimal.Dht11Exception::class.java) { sensor.read() }
    }

    @Test
    fun shortFrameThrows() {
        val conn = MockDHTxxConnection()
        conn.queueRead(shortFrame)
        val sensor = Dht11Minimal(conn)
        assertThrows(Dht11Minimal.Dht11Exception::class.java) { sensor.read() }
    }

    @Test
    fun readTemperatureAndHumidity() {
        val conn = MockDHTxxConnection()
        conn.queueRead(goodFrame)
        val sensor = Dht11Full(conn, 3)
        assertEquals(24.4, sensor.read().first, eps)

        conn.queueRead(goodFrame)
        assertEquals(53.0, sensor.read().second, eps)
    }

    // readRaw(): unprocessed frame, still checksum-validated. This is the
    // check that would catch a bug like C++'s DHT11Full::read_raw() (fixed
    // in this same effort — it forwarded the connection's bool result
    // without validating the checksum at all).
    @Test
    fun readRawReturnsFrameAndRejectsBadChecksum() {
        val conn = MockDHTxxConnection()
        conn.queueRead(goodFrame)
        val sensor = Dht11Full(conn, 3)
        assertArrayEquals(goodFrame, sensor.readRaw())

        conn.queueRead(badChecksumFrame)
        assertThrows(Dht11Minimal.Dht11Exception::class.java) { sensor.readRaw() }
    }

    @Test
    fun readRetrySucceedsAfterOneBadAttempt() {
        val conn = MockDHTxxConnection()
        conn.queueRead(badChecksumFrame) // attempt 1: bad checksum
        conn.queueRead(goodFrame)        // attempt 2: good
        val sensor = Dht11Full(conn, 3)
        val (temperature, humidity) = sensor.readRetry(3)
        assertEquals(24.4, temperature, eps)
        assertEquals(53.0, humidity, eps)
    }

    @Test
    fun readRetryExhaustedOnPersistentChecksumError() {
        val conn = MockDHTxxConnection()
        conn.queueRead(badChecksumFrame)
        conn.queueRead(badChecksumFrame)
        val sensor = Dht11Full(conn, 2)
        assertThrows(Dht11Minimal.Dht11Exception::class.java) { sensor.readRetry(2) }
    }

    // readRetry's catch clause is scoped to Dht11Exception only (`catch (e:
    // Dht11Minimal.Dht11Exception)`), not IOException — matching Python's
    // checksum-only catch scope (unlike Go/Rust/Node.js, which retry on any
    // error). A transport-level IOException must propagate immediately,
    // un-retried, even if a later attempt would have succeeded.
    @Test
    fun readRetryDoesNotCatchTransportError() {
        val conn = MockDHTxxConnection()
        conn.queueThrow(IOException("sensor timeout"))
        conn.queueRead(goodFrame) // would succeed if retried
        val sensor = Dht11Full(conn, 3)
        val thrown = assertThrows(IOException::class.java) { sensor.readRetry(3) }
        assertEquals("sensor timeout", thrown.message)
    }

    @Test
    fun readRetryZeroUsesConstructorDefault() {
        val conn = MockDHTxxConnection()
        conn.queueRead(badChecksumFrame)
        conn.queueRead(badChecksumFrame)
        conn.queueRead(goodFrame) // 3rd attempt succeeds
        val sensor = Dht11Full(conn, 3)
        val (temperature, _) = sensor.readRetry(0) // 0 -> constructor's default (3)
        assertEquals(24.4, temperature, eps)
    }
}
