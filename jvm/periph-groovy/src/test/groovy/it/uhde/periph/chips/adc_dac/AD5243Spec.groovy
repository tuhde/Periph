package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class AD5243Spec extends Specification {

    private static byte[] last(MockConnection c) {
        return c.writes().last()
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    def "construction makes no bus traffic"() {
        given:
        def c = new MockConnection()

        when:
        new AD5243Minimal(c)

        then:
        c.writes().size() == 0
    }

    def "setWiper encodes the instruction byte and clamps"() {
        given:
        def c = new MockConnection()
        def pot = new AD5243Minimal(c)

        when:
        pot.setWiper(channel, value)

        then:
        last(c) == b(instr, expected)

        where:
        channel | value || instr | expected
        0       | 100   || 0x00  | 100
        1       | 200   || 0x80  | 200
        0       | 300   || 0x00  | 255
        0       | -5    || 0x00  | 0
    }

    def "invalid channel throws"() {
        when:
        new AD5243Minimal(new MockConnection()).setWiper(2, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "getWiper selects the channel by instruction byte"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x00, 17)
        c.setRegister(0x80, 99)
        def pot = new AD5243Minimal(c)

        expect:
        pot.getWiper(0) == 17
        pot.getWiper(1) == 99
    }

    def "set resistance picks the nearest code"() {
        given:
        def c = new MockConnection()
        def pot = new AD5243Minimal(c)

        when:
        pot.setResistance(0, 5320.0d)

        then:
        last(c) == b(0x00, 128)

        when:
        pot.setResistance(0, 0.0d)

        then:
        last(c) == b(0x00, 0)

        when:
        pot.setResistance(1, 1e6d)

        then:
        last(c) == b(0x80, 255)
    }

    def "get resistance converts the code"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x00, 128)
        c.setRegister(0x80, 0)
        def pot = new AD5243Minimal(c)

        expect:
        Math.abs(pot.getResistance(0) - 5320.0d) < 0.01d
        Math.abs(pot.getResistance(1) - 320.0d) < 0.01d
    }

    def "setBoth and ratio"() {
        given:
        def c = new MockConnection()
        def pot = new AD5243Full(c)

        when:
        pot.setBoth(1, 2)

        then:
        c.writes()[0] == b(0x00, 1)
        c.writes()[1] == b(0x80, 2)

        when:
        pot.setRatio(0, 0.25d)

        then:
        last(c) == b(0x00, 64)

        when:
        pot.setRatio(0, 1.0d)

        then:
        last(c) == b(0x00, 255)

        when:
        pot.setResistanceWa(1, 5320.0d)

        then:
        last(c) == b(0x80, 128)
    }

    def "read helpers and increment"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x00, 250)
        c.setRegister(0x80, 128)
        def pot = new AD5243Full(c)

        expect:
        pot.getRatio(1) == 0.5d
        Math.abs(pot.getResistanceWa(1) - 5320.0d) < 0.01d

        when:
        def n = pot.increment(0, 3)

        then:
        n == 253
        last(c) == b(0x00, 253)

        when:
        def m = pot.increment(0, 100)

        then:
        m == 255

        when:
        def k = pot.increment(1, -28)

        then:
        k == 100
    }

    def "shutdown keeps SD on later writes and resume clears it"() {
        given:
        def c = new MockConnection()
        def pot = new AD5243Full(c)

        when:
        pot.setWiper(1, 77)
        pot.shutdown(1)

        then:
        last(c) == b(0xC0, 77)
        pot.isShutdown(1)
        !pot.isShutdown(0)

        when:
        pot.setWiper(1, 50)

        then:
        last(c) == b(0xC0, 50)

        when:
        pot.resume(1)

        then:
        last(c) == b(0x80, 50)
        !pot.isShutdown(1)

        when:
        pot.shutdown(0)

        then:
        last(c) == b(0x40, 128)
    }

    def "reset restores midscale and clears shutdown"() {
        given:
        def c = new MockConnection()
        def pot = new AD5243Full(c)
        pot.shutdown(0)

        when:
        pot.reset()

        then:
        c.writes()[c.writes().size() - 2] == b(0x00, 128)
        last(c) == b(0x80, 128)
        !pot.isShutdown(0)
    }

    def "setRab changes the conversion only"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x00, 128)
        def pot = new AD5243Full(c)

        when:
        pot.setRab(50000.0d, 100.0d)

        then:
        c.writes().size() == 0
        Math.abs(pot.getResistance(0) - 25200.0d) < 0.01d

        when:
        pot.setRab(10000.0d)

        then:
        Math.abs(pot.getResistance(0) - 5200.0d) < 0.01d
    }
}
