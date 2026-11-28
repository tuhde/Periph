package it.uhde.periph.chips.rfid

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class St25dvSpec extends Specification {

    static MockConnection mock() {
        MockConnection c = new MockConnection()
        c.setAddressWidth(2)
        return c
    }

    static MockConnection system(int icRef, int memLo, int memHi) {
        MockConnection s = mock()
        s.setRegister(0x0014, memLo, memHi, 0x03, icRef)
        s.setRegister(0x0018, 0x11, 0x22, 0x33, 0x44, 0x55, 0x26, 0x02, 0xE0)
        s.setRegister(0x0020, 0x12)
        s.setRegister(0x0000, 0x88)
        s.setRegister(0x0002, 0x01)
        return s
    }

    static int reg(MockConnection c, int a) { c.registers().get(a) ?: 0 }

    static byte[] bytes(int... v) { v.collect { it as byte } as byte[] }

    static class FakePin implements InputPin {
        EdgeHandler handler
        EdgeTrigger trigger
        void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t }
        void offEdge(EdgeHandler h) { if (h.is(handler)) handler = null }
        boolean isHigh() { true }
        void close() {}
    }

    def "minimal reads identification and memory size"() {
        when:
        def user = mock()
        def sys = system(ic, lo, hi)
        def tag = new St25dvFull(user, sys)

        then:
        tag.memorySize() == size
        tag.icReference() == ic
        sys.writes().size() == 1
        user.writes().isEmpty()

        where:
        ic   | lo   | hi   || size
        0x24 | 0x7F | 0x00 || 512
        0x26 | 0xFF | 0x01 || 2048
        0x26 | 0xFF | 0x07 || 8192
    }

    def "write splits at 16-byte boundaries and ACK-polls each piece"() {
        given:
        def user = mock()
        def tag = new St25dvMinimal(user, system(0x26, 0xFF, 0x01))
        byte[] data = (0..<40).collect { it as byte } as byte[]

        when:
        tag.write(0x0008, data)
        def frames = user.writes().findAll { it.length > 2 }

        then:
        frames.size() == 3
        user.writes().count { it.length == 2 } == 3
        frames[0].length == 10
        frames[1][1] == 0x10 as byte
        frames[2][1] == 0x20 as byte
        (0..<40).every { reg(user, 0x08 + it) == it }
    }

    def "read is range checked and retried"() {
        given:
        def user = mock()
        def tag = new St25dvMinimal(user, system(0x26, 0xFF, 0x01))
        user.setRegister(0x0040, 0x77)

        when:
        tag.read(2047, 2)

        then:
        thrown(IllegalArgumentException)

        when:
        user.failNextWrites(2, 0)
        def b = tag.read(0x0040, 1)

        then:
        b == bytes(0x77)

        when:
        user.failNextWrites(3, 0)
        tag.read(0x0040, 1)

        then:
        thrown(IOException)
    }

    def "uid, revision and password frames"() {
        given:
        def user = mock()
        def sys = system(0x26, 0xFF, 0x01)
        def tag = new St25dvFull(user, sys)
        def pw = bytes(1, 2, 3, 4, 5, 6, 7, 8)
        user.setRegister(0x2004, 0x01)

        expect:
        tag.uid() == bytes(0xE0, 0x02, 0x26, 0x55, 0x44, 0x33, 0x22, 0x11)
        tag.icRevision() == 0x12

        when:
        sys.writes().clear()
        def open = tag.presentPassword(pw)

        then:
        open
        sys.writes()[0] == bytes(0x09, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0x09, 1, 2, 3, 4, 5, 6, 7, 8)

        when:
        sys.writes().clear()
        tag.writePassword(pw)

        then:
        sys.writes()[0] == bytes(0x09, 0x00, 1, 2, 3, 4, 5, 6, 7, 8, 0x07, 1, 2, 3, 4, 5, 6, 7, 8)

        when:
        user.setRegister(0x2004, 0x00)
        tag.writePassword(pw)

        then:
        thrown(IOException)
    }

    def "areas and protection"() {
        given:
        def sys = system(0x26, 0xFF, 0x01)
        def tag = new St25dvFull(mock(), sys)
        sys.setRegister(0x000B, 0x00)

        when:
        tag.setAreaEnd(2, 0x1F)
        tag.setAreaProtection(3, 0x05, 0x03)

        then:
        reg(sys, 0x0007) == 0x1F
        reg(sys, 0x0008) == 0x05
        reg(sys, 0x000B) == 0x30

        when:
        tag.setAreaProtection(2, 0x0C, 0x01)

        then:
        reg(sys, 0x000B) == 0x34
        tag.getAreaProtection(3) == new St25dvFull.AreaProtection(0x05, 0x03)

        when:
        tag.setAreaProtection(1, 0, 4)

        then:
        thrown(IllegalArgumentException)
    }

    def "GPO, energy harvesting and RF state"() {
        given:
        def user = mock()
        def sys = system(0x26, 0xFF, 0x01)
        def tag = new St25dvFull(user, sys)

        when:
        tag.setGpo(St25dvFull.GPO_FIELD_CHANGE | St25dvFull.GPO_RF_WRITE)

        then:
        reg(sys, 0x0000) == 0xC8

        when:
        tag.setGpoRuntime(true)
        tag.setInterruptPulse(3)

        then:
        reg(user, 0x2000) == 0x80
        reg(sys, 0x0001) == 3

        when:
        user.setRegister(0x2002, 0x0C)

        then:
        tag.fieldPresent()

        when:
        tag.setEnergyHarvesting(false, true)

        then:
        reg(sys, 0x0002) == 0x00
        reg(user, 0x2002) == 0x00

        when:
        tag.setRfState(false, true)

        then:
        reg(user, 0x2003) == 0x02
    }

    def "mailbox"() {
        given:
        def user = mock()
        def sys = system(0x26, 0xFF, 0x01)
        def tag = new St25dvFull(user, sys)
        sys.setRegister(0x000D, 0x00)

        when:
        tag.enableMailbox()

        then:
        reg(sys, 0x000D) == 0x01
        reg(user, 0x2006) == 0x01

        when:
        user.setRegister(0x2006, 0x05)
        tag.mailboxWrite(bytes(1))

        then:
        thrown(IOException)

        when:
        user.setRegister(0x2006, 0x01)
        user.writes().clear()
        tag.mailboxWrite(new byte[100])

        then:
        user.writes().last().length == 102

        when:
        user.setRegister(0x2006, 0x05)
        user.setRegister(0x2007, 19)
        (0..<20).each { user.setRegister(0x2008 + it, 0xA0 + it) }
        def r = tag.mailboxRead()

        then:
        r.length == 20
        r[19] == (0xB3 as byte)
    }

    def "NDEF round trip"() {
        given:
        def user = mock()
        def tag = new St25dvFull(user, system(0x26, 0xFF, 0x01))
        def ndef = bytes(0xD1, 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x48)

        when:
        tag.writeNdef(ndef)

        then:
        reg(user, 0) == 0xE1
        reg(user, 2) == 0xFF
        tag.readNdef() == ndef

        when:
        tag.writeNdef(new byte[2048])

        then:
        thrown(IllegalArgumentException)
    }

    def "interrupt mapping and delivery"() {
        given:
        def user = mock()
        def sys = system(0x26, 0xFF, 0x01)
        def tag = new St25dvFull(user, sys)
        sys.setRegister(0x0000, 0x80)

        when:
        tag.enableInterrupt(St25dvFull.SOURCE_FIELD_RISING | St25dvFull.SOURCE_RF_WRITE | St25dvFull.SOURCE_RF_PUT_MSG | St25dvFull.SOURCE_RF_USER)

        then:
        reg(sys, 0x0000) == 0xD9

        when:
        tag.disableInterrupt(St25dvFull.SOURCE_RF_WRITE)

        then:
        reg(sys, 0x0000) == 0x99

        when:
        def pin = new FakePin()
        def got = []
        tag.onInterrupt({ int s -> got << s } as java.util.function.IntConsumer, pin)
        user.setRegister(0x2005, 0x10)
        pin.handler.onEdge()
        user.setRegister(0x2005, 0x00)
        pin.handler.onEdge()

        then:
        pin.trigger == EdgeTrigger.FALLING
        got == [0x10]

        when:
        tag.offInterrupt()

        then:
        pin.handler == null
    }
}
