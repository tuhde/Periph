package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class PCA9539Spec extends Specification {

    static final int REG_INPUT = 0x00, REG_OUTPUT = 0x02, REG_POLARITY = 0x04, REG_CONFIG = 0x06

    /** Register bytes of the data writes (length > 1) after index {@code from}; read-pointer writes are skipped. */
    static List<Integer> dataWrites(MockConnection c, int from) {
        return c.writes().drop(from).findAll { it.length > 1 }.collect { (it[0] & 0xFF) as Integer }
    }

    def "init writes the reset state of both ports as pair writes"() {
        given:
        def c = new MockConnection()

        when:
        def chip = new PCA9539Full(c, 0x74)

        then:
        c.registers().get(REG_OUTPUT) == 0xFF
        c.registers().get(REG_OUTPUT + 1) == 0xFF
        c.registers().get(REG_POLARITY) == 0x00
        c.registers().get(REG_POLARITY + 1) == 0x00
        c.registers().get(REG_CONFIG) == 0xFF
        c.registers().get(REG_CONFIG + 1) == 0xFF
        chip.shadow == 0xFFFF
        chip.direction == 0xFFFF
        dataWrites(c, 0) == [REG_OUTPUT, REG_POLARITY, REG_CONFIG]
    }

    def "port and pin access"() {
        given:
        def c = new MockConnection()
        def chip = new PCA9539Full(c, 0x74)

        when:
        c.setRegister(REG_INPUT, 0xA5, 0x5A)

        then:
        chip.readPort(0) == 0xA5
        chip.readPort(1) == 0x5A
        chip.readAll() == 0x5AA5

        when:
        chip.readPort(2)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.writePort(0, 0x3C)
        chip.writePort(1, 0xC3)

        then:
        c.registers().get(REG_OUTPUT) == 0x3C
        c.registers().get(REG_OUTPUT + 1) == 0xC3
        chip.shadow == 0xC33C

        when:
        int before = c.writes().size()
        chip.writeAll(0x1234)

        then:
        c.registers().get(REG_OUTPUT) == 0x34
        c.registers().get(REG_OUTPUT + 1) == 0x12
        chip.shadow == 0x1234
        dataWrites(c, before).size() == 1

        when:
        chip.writeAll(0xFFFF)
        chip.pin(7).setOutput()

        then:
        c.registers().get(REG_CONFIG) == 0x7F
        c.registers().get(REG_CONFIG + 1) == 0xFF

        when:
        chip.pin(7).setInput()
        chip.pin(12).setOutput()

        then:
        c.registers().get(REG_CONFIG + 1) == 0xEF
        c.registers().get(REG_CONFIG) == 0xFF

        when:
        chip.pin(12).setInput()
        before = c.writes().size()
        chip.pin(9).setOutput()

        then: "OUTPUT is written before CONFIG"
        dataWrites(c, before) == [REG_OUTPUT + 1, REG_CONFIG + 1]

        when:
        chip.pin(9).setInput()
        chip.writeAll(0x0000)
        c.setRegister(REG_INPUT, 0x04, 0x80)

        then:
        chip.pin(2).read()
        chip.pin(15).read()
        !chip.pin(3).read()

        when:
        chip.pin(0).setHigh()
        chip.pin(2).setHigh()
        chip.pin(0).setLow()

        then:
        c.registers().get(REG_OUTPUT) == 0x04

        when:
        chip.pin(8).setHigh()

        then:
        c.registers().get(REG_OUTPUT + 1) == 0x01
        c.registers().get(REG_OUTPUT) == 0x04

        when:
        chip.pin(8).toggle()

        then:
        c.registers().get(REG_OUTPUT + 1) == 0x00
    }

    def "no pull-ups"() {
        given:
        def chip = new PCA9539Full(new MockConnection(), 0x74)

        when:
        chip.pin(3).setPullUp(true)

        then:
        thrown(UnsupportedOperationException)
    }

    def "polarity, direction, raw access, interrupt mask and reset"() {
        given:
        def c = new MockConnection()
        def chip = new PCA9539Full(c, 0x74)

        when:
        int before = c.writes().size()
        chip.configurePolarity(0xF00F)

        then:
        c.registers().get(REG_POLARITY) == 0x0F
        c.registers().get(REG_POLARITY + 1) == 0xF0
        dataWrites(c, before).size() == 1

        when:
        before = c.writes().size()
        chip.setDirection(0xFF00)

        then:
        c.registers().get(REG_CONFIG) == 0x00
        c.registers().get(REG_CONFIG + 1) == 0xFF
        dataWrites(c, before) == [REG_OUTPUT, REG_CONFIG]
        chip.getDirection() == 0xFF00

        when:
        chip.writeRegister(REG_OUTPUT + 1, 0x81)
        chip.writeRegister(REG_CONFIG, 0x0F)
        chip.writeRegister(REG_POLARITY + 1, 0x3C)

        then:
        (chip.shadow >> 8) == 0x81
        chip.direction == 0xFF0F
        chip.readRegister(REG_CONFIG) == 0x0F

        when:
        chip.writeRegister(bad, 0)

        then:
        thrown(IllegalArgumentException)

        where:
        bad << [0, 1, 8]
    }

    def "interrupt mask and reset"() {
        given:
        def c = new MockConnection()
        def chip = new PCA9539Full(c, 0x74)

        when:
        chip.setDirection(0xF0F0)
        c.setRegister(REG_INPUT, 0x00, 0x00)
        chip.pollInterrupt()
        c.setRegister(REG_INPUT, 0x35, 0x9A)

        then: "outputs are masked out of the 16-bit changed mask"
        chip.pollInterrupt() == 0x9030
        chip.pollInterrupt() == 0x0000

        when:
        chip.reset()

        then:
        c.registers().get(REG_OUTPUT) == 0xFF
        c.registers().get(REG_OUTPUT + 1) == 0xFF
        c.registers().get(REG_POLARITY) == 0x00
        c.registers().get(REG_CONFIG + 1) == 0xFF
        chip.shadow == 0xFFFF
        chip.direction == 0xFFFF
    }

    def "watch on an output pin throws"() {
        given:
        def chip = new PCA9539Full(new MockConnection(), 0x74)
        chip.pin(10).setOutput()

        when:
        chip.pin(10).watch({ p -> } as java.util.function.Consumer)

        then:
        thrown(IllegalStateException)
    }

    def "minimal class is constructible"() {
        expect:
        new PCA9539Minimal(new MockConnection(), 0x74).readPort(0) == 0
    }
}
