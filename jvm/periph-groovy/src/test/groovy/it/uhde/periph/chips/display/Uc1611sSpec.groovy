package it.uhde.periph.chips.display

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Uc1611sSpec extends Specification {

    private static final byte[] DE = bytes(0xDA, 0x80, 0x10)   // Status1 with DE set

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length]
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i]
        b
    }

    private static byte[] last(MockConnection c) {
        c.writes()[c.writes().size() - 1]
    }

    private static MockConnection command() {
        def c = new MockConnection()
        c.queueRead(DE)   // Status1.DE set: init does not wait for the pump
        c
    }

    def "init sequence"() {
        given:
        def cmd = command()
        def dat = new MockConnection()

        when:
        def lcd = new Uc1611sMinimal(cmd, dat)

        then: "System Reset, configuration, 20 page addresses, display enable, NOP before the status poll"
        lcd.width() == 240
        lcd.height() == 128
        cmd.writes().size() == 24
        cmd.writes()[0] == bytes(0xE2)
        cmd.writes()[1] == bytes(0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x8F, 0x89, 0xD1)
        cmd.writes()[2] == bytes(0x60, 0x70, 0x00, 0x10)
        cmd.writes()[21] == bytes(0x63, 0x71, 0x00, 0x10)
        cmd.writes()[22] == bytes(0xA9)
        cmd.writes()[23] == bytes(0xE3)
        dat.writes().size() == 160
        (0..<160).every { int i -> dat.writes()[i].length == (i % 8 == 7 ? 16 : 32) && dat.writes()[i].every { it == 0 } }
    }

    def "invalid sizes are rejected without bus traffic"() {
        when:
        def cmd = command()
        new Uc1611sMinimal(cmd, new MockConnection(), w, h)

        then:
        thrown(IllegalArgumentException)

        where:
        w   | h
        241 | 128
        240 | 130
        240 | 136
        0   | 128
    }

    def "pixels and show"() {
        given:
        def cmd = command()
        def dat = new MockConnection()
        def lcd = new Uc1611sMinimal(cmd, dat)
        int c0 = cmd.writes().size()
        int d0 = dat.writes().size()

        when:
        lcd.setPixel(3, 10, true)
        lcd.setPixel(239, 127, true)
        lcd.setPixel(240, 0, true)   // out of range, ignored
        lcd.setPixel(-1, 5, true)

        then:
        cmd.writes().size() == c0
        dat.writes().size() == d0

        when:
        lcd.show()

        then:
        cmd.writes().size() - c0 == 16
        dat.writes().size() - d0 == 128
        cmd.writes()[c0 + 1] == bytes(0x61, 0x70, 0x00, 0x10)
        dat.writes()[d0 + 8][3] == (byte) 0x04
        dat.writes()[d0 + 127].length == 16
        dat.writes()[d0 + 127][15] == (byte) 0x80

        when:
        lcd.clear()
        d0 = dat.writes().size()
        lcd.show()

        then:
        dat.writes().subList(d0, dat.writes().size()).every { byte[] w -> w.every { it == 0 } }
    }

    def "commands"() {
        given:
        def cmd = command()
        def c = new Uc1611sFull(cmd, new MockConnection())

        expect:
        step(c, cmd, { it.setContrast(0x55) }, bytes(0x81, 0x55))
        step(c, cmd, { it.setDisplay(false) }, bytes(0xA8))
        step(c, cmd, { it.sleep() }, bytes(0xA8))
        step(c, cmd, { it.setInvert(true) }, bytes(0xA7))
        step(c, cmd, { it.setInvert(false) }, bytes(0xA6))
        step(c, cmd, { it.setAllOn(true) }, bytes(0xA5))
        step(c, cmd, { it.setAllOn(false) }, bytes(0xA4))
        step(c, cmd, { it.setFlip(true, true) }, bytes(0xC0, 0x06))
        step(c, cmd, { it.setFlip(false, true) }, bytes(0xC0, 0x04))
        step(c, cmd, { it.setScrollLine(40) }, bytes(0x48, 0x52))
        step(c, cmd, { it.setFixedLines(3) }, bytes(0x93))
        step(c, cmd, { it.setBiasRatio(3) }, bytes(0xEB))
        step(c, cmd, { it.setTemperatureCompensation(2) }, bytes(0x26))
        step(c, cmd, { it.setLineRate(1) }, bytes(0xA1))
        step(c, cmd, { it.setPump(false) }, bytes(0x2C))
        step(c, cmd, { it.setPump(true) }, bytes(0x2F))
        step(c, cmd, { it.setPanelLoading(true) }, bytes(0x2B))
        step(c, cmd, { it.setPanelLoading(false) }, bytes(0x28))
        step(c, cmd, { it.setPartialDisplay(true, 10, 100) }, bytes(0xF2, 10, 0xF3, 100, 0x87))
        step(c, cmd, { it.setPartialDisplay(true, 4, -1) }, bytes(0xF2, 4, 0xF3, 127, 0x87))
        step(c, cmd, { it.setPartialDisplay(false, 0, -1) }, bytes(0x84))
        step(c, cmd, { it.setNLineInversion(5, true) }, bytes(0xC8, 0x45))
        step(c, cmd, { it.setDisplayPattern(2) }, bytes(0xD5))
        step(c, cmd, { it.setDisplayPattern(0) }, bytes(0xD1))
        step(c, cmd, { it.sendCommand(0xE3, 0xE3) }, bytes(0xE3, 0xE3))
    }

    private static boolean step(Uc1611sFull c, MockConnection cmd, Closure action, byte[] expected) {
        action.call(c)
        last(cmd) == expected
    }

    def "isolation clock sends two triple-byte commands"() {
        given:
        def cmd = command()
        def c = new Uc1611sFull(cmd, new MockConnection())
        int k = cmd.writes().size()

        when:
        c.setIsolationClock(2, 3)

        then:
        cmd.writes().size() - k == 2
        cmd.writes()[k] == bytes(0x82, 0x13, 0x02)
        cmd.writes()[k + 1] == bytes(0x82, 0x14, 0x03)
    }

    def "write pattern, wake, reset and raw data"() {
        given:
        def cmd = command()
        def dat = new MockConnection()
        def c = new Uc1611sFull(cmd, dat)
        int d0 = dat.writes().size()

        when:
        c.setWritePattern(1)
        c.showPage(0)

        then:
        last(cmd) == bytes(0x60, 0x72, 0x00, 0x10)
        dat.writes().size() - d0 == 8

        when:
        c.setWritePattern(0)
        int n = dat.writes().size()
        c.showPage(16)

        then:
        dat.writes().size() == n

        when:
        c.sendData(new byte[70])

        then:
        dat.writes().size() - n == 3
        last(dat).length == 6

        when:
        cmd.queueRead(DE)
        c.sleep()
        c.wake()

        then:
        cmd.writes()[cmd.writes().size() - 2] == bytes(0xA9)
        last(cmd) == bytes(0xE3)

        when:
        c.setContrast(0x55)
        c.setFlip(false, true)
        cmd.queueRead(DE)
        int k = cmd.writes().size()
        c.reset()

        then:
        cmd.writes().size() - k == 4
        cmd.writes()[k] == bytes(0xE2)
        cmd.writes()[k + 1] == bytes(0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x55, 0x89, 0xD1)
        cmd.writes()[k + 2] == bytes(0xA9)
    }

    def "drawing"() {
        given:
        def c = new Uc1611sFull(command(), new MockConnection())

        when:
        c.fill(true)

        then:
        c.getPixel(0, 0) && c.getPixel(239, 127)

        when:
        c.fill(false)

        then:
        !c.getPixel(0, 0) && !c.getPixel(239, 127) && !c.getPixel(-1, 0) && !c.getPixel(0, 128)

        when:
        c.drawLine(0, 0, 9, 9, true)

        then:
        c.getPixel(0, 0) && c.getPixel(5, 5) && c.getPixel(9, 9) && !c.getPixel(5, 6)

        when:
        c.fill(false)
        c.drawRect(10, 20, 5, 4, true, false)

        then:
        c.getPixel(10, 20) && c.getPixel(14, 23) && c.getPixel(12, 20) && !c.getPixel(12, 21)

        when:
        c.fill(false)
        c.drawRect(10, 20, 3, 3, true, true)

        then:
        c.getPixel(11, 21) && c.getPixel(12, 22) && !c.getPixel(13, 22)

        when:
        c.fill(false)
        c.blit(4, 8, bytes(0xFF, 0x0F, 0x01, 0x80), 2)

        then:
        c.getPixel(4, 8) && c.getPixel(4, 15) && c.getPixel(5, 8) && !c.getPixel(5, 12) &&
            c.getPixel(5, 11) && c.getPixel(4, 16) && c.getPixel(5, 23) && !c.getPixel(4, 17)

        when:
        c.blit(4, 3, bytes(1, 2), 1)

        then:
        thrown(IllegalArgumentException)
    }

    def "top view and custom size"() {
        given:
        def cmd = command()
        def cmd2 = command()

        when:
        new Uc1611sFull(cmd, new MockConnection(), 240, 128, true, 0x60)
        new Uc1611sMinimal(cmd2, new MockConnection(), 100, 64)

        then:
        cmd.writes()[1] == bytes(0x25, 0xC0, 0x04, 0xA3, 0xF1, 0x7F, 0xF2, 0x00, 0xF3, 0x7F, 0x81, 0x60, 0x89, 0xD1)
        cmd2.writes()[1] == bytes(0x25, 0xC0, 0x02, 0xA3, 0xF1, 0x3F, 0xF2, 0x00, 0xF3, 0x3F, 0x81, 0x8F, 0x89, 0xD1)
    }

    def "reads discard the dummy byte and status sends a NOP first"() {
        given:
        def cmd = command()
        def dat = new MockConnection()
        def c = new Uc1611sFull(cmd, dat)
        cmd.queueRead(bytes(0xDA, 0x80, 0x10))

        when:
        def status = c.readStatus()

        then:
        status == bytes(0xDA, 0x80, 0x10)
        last(cmd) == bytes(0xE3)

        when:
        dat.queueRead(bytes(0xEE, 0x11, 0x22, 0x33))
        int k = cmd.writes().size()
        def ram = c.readRam(2, 5, 3)

        then:
        ram == bytes(0x11, 0x22, 0x33)
        cmd.writes()[k] == bytes(0x62, 0x70, 0x05, 0x10)
    }

    def "a command is retried until acknowledged and gives up after the maximum attempts"() {
        given:
        def cmd = command()
        def c = new Uc1611sFull(cmd, new MockConnection())
        int e0 = cmd.writes().size()

        when:
        cmd.failNextWrites(2, 0)
        c.setContrast(0x66)

        then:
        cmd.writes().size() - e0 == 3
        cmd.writes()[e0] == bytes(0x81, 0x66)
        c.retryCount() == 2
        c.ok()

        when:
        cmd.failNextWrites(100, 0)
        e0 = cmd.writes().size()
        c.setInvert(true)

        then:
        thrown(IOException)
        cmd.writes().size() - e0 == Uc1611sMinimal.MAX_ATTEMPTS
        !c.ok()
        c.errorCount() == 1
        c.retryCount() == 2 + Uc1611sMinimal.MAX_ATTEMPTS

        when:
        cmd.failNextWrites(0, 0)
        c.clearErrors()

        then:
        c.ok()
        c.retryCount() == 0
    }

    def "a failed data chunk repeats the whole page"() {
        given:
        def cmd = command()
        def dat = new MockConnection()
        def c = new Uc1611sFull(cmd, dat)
        int e0 = cmd.writes().size()
        int d0 = dat.writes().size()

        when:
        dat.failNextWrites(1, 0)
        c.showPage(1)

        then:
        cmd.writes().size() - e0 == 2
        cmd.writes()[e0] == bytes(0x61, 0x70, 0x00, 0x10)
        cmd.writes()[e0 + 1] == bytes(0x61, 0x70, 0x00, 0x10)
        dat.writes().size() - d0 == 1 + 8
        c.retryCount() == 1

        when:
        e0 = cmd.writes().size()
        d0 = dat.writes().size()
        dat.failNextWrites(1, 2)   // third chunk fails
        c.showPage(2)

        then:
        cmd.writes().size() - e0 == 2
        dat.writes().size() - d0 == 3 + 8
        c.ok()
    }

    def "a dead chip throws from the constructor"() {
        given:
        def cmd = new MockConnection()
        cmd.failNextWrites(1000, 0)
        def dat = new MockConnection()

        when:
        new Uc1611sMinimal(cmd, dat)

        then:
        thrown(IOException)
        cmd.writes().size() == Uc1611sMinimal.MAX_ATTEMPTS
        dat.writes().isEmpty()
    }

    def "reads are retried"() {
        given:
        def cmd = command()
        def dat = new MockConnection()
        def c = new Uc1611sFull(cmd, dat)

        when:
        cmd.failNextReads(2)
        cmd.queueRead(bytes(0xDA, 0x80, 0x10))
        def status = c.readStatus()

        then:
        status == bytes(0xDA, 0x80, 0x10)
        c.retryCount() >= 2

        when:
        cmd.failNextReads(100)
        c.readStatus()

        then:
        thrown(IOException)
        !c.ok()

        when:
        c.clearErrors()
        cmd.failNextReads(0)
        dat.failNextReads(1)
        dat.queueRead(bytes(0xEE, 0x0A, 0x0B))
        int k = cmd.writes().size()
        def ram = c.readRam(1, 4, 2)

        then:
        ram == bytes(0x0A, 0x0B)
        cmd.writes().size() - k == 2
    }
}
