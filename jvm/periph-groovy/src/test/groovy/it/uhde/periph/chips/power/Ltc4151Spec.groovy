package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ltc4151Spec extends Specification {

    private static List<Integer> control(MockConnection c) {
        c.writes().findAll { it.length == 2 && it[0] == (byte) 0x06 }.collect { it[1] & 0xFF }
    }

    def "full API"() {
        given:
        def connection = new MockConnection()

        when: "construction makes no bus access"
        def sensor = new Ltc4151Full(connection)

        then:
        connection.writes().isEmpty()

        when:
        connection.setRegister(0x02, 0x80, 0x00)
        connection.setRegister(0x00, 0x40, 0x00)

        then:
        Math.abs(sensor.voltage() - 51.2d) < 1e-9
        Math.abs(sensor.senseVoltage() - 0.02048d) < 1e-12
        Math.abs(sensor.current() - 1.024d) < 1e-9
        Math.abs(sensor.power() - 51.2d * 1.024d) < 1e-9

        when: "the four LSBs are in bits 7:4; BUSY and reserved bits are ignored"
        connection.setRegister(0x02, 0x00, 0xF8)
        connection.setRegister(0x04, 0x7D, 0x00)

        then:
        Math.abs(sensor.voltage() - 15 * 0.025d) < 1e-12
        Math.abs(sensor.adinVoltage() - 1.0d) < 1e-12

        when:
        connection.setRegister(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00)
        def r = sensor.readAll()

        then:
        Math.abs(r.vin - 51.2d) < 1e-9
        Math.abs(r.senseV - 0.02048d) < 1e-12
        Math.abs(r.current - 1.024d) < 1e-9
        Math.abs(r.power - 51.2d * 1.024d) < 1e-9
        Math.abs(r.adin - 1.0d) < 1e-12
        sensor.readRaw(Ltc4151Full.SENSE) == 0x400
        sensor.readRaw(Ltc4151Full.VIN) == 0x800
        sensor.readRaw(Ltc4151Full.ADIN) == 0x7D0

        when:
        sensor.readRaw(3)

        then:
        thrown(IllegalArgumentException)

        when:
        sensor.setSenseResistor(0.01d)

        then:
        Math.abs(sensor.getSenseResistor() - 0.01d) < 1e-12
        Math.abs(sensor.current() - 2.048d) < 1e-9

        when:
        sensor.setSenseResistor(0.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        sensor.setSenseResistor(0.02d)
        def minimal = new Ltc4151Minimal(connection, 0.005d)

        then:
        Math.abs(minimal.current() - 4.096d) < 1e-9
        Math.abs(minimal.power() - 51.2d * 4.096d) < 1e-9

        when: "CONTROL read-modify-write"
        connection.setRegister(0x06, 0x0C)

        then:
        sensor.readControl() == 0x0C

        when:
        connection.writes().clear()
        sensor.setStuckBusTimer(false)

        then:
        control(connection) == [0x08]

        when:
        connection.setRegister(0x06, 0x08)
        sensor.setStuckBusTimer(true)

        then:
        control(connection).last() == 0x0C

        when:
        sensor.setPageRw(false)

        then:
        control(connection).last() == 0x04

        when:
        connection.setRegister(0x06, 0x04)
        sensor.setPageRw(true)

        then:
        control(connection).last() == 0x0C

        when:
        sensor.writeControl(0xFF)

        then:
        control(connection).last() == 0xEF

        when:
        sensor.writeControl(0x0C)
        connection.setRegister(0x06, 0xA8)
        connection.writes().clear()

        then:
        sensor.isSnapshotMode()

        when:
        sensor.setContinuous()

        then:
        control(connection) == [0x28]

        when:
        connection.setRegister(0x06, 0x0C)
        connection.setRegister(0x01, 0x00)
        connection.setRegister(0x03, 0x08)

        then:
        !sensor.isSnapshotMode()
        !sensor.isBusy(Ltc4151Full.SENSE)
        sensor.isBusy(Ltc4151Full.VIN)

        when: "snapshot(VIN): clear, start with channel 01 + snapshot, restore"
        connection.setRegister(0x03, 0x00)
        connection.setRegister(0x02, 0x80, 0x00)
        connection.writes().clear()
        def v = sensor.snapshot(Ltc4151Full.VIN)

        then:
        Math.abs(v - 51.2d) < 1e-9
        control(connection) == [0x0C, 0xAC, 0x0C]

        when: "BUSY never clears"
        connection.setRegister(0x01, 0x08)
        sensor.snapshot(Ltc4151Full.SENSE)

        then:
        thrown(IOException)

        when: "page read/write disabled: single-register reads"
        connection.setRegister(0x01, 0x00)
        connection.setRegister(0x00, 0x40, 0x00, 0x80, 0x00, 0x7D, 0x00)
        sensor.setPageRw(false)

        then:
        Math.abs(sensor.readAll().power - 51.2d * 1.024d) < 1e-9
        Math.abs(Ltc4151Full.temperatureFromAdin(900, 1200) - 58.82d * (0.75d - 0.1066d)) < 1e-9
    }
}
