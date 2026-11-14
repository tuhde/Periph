package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class AD5251Spec extends Specification {

    private static byte[] last(MockConnection c) {
        return c.writes().last()
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    private static List<List<Integer>> sent(MockConnection c) {
        return c.writes().collect { byte[] w -> w.collect { byte x -> x & 0xFF } }
    }

    def "construction makes no bus traffic"() {
        given:
        def c = new MockConnection()

        when:
        new AD5251Minimal(c)

        then:
        c.writes().size() == 0
    }

    def "setWiper encodes the instruction byte and clamps"() {
        given:
        def c = new MockConnection()
        def pot = new AD5251Minimal(c)

        when:
        pot.setWiper(channel, value)

        then:
        last(c) == b(instr, expected)

        where:
        channel | value || instr | expected
        0       | 40   || 0x01  | 40
        1       | 60   || 0x03  | 60
        0       | 300   || 0x01  | 63
        0       | -5    || 0x01  | 0
    }

    def "setWiper rejects an invalid channel"() {
        when:
        new AD5251Minimal(new MockConnection()).setWiper(2, 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "getWiper uses the instruction byte as register pointer"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x01, 17)
        c.setRegister(0x03, 99)
        def pot = new AD5251Minimal(c)

        expect:
        pot.getWiper(0) == 17
        pot.getWiper(1) == 99
        sent(c) == [[0x01], [0x03]]
    }

    def "resistance conversions use RW once"() {
        given:
        def c = new MockConnection()
        def pot = new AD5251Minimal(c)

        when:
        pot.setResistance(0, 25075.0d)
        def mid = last(c)
        pot.setResistance(0, 0.0d)
        def lo = last(c)
        pot.setResistance(1, 1e6d)
        def hi = last(c)
        c.setRegister(0x01, 32)
        c.setRegister(0x03, 0)

        then:
        mid == b(0x01, 32)
        lo == b(0x01, 0)
        hi == b(0x03, 63)
        Math.abs(pot.getResistance(0) - 25075.0d) < 0.01d
        Math.abs(pot.getResistance(1) - 75.0d) < 0.01d
    }

    def "setBoth is one transaction and ratio helpers work"() {
        given:
        def c = new MockConnection()
        def pot = new AD5251Full(c)

        when:
        pot.setBoth(1, 2)

        then:
        c.writes().size() == 1
        last(c) == b(0x01, 1, 2)

        when:
        pot.setRatio(0, 0.25d)

        then:
        last(c) == b(0x01, 16)

        when:
        pot.setRatio(0, 1.0d)

        then:
        last(c) == b(0x01, 63)

        when:
        pot.setResistanceWa(1, 25075.0d)
        c.setRegister(0x03, 32)

        then:
        last(c) == b(0x03, 32)
        pot.getRatio(1) == 0.5d
        Math.abs(pot.getResistanceWa(1) - 25075.0d) < 0.01d
    }

    def "quick commands"() {
        given:
        def c = new MockConnection()
        def pot = new AD5251Full(c)

        when:
        pot.increment(0); pot.increment(1); pot.increment()
        pot.decrement(0); pot.decrement(1); pot.decrement()

        then:
        sent(c) == [[0xD1], [0xD3], [0xD8], [0xA9], [0xAB], [0xB0]]

        when:
        c.writes().clear()
        pot.doubleCode(0); pot.doubleCode(1); pot.doubleCode()
        pot.halve(0); pot.halve(1); pot.halve()

        then:
        sent(c) == [[0xC1], [0xC3], [0xC8], [0x99], [0x9B], [0xA0]]

        when:
        pot.increment(2)

        then:
        thrown(IllegalArgumentException)
    }

    def "store, restore and reset use ACK polling"() {
        given:
        def c = new MockConnection()
        def pot = new AD5251Full(c)

        when:
        pot.store(1)

        then:
        sent(c) == [[0x93], [0x80]]

        when:
        c.writes().clear()
        c.failNextWrites(2, 1)
        pot.store(0)

        then:
        sent(c) == [[0x91], [0x80], [0x80], [0x80]]

        when:
        c.writes().clear()
        pot.store()

        then:
        sent(c) == [[0x91], [0x80], [0x93], [0x80]]

        when:
        c.writes().clear()
        pot.restore(0)

        then:
        sent(c) == [[0x89], [0x80]]

        when:
        c.writes().clear()
        pot.restore()

        then:
        sent(c) == [[0x89], [0x8B], [0x80]]

        when:
        c.writes().clear()
        pot.reset()

        then:
        sent(c) == [[0xB8], [0x80]]
    }

    def "store times out when the chip never acknowledges"() {
        given:
        def c = new MockConnection()
        def pot = new AD5251Full(c)
        c.failNextWrites(1000, 1)

        when:
        pot.store(0)

        then:
        thrown(IOException)
    }

    def "user EEMEM and tolerance"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x24, 0x5A)
        c.setRegister(0x2F, 0xA5)
        c.setRegister(0x3A, 0x1C, 0x0F)
        c.setRegister(0x3E, 0x82, 0x80)
        def pot = new AD5251Full(c)

        expect:
        pot.readEemem(4) == 0x5A
        pot.readEemem(15) == 0xA5
        Math.abs(pot.readTolerance(0) - 28.05859375d) < 1e-6d
        Math.abs(pot.readTolerance(1) + 2.5d) < 1e-9d
        Math.abs(pot.rabActual(0) - 64029.296875d) < 0.01d

        when:
        c.writes().clear()
        pot.writeEemem(9, 0x42)

        then:
        sent(c) == [[0x29, 0x42], [0x80]]

        when:
        pot.readEemem(3)

        then:
        thrown(IllegalArgumentException)

        when:
        pot.writeEemem(16, 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "setRab changes conversions only"() {
        given:
        def c = new MockConnection()
        c.setRegister(0x01, 32)
        def pot = new AD5251Full(c)

        when:
        pot.setRab(50000.0d, 100.0d)

        then:
        c.writes().size() == 0
        Math.abs(pot.getResistance(0) - 25100.0d) < 0.01d

        when:
        pot.setRab(10000.0d)

        then:
        Math.abs(pot.getResistance(0) - 5100.0d) < 0.01d
    }
}
