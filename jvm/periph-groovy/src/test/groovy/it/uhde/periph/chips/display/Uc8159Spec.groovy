package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import it.uhde.periph.connection.WireMode
import spock.lang.Specification

class Uc8159Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        out
    }

    private static boolean cmdIs(MockDisplayConnection m, int index, int cmd, int... data) {
        m.commands()[index] == cmd && Arrays.equals(m.commandData(index), b(data))
    }

    def "init sequence"() {
        given:
        def m = new MockDisplayConnection()

        when:
        def d = new Uc8159Minimal(m)

        then:
        m.events()[0] == 'reset'
        m.events()[1] == 'waitBusy:false:60000'
        m.commands().size() == 10
        cmdIs(m, 0, 0x00, 0xEF, 0x08)
        cmdIs(m, 1, 0x01, 0x37, 0x00, 0x23, 0x23)
        cmdIs(m, 2, 0x03, 0x00)
        cmdIs(m, 3, 0x06, 0xC7, 0xC7, 0x1D)
        cmdIs(m, 4, 0x30, 0x3C)
        cmdIs(m, 5, 0x41, 0x00)
        cmdIs(m, 6, 0x50, 0x37)
        cmdIs(m, 7, 0x60, 0x22)
        cmdIs(m, 8, 0x61, 0x02, 0x58, 0x01, 0xC0)
        cmdIs(m, 9, 0xE3, 0xAA)
        d.width() == 600
        d.height() == 448
    }

    def "a BUSY timeout during init throws"() {
        when:
        def bad = new MockDisplayConnection()
        bad.setReady(false)
        new Uc8159Minimal(bad)

        then:
        thrown(IOException)
    }

    def "framebuffer mapping"() {
        given:
        def d = new Uc8159Full(new MockDisplayConnection())

        expect:
        d.getPixel(0, 0) == Uc8159Minimal.WHITE

        when:
        for (int c = 0; c < 8; c++) d.setPixel(7 + c, 3, c)
        d.setPixel(599, 447, Uc8159Minimal.ORANGE)
        d.setPixel(-1, 0)
        d.setPixel(600, 0)

        then:
        (0..7).every { d.getPixel(7 + it, 3) == it }
        d.getPixel(598, 447) == Uc8159Minimal.WHITE
        d.getPixel(599, 447) == Uc8159Minimal.ORANGE
        d.getPixel(700, 9) == Uc8159Minimal.BLACK

        when:
        d.clear(Uc8159Minimal.GREEN)

        then:
        d.getPixel(333, 222) == Uc8159Minimal.GREEN
    }

    def "show sends the frame then PON DRF POF"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8159Minimal(m, 1000L)
        d.setPixel(0, 0, Uc8159Minimal.BLACK)
        d.setPixel(1, 0, Uc8159Minimal.ORANGE)
        d.setPixel(599, 447, Uc8159Minimal.RED)
        int n = m.events().size()

        when:
        d.show()
        def tail = m.events().subList(n, m.events().size())
        def frame = tail.findAll { it.startsWith('data:') }.collect { it.substring(5) }.join('')

        then:
        tail[0] == 'command:10:'
        frame.length() == 134400 * 2
        frame.startsWith('0611')
        frame.endsWith('14')
        tail[tail.size() - 6] == 'command:04:'
        tail[tail.size() - 5] == 'waitBusy:false:1000'
        tail[tail.size() - 4] == 'command:12:'
        tail[tail.size() - 2] == 'command:02:'
    }

    def "sleep then show wakes, a timeout throws"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8159Minimal(m, 0L)

        when:
        d.sleep()
        d.show()

        then:
        m.events().count { it == 'reset' } == 2

        when:
        m.setReady(false)
        d.show()

        then:
        thrown(IOException)
    }

    def "settings encodings"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8159Full(m, 0L)
        int base = m.commands().size()

        when:
        d.setFlip(true, false)
        d.setFlip(false, true)
        d.setFlip(false, false)
        d.setFrameRate(0x3A)
        d.setInterval(7)
        d.setInterval(3, Uc8159Minimal.BLACK, 0)
        d.setTemperatureSensor(true, -3)
        d.setVcomDc(-0.10d)
        d.setVcomDc(-2.0d)
        d.setVcomDc(-9.0d)
        d.sendCommand(0x13, b(0))

        then:
        cmdIs(m, base, 0x00, 0xEB, 0x08)
        cmdIs(m, base + 1, 0x00, 0xE7, 0x08)
        cmdIs(m, base + 2, 0x00, 0xEF, 0x08)
        cmdIs(m, base + 3, 0x30, 0x3A)
        cmdIs(m, base + 4, 0x50, 0x37)
        cmdIs(m, base + 5, 0x50, 0x03)
        cmdIs(m, base + 6, 0x41, 0x8D)
        cmdIs(m, base + 7, 0x82, 0x02)
        cmdIs(m, base + 8, 0x82, 0x28)
        cmdIs(m, base + 9, 0x82, 0x50)
        cmdIs(m, base + 10, 0x13, 0x00)
    }

    def "drawing primitives"() {
        given:
        def d = new Uc8159Full(new MockDisplayConnection(), 0L)

        when:
        d.drawLine(0, 0, 9, 9, Uc8159Minimal.RED)
        d.drawRect(10, 1, 3, 3, Uc8159Minimal.YELLOW, true)

        then:
        d.getPixel(9, 9) == Uc8159Minimal.RED
        d.getPixel(5, 4) == Uc8159Minimal.WHITE
        d.getPixel(11, 2) == Uc8159Minimal.YELLOW

        when:
        d.clear()
        d.drawRect(1, 1, 5, 4, Uc8159Minimal.BLUE)

        then:
        d.getPixel(5, 4) == Uc8159Minimal.BLUE
        d.getPixel(3, 2) == Uc8159Minimal.WHITE

        when:
        d.clear()
        d.blit(4, 2, b(0xA0, 0x40), 4, 2, Uc8159Minimal.GREEN)

        then:
        d.getPixel(4, 2) == Uc8159Minimal.GREEN
        d.getPixel(5, 2) == Uc8159Minimal.WHITE
        d.getPixel(5, 3) == Uc8159Minimal.GREEN
    }

    def "reads"() {
        given:
        def m = new MockDisplayConnection(WireMode.HALF_DUPLEX_PIN)
        def d = new Uc8159Full(m, 0L)

        when:
        m.queueRead(b(0x0F))

        then:
        d.readStatus() == 0x0F

        when:
        m.queueRead(b(0xE7, 0x00))

        then:
        d.readTemperature() == -25

        when:
        m.queueRead(b(0x01))

        then:
        !d.readLowPower()

        when:
        m.queueRead(b(0x0C))

        then:
        Math.abs(d.readVcom() - (-0.60d)) < 1e-9d

        when:
        new Uc8159Full(new MockDisplayConnection(), 0L).readStatus()

        then:
        thrown(UnsupportedOperationException)
    }
}
