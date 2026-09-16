package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ade7953Spec extends Specification {

    def "full API smoke test"() {
        given:
        def connection = new MockConnection()
        connection.setAddressWidth(2)
        connection.setRegister(0x21C, 0x89, 0xD1, 0x47)
        def chip = new Ade7953Full(connection, 100.0, 10.0)

        expect:
        Math.abs(chip.voltage() - 35.35533905932738) < 1e-3

        when:
        connection.setRegister(0x21A, 0x89, 0xD1, 0x47)

        then:
        Math.abs(chip.current() - 3.53553390593) < 1e-3

        when:
        connection.setRegister(0x212, 0x4A, 0x31, 0xC1)

        then:
        Math.abs(chip.activePower() - 125.0) < 1e-3

        when:
        def writesBefore = connection.writes().size()
        chip.reset()

        then:
        def swrstFound = (writesBefore + 1..<connection.writes().size()).any { i ->
            def w = connection.writes()[i]
            w.size() >= 4 && (w[3] & 0x80) != 0
        }
        swrstFound
    }
}