package it.uhde.periph.chips.magnetometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Hmc5883lSpec extends Specification {

    static int lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes[i]
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def "construction writes defaults and reads magnetic field (X,Z,Y order)"() {
        given:
        def connection = new MockConnection()

        when:
        def chip = new Hmc5883lMinimal(connection)

        then:
        lastWriteTo(connection, 0x00) == 0x70
        lastWriteTo(connection, 0x01) == 0x20
        lastWriteTo(connection, 0x02) == 0x00

        when:
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0) // x=1000,z=-500,y=2000
        def xyz = chip.magneticField()

        then:
        Math.abs(xyz[0] - (1000 / 1090.0) * 1e-4) < 1e-9
        Math.abs(xyz[1] - (2000 / 1090.0) * 1e-4) < 1e-9
        Math.abs(xyz[2] - (-500 / 1090.0) * 1e-4) < 1e-9
    }

    def "magneticField overflow returns null for that axis"() {
        given:
        def connection = new MockConnection()
        def chip = new Hmc5883lMinimal(connection)

        when:
        connection.setRegister(0x03, 0xF0, 0x00, 0x03, 0xE8, 0x03, 0xE8) // x overflow, z/y=1000
        def xyz = chip.magneticField()

        then:
        xyz[0] == null
        xyz[1] != null
    }

    def "configure and setGain"() {
        given:
        def connection = new MockConnection()
        def full = new Hmc5883lFull(connection)

        when:
        full.configure(30.0, 4, 5)

        then:
        lastWriteTo(connection, 0x00) == 0x54 // MA=10,DO=101
        lastWriteTo(connection, 0x01) == 0xA0 // GN=101
        full.gain == 5

        when:
        full.configure(30.0, 3, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        full.configure(100.0, 4, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        full.configure(30.0, 4, 8)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setGain(2)

        then:
        lastWriteTo(connection, 0x01) == (2 << 5)
        full.gain == 2

        when:
        full.setGain(9)

        then:
        thrown(IllegalArgumentException)
    }

    def "setMode"() {
        given:
        def connection = new MockConnection()
        def full = new Hmc5883lFull(connection)

        when:
        full.setMode("continuous")

        then:
        lastWriteTo(connection, 0x02) == 0b00

        when:
        full.setMode("single")

        then:
        lastWriteTo(connection, 0x02) == 0b01

        when:
        full.setMode("idle")

        then:
        lastWriteTo(connection, 0x02) == 0b10

        when:
        full.setMode("bogus")

        then:
        thrown(IllegalArgumentException)
    }

    def "dataReady and status"() {
        given:
        def connection = new MockConnection()
        def full = new Hmc5883lFull(connection)

        when:
        connection.setRegister(0x09, 0x01)

        then:
        full.dataReady()
        full.status() == 0x01

        when:
        connection.setRegister(0x09, 0x02) // LOCK set, RDY clear

        then:
        !full.dataReady()
    }

    def "singleMeasurement writes mode then reads data"() {
        given:
        def connection = new MockConnection()
        def full = new Hmc5883lFull(connection)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)

        when:
        def xyz = full.singleMeasurement()

        then:
        lastWriteTo(connection, 0x02) == 0x01
        Math.abs(xyz[0] - (1000 / (double) full.gainLsbPerGauss) * 1e-4) < 1e-9
    }

    def "identify reads ID registers"() {
        given:
        def connection = new MockConnection()
        def full = new Hmc5883lFull(connection)
        connection.setRegister(0x0A, 0x48, 0x34, 0x33)

        expect:
        full.identify() == [0x48, 0x34, 0x33] as int[]
    }

    def "selfTest sets bias then restores normal mode"() {
        given:
        def connection = new MockConnection()
        def full = new Hmc5883lFull(connection)
        connection.setRegister(0x00, 0x70) // current Config A (post-init)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0)

        when:
        def xyz = full.selfTest(true)
        def writesA = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == 0x00 }.collect { it[1] & 0xFF }

        then:
        writesA.contains(0x71) // 0x70|0b01
        writesA[-1] == 0x70
        Math.abs(xyz[0] - (1000 / (double) full.gainLsbPerGauss) * 1e-4) < 1e-9
    }
}
