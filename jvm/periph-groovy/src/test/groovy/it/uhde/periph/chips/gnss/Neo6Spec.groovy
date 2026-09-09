package it.uhde.periph.chips.gnss

import it.uhde.periph.connection.Neo6ConnectionMock
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for {@link Neo6Minimal} / {@link Neo6Full} against {@link Neo6ConnectionMock} —
 * no hardware, no bus. Mirrors the coverage of python/tests/gnss/neo6_test_unit.py and
 * go/periph/chips/gnss/neo6_test.go. The JVM connection library has no SPI connection type,
 * so {@link BusType} only offers UART and I2C.
 */
class Neo6Spec extends Specification {

    private static final double EPS = 1e-6

    // Field lists are built explicitly and joined with ',' rather than hand-typed as
    // comma-heavy literals, to avoid miscounting empty fields.
    private static final String GGA_FIX = [
            'GPGGA', '092750.000', '5321.6802', 'N', '00630.3372', 'W',
            '1', '08', '1.03', '61.7', 'M', '55.2', 'M', '', '',
    ].join(',')
    private static final String GGA_NO_FIX = [
            'GPGGA', '092750.000', '', '', '', '',
            '0', '00', '', '', '', '', '', '', '',
    ].join(',')
    private static final String RMC = [
            'GPRMC', '092750.000', 'A', '5321.6802', 'N', '00630.3372', 'W',
            '022.4', '084.4', '230394', '003.1', 'W', 'A',
    ].join(',')
    private static final String VTG = [
            'GPVTG', '084.4', 'T', '077.4', 'M', '022.4', 'N', '041.5', 'K', 'A',
    ].join(',')

    /** Builds a "$&lt;body&gt;*XX\r\n" NMEA sentence with a correct XOR checksum. */
    private static byte[] nmeaSentence(String body) {
        int checksum = 0
        for (byte b : body.getBytes('US-ASCII')) {
            checksum ^= (b & 0xFF)
        }
        String s = '$' + body + String.format('*%02X\r\n', checksum)
        return s.getBytes('US-ASCII')
    }

    /**
     * Builds a UBX frame with a correct Fletcher checksum, computed here — independent of
     * Neo6Minimal's own (package-private) ubxChecksum().
     */
    private static byte[] ubxFrame(int msgClass, int msgId, byte[] payload = new byte[0]) {
        int length = payload.length
        def body = new ByteArrayOutputStream()
        body.write(msgClass)
        body.write(msgId)
        body.write(length & 0xFF)
        body.write((length >> 8) & 0xFF)
        body.write(payload)
        byte[] bodyBytes = body.toByteArray()

        int ckA = 0, ckB = 0
        for (byte b : bodyBytes) {
            ckA = (ckA + (b & 0xFF)) & 0xFF
            ckB = (ckB + ckA) & 0xFF
        }

        def frame = new ByteArrayOutputStream()
        frame.write(0xB5)
        frame.write(0x62)
        frame.write(bodyBytes)
        frame.write(ckA)
        frame.write(ckB)
        return frame.toByteArray()
    }

    /** Queues data and drives update() enough times to consume it all. */
    private static boolean feed(Neo6Minimal gps, Neo6ConnectionMock conn, byte[] data) {
        conn.queueBytes(data)
        boolean gotFix = false
        for (int i = 0; i < data.length; i++) {
            if (gps.update()) gotFix = true
        }
        return gotFix
    }

    private static byte[] lastWrite(Neo6ConnectionMock conn) {
        def writes = conn.writes()
        return writes.get(writes.size() - 1)
    }

    // --- Neo6Minimal: GGA decode across both bus types (UART, I2C) ---

    @Unroll
    def "GGA decode with fix over #busType"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Minimal(conn, busType)

        expect:
        gps.fix() == 0
        gps.latitude() == null

        when:
        boolean gotFix = feed(gps, conn, nmeaSentence(GGA_FIX))

        then:
        gotFix
        gps.fix() == 1
        gps.satellites() == 8
        Math.abs(gps.latitude() - 53.361336667d) < EPS
        Math.abs(gps.longitude() - (-6.505620d)) < EPS
        Math.abs(gps.altitude() - 61.7d) < 1e-3

