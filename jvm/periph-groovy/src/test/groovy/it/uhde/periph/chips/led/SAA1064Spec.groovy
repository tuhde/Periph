package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class SAA1064Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length]
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i]
        r
    }

    private static byte[] lastWrite(MockConnection c) {
        def w = c.writes()
        w[w.size() - 1]
    }

    def "full API"() {
        given:
        def c = new MockConnection()

        when:
        def d = new SAA1064Full(c)

        then: "init"
        c.writes()[0] == b(0x00, 0x47, 0, 0, 0, 0)

        when:
        d.showDigits([1, 2, 3, 4] as int[])

        then:
        lastWrite(c) == b(0x01, 0x06, 0x5B, 0x4F, 0x66)

        when:
        d.showDigits([SAA1064Minimal.BLANK, 0, 9, 8] as int[])

        then:
        lastWrite(c) == b(0x01, 0x00, 0x3F, 0x6F, 0x7F)

        when:
        d.showDigits([10, 0, 0, 0] as int[])

        then:
        thrown(IllegalArgumentException)

        when:
        d.showNumber(42)

        then:
        lastWrite(c) == b(0x01, 0x00, 0x00, 0x66, 0x5B)

        when:
        d.showNumber(0)

        then:
        lastWrite(c) == b(0x01, 0x00, 0x00, 0x00, 0x3F)

        when:
        d.showNumber(9999)

        then:
        lastWrite(c) == b(0x01, 0x6F, 0x6F, 0x6F, 0x6F)

        when:
        d.showNumber(10000)

        then:
        thrown(IllegalArgumentException)

        when:
        d.clear()

        then:
        lastWrite(c) == b(0x01, 0, 0, 0, 0)

        when:
        d.writeRaw([0xAA, 0x55] as int[], 2)

        then:
        lastWrite(c) == b(0x02, 0xAA, 0x55)

        when:
        d.writeRaw([1, 2, 3] as int[], 3)

        then:
        thrown(IllegalArgumentException)

        when:
        d.setMode(false)

        then:
        lastWrite(c) == b(0x00, 0x46)

        when:
        d.setMode(true)
        d.blank(true, false)

        then:
        lastWrite(c) == b(0x00, 0x45)

        when:
        d.blank(true, true)

        then:
        lastWrite(c) == b(0x00, 0x41)

        when:
        d.blank(false, false)

        then:
        lastWrite(c) == b(0x00, 0x47)

        when:
        d.setCurrent(21)

        then:
        lastWrite(c) == b(0x00, 0x77)

        when:
        d.setCurrent(0)

        then:
        lastWrite(c) == b(0x00, 0x07)

        when:
        d.setCurrent(12)

        then:
        lastWrite(c) == b(0x00, 0x47)

        when:
        d.setCurrent(10)

        then:
        thrown(IllegalArgumentException)

        when:
        d.segmentTest(true)

        then:
        lastWrite(c) == b(0x00, 0x4F)

        when:
        d.segmentTest(false)

        then:
        lastWrite(c) == b(0x00, 0x47)

        when:
        d.writeControl(0xFF)

        then:
        lastWrite(c) == b(0x00, 0x7F)

        when:
        d.writeControl(0x47)
        d.showDigit(2, 7, true)

        then:
        lastWrite(c) == b(0x02, 0x87)

        when:
        d.setDecimalPoint(2, false)

        then:
        lastWrite(c) == b(0x02, 0x07)

        when:
        d.setDecimalPoint(3, true)

        then:
        lastWrite(c) == b(0x03, 0xD5)

        when:
        d.showDigit(5, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        d.setSegmentMap([a: 7, b: 6, c: 5, d: 4, e: 3, f: 2, g: 1, dp: 0])
        d.showDigits([1, 1, 1, 1] as int[])

        then:
        lastWrite(c) == b(0x01, 0x60, 0x60, 0x60, 0x60)

        when:
        d.setSegmentMap([a: 0])

        then:
        thrown(IllegalArgumentException)

        when:
        c.queueRead(b(0x80))

        then:
        d.readStatus() == 0x80

        when:
        c.queueRead(b(0x80))

        then:
        d.powerResetFlag()

        when:
        c.queueRead(b(0x00))

        then:
        !d.powerResetFlag()
    }
}
