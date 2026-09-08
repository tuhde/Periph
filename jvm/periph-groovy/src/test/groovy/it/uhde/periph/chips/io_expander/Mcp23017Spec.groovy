package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Mcp23017Spec extends Specification {

    // Mcp23017Full's interrupt-related register constants are private to
    // that class, so this spec mirrors their values locally.
    static final int REG_DEFVALA = 0x06
    static final int REG_INTFA = 0x0E
    static final int REG_INTCAPA = 0x10
    static final int REG_INTCAPB = 0x11

    // MCP23017 reads are register-addressed (writeRead), so MockConnection's
    // registers map can be preloaded directly via setRegister().
    def "full API"() {
        given:
        def connection = new MockConnection()

        when:
        def chip = new Mcp23017Full(connection, 0x20)

        then: "init sequence: OLATA/OLATB=0x00, IODIRA/IODIRB=0x7F (GPA7/GPB7 forced output-only), IPOL=0x00, GPPU=0x00"
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x00
        connection.registers().get(Mcp23017Minimal.REG_OLATB) == 0x00
        connection.registers().get(Mcp23017Minimal.REG_IODIRA) == 0x7F
        connection.registers().get(Mcp23017Minimal.REG_IODIRB) == 0x7F
        connection.registers().get(Mcp23017Minimal.REG_IPOLA) == 0x00
        connection.registers().get(Mcp23017Minimal.REG_GPPUA) == 0x00

        when: "readPort(0)/(1) -> GPIOA/GPIOB"
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0xA5)
        connection.setRegister(Mcp23017Minimal.REG_GPIOB, 0x5A)

        then:
        chip.readPort(0) == 0xA5
        chip.readPort(1) == 0x5A

        when: "writePort updates OLAT register and shadow"
        chip.writePort(0, 0x3C)

        then:
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x3C
        chip.shadow[0] == 0x3C

        when: "pin() read on PORTA and PORTB"
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0x01)
        def pin0 = chip.pin(0)
        connection.setRegister(Mcp23017Minimal.REG_GPIOB, 0x02)
        def pin9 = chip.pin(9)

        then:
        pin0.read()
        pin9.read()

        when: "pin direction: setOutput() clears the IODIRA bit; setInput() sets it"
        def pin1 = chip.pin(1)
        pin1.setOutput()

        then:
        connection.registers().get(Mcp23017Minimal.REG_IODIRA) == (0x7F & ~0x02)

        when:
        pin1.setInput()

        then:
        connection.registers().get(Mcp23017Minimal.REG_IODIRA) == 0x7F

        when: "pin set high/low preserves other output bits (shadow read-modify-write)"
        chip.writePort(0, 0x00)
        pin0.setHigh()

        then:
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x01

        when:
        def pin2 = chip.pin(2)
        pin2.setHigh()

        then:
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x05

        when:
        pin0.setLow()

        then:
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x04

        when: "toggle reads GPIOA (the actual pin level), so keep it in sync with OLATA"
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0x04)
        pin0.toggle()

        then:
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x05

        when:
        connection.setRegister(Mcp23017Minimal.REG_GPIOA, 0x05)
        pin0.toggle()

        then:
        connection.registers().get(Mcp23017Minimal.REG_OLATA) == 0x04

        when: "configurePullup / configurePolarity / setDefaultValue"
        chip.configurePullup(0, 0xFF)
        chip.configurePolarity(1, 0x0F)
        chip.setDefaultValue(0, 0x11)

        then:
        connection.registers().get(Mcp23017Minimal.REG_GPPUA) == 0xFF
        connection.registers().get(Mcp23017Minimal.REG_IPOLB) == 0x0F
        connection.registers().get(REG_DEFVALA) == 0x11

        when: "pollInterrupt(port): reads INTF then INTCAP (discarded); returns INTF value"
        connection.setRegister(REG_INTFA, 0x08)
        connection.setRegister(REG_INTCAPA, 0xFF)

        then:
        chip.pollInterrupt(0) == 0x08

        when: "readCapture(port): reads INTCAP directly"
        connection.setRegister(REG_INTCAPB, 0x22)

        then:
        chip.readCapture(1) == 0x22
    }
}
