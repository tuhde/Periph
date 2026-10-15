package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import it.uhde.periph.connection.WireMode
import spock.lang.Specification

class Il0373Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        out
    }

    private static boolean cmdIs(MockDisplayConnection m, int index, int cmd, int... data) {
        m.commands()[index] == cmd && Arrays.equals(m.commandData(index), b(data))
    }

    def "init sequence at the default size"() {
        given:
        def m = new MockDisplayConnection()

        when:
        def d = new Il0373Minimal(m)

        then:
        m.events()[0] == 'reset'
        m.events()[1] == 'waitBusy:false:30000'
        m.events()[4] == 'waitBusy:false:30000'
        m.commands().size() == 5
        cmdIs(m, 0, 0x06, 0x17, 0x17, 0x17)
        cmdIs(m, 1, 0x04)
        cmdIs(m, 2, 0x00, 0x1F)
        cmdIs(m, 3, 0x61, 0x68, 0x00, 0xD4)
        cmdIs(m, 4, 0x50, 0x97)
        d.width() == 104
        d.height() == 212
    }

    def "init sends TRES for the largest panel"() {
        given:
        def m = new MockDisplayConnection()

        when:
        new Il0373Minimal(m, 160, 296, 0L)

        then:
        cmdIs(m, 3, 0x61, 0xA0, 0x01, 0x28)
    }

    def "invalid sizes and a BUSY timeout are rejected"() {
        when:
        new Il0373Minimal(new MockDisplayConnection(), 101, 212, 0L)

        then:
        thrown(IllegalArgumentException)

        when:
        new Il0373Minimal(new MockDisplayConnection(), 104, 297, 0L)

        then:
        thrown(IllegalArgumentException)

        when:
        def bad = new MockDisplayConnection()
        bad.setReady(false)
        new Il0373Minimal(bad)

        then:
        thrown(IOException)
    }

    def "framebuffer mapping"() {
        given:
        def d = new Il0373Full(new MockDisplayConnection(), 16, 2, 0L)

        expect:
        d.getPixel(0, 0) == Il0373Minimal.WHITE

        when:
        d.setPixel(0, 0, Il0373Minimal.BLACK)
        d.setPixel(9, 1, Il0373Minimal.BLACK)
        d.setPixel(-1, 0)
        d.setPixel(16, 0)
        d.setPixel(0, 2)

        then:
        d.getPixel(0, 0) == Il0373Minimal.BLACK
        d.getPixel(9, 1) == Il0373Minimal.BLACK

        when:
        d.setPixel(0, 0, Il0373Minimal.WHITE)

        then:
        d.getPixel(0, 0) == Il0373Minimal.WHITE
    }

    def "show runs DTM1 DTM2 DRF CDI POF with BUSY waits"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Il0373Minimal(m, 16, 2, 1000L)
        d.setPixel(0, 0, Il0373Minimal.BLACK)
        int n = m.events().size()

        when:
        d.show()
        def tail = m.events().subList(n, m.events().size())

        then:
        tail[0] == 'command:10:'
        tail[1] == 'data:80000000'
        tail[2] == 'command:13:'
        tail[3] == 'data:7fffffff'
        tail[4] == 'command:12:'
        tail[5] == 'waitBusy:false:1000'
        tail[6] == 'command:50:f7'
        tail[7] == 'command:02:'
        tail.size() == 9

        when:
        n = m.events().size()
        d.show()
        tail = m.events().subList(n, m.events().size())

        then:
        tail[0] == 'command:04:'
        tail[1] == 'waitBusy:false:1000'
        tail[2] == 'command:50:97'
        tail[3] == 'command:10:'
    }

    def "a BUSY timeout throws"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Il0373Minimal(m, 16, 2, 0L)

        when:
        m.setReady(false)
        d.show()

        then:
        thrown(IOException)
    }

    def "sleep then show wakes the controller"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Il0373Minimal(m, 16, 2, 0L)

        when:
        d.sleep()
        int l = m.commands().size()

        then:
        cmdIs(m, l - 3, 0x50, 0xF7)
        cmdIs(m, l - 2, 0x02)
        cmdIs(m, l - 1, 0x07, 0xA5)

        when:
        d.show()

        then:
        m.events().count { it == 'reset' } == 2
    }

    def "settings encodings"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Il0373Full(m, 16, 2, 0L)
        int base = m.commands().size()

        when:
        d.setFlip(true, false)
        d.setPower(14.0d, 14.0d, 20.0d)
        d.setPower(6.4d, 15.0d, 17.0d)
        d.setVcomDc(-1.0d)
        d.setVcomDc(-9.0d)
        d.setFrameRate(50)
        d.setFrameRate(200)
        d.setFrameRate(30)
        d.setSoftStart(0x17, 0x17, 0x17)
        d.setInterval(7)
        d.setTemperatureSensor(true, -3)
        d.writeLut(0, new byte[44])
        d.writeLut(4, new byte[42])

        then:
        cmdIs(m, base, 0x00, 0x1B)
        cmdIs(m, base + 1, 0x01, 0x03, 0x00, 0x26, 0x26, 0x03)
        cmdIs(m, base + 2, 0x01, 0x03, 0x03, 0x00, 0x2B, 0x03)
        cmdIs(m, base + 3, 0x82, 0x12)
        cmdIs(m, base + 4, 0x82, 0x3A)
        cmdIs(m, base + 5, 0x30, 0x3C)
        cmdIs(m, base + 6, 0x30, 0x39)
        cmdIs(m, base + 7, 0x30, 0x09)
        cmdIs(m, base + 8, 0x06, 0x17, 0x17, 0x17)
        cmdIs(m, base + 9, 0x50, 0x97)
        cmdIs(m, base + 10, 0x41, 0x8D)
        m.commands()[base + 11] == 0x20
        m.commandData(base + 11).length == 44
        cmdIs(m, base + 12, 0x00, 0x3B)
        m.commands()[base + 13] == 0x24

        when:
        d.writeLut(5, new byte[0])

        then:
        thrown(IllegalArgumentException)
    }

    def "drawing primitives"() {
        given:
        def d = new Il0373Full(new MockDisplayConnection(), 16, 2, 0L)

        when:
        d.drawLine(0, 0, 1, 1)
        d.drawRect(4, 0, 4, 2, Il0373Minimal.BLACK, true)

        then:
        d.getPixel(1, 1) == Il0373Minimal.BLACK
        d.getPixel(4, 0) == Il0373Minimal.BLACK
        d.getPixel(7, 1) == Il0373Minimal.BLACK

        when:
        d.clear()
        d.drawRect(0, 0, 4, 2)

        then:
        d.getPixel(3, 1) == Il0373Minimal.BLACK
        d.getPixel(4, 0) == Il0373Minimal.WHITE

        when:
        d.clear()
        d.blit(8, 0, b(0xA0), 4, 1)

        then:
        d.getPixel(8, 0) == Il0373Minimal.BLACK
        d.getPixel(9, 0) == Il0373Minimal.WHITE
        d.getPixel(10, 0) == Il0373Minimal.BLACK

        when:
        d.clear(Il0373Minimal.BLACK)

        then:
        d.getPixel(5, 1) == Il0373Minimal.BLACK
    }

    def "partial window"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Il0373Full(m, 32, 4, 0L)
        int n = m.events().size()

        when:
        d.showRegion(9, 1, 6, 2)
        def tail = m.events().subList(n, m.events().size())

        then:
        tail[0] == 'command:91:'
        tail[1] == 'command:90:080f0001000200'
        tail[2] == 'command:10:'
        tail[3] == 'data:00'
        tail[5] == 'command:13:'
        tail[6] == 'data:ff'
        tail[tail.size() - 1] == 'command:92:'

        when:
        d.showRegion(0, 0, 0, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        d.showRegion(40, 0, 4, 4)

        then:
        thrown(IllegalArgumentException)
        d.waitBusy(10L)
        m.events()[m.events().size() - 1] == 'waitBusy:false:10'
    }

    def "reads"() {
        given:
        def m = new MockDisplayConnection(WireMode.HALF_DUPLEX_PIN)
        def d = new Il0373Full(m, 16, 2, 0L)

        when:
        m.queueRead(b(0x05))

        then:
        d.readStatus() == 5

        when:
        m.queueRead(b(0xE7, 0x00))

        then:
        d.readTemperature() == -25

        when:
        m.queueRead(b(0x19))

        then:
        d.readRevision() == 0x19

        when:
        m.queueRead(b(0x0A))

        then:
        Math.abs(d.readVcom() - (-0.60d)) < 1e-9d

        when:
        m.queueRead(b(0x00))

        then:
        d.readLowPower()

        when:
        m.queueRead(b(0xFF, 0x11, 0x22))

        then:
        Arrays.equals(d.readOtp(2), b(0x11, 0x22))

        when:
        new Il0373Full(new MockDisplayConnection(), 16, 2, 0L).readStatus()

        then:
        thrown(UnsupportedOperationException)
    }
}
