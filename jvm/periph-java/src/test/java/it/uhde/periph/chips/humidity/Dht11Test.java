package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.MockDHTxxConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Dht11Test {

    private static final double EPS = 0.001;
    private static final byte[] GOOD_FRAME = {0x35, 0x00, 0x18, 0x04, 0x51};
    private static final byte[] NEG_TEMP_FRAME = {0x20, 0x00, 0x0A, (byte) 0x81, (byte) 0xAB};
    private static final byte[] BAD_CHECKSUM_FRAME = {0x35, 0x00, 0x18, 0x04, 0x00};
    private static final byte[] SHORT_FRAME = {0x35, 0x00, 0x18};

    @Test
    void decodeDatasheetExample() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueRead(GOOD_FRAME);
        var sensor = new Dht11Minimal(conn);
        double[] result = sensor.read();
        assertEquals(24.4, result[0], EPS);
        assertEquals(53.0, result[1], EPS);
    }

    @Test
    void decodeNegativeTemperature() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueRead(NEG_TEMP_FRAME);
        var sensor = new Dht11Minimal(conn);
        double[] result = sensor.read();
        assertEquals(-10.1, result[0], EPS);
        assertEquals(32.0, result[1], EPS);
    }

    @Test
    void checksumErrorThrows() {
        var conn = new MockDHTxxConnection();
        conn.queueRead(BAD_CHECKSUM_FRAME);
        var sensor = new Dht11Minimal(conn);
        assertThrows(Dht11Minimal.Dht11Exception.class, sensor::read);
    }

    @Test
    void shortFrameThrows() {
        var conn = new MockDHTxxConnection();
        conn.queueRead(SHORT_FRAME);
        var sensor = new Dht11Minimal(conn);
        assertThrows(Dht11Minimal.Dht11Exception.class, sensor::read);
    }

    @Test
    void readTemperatureAndHumidity() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueRead(GOOD_FRAME);
        var sensor = new Dht11Full(conn, 3);
        assertEquals(24.4, sensor.readTemperature(), EPS);

        conn.queueRead(GOOD_FRAME);
        assertEquals(53.0, sensor.readHumidity(), EPS);
    }

    // readRaw(): unprocessed frame, still checksum-validated. This is the
    // check that would catch a bug like C++'s DHT11Full::read_raw() (fixed
    // in this same effort at cpp/src/chips/humidity/DHT11.h — it forwarded
    // the connection's bool result without validating the checksum at all).
    // The JVM readRaw() already validates via decode(frame) below.
    @Test
    void readRawReturnsFrameAndRejectsBadChecksum() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueRead(GOOD_FRAME);
        var sensor = new Dht11Full(conn, 3);
        assertArrayEquals(GOOD_FRAME, sensor.readRaw());

        conn.queueRead(BAD_CHECKSUM_FRAME);
        assertThrows(Dht11Minimal.Dht11Exception.class, sensor::readRaw);
    }

    @Test
    void readRetrySucceedsAfterOneBadAttempt() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueRead(BAD_CHECKSUM_FRAME); // attempt 1: bad checksum
        conn.queueRead(GOOD_FRAME);         // attempt 2: good
        var sensor = new Dht11Full(conn, 3);
        double[] result = sensor.readRetry(3);
        assertEquals(24.4, result[0], EPS);
        assertEquals(53.0, result[1], EPS);
    }

    @Test
    void readRetryExhaustedOnPersistentChecksumError() {
        var conn = new MockDHTxxConnection();
        conn.queueRead(BAD_CHECKSUM_FRAME);
        conn.queueRead(BAD_CHECKSUM_FRAME);
        var sensor = new Dht11Full(conn, 2);
        assertThrows(Dht11Minimal.Dht11Exception.class, () -> sensor.readRetry(2));
    }

    // readRetry's catch clause is scoped to Dht11Exception only (`catch
    // (Dht11Exception e)`), not IOException — matching Python's
    // checksum-only catch scope (unlike Go/Rust/Node.js, which retry on
    // any error). A transport-level IOException must propagate immediately,
    // un-retried, even if a later attempt would have succeeded.
    @Test
    void readRetryDoesNotCatchTransportError() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueThrow(new IOException("sensor timeout"));
        conn.queueRead(GOOD_FRAME); // would succeed if retried
        var sensor = new Dht11Full(conn, 3);
        IOException thrown = assertThrows(IOException.class, () -> sensor.readRetry(3));
        assertEquals("sensor timeout", thrown.getMessage());
    }

    @Test
    void readRetryZeroUsesConstructorDefault() throws Exception {
        var conn = new MockDHTxxConnection();
        conn.queueRead(BAD_CHECKSUM_FRAME);
        conn.queueRead(BAD_CHECKSUM_FRAME);
        conn.queueRead(GOOD_FRAME); // 3rd attempt succeeds
        var sensor = new Dht11Full(conn, 3);
        double[] result = sensor.readRetry(0); // 0 -> constructor's default (3)
        assertEquals(24.4, result[0], EPS);
    }
}
