package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import spock.lang.Specification

class Ssd1331Spec extends Specification {

    /** Command/parameter bytes as ints (asserting none carries parameters); data events as -length. */
    private static List<Integer> stream(MockDisplayConnection m, int from) {
        List<Integer> out = []
        m.events().drop(from).each { String e ->
            if (e.startsWith('command:')) {
                def p = e.split(':', -1)
                assert p[2] == '' : 'parameters must be separate commands (D/C# low)'
                out << Integer.parseInt(p[1], 16)
            } else if (e.startsWith('data:')) {
                out << (int) (-(e.length() - 5) / 2)
            }
        }
        out
    }

    private static int dataBytes(MockDisplayConnection m, int from) {
        int n = 0
        m.events().drop(from).each { String e -> if (e.startsWith('data:')) n += (int) ((e.length() - 5) / 2) }
        n
    }

    def "init sequence is the vendor sequence"() {
        given:
        def m = new MockDisplayConnection()

        when:
        def d = new Ssd1331Minimal(m)

        then:
        m.events()[0] == 'reset'
        m.events()[1] == 'delay:100'
        stream(m, 0) == [0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06, 0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64,
                         0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4, 0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31,
                         0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E, 0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, 0xAF]
        m.events()[m.events().size() - 2] == 'delay:100'
        dataBytes(m, 0) == 0
        d.width() == 96
        d.height() == 64
    }

    def "framebuffer mapping and drawing"() {
        given:
        def d = new Ssd1331Full(new MockDisplayConnection())

        when:
        d.setPixel(0, 0, Ssd1331Minimal.RED)
        d.setPixel(95, 63, Ssd1331Minimal.BLUE)
        d.setPixel(1, 2, 0x1234)
        d.setPixel(-1, 0, 1)
        d.setPixel(0, -1, 1)
        d.setPixel(96, 0, 1)
        d.setPixel(0, 64, 1)

        then:
        d.getPixel(0, 0) == Ssd1331Minimal.RED
        d.getPixel(95, 63) == Ssd1331Minimal.BLUE
        d.getPixel(1, 2) == 0x1234
        d.getPixel(99, 99) == 0
        Ssd1331Minimal.rgb(255, 0, 0) == 0xF800
        Ssd1331Minimal.rgb(0, 255, 0) == 0x07E0
        Ssd1331Minimal.rgb(0, 0, 255) == 0x001F
        Ssd1331Minimal.rgb(255, 255, 255) == 0xFFFF

        when:
        d.clear(Ssd1331Minimal.GREEN)

        then:
        d.getPixel(0, 0) == Ssd1331Minimal.GREEN
        d.getPixel(95, 63) == Ssd1331Minimal.GREEN

        when:
        d.clear(0)
        d.drawLine(0, 0, 4, 4, Ssd1331Minimal.WHITE)

        then:
        d.getPixel(0, 0) == 0xFFFF && d.getPixel(2, 2) == 0xFFFF && d.getPixel(4, 4) == 0xFFFF
        d.getPixel(4, 0) == 0

        when:
        d.clear(0)
        d.drawRect(2, 2, 4, 3, Ssd1331Minimal.WHITE, false)
        d.drawRect(10, 10, 3, 3, Ssd1331Minimal.RED, true)
        d.blit(20, 20, [1, 2, 3, 4] as int[], 2, 2)

        then:
        d.getPixel(2, 2) == 0xFFFF && d.getPixel(5, 4) == 0xFFFF
        d.getPixel(3, 3) == 0 && d.getPixel(6, 2) == 0
        d.getPixel(11, 11) == Ssd1331Minimal.RED && d.getPixel(13, 13) == 0
        [d.getPixel(20, 20), d.getPixel(21, 20), d.getPixel(20, 21), d.getPixel(21, 21)] == [1, 2, 3, 4]
    }

    def "show and showRegion"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Ssd1331Full(m)
        d.clear(Ssd1331Minimal.RED)
        int n = m.events().size()

        when:
        d.show()
        def s = stream(m, n)

