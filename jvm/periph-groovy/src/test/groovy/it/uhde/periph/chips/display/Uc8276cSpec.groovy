package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockDisplayConnection
import it.uhde.periph.connection.WireMode
import spock.lang.Specification

class Uc8276cSpec extends Specification {

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
        new Uc8276cMinimal(m)

        then:
        m.events()[0] == 'reset'
        m.events()[1] == 'waitBusy:false:30000'
        m.commands().size() == 2
        cmdIs(m, 0, 0x00, 0x0F)
        cmdIs(m, 1, 0x50, 0xD7)
    }

    def "init sends TRES for a smaller panel"() {
        given:
        def m = new MockDisplayConnection()

        when:
        new Uc8276cMinimal(m, 200, 100, 0L)

        then:
        cmdIs(m, 1, 0x61, 0x00, 0xC8, 0x00, 0x64)
    }

    def "invalid sizes and a BUSY_N timeout are rejected"() {
        when:
        new Uc8276cMinimal(new MockDisplayConnection(), 401, 300, 0L)

        then:
        thrown(IllegalArgumentException)

        when:
        new Uc8276cMinimal(new MockDisplayConnection(), 400, 301, 0L)

        then:
        thrown(IllegalArgumentException)

        when:
        def bad = new MockDisplayConnection()
        bad.setReady(false)
        new Uc8276cMinimal(bad)

        then:
        thrown(IOException)
    }

    def "framebuffer mapping"() {
        given:
        def d = new Uc8276cFull(new MockDisplayConnection(), 16, 2, 0L)

        expect:
        d.getPixel(0, 0) == Uc8276cMinimal.WHITE

        when:
        d.setPixel(0, 0, Uc8276cMinimal.BLACK)
        d.setPixel(9, 1, Uc8276cMinimal.RED)
        d.setPixel(-1, 0)
        d.setPixel(16, 0)
        d.setPixel(0, 2)

        then:
        d.getPixel(0, 0) == Uc8276cMinimal.BLACK
        d.getPixel(9, 1) == Uc8276cMinimal.RED

        when:
        d.setPixel(0, 0, Uc8276cMinimal.WHITE)

        then:
        d.getPixel(0, 0) == Uc8276cMinimal.WHITE
    }

    def "show runs DTM1 DTM2 PON DRF POF with BUSY_N waits"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8276cMinimal(m, 16, 2, 1000L)
        d.setPixel(0, 0, Uc8276cMinimal.RED)
        int n = m.events().size()

        when:
        d.show()
        def tail = m.events().subList(n, m.events().size())

        then:
        tail[0] == 'command:10:'
        tail[1] == 'data:7fffffff'
        tail[2] == 'command:13:'
        tail[3] == 'data:80000000'
        tail[4] == 'command:04:'
        tail[5] == 'waitBusy:false:1000'
        tail[6] == 'command:12:'
        tail[8] == 'command:02:'
        tail.size() == 10
    }

    def "show without a red pixel sends zeros, a timeout throws"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8276cMinimal(m, 16, 2, 0L)
        int n = m.events().size()

        when:
        d.show()

        then:
        m.events()[n + 3] == 'data:00000000'

        when:
        m.setReady(false)
        d.show()

        then:
        thrown(IOException)
    }

    def "sleep then show wakes the controller"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8276cMinimal(m, 16, 2, 0L)

        when:
        d.sleep()
        d.show()

        then:
        m.commands().indexOf(0x07) >= 0
        Arrays.equals(m.commandData(m.commands().indexOf(0x07)), b(0xA5))
        m.events().count { it == 'reset' } == 2
    }

    def "settings encodings"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8276cFull(m, 16, 2, 0L)
        int base = m.commands().size()

        when:
        d.setMode(Uc8276cMinimal.MODE_KW)
        d.setFlip(true, false)
        d.setPower(10.0d, 10.0d, 10.0d, 16.0d)
        d.setPower(2.4d, 11.0d, 5.0d, 13.0d)
        d.setVcomDc(-1.0d)
        d.setFrameRate(50)
        d.setFrameRate(200)
        d.setFrameRate(30)
        d.setSoftStart(0x17, 0x17, 0x17)
        d.setInterval(7)
        d.setTemperatureSensor(true, -3)
        d.writeLut(new byte[56], null, new byte[56], null, null)

        then:
        cmdIs(m, base, 0x00, 0x1F)
        cmdIs(m, base + 1, 0x00, 0x1B)
        cmdIs(m, base + 2, 0x01, 0x03, 0x00, 0x26, 0x26, 0x26)
        cmdIs(m, base + 3, 0x01, 0x03, 0x03, 0x00, 0x2B, 0x0D)
        cmdIs(m, base + 4, 0x82, 0x12)
        cmdIs(m, base + 5, 0x30, 0x3C)
        cmdIs(m, base + 6, 0x30, 0x39)
        cmdIs(m, base + 7, 0x30, 0x09)
        cmdIs(m, base + 8, 0x06, 0x17, 0x17, 0x17)
        cmdIs(m, base + 9, 0x50, 0xD7)
        cmdIs(m, base + 10, 0x41, 0x8D)
        m.commands()[base + 11] == 0x20
        m.commands()[base + 12] == 0x22
        cmdIs(m, base + 13, 0x00, 0x3B)
    }

    def "drawing primitives"() {
        given:
        def d = new Uc8276cFull(new MockDisplayConnection(), 16, 2, 0L)

        when:
        d.drawLine(0, 0, 1, 1)
        d.drawRect(4, 0, 4, 2, Uc8276cMinimal.RED, true)

        then:
        d.getPixel(1, 1) == Uc8276cMinimal.BLACK
        d.getPixel(4, 0) == Uc8276cMinimal.RED
        d.getPixel(7, 1) == Uc8276cMinimal.RED

        when:
        d.clear()
        d.drawRect(0, 0, 4, 2)

        then:
        d.getPixel(3, 1) == Uc8276cMinimal.BLACK
        d.getPixel(4, 0) == Uc8276cMinimal.WHITE

        when:
        d.clear()
        d.blit(8, 0, b(0xA0), 4, 1)

        then:
        d.getPixel(8, 0) == Uc8276cMinimal.BLACK
        d.getPixel(9, 0) == Uc8276cMinimal.WHITE
        d.getPixel(10, 0) == Uc8276cMinimal.BLACK

        when:
        d.clear(Uc8276cMinimal.RED)

        then:
        d.getPixel(5, 1) == Uc8276cMinimal.RED
    }

    def "partial window"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8276cFull(m, 32, 4, 0L)
        int n = m.events().size()

        when:
        d.showRegion(9, 1, 6, 2)
        def tail = m.events().subList(n, m.events().size())

        then:
        tail[0] == 'command:91:'
        tail[1] == 'command:90:0008000f0001000200'
        tail[2] == 'command:10:'
        tail[3] == 'data:ff'
        tail[tail.size() - 1] == 'command:92:'

        when:
        d.showRegion(0, 0, 0, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        d.showRegion(40, 0, 4, 4)

        then:
        thrown(IllegalArgumentException)
    }

    def "auto sequence and wait_busy"() {
        given:
        def m = new MockDisplayConnection()
        def d = new Uc8276cFull(m, 16, 2, 0L)

        when:
        d.refreshAuto()

        then:
        cmdIs(m, m.commands().size() - 1, 0x17, 0xA5)

        when:
        d.refreshAuto(true)

        then:
        cmdIs(m, m.commands().size() - 1, 0x17, 0xA7)
        d.waitBusy(10L)
        m.events()[m.events().size() - 1] == 'waitBusy:false:10'
    }

    def "reads"() {
        given:
        def m = new MockDisplayConnection(WireMode.HALF_DUPLEX_PIN)
        def d = new Uc8276cFull(m, 16, 2, 0L)

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
        d.readRevision() == [-1, 0x19] as int[]

        when:
        m.queueRead(b(0x00, 0x02, 0x01, 0x02, 0x03))

        then:
        d.readRevision(true) == [2, 0x030201] as int[]

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
        new Uc8276cFull(new MockDisplayConnection(), 16, 2, 0L).readStatus()

        then:
        thrown(UnsupportedOperationException)
    }
}
