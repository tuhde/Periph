package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import it.uhde.periph.connection.MockDisplayConnection
import spock.lang.Specification

class SSD1305Spec extends Specification {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length]
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]
        b
    }

    private static byte[] last(MockConnection c) {
        c.writes()[c.writes().size() - 1]
    }

    private static final byte[] SETUP = bytes(0x00, 0xAE, 0xD5, 0x70, 0xA8, 0x3F, 0xD3, 0x00, 0x40, 0xA0, 0xC0, 0xDA,
            0x12, 0x81, 0x80, 0xD9, 0x22, 0xDB, 0x34, 0xAD, 0x8E, 0xA4, 0xA6, 0x20, 0x02)

    def "init sequence"() {
        given:
        def c = new MockConnection()

        when:
        def lcd = new SSD1305Minimal(c)

        then:
        lcd.width() == 128
        lcd.height() == 64
        c.writes()[0] == SETUP
        c.writes()[1] == bytes(0x00, 0xB0, 0x00, 0x10)
        (2..6).every { int i -> c.writes()[i][0] == 0x40 && c.writes()[i].length - 1 == [31, 31, 31, 31, 8][i - 2] && c.writes()[i][1..-1].every { it == 0 } }
        c.writes().size() == 1 + 8 * 6 + 1
        c.writes()[1 + 7 * 6] == bytes(0x00, 0xB7, 0x00, 0x10)
        last(c) == bytes(0x00, 0xAF)
    }

    def "pixels and show"() {
        given:
        def c = new MockConnection()
        def lcd = new SSD1305Minimal(c)
        int n0 = c.writes().size()

        when:
        lcd.setPixel(3, 10, true)
        lcd.setPixel(127, 63, true)
        lcd.setPixel(128, 0, true)
        lcd.setPixel(-1, 5, true)

        then: "framebuffer only"
        c.writes().size() == n0

        when:
        lcd.show()

        then:
        c.writes().size() - n0 == 48
        c.writes()[n0 + 6] == bytes(0x00, 0xB1, 0x04, 0x10)
        c.writes()[n0 + 7][1 + 3] == 0x04
        c.writes()[n0 + 47][-1] == (byte) 0x80

        when:
        lcd.clear()
        n0 = c.writes().size()
        lcd.show()

        then:
        c.writes().subList(n0, c.writes().size()).every { byte[] w -> w[0] != 0x40 || w[1..-1].every { it == 0 } }
    }

    def "options and validation"() {
        when:
        def o = new MockConnection()
        new SSD1305Minimal(o, 128, 32, 0)

        then:
        o.writes()[0][11] == (byte) 0xDA
        o.writes()[0][12] == 0x02
        o.writes()[0][4] == (byte) 0xA8
        o.writes()[0][5] == 0x1F

        when:
        def o2 = new MockConnection()
        def narrow = new SSD1305Minimal(o2, 100, 64, 16)
        int n0 = o2.writes().size()
        narrow.show()

        then:
        o2.writes()[n0] == bytes(0x00, 0xB0, 0x00, 0x11)

        when:
        def bad = new MockConnection()
        new SSD1305Minimal(bad, w, h, off)

        then:
        thrown(IllegalArgumentException)
        bad.writes().isEmpty()

        where:
        w   | h  | off
        133 | 64 | 0
        128 | 60 | 0
        128 | 8  | 0
        128 | 72 | 0
        128 | 64 | 5
        0   | 64 | 0
    }

    def "commands"() {
        given:
        def fc = new MockConnection()
        def f = new SSD1305Full(fc)
        def cases = [
            [{ SSD1305Full chip -> chip.setContrast(0x55) }, bytes(0x00, 0x81, 0x55)],
            [{ SSD1305Full chip -> chip.setDisplay(false) }, bytes(0x00, 0xAE)],
            [{ SSD1305Full chip -> chip.setDisplay(true) }, bytes(0x00, 0xAF)],
            [{ SSD1305Full chip -> chip.sleep() }, bytes(0x00, 0xAE)],
            [{ SSD1305Full chip -> chip.wake() }, bytes(0x00, 0xAF)],
            [{ SSD1305Full chip -> chip.setInvert(true) }, bytes(0x00, 0xA7)],
            [{ SSD1305Full chip -> chip.setInvert(false) }, bytes(0x00, 0xA6)],
            [{ SSD1305Full chip -> chip.setAllOn(true) }, bytes(0x00, 0xA5)],
            [{ SSD1305Full chip -> chip.setAllOn(false) }, bytes(0x00, 0xA4)],
            [{ SSD1305Full chip -> chip.setFlip(true, true) }, bytes(0x00, 0xA1, 0xC8)],
            [{ SSD1305Full chip -> chip.setFlip(false, true) }, bytes(0x00, 0xA0, 0xC8)],
            [{ SSD1305Full chip -> chip.setFlip(false, false) }, bytes(0x00, 0xA0, 0xC0)],
            [{ SSD1305Full chip -> chip.setStartLine(40) }, bytes(0x00, 0x68)],
            [{ SSD1305Full chip -> chip.setDisplayOffset(12) }, bytes(0x00, 0xD3, 12)],
            [{ SSD1305Full chip -> chip.setMultiplex(48) }, bytes(0x00, 0xA8, 47)],
            [{ SSD1305Full chip -> chip.setClock(3, 8) }, bytes(0x00, 0xD5, 0x82)],
            [{ SSD1305Full chip -> chip.setPrecharge(2, 5) }, bytes(0x00, 0xD9, 0x52)],
            [{ SSD1305Full chip -> chip.setComConfig(false, false) }, bytes(0x00, 0xDA, 0x02)],
            [{ SSD1305Full chip -> chip.setComConfig(true, false) }, bytes(0x00, 0xDA, 0x12)],
            [{ SSD1305Full chip -> chip.setComConfig(true, true) }, bytes(0x00, 0xDA, 0x32)],
            [{ SSD1305Full chip -> chip.setVcomh(0xFF) }, bytes(0x00, 0xDB, 0x3C)],
            [{ SSD1305Full chip -> chip.setDimMode(0x40, 0x20) }, bytes(0x00, 0xAB, 0x00, 0x40, 0x20)],
            [{ SSD1305Full chip -> chip.setDim(true) }, bytes(0x00, 0xAC)],
            [{ SSD1305Full chip -> chip.setDim(false) }, bytes(0x00, 0xAF)],
            [{ SSD1305Full chip -> chip.setBrightness(0x33) }, bytes(0x00, 0x82, 0x33)],
            [{ SSD1305Full chip -> chip.setLut(49, 63, 40, 31) }, bytes(0x00, 0x91, 49, 63, 40, 31)],
            [{ SSD1305Full chip -> chip.setBankColors(bytes(1, 2, 3, 4), null) }, bytes(0x00, 0x92, 1, 2, 3, 4)],
            [{ SSD1305Full chip -> chip.setBankColors(bytes(1, 2, 3, 4), bytes(5, 6, 7, 8)) }, bytes(0x00, 0x93, 5, 6, 7, 8)],
            [{ SSD1305Full chip -> chip.setAreaColor(true, true) }, bytes(0x00, 0xD8, 0x35)],
            [{ SSD1305Full chip -> chip.setScrollHorizontal(true, 0, 7, 0, 1) }, bytes(0x00, 0x26, 1, 0, 0, 7)],
            [{ SSD1305Full chip -> chip.setScrollHorizontal(false, 1, 3, 4, 2) }, bytes(0x00, 0x27, 2, 1, 4, 3)],
            [{ SSD1305Full chip -> chip.setScrollVertical(true, 0, 7, 6, 1, 5) }, bytes(0x00, 0x29, 1, 0, 6, 7, 5)],
            [{ SSD1305Full chip -> chip.setScrollArea(8, 40) }, bytes(0x00, 0xA3, 8, 40)],
            [{ SSD1305Full chip -> chip.scrollStart() }, bytes(0x00, 0x2F)],
            [{ SSD1305Full chip -> chip.setAddressingMode(SSD1305Minimal.ADDR_HORIZONTAL) }, bytes(0x00, 0x20, 0x00)],
            [{ SSD1305Full chip -> chip.setAddressingMode(SSD1305Minimal.ADDR_VERTICAL) }, bytes(0x00, 0x20, 0x01)],
            [{ SSD1305Full chip -> chip.setAddressingMode(SSD1305Minimal.ADDR_PAGE) }, bytes(0x00, 0x20, 0x02)],
            [{ SSD1305Full chip -> chip.sendCommand(0xE3, 0xE3) }, bytes(0x00, 0xE3, 0xE3)],
        ]

        expect:
        cases.every { List c ->
            ((Closure) c[0]).call(f)
            last(fc) == (byte[]) c[1]
        }
    }

    def "scroll stop rewrites the RAM and addressing modes change show"() {
        given:
        def fc = new MockConnection()
        def f = new SSD1305Full(fc)
        int ks = fc.writes().size()

        when:
        f.scrollStop()

        then:
        fc.writes()[ks] == bytes(0x00, 0x2E)
        fc.writes().size() - ks == 1 + 48

        when:
        f.setAddressingMode(SSD1305Minimal.ADDR_HORIZONTAL)
        ks = fc.writes().size()
        f.show()

        then:
        fc.writes()[ks] == bytes(0x00, 0x21, 4, 131, 0x22, 0, 7)
        fc.writes().subList(ks + 1, fc.writes().size()).sum { it.length - 1 } == 128 * 8

        when:
        f.setAddressingMode(SSD1305Minimal.ADDR_VERTICAL)
        f.setPixel(0, 9, true)
        ks = fc.writes().size()
        f.show()

        then:
        fc.writes()[ks + 1][1] == 0x00
        fc.writes()[ks + 1][2] == 0x02

        when:
        f.setAddressingMode(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "show page"() {
        given:
        def fc = new MockConnection()
        def f = new SSD1305Full(fc)
        int n0 = fc.writes().size()

        when:
        f.showPage(2)

        then:
        fc.writes()[n0] == bytes(0x00, 0xB2, 0x04, 0x10)
        fc.writes().size() - n0 == 6

        when:
        f.showPage(8)

        then:
        fc.writes().size() - n0 == 6
    }

    def "drawing"() {
        given:
        def f = new SSD1305Full(new MockConnection())

        when:
        f.fill(true)

        then:
        f.getPixel(0, 0) && f.getPixel(127, 63)

        when:
        f.fill(false)

        then:
        !f.getPixel(0, 0) && !f.getPixel(-1, 0) && !f.getPixel(0, 64)

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

    def "reads"() {
        given:
        def rc = new MockConnection()
        def c = new SSD1305Full(rc)

        when:
        rc.queueRead(bytes(0x40))
        def status = c.readStatus()

        then:
        status == 0x40
        last(rc) == bytes(0x00)

        when:
        rc.queueRead(bytes(0xEE, 0x11, 0x22, 0x33))
        int k = rc.writes().size()
        def ram = c.readPage(2, 5, 3)

        then:
        ram == bytes(0x11, 0x22, 0x33)
        rc.writes()[k] == bytes(0x00, 0xB2, 0x05, 0x10)
        rc.writes()[k + 1] == bytes(0x40)
    }

    def "spi"() {
        given:
        def dc = new MockDisplayConnection()

        when:
        def lcd = new SSD1305SpiMinimal(dc)
        def cmds = dc.commands()

        then:
        dc.events()[0] == 'reset'
        (0..<24).every { int i -> cmds[i] == (SETUP[i + 1] & 0xFF) }
        cmds[-1] == 0xAF
        (0..<cmds.size()).every { int i -> dc.commandData(i).length == 0 }
        dc.events().count { it.startsWith('data:') } == 8

        when:
        int n = dc.events().size()
        lcd.setPixel(5, 0, true)
        lcd.show()
        def shown = dc.events().subList(n, dc.events().size())

        then:
        shown[0].startsWith('command:b0')
        shown[1].startsWith('command:04')
        shown[2].startsWith('command:10')
        shown[3].startsWith('data:')
        shown[3].length() - 'data:'.length() == 128 * 2

        when:
        def dc2 = new MockDisplayConnection()
        def c = new SSD1305SpiFull(dc2)
        c.readStatus()

        then:
        thrown(UnsupportedOperationException)

        when:
        c.readPage(0, 0, 2)

        then:
        thrown(UnsupportedOperationException)

        when:
        int m = dc2.commands().size()
        c.setContrast(0x20)

        then:
        dc2.commands().subList(m, dc2.commands().size()) == [0x81, 0x20]
    }
}
