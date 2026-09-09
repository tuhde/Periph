package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockDHTxxConnection
import spock.lang.Specification

class Dht11Spec extends Specification {

    private static final double EPS = 0.001
    private static final byte[] GOOD_FRAME = [0x35, 0x00, 0x18, 0x04, 0x51] as byte[]
    private static final byte[] NEG_TEMP_FRAME = [0x20, 0x00, 0x0A, (byte) 0x81, (byte) 0xAB] as byte[]
    private static final byte[] BAD_CHECKSUM_FRAME = [0x35, 0x00, 0x18, 0x04, 0x00] as byte[]
    private static final byte[] SHORT_FRAME = [0x35, 0x00, 0x18] as byte[]

    def "decode datasheet example"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(GOOD_FRAME)
        def sensor = new Dht11Minimal(conn)

        when:
        def result = sensor.read()

        then:
        Math.abs(result[0] - 24.4d) < EPS
        Math.abs(result[1] - 53.0d) < EPS
    }

    def "decode negative temperature"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(NEG_TEMP_FRAME)
        def sensor = new Dht11Minimal(conn)

        when:
        def result = sensor.read()

        then:
        Math.abs(result[0] - (-10.1d)) < EPS
        Math.abs(result[1] - 32.0d) < EPS
    }

    def "checksum error throws"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(BAD_CHECKSUM_FRAME)
        def sensor = new Dht11Minimal(conn)

        when:
        sensor.read()

        then:
        thrown(Dht11Minimal.Dht11Exception)
    }

    def "short frame throws"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(SHORT_FRAME)
        def sensor = new Dht11Minimal(conn)

        when:
        sensor.read()

        then:
        thrown(Dht11Minimal.Dht11Exception)
    }

    def "readTemperature and readHumidity"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(GOOD_FRAME)
        def sensor = new Dht11Full(conn, 3)

        expect:
        Math.abs(sensor.read()[0] - 24.4d) < EPS

        when:
        conn.queueRead(GOOD_FRAME)

        then:
        Math.abs(sensor.read()[1] - 53.0d) < EPS
    }

    // readRaw(): unprocessed frame, still checksum-validated. This is the
    // check that would catch a bug like C++'s DHT11Full::read_raw() (fixed
    // in this same effort — it forwarded the connection's bool result
    // without validating the checksum at all).
    def "readRaw returns frame and rejects bad checksum"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(GOOD_FRAME)
        def sensor = new Dht11Full(conn, 3)

        expect:
        sensor.readRaw() == GOOD_FRAME

        when:
        conn.queueRead(BAD_CHECKSUM_FRAME)
        sensor.readRaw()

        then:
        thrown(Dht11Minimal.Dht11Exception)
    }

    def "readRetry succeeds after one bad attempt"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(BAD_CHECKSUM_FRAME) // attempt 1: bad checksum
        conn.queueRead(GOOD_FRAME)         // attempt 2: good
        def sensor = new Dht11Full(conn, 3)

        when:
        def result = sensor.readRetry(3)

        then:
        Math.abs(result[0] - 24.4d) < EPS
        Math.abs(result[1] - 53.0d) < EPS
    }

    def "readRetry exhausted on persistent checksum error"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(BAD_CHECKSUM_FRAME)
        conn.queueRead(BAD_CHECKSUM_FRAME)
        def sensor = new Dht11Full(conn, 2)

        when:
        sensor.readRetry(2)

        then:
        thrown(Dht11Minimal.Dht11Exception)
    }

    // readRetry's catch clause is scoped to Dht11Exception only (`catch
    // (Dht11Exception e)`), not IOException — matching Python's
    // checksum-only catch scope (unlike Go/Rust/Node.js, which retry on any
    // error). A transport-level IOException must propagate immediately,
    // un-retried, even if a later attempt would have succeeded.
    def "readRetry does not catch transport error"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueThrow(new IOException("sensor timeout"))
        conn.queueRead(GOOD_FRAME) // would succeed if retried
        def sensor = new Dht11Full(conn, 3)

        when:
        sensor.readRetry(3)

        then:
        IOException e = thrown(IOException)
        e.message == "sensor timeout"
    }

    def "readRetry zero uses constructor default"() {
        given:
        def conn = new MockDHTxxConnection()
        conn.queueRead(BAD_CHECKSUM_FRAME)
        conn.queueRead(BAD_CHECKSUM_FRAME)
        conn.queueRead(GOOD_FRAME) // 3rd attempt succeeds
        def sensor = new Dht11Full(conn, 3)

        when:
        def result = sensor.readRetry(0) // 0 -> constructor's default (3)

        then:
        Math.abs(result[0] - 24.4d) < EPS
    }
}
