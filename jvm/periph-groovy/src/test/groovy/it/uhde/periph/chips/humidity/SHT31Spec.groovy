package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class SHT31Spec extends Specification {

    MockConnection mock
    SHT31Full s

    static byte[] frame(int rawT, int rawRh) {
        byte[] f = new byte[6]
        f[0] = (byte) (rawT >> 8); f[1] = (byte) rawT
        f[3] = (byte) (rawRh >> 8); f[4] = (byte) rawRh
        f[2] = (byte) SHT31Minimal.crc8(f, 0, 2)
        f[5] = (byte) SHT31Minimal.crc8(f, 3, 2)
        return f
    }

    List<Integer> cmds() {
        return mock.writes().collect { byte[] w -> ((w[0] & 0xFF) << 8) | (w[1] & 0xFF) }
    }

    def setup() {
        mock = new MockConnection()
        s = new SHT31Full(mock)
    }

    def "crc reference"() {
        expect:
        SHT31Minimal.crc8([0xBE, 0xEF] as byte[], 0, 2) == 0x92
    }

    def "init and read"() {
        expect:
        cmds() == [0x30A2]

        when:
        mock.writes().clear()
        mock.queueRead(frame(0x6666, 0x8000))
        def v = s.read()

        then:
        cmds() == [0x2400]
        Math.abs(v[0] - (-45.0d + 175.0d * 0x6666 / 65535.0d)) < 1e-9
        Math.abs(v[1] - 100.0d * 0x8000 / 65535.0d) < 1e-9
    }

    def "read polls and retries"() {
        when:
        mock.failNextReads(3)
        mock.queueRead(frame(0, 65535))
        def v = s.read()

        then:
        Math.abs(v[0] + 45.0d) < 1e-9
        Math.abs(v[1] - 100.0d) < 1e-9

        when:
        mock.writes().clear()
        byte[] bad = frame(0x1234, 0x5678)
        bad[2] = (byte) (bad[2] ^ 0xFF)
        mock.queueRead(bad)
        mock.queueRead(frame(0x1234, 0x5678))
        s.read()

        then:
        cmds() == [0x2400, 0x2400]

        when:
        mock.queueRead(bad)
        mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)
    }

    def "raw and single values"() {
        when:
        mock.queueRead(frame(1000, 2000))
        def raw = s.readRaw()
        mock.queueRead(frame(0, 0))
        def t = s.readTemperature()
        mock.queueRead(frame(0, 65535))
        def rh = s.readHumidity()

        then:
        raw == [1000, 2000] as int[]
        Math.abs(t + 45.0d) < 1e-9
        Math.abs(rh - 100.0d) < 1e-9
    }

    def "repeatability and stretching"() {
        when:
        mock.writes().clear()
        s.setRepeatability("low")
        mock.queueRead(frame(0, 0)); s.read()
        s.setRepeatability("medium")
        mock.queueRead(frame(0, 0)); s.read()
        s.setClockStretching(true)
        mock.queueRead(frame(0, 0)); s.read()
        s.setRepeatability("high")
        mock.queueRead(frame(0, 0)); s.read()

        then:
        cmds() == [0x2416, 0x240B, 0x2C0D, 0x2C06]

        when:
        s.setRepeatability("ultra")

        then:
        thrown(IllegalArgumentException)
    }

    def "periodic mode"() {
        when:
        mock.writes().clear()
        s.startPeriodic(2.0d, "high", false)
        s.startPeriodic(0.5d, "low", false)
        s.startPeriodic(10.0d, "medium", false)
        s.startPeriodic(1.0d, "high", true)

        then:
        cmds() == [0x2236, 0x202F, 0x2721, 0x2B32]

        when:
        s.startPeriodic(3.0d, "high", false)

        then:
        thrown(IllegalArgumentException)

        when:
        mock.writes().clear()
        mock.queueRead(frame(0x4000, 0x4000))
        def v = s.fetch()

        then:
        cmds() == [0xE000]
        Math.abs(v[0] - (-45.0d + 175.0d * 0x4000 / 65535.0d)) < 1e-9

        when:
        mock.failNextReads(1)

        then:
        s.fetch() == null

        when:
        mock.writes().clear()
        s.stopPeriodic()

        then:
        cmds() == [0x3093]
    }

    def "heater status reset"() {
        when:
        mock.writes().clear()
        s.setHeater(true)
        s.setHeater(false)
        s.clearStatus()

        then:
        cmds() == [0x306D, 0x3066, 0x3041]

        when:
        byte[] st = [0x80, 0x10, 0] as byte[]
        st[2] = (byte) SHT31Minimal.crc8(st, 0, 2)
        mock.queueRead(st)

        then:
        s.readStatus() == 0x8010

        when:
        mock.queueRead([0x80, 0x10, 0x00] as byte[])
        s.readStatus()

        then:
        thrown(IOException)

        when:
        mock.writes().clear()
        s.softReset()

        then:
        cmds() == [0x30A2]
    }

    def "dew point"() {
        when:
        mock.queueRead(frame((int) Math.round((25.0d + 45.0d) * 65535.0d / 175.0d), (int) Math.round(50.0d * 65535.0d / 100.0d)))

        then:
        Math.abs(s.dewPoint() - 13.86d) < 0.05d
    }
}
