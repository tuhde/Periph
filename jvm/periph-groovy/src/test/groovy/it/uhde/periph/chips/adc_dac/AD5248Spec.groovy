package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class AD5248Spec extends Specification {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length]
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i]
        return out
    }

    private static byte[] last(MockConnection c) {
        return c.writes()[c.writes().size() - 1]
    }

    def "full API"() {
        given:
        def connection = new MockConnection()
        def chip = new AD5248Full(connection)

        expect:
        connection.writes().isEmpty()

        when:
        chip.setWiper(0, 100)
        then:
        last(connection) == b(0x00, 100)

        when:
        chip.setWiper(1, 200)
        then:
        last(connection) == b(0x80, 200)

        when:
        chip.setWiper(0, 999)
        then:
        last(connection) == b(0x00, 255)

        when:
        chip.setWiper(0, -5)
        then:
        last(connection) == b(0x00, 0)

        when:
        chip.setWiper(2, 0)
        then:
        thrown(IllegalArgumentException)

        when: "GetWiper sends the instruction byte alone"
        chip.setWiper(0, 100)
        chip.setWiper(1, 200)
        int before = connection.writes().size()
        then:
        chip.getWiper(0) == 100
        connection.writes().size() == before + 1
        last(connection) == b(0x00)
        chip.getWiper(1) == 200

        when:
        chip.setWiper(0, 128)
        then:
        Math.abs(chip.getResistance(0) - 5320.0d) < 1e-9

        when:
        chip.setResistance(1, 5320.0d)
        then:
        last(connection) == b(0x80, 128)

        when:
        chip.setResistance(1, 1.0d)
        then:
        last(connection) == b(0x80, 0)

        when:
        chip.setResistance(1, 1e9d)
        then:
        last(connection) == b(0x80, 255)

        when:
        chip.setBoth(10, 20)
        then:
        connection.writes()[connection.writes().size() - 2] == b(0x00, 10)
        last(connection) == b(0x80, 20)

        when:
        chip.setRatio(0, 0.5d)
        then:
        last(connection) == b(0x00, 128)

        when:
        chip.setRatio(0, 1.0d)
        then:
        last(connection) == b(0x00, 255)

        when:
        chip.setWiper(1, 64)
        then:
        chip.getRatio(1) == 0.25d

        when:
        chip.setWiper(0, 250)
        then:
        chip.increment(0, 10) == 255
        chip.increment(0, -5) == 250

        when:
        chip.setWiper(1, 77)
        chip.shutdown(1)
        then:
        last(connection) == b(0xC0, 77)
        chip.isShutdown(1)
        !chip.isShutdown(0)

        when:
        chip.setWiper(1, 99)
        then:
        last(connection) == b(0xC0, 99)

        when:
        chip.resume(1)
        then:
        last(connection) == b(0x80, 99)
        !chip.isShutdown(1)

        when:
        chip.shutdown(0)
        chip.reset()
        then:
        connection.writes()[connection.writes().size() - 2] == b(0x00, 0x80)
        last(connection) == b(0x80, 0x80)
        !chip.isShutdown(0)

        when:
        int n = connection.writes().size()
        chip.setRab(50000.0d, 100.0d)
        then:
        connection.writes().size() == n

        when:
        chip.setWiper(0, 128)
        then:
        Math.abs(chip.getResistance(0) - 25200.0d) < 1e-9
    }
}
