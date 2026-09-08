package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Pcf8574Spec extends Specification {

    // PCF8574 has no sub-registers: every transaction is a single plain
    // byte read()/write() (no register pointer), so MockConnection's
    // register map is never consulted — reads must be preloaded via
    // queueRead() in the exact order the driver will issue them.
    def "full API"() {
        given:
        def connection = new MockConnection()

        when:
        def chip = new Pcf8574Full(connection)

        then: "construction writes 0xFF (all pins to quasi-bidirectional input mode)"
        connection.writes()[0].length == 1
        connection.writes()[0][0] == (byte) 0xFF

        when: "readPort(): plain single-byte read"
        connection.queueRead([(byte) 0x5A] as byte[])

        then:
        chip.readPort() == 0x5A

        when: "writePort(): plain single-byte write; updates shadow"
        chip.writePort(0x3C)

        then:
        connection.writes().last()[0] == (byte) 0x3C

        when: "pin().read() reads the live bus level (not the shadow)"
        def pin3 = chip.pin(3)
        connection.queueRead([(byte) 0x08] as byte[]) // bit 3 high

        then:
        pin3.read()

        when: "pin set high/low preserves other shadow bits (read-modify-write)"
        chip.writePort(0xFF)
        pin3.setLow()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x08)

        when:
        def pin5 = chip.pin(5)
        pin5.setLow()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x08 & ~0x20)

        when:
        pin3.setHigh()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x20)

        when: "toggle"
        pin3.toggle()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x20 & ~0x08)

        when:
        pin3.toggle()

        then:
        connection.writes().last()[0] == (byte) (0xFF & ~0x20)

        when: "setInput()/setOutput() releases high (input) or drives low (output)"
        def pin0 = chip.pin(0)
        pin0.setOutput()

        then:
        (connection.writes().last()[0] & 0x01) == 0

        when:
        pin0.setInput()

        then:
        (connection.writes().last()[0] & 0x01) == 1

        when: "pollInterrupt() compares to the previous read and returns the changed-pin bitmask"
        connection.queueRead([(byte) 0xFF] as byte[])
        chip.pollInterrupt() // resync prev to a known value (0xFF)
        connection.queueRead([(byte) 0xF7] as byte[]) // bit 3 now low

        then:
        chip.pollInterrupt() == 0x08

        when:
        connection.queueRead([(byte) 0xF7] as byte[]) // no further change

        then:
        chip.pollInterrupt() == 0x00
    }
}
