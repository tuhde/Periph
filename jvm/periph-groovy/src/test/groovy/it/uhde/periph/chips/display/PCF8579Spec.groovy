package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class PCF8579Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        out
    }

    private static byte[] cat(List<Integer> head, int n, int fill) {
        byte[] out = new byte[head.size() + n]
        for (int i = 0; i < head.size(); i++) out[i] = (byte) head[i]
        for (int i = 0; i < n; i++) out[head.size() + i] = (byte) fill
        out
    }

    private static byte[] last(MockConnection c) {
        def w = c.writes()
        w[w.size() - 1]
    }

    private static byte[] prev(MockConnection c) {
        def w = c.writes()
        w[w.size() - 2]
    }

    def "init clears the RAM then switches the display on"() {
        when:
        def c = new MockConnection()
        new PCF8579Full(c)

        then:
        c.writes()[0] == cat([0xE0, 0xC2, 0xFC, 0xF8, 0x00], 160, 0)
        c.writes()[1] == b(0x46)

        when:
        def sub = new MockConnection()
        new PCF8579Minimal(sub, 3, 16, true)

        then:
        sub.writes()[0] == cat([0xE3, 0xD2, 0xFC, 0xF8, 0x00], 160, 0)
        sub.writes()[1] == b(0x56)

        expect:
        [[8, 0x45], [24, 0x47], [32, 0x44]].every { List<Integer> cs ->
            def m = new MockConnection()
            new PCF8579Minimal(m, 0, cs[0])
            m.writes()[1] == b(cs[1])
        }

        when:
        new PCF8579Minimal(new MockConnection(), 0, 12)

        then:
        thrown(IllegalArgumentException)

        when:
        new PCF8579Minimal(new MockConnection(), 16)

        then:
        thrown(IllegalArgumentException)
    }

    def "pixels and columns"() {
        given:
        def c = new MockConnection()
        def lcd = new PCF8579Full(c)

        when:
        lcd.clear()

        then:
        last(c) == cat([0xE0, 0xF8, 0x00], 160, 0)

        when:
        lcd.setPixel(0, 0, true)

        then:
        last(c) == b(0xE0, 0xF0, 0, 0x01)

        when:
        lcd.setPixel(3, 9, true)

        then:
        last(c) == b(0xE0, 0xF1, 3, 0x02)

        when:
        lcd.setPixel(3, 10, true)

        then:
        last(c) == b(0xE0, 0xF1, 3, 0x06)

        when:
        lcd.setPixel(3, 9, false)

        then:
        last(c) == b(0xE0, 0xF1, 3, 0x04)

        when:
        lcd.setPixel(39, 15, true)

        then:
        last(c) == b(0xE0, 0xF1, 39, 0x80)

        when:
        int n = c.writes().size()
        lcd.setPixel(40, 0, true)
        lcd.setPixel(0, 16, true)
        lcd.setPixel(-1, 0, true)

        then:
        c.writes().size() == n
        lcd.getPixel(0, 0)
        !lcd.getPixel(3, 9)
        c.writes().size() == n

        when:
        lcd.writeColumns(38, 0, b(1, 2, 3, 4))

        then:
        last(c) == b(0xE0, 0xF0, 38, 1, 2)

        when:
        lcd.writeColumns(0, 2, b(1))

        then:
        thrown(IllegalArgumentException)
    }

    def "drawing"() {
        given:
        def c = new MockConnection()
        def lcd = new PCF8579Full(c)

        when:
        lcd.fill(true)

        then:
        prev(c) == cat([0xE0, 0xF0, 0], 40, 0xFF)
        last(c) == cat([0xE0, 0xF1, 0], 40, 0xFF)

        when:
        lcd.fill(false)

        then:
        !lcd.getPixel(5, 5)

        when:
        lcd.clear()
        int n = c.writes().size()
        lcd.drawLine(0, 0, 3, 0, true)

        then:
        c.writes().size() == n + 1
        last(c) == b(0xE0, 0xF0, 0, 1, 1, 1, 1)

        when:
        lcd.clear()
        lcd.drawRect(0, 0, 3, 3, true, false)

        then:
        lcd.getPixel(0, 0)
        lcd.getPixel(2, 2)
        !lcd.getPixel(1, 1)

        when:
        lcd.drawRect(0, 0, 3, 3, true, true)

        then:
        lcd.getPixel(1, 1)

        when:
        lcd.showBitmap(2, 8, b(0xAA, 0xBB))

        then:
        last(c) == b(0xE0, 0xF1, 2, 0xAA, 0xBB)

        when:
        lcd.showBitmap(0, 3, b(1))

        then:
        thrown(IllegalArgumentException)
    }

    def "configuration"() {
        given:
        def c = new MockConnection()
        def lcd = new PCF8579Full(c)

        when:
        lcd.setDisplay(PCF8579Full.DISPLAY_BLANK)

        then:
        last(c) == b(0x42)

        when:
        lcd.setDisplay(PCF8579Full.DISPLAY_INVERSE)

        then:
        last(c) == b(0x4E)

        when:
        lcd.setDisplay(PCF8579Full.DISPLAY_ALL_ON)

        then:
        last(c) == b(0x4A)

        when:
        lcd.setDisplay(PCF8579Full.DISPLAY_NORMAL)

        then:
        last(c) == b(0x46)

        when:
        lcd.setStartBank(1)

        then:
        last(c) == b(0x7D)

        when:
        lcd.scroll(1)

        then:
        last(c) == b(0x7C)

        when:
        lcd.scroll(-1)

        then:
        last(c) == b(0x7D)

        when:
        lcd.setStartBank(4)

        then:
        thrown(IllegalArgumentException)

        when:
        lcd.setMode(24, true)

        then:
        last(c) == b(0x57)

        when:
        lcd.setMode(16, false)

        then:
        last(c) == b(0x46)

        when:
        lcd.setMode(12, false)

        then:
        thrown(IllegalArgumentException)

        when:
        def sc = new MockConnection()
        new PCF8579Full(sc, 5).setPixel(0, 0, true)

        then:
        last(sc)[0] == (byte) 0xE5

        when:
        lcd.reset()

        then:
        prev(c) == cat([0xE0, 0xC2, 0xFC, 0xF8, 0x00], 160, 0)
        last(c) == b(0x46)
    }

    def "raw RAM"() {
        given:
        def c = new MockConnection()
        def lcd = new PCF8579Full(c)

        when:
        lcd.writeRam(10, 0, b(1, 2), PCF8579Full.ACCESS_CHARACTER)

        then:
        last(c) == b(0xE0, 0xF0, 10, 1, 2)

        when:
        lcd.writeRam(10, 2, b(1, 2, 3, 4), PCF8579Full.ACCESS_HALF_GRAPHIC)

        then:
        last(c) == b(0xE0, 0xF6, 10, 1, 2, 3, 4)

        when:
        lcd.writeRam(10, 0, b(1, 2, 3, 4, 5, 6, 7, 8), PCF8579Full.ACCESS_FULL_GRAPHIC)

        then:
        last(c) == b(0xE0, 0xF8, 10, 1, 2, 3, 4, 5, 6, 7, 8)

        when:
        lcd.writeRam(39, 0, b(0, 0), 0)

        then:
        thrown(IllegalArgumentException)

        when:
        lcd.writeRam(0, 4, b(1), 0)

        then:
        thrown(IllegalArgumentException)

        when:
        lcd.writeRam(0, 1, b(1), 1)

        then:
        thrown(IllegalArgumentException)

        when:
        lcd.writeRamCascade(new byte[200], PCF8579Full.ACCESS_FULL_GRAPHIC)

        then:
        last(c) == cat([0xE0, 0xF8, 0], 200, 0)

        when:
        def data = lcd.readRam(16, 0, 4, PCF8579Full.ACCESS_CHARACTER)

        then:
        last(c) == b(0xE0, 0xF0, 16)
        data.length == 4

        when:
        lcd.readRam(5, 2, 3, PCF8579Full.ACCESS_HALF_GRAPHIC)

        then:
        last(c) == b(0xE0, 0xF6, 5)
    }
}