        where:
        busType << BusType.values()
    }

    // --- Neo6Minimal: no-fix GGA updates fix/satellites but not lat/lon ---

    def "no-fix GGA keeps last position"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Minimal(conn)
        feed(gps, conn, nmeaSentence(GGA_FIX))

        when:
        boolean gotFix = feed(gps, conn, nmeaSentence(GGA_NO_FIX))

        then:
        !gotFix
        gps.fix() == 0
        Math.abs(gps.latitude() - 53.361336667d) < EPS
    }

    // --- Checksum validation: a corrupted sentence is silently discarded ---

    def "bad checksum is discarded"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Minimal(conn)
        byte[] bad = nmeaSentence(GGA_FIX)
        bad[bad.length - 4] ^= (byte) 0xFF // corrupt one checksum hex digit

        when:
        boolean gotFix = feed(gps, conn, bad)

        then:
        !gotFix
        gps.fix() == 0
    }

    // --- Leading 0xFF idle-filler bytes before '$' are ignored ---

    def "leading garbage is ignored"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Minimal(conn)
        byte[] data = [(byte) 0xFF, (byte) 0xFF, (byte) 0xFF] + nmeaSentence(GGA_FIX).toList()

        when:
        boolean gotFix = feed(gps, conn, data as byte[])

        then:
        gotFix
    }

    // --- Neo6Full: RMC (speed/course/utcTime/utcDate) ---

    def "RMC fields"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        feed(gps, conn, nmeaSentence(RMC))

        then:
        Math.abs(gps.speed() - 22.4d * 0.514444d) < 1e-4
        Math.abs(gps.course() - 84.4d) < EPS
        gps.utcTime() == '092750.000'
        gps.utcDate() == '230394'
    }

    // --- Neo6Full: VTG (course/speed) ---

    def "VTG fields"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        feed(gps, conn, nmeaSentence(VTG))

        then:
        Math.abs(gps.course() - 84.4d) < EPS
        Math.abs(gps.speed() - 41.5d / 3.6d) < 1e-4
    }

    // --- Neo6Full: GGA-derived HDOP ---

    def "GGA HDOP"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        feed(gps, conn, nmeaSentence(GGA_FIX))

        then:
        Math.abs(gps.hdop() - 1.03d) < EPS
    }

    // --- Neo6Full: sendUbx frames a correct message ---

    def "sendUbx frames correctly"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        gps.sendUbx(0x06, 0x08, [1, 2, 3] as byte[])

        then:
        lastWrite(conn) == ubxFrame(0x06, 0x08, [1, 2, 3] as byte[])
    }

    // --- Neo6Full: setRate / setPlatform / coldStart / saveConfig ---

    def "setRate sends CFG-RATE"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        gps.setRate(5)
        int measRateMs = 1000 / 5
        byte[] expected = [
                (byte) (measRateMs & 0xFF), (byte) ((measRateMs >> 8) & 0xFF),
                (byte) 1, (byte) 0,
                (byte) 0, (byte) 0,
        ]

        then:
        lastWrite(conn) == ubxFrame(0x06, 0x08, expected)
    }

    def "setPlatform sends CFG-NAV5"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        gps.setPlatform(4)
        byte[] expected = new byte[36]
        expected[0] = 0x01
        expected[2] = 4

        then:
        lastWrite(conn) == ubxFrame(0x06, 0x24, expected)
    }

    def "coldStart sends CFG-RST"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        gps.coldStart()
        byte[] expected = [(byte) 0xFF, (byte) 0xFF, (byte) 0x02, (byte) 0x00]

        then:
        lastWrite(conn) == ubxFrame(0x06, 0x04, expected)
    }

    def "saveConfig sends CFG-CFG"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)

        when:
        gps.saveConfig()
        byte[] expected = [
                0, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                0, 0, 0, 0,
                0x07,
        ]

        then:
        lastWrite(conn) == ubxFrame(0x06, 0x09, expected)
    }

    // --- Neo6Full: pollUbx returns the response payload on a matching frame ---

    def "pollUbx returns payload"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)
        byte[] responsePayload = (0..27).collect { (byte) it } as byte[]
        conn.queueBytes(ubxFrame(0x01, 0x02, responsePayload))

        when:
        byte[] payload = gps.pollUbx(0x01, 0x02)

        then:
        payload == responsePayload
        conn.writes().get(0) == ubxFrame(0x01, 0x02)
    }

    // --- Neo6Full: pollUbx throws IOException on an ACK-NAK response ---

    def "pollUbx throws on ACK-NAK"() {
        given:
        def conn = new Neo6ConnectionMock()
        def gps = new Neo6Full(conn)
        conn.queueBytes(ubxFrame(0x05, 0x00, [0x06, 0x08] as byte[])) // ACK-NAK for CFG-RATE

        when:
        gps.pollUbx(0x06, 0x08)

        then:
        thrown(IOException)
    }
}
