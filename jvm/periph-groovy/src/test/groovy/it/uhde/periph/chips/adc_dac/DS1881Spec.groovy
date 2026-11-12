package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class DS1881Spec extends Specification {

    private static byte[] last(MockConnection c) {
        return c.writes().last()
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    def "init writes the config only when it differs"() {
        given:
        def c = new MockConnection()
        def pot = new DS1881Minimal(c)

        expect:
        c.writes().size() == 0

        when:
        pot.init()

        then:
        c.writes().size() == 1
        last(c) == b(0x86)

        when:
        def c2 = new MockConnection()
        c2.queueRead(b(10, 20, 0x86))
        new DS1881Minimal(c2).init()
        def c3 = new MockConnection()
        c3.queueRead(b(0, 0, 0xB6))
        new DS1881Minimal(c3).init()

        then:
        c2.writes().size() == 0
        c3.writes().size() == 0
    }

    def "attenuation encoding, rounding and clamping"() {
        given:
        def c = new MockConnection()
        def pot = new DS1881Minimal(c)

        when:
        pot.setAttenuation(0, 20.0d)
        then:
        last(c) == b(20)

        when:
        pot.setAttenuation(1, 20.4d)
        then:
        last(c) == b(0x40 | 20)

        when:
        pot.setAttenuation(1, 20.6d)
        then:
        last(c) == b(0x40 | 21)

        when:
        pot.setAttenuation(0, 100.0d)
        then:
        last(c) == b(62)

        when:
        pot.setAttenuation(0, -5.0d)
        then:
        last(c) == b(0)

        when:
        pot.mute(1)
        then:
        last(c) == b(0x7F)

        when:
        pot.setPosition(0, 99)
        then:
        last(c) == b(63)

        when:
        pot.setPosition(0, -3)
        then:
        last(c) == b(0)

        when:
        pot.setAttenuation(2, 0.0d)
        then:
        thrown(IllegalArgumentException)

        when:
        pot.getPosition(-1)
        then:
        thrown(IllegalArgumentException)
    }

    def "reads mask the upper bits"() {
        given:
        def c = new MockConnection()
        4.times { c.queueRead(b(0xC5, 0x3F, 0x86)) }
        def pot = new DS1881Minimal(c)

        expect:
        pot.getPosition(0) == 5
        pot.getPosition(1) == 63
        pot.getAttenuation(1) == 80.0d
        pot.getAttenuation(0) == 5.0d
        c.writes().size() == 0
    }

    def "stereo write is one transaction"() {
        given:
        def c = new MockConnection()
        def full = new DS1881Full(c)

        when:
        full.setBoth(10, 99)
        then:
        c.writes().size() == 1
        last(c) == b(10, 0x40 | 63)

        when:
        full.setAttenuationBoth(30.0d)
        then:
        last(c) == b(30, 0x40 | 30)

        when:
        full.setAttenuationBoth(5.0d, 70.0d)
        then:
        last(c) == b(5, 0x7F)
    }

    def "option 1 and 2 tables"() {
        given:
        def c = new MockConnection()
        def full = new DS1881Full(c)

        expect:
        full.positionToDb(0) == 0.0d
        full.positionToDb(62) == 62.0d
        full.positionToDb(63) == 80.0d
        full.maxPosition() == 62
        full.dbToPosition(12.4d) == 12
        full.dbToPosition(75.0d) == 63

        when:
        full.configure(2, true, false)
        int n = c.writes().size()
        def written = last(c)
        full.configure(2, true, false)

        then:
        written == b(0x87)
        c.writes().size() == n
        [0, 12, 13, 24, 25, 32, 33].collect { full.positionToDb(it) } == [0.0d, 12.0d, 14.0d, 36.0d, 39.0d, 60.0d, 80.0d]
        full.maxPosition() == 32
        full.dbToPosition(14.0d) == 13
        full.dbToPosition(36.0d) == 24
        full.dbToPosition(60.0d) == 32
        full.dbToPosition(37.0d) == 24
        full.dbToPosition(38.0d) == 25
        full.dbToPosition(70.0d) == 33

        when:
        full.setBoth(40, 40)
        then:
        last(c) == b(33, 0x40 | 33)

        when:
        full.mute(0)
        then:
        last(c) == b(33)

        when:
        full.configure(3, true, false)
        then:
        thrown(IllegalArgumentException)
    }

    def "config bits are kept and nonvolatile writes poll"() {
        given:
        def c = new MockConnection()
        def full = new DS1881Full(c)

        when:
        full.setZeroCrossing(false)
        then:
        last(c) == b(0x84)

        when:
        full.setNonvolatile(true)
        then:
        last(c) == b(0x80)

        when:
        full.setOption(2)
        then:
        last(c) == b(0x81)

        when:
        c.failNextReads(1)
        full.setBoth(1, 2)
        then:
        last(c) == b(1, 0x42)
    }

    def "getConfig and readAll"() {
        given:
        def c = new MockConnection()
        c.queueRead(b(0, 0, 0x87))
        c.queueRead(b(1, 2, 0x82))
        c.queueRead(b(0x41, 0x02, 0x86))
        def full = new DS1881Full(c)

        when:
        def cfg = full.getConfig()

        then:
        cfg.option == 2
        cfg.zeroCrossing
        !cfg.nonvolatile
        cfg.raw == 0x87
        full.maxPosition() == 32

        when:
        def cfg2 = full.getConfig()

        then:
        cfg2.nonvolatile
        cfg2.option == 1
        full.readAll() == [1, 2, 0x86] as int[]
    }

    def "waitReady recovers and times out"() {
        given:
        def c = new MockConnection()
        def full = new DS1881Full(c)

        when:
        c.failNextReads(3)
        full.waitReady(0.05d)

        then:
        noExceptionThrown()

        when:
        c.failNextReads(1000)
        full.waitReady(0.01d)

        then:
        thrown(IOException)
    }

    def "store and reset"() {
        given:
        def c = new MockConnection()
        c.queueRead(b(12, 34, 0x86))
        def full = new DS1881Full(c)

        when:
        full.store()
        def w = c.writes()

        then:
        w.size() == 3
        w[0] == b(0x82)
        w[1] == b(12, 0x40 | 34)
        w[2] == b(0x86)

        when:
        def c2 = new MockConnection()
        def f2 = new DS1881Full(c2)
        f2.configure(2, false, true)
        f2.reset()

        then:
        last(c2) == b(0x86, 0x3F, 0x7F)
        f2.maxPosition() == 62
    }
}
