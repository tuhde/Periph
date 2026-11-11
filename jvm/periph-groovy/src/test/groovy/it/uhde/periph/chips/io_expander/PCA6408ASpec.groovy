package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class PCA6408ASpec extends Specification {

    static final int REG_INPUT = 0x00, REG_OUTPUT = 0x01, REG_POLARITY = 0x02, REG_CONFIG = 0x03

    /** Register bytes of the data writes (length > 1) after index {@code from}; read-pointer writes are skipped. */
    static List<Integer> dataWrites(MockConnection c, int from) {
        return c.writes().drop(from).findAll { it.length > 1 }.collect { (it[0] & 0xFF) as Integer }
    }

    def "init writes the reset state"() {
        given:
        def c = new MockConnection()

        when:
        def chip = new PCA6408AFull(c, 0x20)

        then:
        c.registers().get(REG_OUTPUT) == 0xFF
        c.registers().get(REG_POLARITY) == 0x00
        c.registers().get(REG_CONFIG) == 0xFF
        chip.shadow == 0xFF
        chip.direction == 0xFF
        !dataWrites(c, 0).contains(REG_INPUT)
    }

    def "port and pin access"() {
        given:
        def c = new MockConnection()
        def chip = new PCA6408AFull(c, 0x20)

        when:
        c.setRegister(REG_INPUT, 0xA5)

        then:
        chip.readPort(0) == 0xA5
        chip.readPort(1) == 0xA5

        when:
        chip.writePort(0, 0x3C)

        then:
        c.registers().get(REG_OUTPUT) == 0x3C
        chip.shadow == 0x3C

        when:
        chip.pin(7).setOutput()

        then:
        c.registers().get(REG_CONFIG) == 0x7F

        when:
        chip.pin(7).setInput()

        then:
        c.registers().get(REG_CONFIG) == 0xFF

        when:
        chip.writePort(0, 0x00)
        int before = c.writes().size()
        chip.pin(1).setOutput()

        then: "OUTPUT is written before CONFIG"
        dataWrites(c, before) == [REG_OUTPUT, REG_CONFIG]

        when:
        chip.writePort(0, 0x00)
        c.setRegister(REG_INPUT, 0x04)

        then:
        chip.pin(2).read()

        when:
        chip.pin(0).setHigh()

        then:
        c.registers().get(REG_OUTPUT) == 0x01

        when:
        chip.pin(2).setHigh()

        then:
        c.registers().get(REG_OUTPUT) == 0x05

        when:
        chip.pin(0).setLow()

        then:
        c.registers().get(REG_OUTPUT) == 0x04

        when:
        chip.pin(0).toggle()

        then:
        c.registers().get(REG_OUTPUT) == 0x05
    }

    def "no pull-ups"() {
        given:
        def chip = new PCA6408AFull(new MockConnection(), 0x20)

        when:
        chip.pin(3).setPullUp(true)

        then:
        thrown(UnsupportedOperationException)
    }

    def "polarity, direction, raw access, interrupt mask and reset"() {
        given:
        def c = new MockConnection()
        def chip = new PCA6408AFull(c, 0x20)

        when:
        chip.configurePolarity(0x0F)

        then:
        c.registers().get(REG_POLARITY) == 0x0F

        when:
        int before = c.writes().size()
        chip.setDirection(0xF0)

        then:
        c.registers().get(REG_CONFIG) == 0xF0
        dataWrites(c, before) == [REG_OUTPUT, REG_CONFIG]
        chip.getDirection() == 0xF0

        when:
        chip.writeRegister(REG_OUTPUT, 0x81)
        chip.writeRegister(REG_CONFIG, 0x0F)

        then:
        chip.shadow == 0x81
        chip.direction == 0x0F
        chip.readRegister(REG_CONFIG) == 0x0F

        when:
        chip.writeRegister(REG_INPUT, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setDirection(0xF0)
        c.setRegister(REG_INPUT, 0x00)
        chip.pollInterrupt()
        c.setRegister(REG_INPUT, 0x35)

        then: "outputs are masked out of the changed mask"
        chip.pollInterrupt() == 0x30
        chip.pollInterrupt() == 0x00

        when:
        chip.reset()

        then:
        c.registers().get(REG_OUTPUT) == 0xFF
        c.registers().get(REG_POLARITY) == 0x00
        c.registers().get(REG_CONFIG) == 0xFF
        chip.shadow == 0xFF
        chip.direction == 0xFF
    }

    def "watch on an output pin throws"() {
        given:
        def chip = new PCA6408AFull(new MockConnection(), 0x20)
        chip.pin(0).setOutput()

        when:
        chip.pin(0).watch({ p -> } as java.util.function.Consumer)

        then:
        thrown(IllegalStateException)
    }

    def "minimal class is constructible"() {
        expect:
        new PCA6408AMinimal(new MockConnection(), 0x20).readPort() == 0
    }
}
