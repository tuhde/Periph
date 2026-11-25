package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.MockDisplayConnection
import spock.lang.Specification

class SSD1306Spec extends Specification {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length]
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]
        b
    }

    private static byte[] last(MockConnection c) {
        c.writes()[c.writes().size() - 1]
    }

    private static final byte[] SETUP = bytes(0x00, 0xAE, 0xD5, 0x80, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0x8D, 0x14, 0x20, 0x02,
            0xA0, 0xC0, 0xDA, 0x12, 0x81, 0x7F, 0xD9, 0x22, 0xDB, 0x20, 0xA4, 0xA6)

    def "init sequence"() {
        given:
        def c = new MockConnection()

        when:
        def lcd = new SSD1306Minimal(c)

        then:
        lcd.width() == 128
        lcd.height() == 64
        c.writes()[0] == SETUP
        c.writes()[1] == bytes(0x00, 0xB0, 0x00, 0x10)
        (2..6).every { int i -> c.writes()[i][0] == 0x40 && c.writes()[i].length - 1 == [31, 31, 31, 31, 4][i - 2] && c.writes()[i][1..-1].every { it == 0 } }
        c.writes().size() == 1 + 8 * 6 + 1
        c.writes()[1 + 7 * 6] == bytes(0x00, 0xB7, 0x00, 0x10)
        last(c) == bytes(0x00, 0xAF)
    }

    def "pixels and show"() {
        given:
        def c = new MockConnection()
        def lcd = new SSD1306Minimal(c)
        int n0 = c.writes().size()

        when:
        lcd.setPixel(3, 10, true)
        lcd.setPixel(127, 63, true)
        lcd.setPixel(128, 0, true)
        lcd.setPixel(-1, 5, true)

        then:
        c.writes().size() == n0

        when:
        lcd.show()

        then:
        c.writes().size() - n0 == 48
        c.writes()[n0 + 6] == bytes(0x00, 0xB1, 0x00, 0x10)
        c.writes()[n0 + 7][1 + 3] == 0x04
        c.writes()[n0 + 47][-1] == (byte) 0x80
    }

    def "options and validation"() {
        when:
        def o = new MockConnection()
        new SSD1306Minimal(o, 128, 32, true)

        then:
        o.writes()[0][9] == (byte) 0x8D
        o.writes()[0][10] == 0x10
        o.writes()[0][15] == (byte) 0xDA
        o.writes()[0][16] == 0x02
        o.writes()[0][4] == (byte) 0xA8
        o.writes()[0][5] == 0x1F

        when:
        def bad = new MockConnection()
        new SSD1306Minimal(bad, w, h, false)

        then:
        thrown(IllegalArgumentException)
        bad.writes().isEmpty()

        where:
        w   | h
        129 | 64
        128 | 60
        128 | 8
        128 | 72
        0   | 64
    }

    def "commands"() {
        given:
        def fc = new MockConnection()
        def f = new SSD1306Full(fc)
        def cases = [
            [{ SSD1306Full chip -> chip.setContrast(0x55) }, bytes(0x00, 0x81, 0x55)],
            [{ SSD1306Full chip -> chip.setDisplay(false) }, bytes(0x00, 0xAE)],
            [{ SSD1306Full chip -> chip.setDisplay(true) }, bytes(0x00, 0xAF)],
            [{ SSD1306Full chip -> chip.sleep() }, bytes(0x00, 0xAE)],
            [{ SSD1306Full chip -> chip.wake() }, bytes(0x00, 0xAF)],
            [{ SSD1306Full chip -> chip.setInvert(true) }, bytes(0x00, 0xA7)],
            [{ SSD1306Full chip -> chip.setInvert(false) }, bytes(0x00, 0xA6)],
            [{ SSD1306Full chip -> chip.setAllOn(true) }, bytes(0x00, 0xA5)],
            [{ SSD1306Full chip -> chip.setAllOn(false) }, bytes(0x00, 0xA4)],
            [{ SSD1306Full chip -> chip.setFlip(true, true) }, bytes(0x00, 0xA1, 0xC8)],
            [{ SSD1306Full chip -> chip.setFlip(false, true) }, bytes(0x00, 0xA0, 0xC8)],
            [{ SSD1306Full chip -> chip.setFlip(false, false) }, bytes(0x00, 0xA0, 0xC0)],
            [{ SSD1306Full chip -> chip.setStartLine(40) }, bytes(0x00, 0x68)],
            [{ SSD1306Full chip -> chip.setDisplayOffset(12) }, bytes(0x00, 0xD3, 12)],
            [{ SSD1306Full chip -> chip.setMultiplex(48) }, bytes(0x00, 0xA8, 47)],
            [{ SSD1306Full chip -> chip.setClock(3, 8) }, bytes(0x00, 0xD5, 0x82)],
            [{ SSD1306Full chip -> chip.setPrecharge(2, 5) }, bytes(0x00, 0xD9, 0x52)],
            [{ SSD1306Full chip -> chip.setComConfig(false, false) }, bytes(0x00, 0xDA, 0x02)],
            [{ SSD1306Full chip -> chip.setComConfig(true, false) }, bytes(0x00, 0xDA, 0x12)],
            [{ SSD1306Full chip -> chip.setComConfig(true, true) }, bytes(0x00, 0xDA, 0x32)],
            [{ SSD1306Full chip -> chip.setVcomh(0xFF) }, bytes(0x00, 0xDB, 0x70)],
            [{ SSD1306Full chip -> chip.setChargePump(true) }, bytes(0x00, 0x8D, 0x14)],
            [{ SSD1306Full chip -> chip.setChargePump(false) }, bytes(0x00, 0x8D, 0x10)],
            [{ SSD1306Full chip -> chip.setScrollHorizontal(true, 0, 7, 7) }, bytes(0x00, 0x26, 0x00, 0, 7, 7, 0x00, 0xFF)],
            [{ SSD1306Full chip -> chip.setScrollHorizontal(false, 1, 3, 4) }, bytes(0x00, 0x27, 0x00, 1, 4, 3, 0x00, 0xFF)],
            [{ SSD1306Full chip -> chip.setScrollVertical(true, 0, 7, 6, 5) }, bytes(0x00, 0x29, 0x00, 0, 6, 7, 5)],
            [{ SSD1306Full chip -> chip.setScrollArea(8, 40) }, bytes(0x00, 0xA3, 8, 40)],
            [{ SSD1306Full chip -> chip.sendCommand(0xE3, 0xE3) }, bytes(0x00, 0xE3, 0xE3)],
            [{ SSD1306Full chip -> chip.scrollStart() }, bytes(0x00, 0x2F)],
        ]

        expect:
        cases.every { List c ->
            ((Closure) c[0]).call(f)
            last(fc) == (byte[]) c[1]
        }
    }

    def "scrolling blocks RAM access and stop rewrites"() {
        given:
        def fc = new MockConnection()
        def f = new SSD1306Full(fc)
        f.scrollStart()
        int n0 = fc.writes().size()

        when:
        f.show()

        then:
        thrown(IllegalStateException)

        when:
        f.showPage(0)

        then:
        thrown(IllegalStateException)
        fc.writes().size() == n0

        when:
        f.scrollStop()

        then:
        fc.writes()[n0] == bytes(0x00, 0x2E)
        fc.writes().size() - n0 == 1 + 48
    }

    def "addressing modes and show page"() {
        given:
        def fc = new MockConnection()
        def f = new SSD1306Full(fc)

        when:
        f.setAddressingMode(SSD1306Minimal.ADDR_HORIZONTAL)
        int n0 = fc.writes().size()
        f.show()

        then:
        fc.writes()[n0] == bytes(0x00, 0x21, 0, 127, 0x22, 0, 7)
        fc.writes().subList(n0 + 1, fc.writes().size()).sum { it.length - 1 } == 128 * 8

        when:
        f.setAddressingMode(SSD1306Minimal.ADDR_VERTICAL)
        f.setPixel(0, 9, true)
        n0 = fc.writes().size()
        f.show()

        then:
        fc.writes()[n0 + 1][1] == 0x00
        fc.writes()[n0 + 1][2] == 0x02

        when:
        f.setPixel(0, 9, false)
        n0 = fc.writes().size()
        f.showPage(2)

        then:
        fc.writes()[n0] == bytes(0x00, 0x21, 0, 127, 0x22, 2, 2)

        when:
        f.setAddressingMode(SSD1306Minimal.ADDR_PAGE)
        n0 = fc.writes().size()
        f.showPage(2)
        f.showPage(8)

        then:
        fc.writes()[n0] == bytes(0x00, 0xB2, 0x00, 0x10)
        fc.writes().size() - n0 == 6

        when:
        f.setAddressingMode(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "drawing"() {
        given:
        def f = new SSD1306Full(new MockConnection())

        when:
        f.fill(true)

        then:
        f.getPixel(0, 0) && f.getPixel(127, 63)

        when:
        f.fill(false)

        then:
        !(f.getPixel(0, 0) || f.getPixel(-1, 0) || f.getPixel(0, 64))

        when:
        f.drawLine(0, 0, 9, 9, true)

        then:
        f.getPixel(0, 0) && f.getPixel(5, 5) && f.getPixel(9, 9) && !f.getPixel(5, 6)

        when:
        f.fill(false)
        f.drawRect(10, 20, 5, 4, true, false)

        then:
        f.getPixel(10, 20) && f.getPixel(14, 23) && f.getPixel(12, 20) && !f.getPixel(12, 21)

        when:
        f.fill(false)
        f.drawRect(10, 20, 3, 3, true, true)

        then:
        f.getPixel(11, 21) && f.getPixel(12, 22) && !f.getPixel(13, 22)

        when:
        f.fill(false)
        f.blit(4, 8, bytes(0xFF, 0x0F, 0x01, 0x80), 2)

        then:
        f.getPixel(4, 8) && f.getPixel(4, 15) && f.getPixel(5, 8) && !f.getPixel(5, 12) &&
                f.getPixel(5, 11) && f.getPixel(4, 16) && f.getPixel(5, 23) && !f.getPixel(4, 17)

        when:
        f.blit(4, 3, bytes(1, 2), 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "spi"() {
        given:
        def dc = new MockDisplayConnection()

        when:
        def lcd = new SSD1306SpiMinimal(dc)
        def cmds = dc.commands()

        then:
        dc.events()[0] == 'reset'
        (0..<24).every { int i -> cmds[i] == (SETUP[i + 1] & 0xFF) }
        cmds[cmds.size() - 1] == 0xAF
        dc.events().count { it.startsWith('data:') } == 8

        when:
        int n = dc.events().size()
        lcd.setPixel(5, 0, true)
        lcd.show()
        def shown = dc.events().subList(n, dc.events().size())

        then:
        shown[0].startsWith('command:b0')
        shown[1].startsWith('command:00')
        shown[2].startsWith('command:10')
        shown[3].startsWith('data:')
        shown[3].length() - 'data:'.length() == 128 * 2

        when:
        def dc2 = new MockDisplayConnection()
        def c = new SSD1306SpiFull(dc2)
        int m = dc2.commands().size()
        c.setContrast(0x20)

        then:
        dc2.commands().subList(m, dc2.commands().size()) == [0x81, 0x20]

        when:
        c.scrollStart()
        c.show()

        then:
        thrown(IllegalStateException)
    }
}
