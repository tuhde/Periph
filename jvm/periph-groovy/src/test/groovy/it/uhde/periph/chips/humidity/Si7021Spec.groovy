package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Si7021Spec extends Specification {

    MockConnection mock
    Si7021Full s

    static byte[] frame(int code) {
        byte[] f = new byte[3]
        f[0] = (byte) (code >> 8); f[1] = (byte) code
        f[2] = (byte) Si7021Minimal.crc8(f, 0, 2)
        return f
    }

    static int rhCode(double rh) { return (int) Math.round((rh + 6.0d) * 65536.0d / 125.0d) }
    static int tCode(double t) { return (int) Math.round((t + 46.85d) * 65536.0d / 175.72d) }
    static byte[] tBytes(double t) { return [(byte) (tCode(t) >> 8), (byte) tCode(t)] as byte[] }

    List<Integer> cmds() {
        return mock.writes().collect { byte[] w -> w[0] & 0xFF }
    }

    boolean lastWrite(int a, int b) {
        byte[] w = mock.writes().last()
        return w.length == 2 && (w[0] & 0xFF) == a && (w[1] & 0xFF) == b
    }

    def setup() {
        mock = new MockConnection()
        s = new Si7021Full(mock)
    }

    def "crc reference"() {
        expect:
        Si7021Minimal.crc8([0, 0] as byte[], 0, 2) == 0x00
        Si7021Minimal.crc8([1] as byte[], 0, 1) == 0x31
    }

    def "init does no bus access and read uses F5 then E0"() {
        expect:
        mock.writes().isEmpty()

        when:
        mock.queueRead(frame(rhCode(50.0d)))
        mock.queueRead(tBytes(25.0d))
        def v = s.read()

        then:
        cmds() == [0xF5, 0xE0]
        Math.abs(v[0] - 25.0d) < 0.02d
        Math.abs(v[1] - 50.0d) < 0.02d
        new Si7021Minimal(new MockConnection()) != null
    }

    def "read polls while nacked"() {
        when:
        mock.failNextReads(3)
        mock.queueRead(frame(rhCode(40.0d)))
        mock.queueRead(tBytes(20.0d))
        def v = s.read()

        then:
        Math.abs(v[1] - 40.0d) < 0.02d
    }

    def "timeout and crc mismatch throw"() {
        when:
        mock.failNextReads(1000)
        s.read()

        then:
        thrown(IOException)

        when:
        mock = new MockConnection()
        s = new Si7021Full(mock)
        byte[] bad = frame(0x6000)
        bad[2] = (byte) (bad[2] ^ 0xFF)
        mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)
    }

    def "humidity is clamped"() {
        when:
        mock.queueRead(frame(0x0000)); mock.queueRead([0, 0] as byte[])
        def low = s.read()[1]
        mock.queueRead(frame(0xFFFC)); mock.queueRead([0, 0] as byte[])
        def high = s.read()[1]

        then:
        low == 0.0d
        high == 100.0d
    }

    def "single values and raw codes"() {
        when:
        mock.queueRead(frame(rhCode(30.0d)))
        def rh = s.readHumidity()
        mock.queueRead(frame(tCode(-10.0d)))
        def t = s.readTemperature()
        mock.queueRead(tBytes(20.0d))
        def tp = s.readTemperaturePrevious()

        then:
        Math.abs(rh - 30.0d) < 0.02d
        Math.abs(t + 10.0d) < 0.02d
        Math.abs(tp - 20.0d) < 0.02d
        cmds() == [0xF5, 0xF3, 0xE0]

        when:
        mock.queueRead(frame(0x1234))
        def rawRh = s.readRawHumidity()
        mock.queueRead(frame(0x4321))
        def rawT = s.readRawTemperature()

        then:
        rawRh == 0x1234
        rawT == 0x4321
    }

    def "hold master commands"() {
        when:
        s.setClockStretching(true)
        mock.queueRead(frame(rhCode(50.0d))); mock.queueRead(tBytes(25.0d))
        s.read()
        mock.queueRead(frame(0x6666))
        s.readTemperature()

        then:
        cmds() == [0xE5, 0xE0, 0xE3]
    }

    def "resolution"() {
        when:
        mock.setRegister(0xE7, 0x3A)
        s.setResolution(11, 11)

        then:
        lastWrite(0xE6, 0xBB)

        when:
        s.setResolution(8, 12)

        then:
        lastWrite(0xE6, 0x3B)

        when:
        s.setResolution(10, 13)

        then:
        lastWrite(0xE6, 0xBA)

        when:
        s.setResolution(12, 12)

        then:
        thrown(IllegalArgumentException)

        when:
        mock.setRegister(0xE7, 0xBB)
        def a = s.getResolution()
        mock.setRegister(0xE7, 0x3B)
        def b = s.getResolution()
        mock.setRegister(0xE7, 0xBA)
        def c = s.getResolution()
        mock.setRegister(0xE7, 0x3A)
        def d = s.getResolution()

        then:
        a == [11, 11] as int[]
        b == [8, 12] as int[]
        c == [10, 13] as int[]
        d == [12, 14] as int[]
    }

    def "heater vdd and user register"() {
        when:
        mock.setRegister(0xE7, 0x3A)
        s.setHeater(true)

        then:
        lastWrite(0xE6, 0x3E)

        when:
        mock.setRegister(0xE7, 0x3E)

        then:
        s.isHeaterOn()

        when:
        s.setHeater(false)

        then:
        lastWrite(0xE6, 0x3A)

        when:
        mock.setRegister(0xE7, 0x3A)

        then:
        !s.isHeaterOn()
        !s.vddLow()
        s.readUserRegister() == 0x3A
        Math.abs(s.conversionTime() - 0.017d) < 1e-9d

        when:
        mock.setRegister(0xE7, 0x7A)

        then:
        s.vddLow()

        when:
        mock.setRegister(0xE7, 0x3A)
        s.writeUserRegister(0x00)

        then:
        lastWrite(0xE6, 0x3A)
    }

    def "reset and identification"() {
        given:
        mock = new MockConnection()
        mock.setAddressWidth(2)
        mock.setRegister(0xFA0F, 0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0)
        mock.setRegister(0xFCC9, 0x15, 0x66, 0, 0x77, 0x88, 0)
        mock.setRegister(0x84B8, 0xFF)
        s = new Si7021Full(mock)

        expect:
        s.serialNumber() == 0x1122334415667788L
        s.deviceType() == 0x15
        s.firmwareRevision() == 0xFF

        when:
        mock.writes().clear()
        s.softReset()

        then:
        cmds() == [0xFE]
    }

    def "dew point"() {
        when:
        mock.queueRead(frame(rhCode(50.0d)))
        mock.queueRead(tBytes(25.0d))
        def dp = s.dewPoint()

        then:
        Math.abs(dp - 13.86d) < 0.05d
    }
}
