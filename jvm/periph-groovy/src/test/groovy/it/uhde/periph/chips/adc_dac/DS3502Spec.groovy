package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class DS3502Spec extends Specification {

    static MockConnection mock(int wr) {
        def c = new MockConnection()
        c.setRegister(0x00, wr)
        c.setRegister(0x02, 0x00)
        return c
    }

    static int wr(MockConnection c) { c.registers().get(0x00) }
    static int cr(MockConnection c) { c.registers().get(0x02) }
    static List<byte[]> dataWrites(MockConnection c) { c.writes().findAll { it.length > 1 } }

    def "init sets MODE = 1, keeps the wiper and caches WR"() {
        given:
        def c = mock(0x23)

        when:
        def pot = new DS3502Full(c)

        then:
        cr(c) == 0x80
        dataWrites(c).size() == 1
        (dataWrites(c)[0][0] & 0xFF) == 0x02
        wr(c) == 0x23
        pot.wiper == 0x23
    }

    def "init retries once after a NACK and fails after two"() {
        given:
        def c = mock(0x40)
        c.failNextWrites(1, 0)

        when:
        new DS3502Minimal(c)

        then:
        cr(c) == 0x80
        dataWrites(c).size() == 2

        when:
        def d = mock(0x40)
        d.failNextWrites(2, 0)
        new DS3502Minimal(d)

        then:
        thrown(IOException)
    }

    def "wiper and voltage conversions"() {
        given:
        def c = mock(0x40)
        def pot = new DS3502Minimal(c, 15.0d, 0.0d)

        when:
        pot.setWiper(100)
        then:
        wr(c) == 100

        when:
        pot.setWiper(300)
        then:
        wr(c) == 127

        when:
        pot.setWiper(-5)
        then:
        wr(c) == 0

        when:
        c.setRegister(0x00, 0xFF)
        then:
        pot.wiper == 127

        when:
        pot.setVoltage(6.0d)
        then:
        wr(c) == 51
        Math.abs(pot.voltage - 15.0d * 51 / 127) < 1e-9

        when:
        pot.setVoltage(99.0d)
        then:
        wr(c) == 127

        when:
        pot.setVoltage(-3.0d)
        then:
        wr(c) == 0
    }

    def "offset, inverted and zero-span endpoints"() {
        given:
        def c = mock(0x40)
        def c3 = mock(0x40)

        when:
        new DS3502Minimal(c, 12.0d, 2.0d).setVoltage(7.0d)
        new DS3502Minimal(c3, 5.0d, 5.0d).setVoltage(5.0d)

        then:
        wr(c) == 64
        wr(c3) == 0
    }

    def "ratio, resistance and increment"() {
        given:
        def c = mock(0x40)
        def pot = new DS3502Full(c)

        when:
        pot.setRatio(0.5d)
        then:
        wr(c) == 64

        when:
        pot.setRatio(2.0d)
        then:
        wr(c) == 127
        pot.ratio == 1.0d

        when:
        c.setRegister(0x00, 0)
        then:
        pot.getResistance('L') == 0.0d
        pot.getResistance('H') == 10000.0d

        when:
        c.setRegister(0x00, 127)
        then:
        pot.getResistance() == 10000.0d
        pot.getResistance('H') == 0.0d

        when:
        pot.getResistance('X')
        then:
        thrown(IllegalArgumentException)

        when:
        c.setRegister(0x00, 0x40)
        then:
        pot.increment(5) == 69
        wr(c) == 69
        pot.increment(-10) == 59

        when:
        c.setRegister(0x00, 125)
        then:
        pot.increment(10) == 127

        when:
        c.setRegister(0x00, 3)
        then:
        pot.increment(-10) == 0
    }

    def "MODE and storeWiper"() {
        given:
        def c = mock(0x40)
        def pot = new DS3502Full(c)

        expect:
        !pot.mode

        when:
        pot.setMode(true)
        then:
        cr(c) == 0x00
        pot.mode

        when:
        pot.setMode(false)
        def n = dataWrites(c).size()
        pot.storeWiper(77)
        def seq = dataWrites(c).drop(n)
        then:
        seq.size() == 3
        seq.collect { [it[0] & 0xFF, it[1] & 0xFF] } == [[0x02, 0x00], [0x00, 77], [0x02, 0x80]]
        cr(c) == 0x80
        pot.wiper == 77

        when:
        c.setRegister(0x00, 33)
        pot.storeWiper()
        then:
        wr(c) == 33
    }

    def "storeWiper restores MODE = 1 on timeout"() {
        given:
        def c = mock(0x40)
        def pot = new DS3502Full(c)
        c.failNextWrites(31, 2)

        when:
        pot.storeWiper(10)

        then:
        thrown(IOException)
        cr(c) == 0x80
    }

    def "waitReady polls and endpoints change the conversion"() {
        given:
        def c = mock(0x40)
        def pot = new DS3502Full(c)

        when:
        c.failNextWrites(3, 0)
        pot.waitReady()
        c.failNextWrites(1000, 0)
        pot.waitReady(5)

        then:
        thrown(IOException)

        when:
        c.failNextWrites(0, 0)
        def n = c.writes().size()
        pot.setEndpoints(10.0d)
        c.setRegister(0x00, 127)

        then:
        c.writes().size() == n
        Math.abs(pot.voltage - 10.0d) < 1e-9
    }
}
