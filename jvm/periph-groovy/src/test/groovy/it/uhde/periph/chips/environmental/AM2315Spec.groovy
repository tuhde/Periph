package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class AM2315Spec extends Specification {

    MockConnection mock
    AM2315Full s

    static byte[] resp(int... body) {
        byte[] f = new byte[body.length + 2]
        for (int i = 0; i < body.length; i++) f[i] = (byte) body[i]
        int crc = AM2315Minimal.crc16(f, 0, body.length)
        f[body.length] = (byte) crc
        f[body.length + 1] = (byte) (crc >> 8)
        return f
    }

    static byte[] readResp(int... data) {
        List<Integer> body = [0x03, data.length]
        data.each { int d -> body << d }
        return resp(body as int[])
    }

    static byte[] bytes(int... v) {
        byte[] b = new byte[v.length]
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]
        return b
    }

    byte[] lastWrite() { return mock.writes().last() }

    def setup() {
        mock = new MockConnection()
        s = new AM2315Full(mock)
        s.setMinIntervalMs(0L)      // tests do not wait the 2 s between transactions
    }

    def "crc reference"() {
        expect:
        AM2315Minimal.crc16(bytes(0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA), 0, 6) == 0xA531
        AM2315Minimal.crc16(bytes(0x03, 0x04, 0x03, 0x39, 0x01, 0x15), 0, 6) == 0xFEE1
        AM2315Minimal.crc16(bytes(0x10, 0x10, 0x02, 0x01, 0x02), 0, 5) == 0x92C0
    }

    def "constructor does no bus access"() {
        expect:
        mock.writes().isEmpty()
        new AM2315Minimal(new MockConnection()) != null
    }

    def "read wakes then commands then decodes"() {
        when:
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        def v = s.read()

        then:
        mock.writes().size() == 2
        mock.writes()[0] == bytes(0x00)
        mock.writes()[1] == bytes(0x03, 0x00, 0x04)
        Math.abs(v[0] - 25.0d) < 1e-9d
        Math.abs(v[1] - 50.0d) < 1e-9d
    }

    def "datasheet frame"() {
        when:
        mock.queueRead(bytes(0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE))
        def v = s.read()

        then:
        Math.abs(v[0] - 27.7d) < 1e-9d
        Math.abs(v[1] - 82.5d) < 1e-9d
    }

    def "negative temperature is sign magnitude"() {
        when:
        mock.queueRead(readResp(0x01, 0xF4, 0x80, 0x65))

        then:
        Math.abs(s.read()[0] + 10.1d) < 1e-9d
    }

    def "wake nack is ignored"() {
        when:
        mock.failNextWrites(1, 0)
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))

        then:
        Math.abs(s.read()[1] - 50.0d) < 1e-9d
    }

    def "crc retry recovers then fails"() {
        given:
        byte[] bad = readResp(0x01, 0xF4, 0x00, 0xFA)
        bad[bad.length - 1] = (byte) (bad[bad.length - 1] ^ 0xFF)

        when:
        mock.queueRead(bad)
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        def v = s.read()

        then:
        Math.abs(v[1] - 50.0d) < 1e-9d
        mock.writes().size() == 4

        when:
        setup()
        mock.queueRead(bad)
        mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)
    }

    def "bus error is retried once"() {
        when:
        mock.failNextReads(1)
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))

        then:
        Math.abs(s.read()[0] - 25.0d) < 1e-9d

        when:
        setup()
        mock.failNextReads(2)
        s.read()

        then:
        thrown(IOException)
    }

    def "error response is not retried"() {
        when:
        mock.queueRead(bytes(0x83, 0, 0, 0))
        s.read()

        then:
        def e = thrown(AM2315Minimal.ErrorResponse)
        e.code() == 0x83
        mock.writes().size() == 2
    }

    def "minimum interval is enforced"() {
        given:
        def c = new MockConnection()
        def m = new AM2315Minimal(c)
        m.setMinIntervalMs(50L)
        c.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))

        when:
        long t0 = System.nanoTime()
        m.read()

        then:
        (System.nanoTime() - t0).intdiv(1_000_000L) >= 40L
    }

    def "single values and raw"() {
        when:
        mock.queueRead(readResp(0x00, 0x7B))

        then:
        Math.abs(s.readHumidity() - 12.3d) < 1e-9d
        lastWrite() == bytes(0x03, 0x00, 0x02)

        when:
        mock.queueRead(readResp(0x80, 0x65))

        then:
        Math.abs(s.readTemperature() + 10.1d) < 1e-9d
        lastWrite() == bytes(0x03, 0x02, 0x02)

        when:
        mock.queueRead(readResp(0x01, 0xF4, 0x80, 0x65))

        then:
        s.readRaw() == [0x01F4, 0x8065] as int[]
    }

    def "read fresh returns second"() {
        when:
        mock.queueRead(readResp(0x01, 0xF4, 0x00, 0xFA))
        mock.queueRead(readResp(0x01, 0xF9, 0x01, 0x00))
        def v = s.readFresh()

        then:
        Math.abs(v[0] - 25.6d) < 1e-9d
        Math.abs(v[1] - 50.5d) < 1e-9d
    }

    def "register read and limits"() {
        when:
        mock.queueRead(readResp(1, 2, 3))

        then:
        s.readRegisters(0x08, 3) == bytes(1, 2, 3)

        when:
        s.readRegisters(0, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        s.readRegisters(0, 11)

        then:
        thrown(IllegalArgumentException)
    }

    def "device info"() {
        when:
        mock.queueRead(readResp(0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78))

        then:
        s.readDeviceInfo() == [0x090FL, 0x10L, 0x12345678L] as long[]
        lastWrite() == bytes(0x03, 0x08, 0x07)
    }

    def "user registers"() {
        when:
        mock.queueRead(readResp(0x12, 0x34, 0xAB, 0xCD))

        then:
        s.readUserRegisters() == [0x1234, 0xABCD] as int[]

        when:
        mock.queueRead(resp(0x10, 0x10, 0x04))
        s.writeUserRegisters(0x1234, 0xABCD)

        then:
        lastWrite() == resp(0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD)
    }

    def "write registers frame and limits"() {
        when:
        mock.queueRead(resp(0x10, 0x10, 0x02))
        s.writeRegisters(0x10, bytes(1, 2))

        then:
        lastWrite() == bytes(0x10, 0x10, 0x02, 1, 2, 0xC0, 0x92)

        when:
        s.writeRegisters(0x0F, bytes(1))

        then:
        thrown(IllegalArgumentException)

        when:
        s.writeRegisters(0x13, bytes(1, 2))

        then:
        thrown(IllegalArgumentException)

        when:
        s.writeRegisters(0x10, new byte[0])

        then:
        thrown(IllegalArgumentException)

        when:
        mock.queueRead(bytes(0x82, 0, 0, 0, 0))
        s.writeRegisters(0x10, bytes(1))

        then:
        thrown(AM2315Minimal.ErrorResponse)
    }

    def "wake"() {
        when:
        s.wake()

        then:
        mock.writes().size() == 1
        mock.writes()[0] == bytes(0x00)
    }
}