        then:
        s.subList(0, 6) == [0x15, 0, 0x5F, 0x75, 0, 0x3F]
        s[6] == -192
        s.size() == 6 + 64
        dataBytes(m, n) == 12288
        m.events()[n + 6].startsWith('data:f8')

        when:
        n = m.events().size()
        d.showRegion(10, 20, 4, 2)

        then:
        stream(m, n) == [0x15, 10, 13, 0x75, 20, 21, -8, -8]

        when:
        n = m.events().size()
        d.showRegion(90, 60, 20, 20)

        then:
        stream(m, n).subList(0, 6) == [0x15, 90, 95, 0x75, 60, 63]
        dataBytes(m, n) == 48

        when:
        n = m.events().size()
        d.showRegion(200, 0, 5, 5)

        then:
        m.events().size() == n
    }

    def "hardware primitives and settings"() {
        given:
        List cases = [
            [{ Ssd1331Full d -> d.hwLine(1, 2, 3, 4, Ssd1331Minimal.RED) }, [0x21, 1, 2, 3, 4, 0x3E, 0, 0]],
            [{ Ssd1331Full d -> d.hwRect(10, 20, 5, 6, Ssd1331Minimal.GREEN, Ssd1331Minimal.BLUE, true) }, [0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E]],
            [{ Ssd1331Full d -> d.hwRect(10, 20, 5, 6, Ssd1331Minimal.WHITE, 0, false) }, [0x26, 0, 0x22, 10, 20, 14, 25, 0x3E, 0x3F, 0x3E, 0, 0, 0]],
            [{ Ssd1331Full d -> d.hwRect(10, 20, 0, 6, Ssd1331Minimal.WHITE, 0, false) }, []],
            [{ Ssd1331Full d -> d.hwClear(0, 1, 2, 3) }, [0x25, 0, 1, 2, 3]],
            [{ Ssd1331Full d -> d.hwDim(0, 1, 2, 3) }, [0x24, 0, 1, 2, 3]],
            [{ Ssd1331Full d -> d.hwCopy(1, 2, 3, 4, 5, 6) }, [0x23, 1, 2, 3, 4, 5, 6]],
            [{ Ssd1331Full d -> d.scroll(1, 0, 64, 2, 7) }, [0x27, 1, 0, 64, 2, 3, 0x2F]],
            [{ Ssd1331Full d -> d.scrollStop() }, [0x2E]],
            [{ Ssd1331Full d -> d.setContrast(1, 2, 3) }, [0x81, 1, 0x82, 2, 0x83, 3]],
            [{ Ssd1331Full d -> d.setMasterCurrent(16) }, [0x87, 0x0F]],
            [{ Ssd1331Full d -> d.setMasterCurrent(0) }, [0x87, 0x00]],
            [{ Ssd1331Full d -> d.setDisplayMode(Ssd1331Full.MODE_INVERSE) }, [0xA7]],
            [{ Ssd1331Full d -> d.setFlip(false, false) }, [0xA0, 0x60]],
            [{ Ssd1331Full d -> d.setFlip(true, true) }, [0xA0, 0x72]],
            [{ Ssd1331Full d -> d.setPowerSave(true) }, [0xB0, 0x1A]],
            [{ Ssd1331Full d -> d.setPowerSave(false) }, [0xB0, 0x0B]],
            [{ Ssd1331Full d -> d.dimMode(1, 2, 3, 0xFF) }, [0xAB, 0, 1, 2, 3, 0x1F, 0xAC]],
            [{ Ssd1331Full d -> d.displayOff(); d.displayOn() }, [0xAE, 0xAF]],
            [{ Ssd1331Full d -> d.grayLinear() }, [0xB9]],
            [{ Ssd1331Full d -> d.sendCommand(0xAA, 0x12, 0x34) }, [0xAA, 0x12, 0x34]],
        ]

        expect:
        cases.each { List c ->
            def m = new MockDisplayConnection()
            def chip = new Ssd1331Full(m)
            int n = m.events().size()
            ((Closure) c[0]).call(chip)
            assert stream(m, n) == c[1]
        }
    }
}
