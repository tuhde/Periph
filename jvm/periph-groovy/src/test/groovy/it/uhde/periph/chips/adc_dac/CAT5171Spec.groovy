package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class CAT5171Spec extends Specification {

    private static byte[] last(MockConnection c) {
        return c.writes().last()
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    private static MockConnection withReads(int... values) {
        def c = new MockConnection()
        for (int v : values) c.queueRead(b(v))
        return c
    }

    def "construction makes no bus traffic"() {
        given:
        def c = new MockConnection()

        when:
        new CAT5171Minimal(c)

        then:
        c.writes().size() == 0
    }

    def "setWiper encodes the instruction byte and clamps"() {
        given:
        def c = new MockConnection()
        def pot = new CAT5171Minimal(c)

        when:
        pot.setWiper(value)

        then:
        last(c) == b(0x00, expected)

        where:
        value || expected
        100   || 100
        300   || 255
        -5    || 0
    }

    def "getWiper is a bare read"() {
        given:
        def c = withReads(17)
        def pot = new CAT5171Minimal(c)

        expect:
        pot.getWiper() == 17
        c.writes().size() == 0
    }

    def "resistance conversions"() {
        given:
        def c = new MockConnection()
        def pot = new CAT5171Minimal(c)

        when:
        pot.setResistance(ohms)

        then:
        last(c) == b(0x00, expected)

        where:
        ohms    || expected
        50050.0 || 128
        0.0     || 0
        1.0e7   || 255
    }

    def "getResistance reads the wiper"() {
        given:
        def pot = new CAT5171Minimal(withReads(128, 0))

        expect:
        Math.abs(pot.getResistance() - 50050.0d) < 0.01d
        Math.abs(pot.getResistance() - 50.0d) < 0.01d
    }

    def "ratio and W-A helpers"() {
        given:
        def c = new MockConnection()
        def pot = new CAT5171Full(c)

        when:
        pot.setRatio(0.25d)

        then:
        last(c) == b(0x00, 64)

        when:
        pot.setRatio(1.0d)

        then:
        last(c) == b(0x00, 255)

        when:
        pot.setResistanceWa(50050.0d)

        then:
        last(c) == b(0x00, 128)

        expect:
        new CAT5171Full(withReads(128)).getRatio() == 0.5d
        Math.abs(new CAT5171Full(withReads(64)).getResistanceWa() - 75050.0d) < 0.01d
    }

    def "increment returns the clamped code"() {
        given:
        def c = withReads(250, 250, 128)
        def pot = new CAT5171Full(c)

        expect:
        pot.increment(3) == 253
        last(c) == b(0x00, 253)
        pot.increment(100) == 255
        pot.increment(-28) == 100
        last(c) == b(0x00, 100)
    }

    def "shutdown and resume track state and keep the code"() {
        given:
        def c = new MockConnection()
        def pot = new CAT5171Full(c)

        expect:
        !pot.isShutdown()

        when:
        pot.shutdown()

        then:
        last(c) == b(0x20, 128)
        pot.isShutdown()

        when:
        pot.resume()

        then:
        last(c) == b(0x00, 128)
        !pot.isShutdown()

        when:
        pot.setWiper(77)
        pot.shutdown()

        then:
        last(c) == b(0x20, 77)

        when:
        pot.setWiper(50)

        then:
        last(c) == b(0x20, 50)

        when:
        pot.resume()

        then:
        last(c) == b(0x00, 50)
    }

    def "midscale keeps SD and reset clears it"() {
        given:
        def c = new MockConnection()
        def pot = new CAT5171Full(c)

        when:
        pot.midscale()

        then:
        last(c) == b(0x40, 0x80)

        when:
        pot.shutdown()
        pot.midscale()

        then:
        last(c) == b(0x60, 0x80)
        pot.isShutdown()

        when:
        pot.reset()

        then:
        last(c) == b(0x40, 0x80)
        !pot.isShutdown()
    }

    def "setRab changes conversion only"() {
        given:
        def c = withReads(128, 128)
        def pot = new CAT5171Full(c)

        when:
        pot.setRab(50000.0d, 100.0d)

        then:
        c.writes().size() == 0
        Math.abs(pot.getResistance() - 25100.0d) < 0.01d

        when:
        pot.setRab(50000.0d)

        then:
        Math.abs(pot.getResistance() - 25100.0d) < 0.01d
    }
}
