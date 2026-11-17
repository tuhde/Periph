package it.uhde.periph.chips.led

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class HT16K33Spec extends Specification {

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
        def d = new HT16K33Full(c)

        then: "init sequence"
        c.writes()[0] == b(0x21)
        c.writes()[1] == b(0xA0)
        c.writes()[2] == b(0xEF)
        c.writes()[3] == new byte[17]
        c.writes()[4] == b(0x81)

        when:
        d.setPixel(1, 9, true)
        d.show()
        def exp = new byte[17]
        exp[1 + 3] = (byte) 0x02

        then:
        lastWrite(c) == exp
        d.getPixel(1, 9)
        !d.getPixel(1, 8)

        when:
        d.setPixel(7, 15)
        d.setPixel(1, 9, false)

        then:
        !d.getPixel(1, 9)
        d.getPixel(7, 15)

        when:
        d.setPixel(8, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        d.clear()

        then:
        lastWrite(c) == new byte[17]

        when:
        d.writeRaw(b(0xAB, 0xCD), 4)

        then:
        lastWrite(c) == b(0x04, 0xAB, 0xCD)

        when:
        d.writeRaw(b(0, 0, 0), 14)

        then:
        thrown(IllegalArgumentException)

        when:
        d.setBrightness(7)

        then:
        lastWrite(c) == b(0xE7)

        when:
        d.setBlink(1.0d)

        then:
        lastWrite(c) == b(0x85)

        when:
        d.setBlink(0.5d)

        then:
        lastWrite(c) == b(0x87)

        when:
        d.setBlink(2.0d)

        then:
        lastWrite(c) == b(0x83)

        when:
        d.setBlink(3.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        d.displayOff()

        then:
        lastWrite(c) == b(0x82)

        when:
        d.displayOn()
        d.setBlink(0.0d)
        d.setIntOutput(true, false)

        then:
        lastWrite(c) == b(0xA1)

        when:
        d.setIntOutput(true, true)

        then:
        lastWrite(c) == b(0xA3)

        when:
        d.standby()

        then:
        lastWrite(c) == b(0x20)

        when:
        int n = c.writes().size()
        d.wake()
        def w = c.writes()

        then:
        w[n] == b(0x21)
        w[n + 1] == b(0xA3)
        w[n + 2] == b(0xE7)
        w[n + 3].length == 17
        w[n + 4] == b(0x81)

        when:
        c.queueRead(b(0x01, 0x00, 0x00, 0x10, 0x00, 0x00))
        long keys = d.readKeys()

        then:
        lastWrite(c) == b(0x40)
        keys == (1L | (1L << 25))

        when:
        c.queueRead(b(0x01))

        then:
        d.keyPressed()
        lastWrite(c) == b(0x60)

        when:
        c.queueRead(b(0x00))

        then:
        d.pollInterrupt() == 0L

        when:
        c.queueRead(b(0x01))
        c.queueRead(b(0, 0, 0, 0, 0x01, 0))

        then:
        d.pollInterrupt() == (1L << 26)
    }
}
