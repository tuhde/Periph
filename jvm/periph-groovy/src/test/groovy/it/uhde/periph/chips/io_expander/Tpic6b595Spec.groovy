package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockSiPo
import spock.lang.Specification

class Tpic6b595Spec extends Specification {

    def "construction clears and flushes all zero"() {
        given:
        def connection = new MockSiPo()

        when:
        def chip = new Tpic6b595Minimal(connection)

        then:
        connection.clearCount() == 1
        connection.writes().last() == [0x00] as byte[]
        chip.shadow[0] == 0
    }

    def "construction without SRCLR does not throw"() {
        // Regression: SiPoConnection.clear() throws IllegalStateException when
        // SRCLR is unconfigured; the constructor must swallow it.
        given:
        def connection = new MockSiPo(false, true)

        when:
        new Tpic6b595Minimal(connection)

        then:
        noExceptionThrown()
        connection.clearCount() == 0
        connection.writes().last() == [0x00] as byte[]
    }

    def "pin set high/low/toggle/read"() {
        given:
        def connection = new MockSiPo()
        def chip = new Tpic6b595Minimal(connection)
        def pin3 = chip.pin(3)

        when:
        pin3.setHigh()

        then:
        chip.shadow[0] == 0x08
        connection.writes().last() == [0x08] as byte[]
        pin3.read()

        when:
        pin3.setLow()

        then:
        chip.shadow[0] == 0x00
        !pin3.read()

        when:
        pin3.toggle()

        then:
        chip.shadow[0] == 0x08

        when:
        pin3.toggle()

        then:
        chip.shadow[0] == 0x00

        when:
        def pin5 = chip.pin(5)
        pin5.setHigh()

        then:
        chip.shadow[0] == 0x20

        when:
        pin3.setHigh()

        then: "pin5 preserved"
        chip.shadow[0] == 0x28
    }

    def "writePort/fill/off"() {
        given:
        def connection = new MockSiPo()
        def chip = new Tpic6b595Minimal(connection)

        when:
        chip.writePort(0, 0x3C)

        then:
        chip.shadow[0] == 0x3C
        connection.writes().last() == [0x3C] as byte[]

        when:
        chip.fill(true)

        then:
        chip.shadow[0] == 0xFF as int
        connection.writes().last() == [(byte) 0xFF] as byte[]

        when:
        chip.off()

        then:
        chip.shadow[0] == 0x00
        connection.writes().last() == [0x00] as byte[]
    }

    def "cascade wire-order reversed"() {
        given:
        def connection = new MockSiPo()
        def chip = new Tpic6b595Minimal(connection, 3)

        when:
        chip.writePort(0, 0xAA)
        chip.writePort(1, 0xBB)
        chip.writePort(2, 0xCC)

        then:
        connection.writes().last() == [(byte) 0xCC, (byte) 0xBB, (byte) 0xAA] as byte[]

        when:
        def pinFar = chip.pin(16) // device 2, bit 0
        pinFar.setHigh()

        then:
        chip.shadow[2] == 0xCD
        connection.writes().last() == [(byte) 0xCD, (byte) 0xBB, (byte) 0xAA] as byte[]
    }

    def "full clear and setOutputEnable"() {
        given:
        def connection = new MockSiPo()
        def full = new Tpic6b595Full(connection)

        when:
        full.clear()

        then:
        connection.clearCount() == 2 // +1 from construction

        when:
        full.setOutputEnable(true)
        full.setOutputEnable(false)

        then:
        connection.outputEnableCalls() == [true, false]
    }

    def "full clear and setOutputEnable throw when unwired"() {
        given:
        def connection = new MockSiPo(false, false)
        def full = new Tpic6b595Full(connection)

        when:
        full.clear()

        then:
        thrown(IllegalStateException)

        when:
        full.setOutputEnable(true)

        then:
        thrown(IllegalStateException)
    }

    def "full writeAll zero-extends and truncates"() {
        given:
        def connection = new MockSiPo()
        def full = new Tpic6b595Full(connection, 3)

        when: "shorter than numDevices -> zero-extend"
        full.writeAll([0x11, 0x22] as int[])

        then:
        full.shadow[0] == 0x11
        full.shadow[1] == 0x22
        full.shadow[2] == 0x00
        connection.writes().last() == [0x00, 0x22, 0x11] as byte[]

        when: "longer than numDevices -> truncate"
        full.writeAll([0x44, 0x55, 0x66, 0x77] as int[])

        then:
        full.shadow[0] == 0x44
        full.shadow[1] == 0x55
        full.shadow[2] == 0x66
        connection.writes().last() == [(byte) 0x66, (byte) 0x55, (byte) 0x44] as byte[]
    }
}
