package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Htu31dSpec extends Specification {

    MockConnection mock
    Htu31dFull s

    static byte[] word(int code) {
        byte[] f = new byte[3]
        f[0] = (byte) (code >> 8); f[1] = (byte) code
        f[2] = (byte) Htu31dMinimal.crc8(f, 0, 2)
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
        return mock.writes().collect { byte[] w -> w[0] & 0xFF }
    }

    def setup() {
        mock = new MockConnection()
        s = new Htu31dFull(mock)
    }

    def "crc reference"() {
        expect:
        Htu31dMinimal.crc8([0xDC] as byte[], 0, 1) == 0x79
        Htu31dMinimal.crc8([0x68, 0x3A] as byte[], 0, 2) == 0x7C
        Htu31dMinimal.crc8([0x4E, 0x85] as byte[], 0, 2) == 0x6B
    }

    def "constructor does no bus access and init resets"() {
        expect:
        mock.writes().isEmpty()

        when:
        s.init()

        then:
        cmds() == [0x1E]
        new Htu31dMinimal(new MockConnection()) != null
    }

    def "read converts and sends commands"() {
        when:
        mock.queueRead(trh(0x683A, 0x8000))
        double[] m = s.read()

        then:
        cmds() == [0x5E, 0x00]
        Math.abs(m[0] - (-40.0d + 165.0d * 0x683A / 65535.0d)) < 1e-6d
        Math.abs(m[1] - (100.0d * 0x8000 / 65535.0d)) < 1e-6d
    }

    def "full scale and clamp"() {
        when:
        mock.queueRead(trh(0xFFFF, 0xFFFF))
        double[] hi = s.read()
        mock.queueRead(trh(0, 0))
        double[] lo = s.read()

        then:
        Math.abs(hi[0] - 125.0d) < 1e-6d
        Math.abs(hi[1] - 100.0d) < 1e-6d
        Math.abs(lo[0] + 40.0d) < 1e-6d
        lo[1] == 0.0d
    }

    def "crc mismatch throws"() {
        given:
        byte[] bad = trh(0x683A, 0x8000)
        bad[2] = (byte) (bad[2] ^ 0xFF)

        when:
        mock.queueRead(bad)
        s.read()

        then:
        thrown(IOException)

        when:
        byte[] bad2 = trh(0x683A, 0x8000)
        bad2[5] = (byte) (bad2[5] ^ 0xFF)
        mock.queueRead(bad2)
        s.read()

        then:
        thrown(IOException)
    }

    def "single values and raw"() {
        when:
        mock.queueRead(word(0x4000))
        double h = s.readHumidity()

        then:
        Math.abs(h - 100.0d * 0x4000 / 65535.0d) < 1e-6d
        cmds() == [0x5E, 0x10]

        when:
        mock.writes().clear()
        mock.queueRead(word(0x4000))
        double t = s.readTemperature()

        then:
        Math.abs(t - (-40.0d + 165.0d * 0x4000 / 65535.0d)) < 1e-6d
        cmds() == [0x5E, 0x00]

        when:
        mock.queueRead(trh(0x1234, 0x5678))

        then:
        s.readRaw() == [0x1234, 0x5678] as int[]
    }

    def "resolution"() {
        expect:
        s.getResolution() == [3, 3] as int[]
        Math.abs(s.conversionTime() - 0.02032d) < 1e-9d

        when:
        s.setResolution(2, 1)
        mock.queueRead(trh(0x4000, 0x4000))
        s.read()

        then:
        cmds()[0] == (0x40 | (2 << 3) | (1 << 1))

        when:
        s.setResolution(4, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        mock.writes().clear()
        s.setResolution(1, 2)
        s.startConversion()

        then:
        cmds() == [0x40 | (1 << 3) | (2 << 1)]
    }

    def "read result uses read command only"() {
        when:
        mock.queueRead(trh(0x2000, 0x6000))
        double[] m = s.readResult()

        then:
        cmds() == [0x00]
        Math.abs(m[1] - 100.0d * 0x6000 / 65535.0d) < 1e-6d
    }

    def "heater and diagnostic"() {
        when:
        s.setHeater(true)
        s.setHeater(false)

        then:
        cmds() == [0x04, 0x02]

        when:
        mock.queueRead([0x01] as byte[])

        then:
        s.isHeaterOn()

        when:
        mock.queueRead([(byte) 0xA5] as byte[])

        then:
        s.diagnostic() == 0xA5

        when:
        mock.queueRead([(byte) 0xA5] as byte[])
        Map<String, Boolean> f = s.diagnosticFlags()

        then:
        f.get('nvm_err')
        f.get('rh_high_err')
        f.get('t_high_err')
        f.get('heater_on')
        !f.get('rh_overrun')
        !f.get('t_low_err')
    }

    def "serial number"() {
        given:
        byte[] d = [(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, (byte) 0] as byte[]
        d[3] = (byte) Htu31dMinimal.crc8(d, 0, 3)

        when:
        mock.queueRead(d)

        then:
        s.serialNumber() == 0xABCDEF

        when:
        byte[] bad = d.clone()
        bad[3] = (byte) (bad[3] ^ 0x55)
        mock.queueRead(bad)
        s.serialNumber()

        then:
        thrown(IOException)
    }

    def "soft reset restores defaults"() {
        when:
        s.setResolution(0, 1)
        s.softReset()

        then:
        cmds() == [0x1E]
        s.getResolution() == [3, 3] as int[]
    }

    def "dew point"() {
        when:
        mock.queueRead(trh((int) Math.round(65.0d * 65535.0d / 165.0d), (int) Math.round(0.5d * 65535.0d)))

        then:
        Math.abs(s.dewPoint() - 13.86d) < 0.15d
    }
}
