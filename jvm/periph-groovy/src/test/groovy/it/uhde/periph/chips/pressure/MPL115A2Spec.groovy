package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class MPL115A2Spec extends Specification {

    private static MockConnection preload() {
        // Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
        // ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
        def c = new MockConnection()
        c.setRegister(MPL115A2Minimal.REG_COEF, 0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8)
        c.setRegister(MPL115A2Minimal.REG_PADC, 0x66, 0x80, 0x7E, 0xC0)
        return c
    }

    def "full API"() {
        given:
        def connection = preload()
        def sensor = new MPL115A2Full(connection)

        expect:
        Math.abs(sensor.coefficients()[0] - 2009.75d) < 1e-9d
        Math.abs(sensor.coefficients()[1] + 2.375854d) < 1e-5d
        Math.abs(sensor.coefficients()[2] + 0.92047d) < 1e-5d
        Math.abs(sensor.coefficients()[3] - 0.000790d) < 1e-6d

        when:
        double p = sensor.readPressure()

        then:
        Math.abs(p - 96586.0d) < 30.0d
        connection.writes().any { it.length == 2 && (it[0] & 0xFF) == MPL115A2Minimal.REG_CONVERT && it[1] == 0 }
        Math.abs(sensor.readTemperature() - (25.0d + 9 / -5.35d)) < 1e-9d
        sensor.readRaw() == [410, 507] as int[]
        Math.abs(sensor.read()[0] - p) < 1e-9d
        Math.abs(sensor.compensate(410, 507) - p) < 1e-9d
        sensor.compensate(0, 0) > 40000 && sensor.compensate(1023, 0) < 130000
        Math.abs(sensor.pressureHpa() - p / 100.0d) < 1e-9d
        Math.abs(sensor.altitude() - 44330.0d * (1.0d - Math.pow(p / 101325.0d, 1.0d / 5.255d))) < 1e-6d
        Math.abs(sensor.altitude(p)) < 1e-9d

        when:
        connection.setRegister(MPL115A2Minimal.REG_COEF, 0x00, 0x08, 0, 0, 0, 0, 0, 0)
        sensor.reloadCoefficients()

        then:
        sensor.coefficients()[0] == 1.0d
    }

    def "minimal"() {
        expect:
        Math.abs(new MPL115A2Minimal(preload()).readPressure() - 96586.0d) < 30.0d
    }
}
