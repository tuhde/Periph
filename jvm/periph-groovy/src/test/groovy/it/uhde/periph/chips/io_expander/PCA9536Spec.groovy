package it.uhde.periph.chips.io_expander

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class PCA9536Spec extends Specification {

    static final int REG_INPUT = 0x00, REG_OUTPUT = 0x01, REG_POLARITY = 0x02, REG_CONFIG = 0x03

    /** Register bytes of the data writes (length > 1) after index {@code from}; read-pointer writes are skipped. */
    static List<Integer> dataWrites(MockConnection c, int from) {
        return c.writes().drop(from).findAll { it.length > 1 }.collect { (it[0] & 0xFF) as Integer }
    }

    def "init writes the reset state"() {
        given:
        def c = new MockConnection()

        when:
        def chip = new PCA9536Full(c, 0x41)

        then:
        c.registers().get(REG_OUTPUT) == 0xFF
        c.registers().get(REG_POLARITY) == 0x00
        c.registers().get(REG_CONFIG) == 0xFF
        chip.shadow == 0xFF
        chip.direction == 0x0F
        !dataWrites(c, 0).contains(REG_INPUT)
    }

    def "port and pin access"() {
        given:
        def c = new MockConnection()
        def chip = new PCA9536Full(c, 0x41)

        when:
        c.setRegister(REG_INPUT, 0xF5)

        then:
        chip.readPort(0) == 0x05
        chip.readPort(1) == 0x05

        when:
        chip.writePort(0, 0x0C)

        then:
        c.registers().get(REG_OUTPUT) == 0xFC
        chip.shadow == 0xFC

        when:
        chip.pin(3).setOutput()

        then:
        c.registers().get(REG_CONFIG) == 0xF7

        when:
        chip.pin(3).setInput()

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
        c.registers().get(REG_OUTPUT) == 0xF1

        when:
        chip.pin(2).setHigh()

        then:
        c.registers().get(REG_OUTPUT) == 0xF5

        when:
        chip.pin(0).setLow()

        then:
        c.registers().get(REG_OUTPUT) == 0xF4

        when:
        chip.pin(0).toggle()

        then:
        c.registers().get(REG_OUTPUT) == 0xF5
    }

    def "the weak pull-up is fixed"() {
        given:
        def chip = new PCA9536Full(new MockConnection(), 0x41)

        when:
        chip.pin(3).setPullUp(true)

        then:
        thrown(UnsupportedOperationException)
    }

    def "polarity, direction, raw access, change mask and reset"() {
        given:
        def c = new MockConnection()
        def chip = new PCA9536Full(c, 0x41)

        when:
        chip.configurePolarity(0x0F)

        then:
        c.registers().get(REG_POLARITY) == 0x0F

        when:
        int before = c.writes().size()
        chip.setDirection(0x0C)

        then:
        c.registers().get(REG_CONFIG) == 0xFC
        dataWrites(c, before) == [REG_OUTPUT, REG_CONFIG]
        chip.getDirection() == 0x0C

        when:
        chip.writeRegister(REG_OUTPUT, 0xF1)
        chip.writeRegister(REG_CONFIG, 0x0F)

        then:
        chip.shadow == 0xF1
        chip.direction == 0x0F
        chip.readRegister(REG_CONFIG) == 0x0F

        when:
        chip.writeRegister(REG_INPUT, 0)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setDirection(0x0C)
        c.setRegister(REG_INPUT, 0x00)
        chip.pollChanges()
        c.setRegister(REG_INPUT, 0x0D)

        then: "outputs are masked out of the changed mask"
        chip.pollChanges() == 0x0C
        chip.pollChanges() == 0x00

        when: "a polarity change refreshes the cache and is not reported"
        c.setRegister(REG_INPUT, 0x00)
        chip.configurePolarity(0x0C)

        then:
        chip.pollChanges() == 0x00

        when:
        chip.reset()

        then:
        c.registers().get(REG_OUTPUT) == 0xFF
        c.registers().get(REG_POLARITY) == 0x00
        c.registers().get(REG_CONFIG) == 0xFF
        chip.shadow == 0xFF
        chip.direction == 0x0F
    }

    def "watchChanges and pin watch poll the input"() {
        given:
        def c = new MockConnection()
        def chip = new PCA9536Full(c, 0x41)
        def changes = new java.util.concurrent.LinkedBlockingQueue<Integer>()

        when:
        chip.watchChanges({ int m -> changes.add(m) } as java.util.function.IntConsumer, 0.002d)
        c.setRegister(REG_INPUT, 0x04)

        then:
        changes.poll(2, java.util.concurrent.TimeUnit.SECONDS) == 0x04

        when:
        chip.unwatchChanges()
        def hits = new java.util.concurrent.LinkedBlockingQueue<Integer>()
        chip.pin(3).watch({ p -> hits.add(3) } as java.util.function.Consumer, it.uhde.periph.connection.EdgeTrigger.RISING)
        c.setRegister(REG_INPUT, 0x0C)

        then:
        hits.poll(2, java.util.concurrent.TimeUnit.SECONDS) == 3

        cleanup:
        chip.unwatchChanges()
    }

    def "watch on an output pin throws"() {
        given:
        def chip = new PCA9536Full(new MockConnection(), 0x41)
        chip.pin(0).setOutput()

        when:
        chip.pin(0).watch({ p -> } as java.util.function.Consumer)

        then:
        thrown(IllegalStateException)
    }

    def "minimal class is constructible"() {
        expect:
        new PCA9536Minimal(new MockConnection(), 0x41).readPort() == 0
    }
}
