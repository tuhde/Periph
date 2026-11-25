package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class HTU21DFSpec extends Specification {

    MockConnection mock
    HTU21DFFull s

    static byte[] frame(int code) {
        byte[] f = new byte[3]
        f[0] = (byte) (code >> 8); f[1] = (byte) code
        f[2] = (byte) HTU21DFMinimal.crc8(f, 0, 2)
        return f
    }

    static byte[] corrupt(byte[] f) {
        byte[] bad = f.clone()
        bad[2] = (byte) (bad[2] ^ 0xFF)
        return bad
    }

    static int rhCode(double rh) { return ((int) Math.round((rh + 6.0d) * 65536.0d / 125.0d) & 0xFFFC) | 0x02 }
    static int tCode(double t) { return (int) Math.round((t + 46.85d) * 65536.0d / 175.72d) & 0xFFFC }

    List<Integer> cmds() {
        return mock.writes().collect { byte[] w -> w[0] & 0xFF }
    }

    boolean lastWrite(int a, int b) {
        byte[] w = mock.writes().last()
        return w.length == 2 && (w[0] & 0xFF) == a && (w[1] & 0xFF) == b
    }

    def setup() {
        mock = new MockConnection()
        s = new HTU21DFFull(mock)
    }

    def "crc reference"() {
        expect:
        HTU21DFMinimal.crc8([0x68, 0x3A] as byte[], 0, 2) == 0x7C
        HTU21DFMinimal.crc8([0x4E, 0x85] as byte[], 0, 2) == 0x6B
        HTU21DFMinimal.crc8([0xDC] as byte[], 0, 1) == 0x79
    }

    def "constructor does no bus access and init resets"() {
        expect:
        mock.writes().isEmpty()

        when:
        s.init()

        then:
        cmds() == [0xFE]
        new HTU21DFMinimal(new MockConnection()) != null
    }

    def "read uses F3 then F5"() {
        when:
        mock.queueRead(frame(tCode(25.0d))); mock.queueRead(frame(rhCode(50.0d)))
        def v = s.read()

        then:
        cmds() == [0xF3, 0xF5]
        Math.abs(v[0] - 25.0d) < 0.05d
        Math.abs(v[1] - 50.0d) < 0.05d
    }

    def "datasheet conversion"() {
        when:
        mock.queueRead(frame(0x683A)); mock.queueRead(frame(0x683A))
        def v = s.read()

        then:
        Math.abs(v[0] - (-46.85d + 175.72d * 0x6838 / 65536.0d)) < 1e-6d
        Math.abs(v[1] - (-6.0d + 125.0d * 0x6838 / 65536.0d)) < 1e-6d
    }

    def "read polls while nacked"() {
        when:
        mock.failNextReads(3)
        mock.queueRead(frame(tCode(20.0d))); mock.queueRead(frame(rhCode(40.0d)))

        then:
        Math.abs(s.read()[1] - 40.0d) < 0.05d
    }

    def "timeout and CRC mismatch throw, one retry recovers"() {
        when:
        mock.failNextReads(1000)
        s.read()

        then:
        thrown(IOException)

        when:
        mock = new MockConnection(); s = new HTU21DFFull(mock)
        byte[] bad = corrupt(frame(0x6000))
        mock.queueRead(bad); mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)

        when:
        mock = new MockConnection(); s = new HTU21DFFull(mock)
        mock.queueRead(bad); mock.queueRead(frame(tCode(25.0d))); mock.queueRead(frame(rhCode(50.0d)))
        def v = s.read()

        then:
        Math.abs(v[0] - 25.0d) < 0.05d
        cmds() == [0xF3, 0xF3, 0xF5]
    }

    def "humidity is not clamped"() {
        when:
        mock.queueRead(frame(0)); mock.queueRead(frame(0))

        then:
        Math.abs(s.read()[1] + 6.0d) < 1e-9d
    }

    def "single values raw and compensated"() {
        when:
        mock.queueRead(frame(rhCode(30.0d)))
        def rh = s.readHumidity()
        mock.queueRead(frame(tCode(-10.0d)))
        def t = s.readTemperature()

        then:
        Math.abs(rh - 30.0d) < 0.05d
        Math.abs(t + 10.0d) < 0.05d
        cmds() == [0xF5, 0xF3]

        when:
        mock.queueRead(frame(0x1237)); mock.queueRead(frame(0x4321))
        def raw = s.readRaw()

        then:
        raw[0] == 0x1234
        raw[1] == 0x4320

        when:
        mock.queueRead(frame(tCode(35.0d))); mock.queueRead(frame(rhCode(50.0d)))

        then:
        Math.abs(s.readHumidityCompensated() - 51.5d) < 0.1d
    }

    def "hold master commands"() {
        when:
        s.setHoldMaster(true)
        mock.queueRead(frame(tCode(25.0d))); mock.queueRead(frame(rhCode(50.0d)))
        s.read()

        then:
        cmds() == [0xE3, 0xE5]
    }

    def "resolution"() {
        when:
        mock.setRegister(0xE7, 0x02)
        s.setResolution(3)

        then:
        lastWrite(0xE6, 0x83)

        when:
        s.setResolution(1)

        then:
        lastWrite(0xE6, 0x03)

        when:
        s.setResolution(2)

        then:
        lastWrite(0xE6, 0x82)

        when:
        s.setResolution(4)

        then:
        thrown(IllegalArgumentException)

        when:
        mock.setRegister(0xE7, reg)

        then:
        s.getResolution() == res

        where:
        reg  | res
        0x83 | 3
        0x03 | 1
        0x82 | 2
        0x02 | 0
    }

    def "heater battery and user register"() {
        when:
        mock.setRegister(0xE7, 0x02)
        s.setHeater(true)

        then:
        lastWrite(0xE6, 0x06)

        when:
        mock.setRegister(0xE7, 0x06)

        then:
        s.heater()

        when:
        s.setHeater(false)

        then:
        lastWrite(0xE6, 0x02)

        when:
        mock.setRegister(0xE7, 0x02)

        then:
        !s.heater()
        !s.batteryLow()

        when:
        mock.setRegister(0xE7, 0x42)

        then:
        s.batteryLow()
        s.readUserRegister() == 0x42
    }

    def "soft reset and dew point"() {
        when:
        s.softReset()

        then:
        cmds() == [0xFE]

        when:
        mock.queueRead(frame(tCode(25.0d))); mock.queueRead(frame(rhCode(50.0d)))

        then:
        Math.abs(s.dewPoint() - 13.86d) < 0.1d
    }
}
