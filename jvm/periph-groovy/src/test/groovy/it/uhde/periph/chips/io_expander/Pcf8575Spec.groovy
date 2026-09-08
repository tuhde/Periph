package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Pcf8575Spec extends Specification {

    // PCF8575 has no sub-registers: every transaction is a plain 2-byte
    // read()/write() (Port 0 first, Port 1 second; no register pointer), so
    // MockConnection's register map is never consulted — reads must be
    // preloaded via queueRead() in the exact order the driver will issue
    // them. Pcf8575Full's constructor issues one extra 2-byte read to seed
    // `prev`, so it must be queued too.
    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.queueRead([(byte) 0xFF, (byte) 0xFF] as byte[]) // Full ctor seeds prev

        when:
        def chip = new Pcf8575Full(connection)

        then:
        connection.writes()[0].length == 2
        connection.writes()[0][0] == (byte) 0xFF
        connection.writes()[0][1] == (byte) 0xFF

        when: "readPort(0)/(1): both derived from one 2-byte read"
        connection.queueRead([(byte) 0x5A, (byte) 0xA5] as byte[])
        def p0 = chip.readPort(0)
        connection.queueRead([(byte) 0x5A, (byte) 0xA5] as byte[])
        def p1 = chip.readPort(1)

        then:
        p0 == 0x5A
        p1 == 0xA5

        when: "writePort(): writes both shadow bytes, preserving the untouched port"
        chip.writePort(0, 0x3C)

        then:
        connection.writes().last()[0] == (byte) 0x3C
        connection.writes().last()[1] == (byte) 0xFF

        when:
        chip.writePort(1, 0x0F)

        then:
        connection.writes().last()[0] == (byte) 0x3C
        connection.writes().last()[1] == (byte) 0x0F

        when: "pin() read on Port 0 and Port 1"
        def pin3 = chip.pin(3)   // Port 0, bit 3
        connection.queueRead([(byte) 0x08, (byte) 0x00] as byte[])
        def pin3High = pin3.read()
        def pin11 = chip.pin(11) // Port 1, bit 3
        connection.queueRead([(byte) 0x00, (byte) 0x08] as byte[])
        def pin11High = pin11.read()

        then:
        pin3High
        pin11High

        when: "pin set high/low preserves other shadow bits within the same port"
        chip.writePort(0, 0xFF)
        chip.writePort(1, 0xFF)
        pin3.setLow()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x08)
        connection.writes().last()[1] == (byte) 0xFF

        when:
        def pin5 = chip.pin(5)
        pin5.setLow()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x08 & ~0x20)
        connection.writes().last()[1] == (byte) 0xFF

        when:
        pin11.setLow()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x08 & ~0x20)
        connection.writes().last()[1] == (byte) (0xFF & ~0x08)

        when: "toggle"
        pin3.toggle()

        then:
        (connection.writes().last()[0] & 0x08) == 0x08

        when:
        pin3.toggle()

        then:
        (connection.writes().last()[0] & 0x08) == 0

        when: "setInput()/setOutput() releases high (input) or drives low (output)"
        def pin0 = chip.pin(0)
        pin0.setOutput()

        then:
        (connection.writes().last()[0] & 0x01) == 0

        when:
        pin0.setInput()

        then:
        (connection.writes().last()[0] & 0x01) == 1

        when: "pollInterrupt() compares to the previous 2-byte read and returns the 16-bit changed-pin bitmask"
        connection.queueRead([(byte) 0xFF, (byte) 0xFF] as byte[])
        chip.pollInterrupt() // resync prev to a known value
        connection.queueRead([(byte) 0xF7, (byte) 0xFE] as byte[]) // Port0 bit3 low, Port1 bit0 low

        then:
        chip.pollInterrupt() == (0x08 | (0x01 << 8))

        when:
        connection.queueRead([(byte) 0xF7, (byte) 0xFE] as byte[]) // no further change

        then:
        chip.pollInterrupt() == 0x00
    }
}
