package it.uhde.periph.chips.other

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Mpr121Spec extends Specification {

    def "minimal construction writes expected registers"() {
        given:
        def connection = new MockConnection()

        when:
        new Mpr121Minimal(connection)

        then:
        (connection.registers()[Mpr121Minimal.REG_SRST] & 0xFF) == Mpr121Minimal.SOFT_RESET_KEY
        (connection.registers()[Mpr121Minimal.REG_ECR]  & 0xFF) == Mpr121Minimal.ECR_DEFAULT
        (connection.registers()[Mpr121Minimal.REG_E0TTH] & 0xFF) == Mpr121Minimal.TOUCH_DEFAULT
        (connection.registers()[Mpr121Minimal.REG_E0RTH] & 0xFF) == Mpr121Minimal.RELEASE_DEFAULT
    }

    def "touched decodes bitmask"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE0_7_TOUCH, 0x5A, 0x05)

        when:
        def chip = new Mpr121Minimal(connection)

        then:
        chip.touched() == (0x5A | ((0x05 & 0x0F) << 8))
    }

    def "is_touched per electrode"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE0_7_TOUCH, 0x28, 0x08)
        def chip = new Mpr121Minimal(connection)

        expect:
        chip.isTouched(5)
        chip.isTouched(11)
        !chip.isTouched(0)
    }

    def "is_touched rejects out-of-range"() {
        given:
        def chip = new Mpr121Minimal(new MockConnection())

        when:
        chip.isTouched(12)

        then:
        thrown(IllegalArgumentException)
    }

    def "filtered decodes 10-bit"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(0x04, 0x80, 0x02)
        def chip = new Mpr121Full(connection)

        expect:
        chip.filtered(0) == 0x280
    }

    def "baseline shifts left 2"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(0x1E, 0x80)
        def chip = new Mpr121Full(connection)

        expect:
        chip.baseline(0) == 0x200
    }

    def "set_baseline shifts right 2"() {
        given:
        def connection = new MockConnection()
        def chip = new Mpr121Full(connection)

        when:
        chip.setBaseline(0, 0x300)

        then:
        (connection.registers()[0x1E] & 0xFF) == 0xC0
    }

    def "proximity_touched reads bit 4"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x10)
        def chip = new Mpr121Full(connection)

        expect:
        chip.proximityTouched()

        when:
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x00)

        then:
        !chip.proximityTouched()
    }

    def "clear_overcurrent clears bit 7"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Mpr121Minimal.REG_ELE8_PROX_TCH, 0x80)
        def chip = new Mpr121Full(connection)

        when:
        chip.clearOvercurrent()

        then:
        (connection.registers()[Mpr121Minimal.REG_ELE8_PROX_TCH] & 0x80) == 0
    }

    def "configure_sampling packs bits"() {
        given:
        def connection = new MockConnection()
        def chip = new Mpr121Full(connection)

        when:
        chip.configureSampling(10, 2, 1, 2, 5)

        then:
        (connection.registers()[Mpr121Minimal.REG_CDC_CONFIG] & 0xFF) == 0x4A
        (connection.registers()[Mpr121Minimal.REG_CDT_CONFIG] & 0xFF) == 0x4D
    }

    def "configure_debounce packs bits"() {
        given:
        def connection = new MockConnection()
        def chip = new Mpr121Full(connection)

        when:
        chip.configureDebounce(3, 5)

        then:
        (connection.registers()[Mpr121Minimal.REG_DEBOUNCE] & 0xFF) == 0x53
    }

    def "enable/disable interrupt"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Mpr121Minimal.REG_AUTOCONFIG1, 0x00)
        def chip = new Mpr121Full(connection)

        when:
        chip.enableInterrupt(Mpr121Full.SOURCE_OOR)

        then:
        (connection.registers()[Mpr121Minimal.REG_AUTOCONFIG1] & 0x07) == 0x04

        when:
        connection.setRegister(Mpr121Minimal.REG_AUTOCONFIG1, 0x04)
        chip.disableInterrupt(Mpr121Full.SOURCE_OOR)

        then:
        (connection.registers()[Mpr121Minimal.REG_AUTOCONFIG1] & 0x07) == 0x00
    }
}
