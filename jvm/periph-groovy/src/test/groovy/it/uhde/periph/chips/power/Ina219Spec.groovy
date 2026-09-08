package it.uhde.periph.chips.power

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ina219Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()

        when: "rShunt=0.1, maxCurrent=2.0 -> currentLsb=2.0/32768, cal=0x1A36"
        def sensor = new Ina219Full(connection, 0.1d, 2.0d)

        then:
        connection.writes().last() == [0x05, 0x1A, 0x36] as byte[]

        when: "Bus Voltage: raw=(1000<<3)|0b010 = 0x1F42 -> voltage=4.0V, CNVR=1, OVF=0"
        connection.setRegister(0x02, 0x1F, 0x42)

        then:
        sensor.voltage() == 4.0d
        sensor.conversionReady()
        !sensor.overflow()

        when: "Bus Voltage: raw=(1000<<3)|0b001 = 0x1F41 -> CNVR=0, OVF=1"
        connection.setRegister(0x02, 0x1F, 0x41)

        then:
        sensor.overflow()

        when: "Shunt Voltage: raw=-500 (0xFE0C) -> -0.005 V"
        connection.setRegister(0x01, 0xFE, 0x0C)

        then:
        Math.abs(sensor.shuntVoltage() - (-0.005d)) < 1e-9

        when: "Current: raw=1000 (0x03E8) -> 1000 * currentLsb"
        connection.setRegister(0x04, 0x03, 0xE8)

        then:
        Math.abs(sensor.current() - (1000 * (2.0d / 32768))) < 1e-9

        when: "Power: raw=2000 (0x07D0) -> 2000 * 20 * currentLsb"
        connection.setRegister(0x03, 0x07, 0xD0)

        then:
        Math.abs(sensor.power() - (2000 * 20 * (2.0d / 32768))) < 1e-9

        when: "configure() re-writes Calibration afterward"
        sensor.configure(0, 1, 0x0B, 0x02, 5)
        def writes = connection.writes()

        then:
        writes[-2] == [0x00, 0x0D, (byte) 0x95] as byte[]
        writes[-1] == [0x05, 0x1A, 0x36] as byte[]

        when: "shutdown(): MODE forced to 0 (0x0D95 -> 0x0D90)"
        sensor.shutdown()

        then:
        connection.writes().last() == [0x00, 0x0D, (byte) 0x90] as byte[]

        when: "wake(): restores the previously configured mode (5) -> 0x0D95"
        sensor.wake()

        then:
        connection.writes().last() == [0x00, 0x0D, (byte) 0x95] as byte[]

        when: "trigger(): re-writes the current config unchanged"
        sensor.trigger()

        then:
        connection.writes().last() == [0x00, 0x0D, (byte) 0x95] as byte[]

        when: "reset(): RST, restore cached config, re-write Calibration"
        sensor.reset()
        def writes2 = connection.writes()

        then:
        writes2[-3] == [0x00, (byte) 0x80, 0x00] as byte[]
        writes2[-2] == [0x00, 0x0D, (byte) 0x95] as byte[]
        writes2[-1] == [0x05, 0x1A, 0x36] as byte[]
    }
}
