package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Shtc3Spec extends Specification {

    MockConnection mock
    Shtc3Full s

    static byte[] word(int code) {
        byte[] f = new byte[3]
        f[0] = (byte) (code >> 8); f[1] = (byte) code
        f[2] = (byte) Shtc3Minimal.crc8(f, 0, 2)
        return f
    }

    static byte[] trh(int t, int rh) {
        byte[] a = word(t), b = word(rh)
        byte[] out = new byte[6]
        System.arraycopy(a, 0, out, 0, 3)
        System.arraycopy(b, 0, out, 3, 3)
        return out
    }

    List<Integer> cmds() {
        return mock.writes().collect { byte[] w -> ((w[0] & 0xFF) << 8) | (w[1] & 0xFF) }
    }

    def setup() {
        mock = new MockConnection()
        s = new Shtc3Full(mock)
    }

    def "crc reference"() {
        expect:
        Shtc3Minimal.crc8([0xBE, 0xEF] as byte[], 0, 2) == 0x92
        Shtc3Minimal.crc8([0x00] as byte[], 0, 1) == 0xAC
    }

    def "constructor does no bus access and init runs the sequence"() {
        expect:
        mock.writes().isEmpty()

        when:
        mock.queueRead(word(0x0807))
        s.init()

        then:
        cmds() == [0x3517, 0x805D, 0xEFC8, 0xB098]
    }

    def "init rejects a wrong id and a bad crc"() {
        when:
        mock.queueRead(word(0x0000))
        s.init()

        then:
        thrown(IOException)

        when:
        def bad = word(0x0807)
        bad[2] = (byte) (bad[2] ^ 0x55)
        mock = new MockConnection()
        s = new Shtc3Full(mock)
        mock.queueRead(bad)
        s.init()

        then:
        thrown(IOException)
    }

    def "read converts and sends the commands"() {
        when:
        mock.queueRead(trh(0x6666, 0x6000))
        double[] m = s.read()

        then:
        cmds() == [0x7866, 0xB098]
        Math.abs(m[0] - (-45.0d + 175.0d * 0x6666 / 65536.0d)) < 1e-6
        Math.abs(m[1] - 37.5d) < 1e-6
    }

    def "second read wakes first"() {
        when:
        mock.queueRead(trh(0x6666, 0x6000))
        mock.queueRead(trh(0x6666, 0x6000))
        s.read()
        s.read()

        then:
        cmds() == [0x7866, 0xB098, 0x3517, 0x7866, 0xB098]
    }

    def "full scale and clamp"() {
        when:
        mock.queueRead(trh(0xFFFF, 0xFFFF))
        double[] m = s.read()

        then:
        Math.abs(m[0] - 130.0d) < 0.01d
        m[1] <= 100.0d && m[1] > 99.9d

        when:
        mock.queueRead(trh(0, 0))
        m = s.read()

        then:
        Math.abs(m[0] + 45.0d) < 1e-6
        m[1] == 0.0d
    }

    def "crc mismatch is retried once then rejected"() {
        when:
        byte[] bad = trh(0x6666, 0x6000)
        bad[2] = (byte) (bad[2] ^ 0xFF)
        mock.queueRead(bad)
        mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)
        cmds() == [0x7866, 0x7866, 0xB098]
    }

    def "crc glitch is recovered"() {
        when:
        byte[] bad = trh(0x6666, 0x6000)
        bad[5] = (byte) (bad[5] ^ 0xFF)
        mock.queueRead(bad)
        mock.queueRead(trh(0x6666, 0x6000))
        double[] m = s.read()

        then:
        Math.abs(m[1] - 37.5d) < 1e-6
        cmds() == [0x7866, 0x7866, 0xB098]
    }

    def "single values and raw"() {
        when:
        mock.queueRead(trh(0x4000, 0x4000))
        mock.queueRead(trh(0x4000, 0x4000))
        mock.queueRead(trh(0x1234, 0x5678))

        then:
        Math.abs(s.readHumidity() - 25.0d) < 1e-6
        Math.abs(s.readTemperature() - (-45.0d + 175.0d * 0.25d)) < 1e-6
        s.readRaw() == [0x1234, 0x5678] as int[]
    }

    def "mode commands"() {
        when:
        3.times { mock.queueRead(trh(0x6666, 0x6000)) }
        s.setLowPower(true)
        s.read()
        s.setClockStretching(true)
        s.read()
        s.setLowPower(false)
        s.read()

        then:
        cmds() == [0x609C, 0xB098, 0x3517, 0x6458, 0xB098, 0x3517, 0x7CA2, 0xB098]
    }

    def "sleep control"() {
        when:
        mock.queueRead(trh(0x6666, 0x6000))
        mock.queueRead(trh(0x6666, 0x6000))
        s.setSleepBetween(false)
        s.read()
        s.read()
        s.sleep()
        s.wake()

        then:
        cmds() == [0x7866, 0x7866, 0xB098, 0x3517]
    }

    def "soft reset and read id"() {
        when:
        s.softReset()

        then:
        cmds() == [0x805D]

        when:
        mock = new MockConnection()
        s = new Shtc3Full(mock)
        mock.queueRead(word(0xAB07))

        then:
        s.readId() == 0xAB07
        cmds() == [0xEFC8]

        when:
        s.sleep()
        mock.queueRead(word(0x0807))

        then:
        (s.readId() & 0x0FFF) == 0x0807
        cmds() == [0xEFC8, 0xB098, 0x3517, 0xEFC8, 0xB098]

        when:
        byte[] bad = word(0x0807)
        bad[2] = (byte) (bad[2] ^ 0x55)
        mock.queueRead(bad)
        s.readId()

        then:
        thrown(IOException)
    }

    def "dew point"() {
        when:
        int tRaw = (int) Math.round((25.0d + 45.0d) * 65536.0d / 175.0d)
        mock.queueRead(trh(tRaw, 0x8000))

        then:
        Math.abs(s.dewPoint() - 13.86d) < 0.15d

        when:
        mock.queueRead(trh(tRaw, 0))

        then:
        s.dewPoint() < -20.0d
    }
}
