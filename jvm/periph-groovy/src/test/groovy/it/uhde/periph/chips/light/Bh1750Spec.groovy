package it.uhde.periph.chips.light

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bh1750Spec extends Specification {

    MockConnection mock
    Bh1750Full s

    static byte[] word(int raw) {
        return [(byte) (raw >> 8), (byte) raw] as byte[]
    }

    List<Integer> cmds() {
        return mock.writes().collect { byte[] w -> w[0] & 0xFF }
    }

    def setup() {
        mock = new MockConnection()
        s = new Bh1750Full(mock)
    }

    def "constructor does no bus access and init powers on"() {
        expect:
        mock.writes().isEmpty()

        when:
        s.init()

        then:
        cmds() == [0x01]
    }

    def "minimal read converts the datasheet example"() {
        given:
        MockConnection c = new MockConnection()
        Bh1750Minimal m = new Bh1750Minimal(c)
        c.queueRead(word(0x8342))

        expect:
        Math.abs(m.read() - 0x8342 / 1.2d) < 0.01
        c.writes().collect { byte[] w -> w[0] & 0xFF } == [0x20]
    }

    def "one-time modes"() {
        when:
        mock.queueRead(word(0x12))
        double h = s.measureOnce(Bh1750Full.MODE_H)

        then:
        Math.abs(h - 15.0d) < 1e-3
        cmds() == [0x20]

        when:
        mock.writes().clear()
        mock.queueRead(word(0x12))
        double h2 = s.measureOnce(Bh1750Full.MODE_H2)

        then:
        Math.abs(h2 - 7.5d) < 1e-3
        cmds() == [0x21]

        when:
        mock.writes().clear()
        mock.queueRead(word(0x12))
        double l = s.measureOnce(Bh1750Full.MODE_L)

        then:
        Math.abs(l - 15.0d) < 1e-3
        cmds() == [0x23]

        when:
        s.measureOnce(7)

        then:
        thrown(IllegalArgumentException)
    }

    def "read follows the current mode"() {
        when:
        mock.queueRead(word(0x12))
        s.measureOnce(Bh1750Full.MODE_H2)
        mock.writes().clear()
        mock.queueRead(word(0x12))
        double lux = s.read()

        then:
        Math.abs(lux - 7.5d) < 1e-3
        cmds() == [0x21]

        when:
        mock.writes().clear()
        mock.queueRead(word(0x12))

        then:
        s.readRaw() == 0x12
        cmds() == [0x21]
    }

    def "continuous mode"() {
        when:
        s.startContinuous(Bh1750Full.MODE_H)
        s.startContinuous(Bh1750Full.MODE_H2)
        s.startContinuous(Bh1750Full.MODE_L)

        then:
        cmds() == [0x10, 0x11, 0x13]

        when:
        s.startContinuous(Bh1750Full.MODE_H2)
        mock.writes().clear()
        mock.queueRead(word(0x12))
        double lux = s.readContinuous()

        then:
        Math.abs(lux - 7.5d) < 1e-3
        mock.writes().isEmpty()

        when:
        s.startContinuous(9)

        then:
        thrown(IllegalArgumentException)
    }

    def "power control and reset"() {
        when:
        s.powerDown()
        s.powerOn()

        then:
        cmds() == [0x00, 0x01]

        when:
        mock.writes().clear()
        s.reset()

        then:
        cmds() == [0x01, 0x07]
    }

    def "measurement time register"() {
        expect:
        s.getMeasurementTimeRegister() == 69

        when:
        s.setMeasurementTime(69)

        then:
        cmds() == [0x42, 0x65]

        when:
        mock.writes().clear()
        s.setMeasurementTime(254)

        then:
        cmds() == [0x47, 0x7E]
        s.getMeasurementTimeRegister() == 254

        when:
        mock.writes().clear()
        s.setMeasurementTime(31)

        then:
        cmds() == [0x40, 0x7F]

        when:
        mock.writes().clear()
        s.setMeasurementTime(30)

        then:
        thrown(IllegalArgumentException)
        mock.writes().isEmpty()
        s.getMeasurementTimeRegister() == 31

        when:
        s.setMeasurementTime(255)

        then:
        thrown(IllegalArgumentException)
    }

    def "sensitivity scales everything"() {
        expect:
        Math.abs(s.luxPerCount() - 1 / 1.2d) < 1e-9
        Math.abs(s.luxPerCount(Bh1750Full.MODE_H2) - 0.5d / 1.2d) < 1e-9
        Math.abs(s.measurementTime() - 0.180d) < 1e-9
        Math.abs(s.measurementTime(Bh1750Full.MODE_L) - 0.024d) < 1e-9

        when:
        s.setSensitivity(2.0d)

        then:
        s.getMeasurementTimeRegister() == 138
        Math.abs(s.luxPerCount() - 1 / 1.2d / 2) < 1e-9
        Math.abs(s.measurementTime() - 0.360d) < 1e-9

        when:
        mock.queueRead(word(0x12))
        double lux = s.measureOnce(Bh1750Full.MODE_H)

        then:
        Math.abs(lux - 7.5d) < 1e-3

        when:
        s.setSensitivity(0.4d)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setSensitivity(3.7d)

        then:
        thrown(IllegalArgumentException)

        when:
        s.setSensitivity(0.45d)

        then:
        s.getMeasurementTimeRegister() == 31

        when:
        s.setSensitivity(3.68d)

        then:
        s.getMeasurementTimeRegister() == 254
    }

    def "bus error propagates"() {
        when:
        mock.failNextWrites(1, 0)
        s.measureOnce(Bh1750Full.MODE_H)

        then:
        thrown(IOException)
    }
}
