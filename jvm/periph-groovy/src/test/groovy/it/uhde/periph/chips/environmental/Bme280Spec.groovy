package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bme280Spec extends Specification {

    def "full API"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Bme280Minimal.REG_ID, 0x60)
        // Calibration NVM block 1 (26 bytes from 0x88), BMP280 datasheet worked
        // example, plus digH1=75 at 0xA1.
        connection.setRegister(Bme280Minimal.REG_CALIB,
                0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
                0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
                0x70, 0x17, 0x00, 0x4B)
        // Calibration NVM block 2 (7 bytes from 0xE1): digH2=384, digH3=0,
        // digH4=301, digH5=50, digH6=30.
        connection.setRegister(Bme280Minimal.REG_CAL_H2, 0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E)
        // ADC burst (8 bytes from 0xF7): adcP=415148, adcT=519888, adcH=32768.
        connection.setRegister(Bme280Minimal.REG_DATA, 0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00)

        def expectedT = 25.08d
        def expectedP = 1006.5325390625d
        def expectedH = 79.0869140625d

        when:
        def sensor = new Bme280Full(connection)

        then: "default ctrlMeas (0x25) already encodes forced mode, written at construction"
        connection.registers().get(Bme280Minimal.REG_CTRL_HUM) == 0x01
        connection.registers().get(Bme280Minimal.REG_CTRL_MEAS) == 0x25
        connection.registers().get(Bme280Minimal.REG_CONFIG) == 0x00

        expect:
        Math.abs(sensor.temperature() - expectedT) < 0.01
        Math.abs(sensor.pressure() - expectedP) < 0.01
        Math.abs(sensor.humidity() - expectedH) < 0.01

        when:
        int lastCtrlMeas = connection.registers().get(Bme280Minimal.REG_CTRL_MEAS)

        then:
        (lastCtrlMeas & 0x03) == 1

        when:
        sensor.configure(2, 3, 1, 3, 2, 5)

        then:
        connection.registers().get(Bme280Minimal.REG_CTRL_HUM) == 1
        connection.registers().get(Bme280Minimal.REG_CONFIG) == ((5 << 5) | (2 << 2))
        connection.registers().get(Bme280Minimal.REG_CTRL_MEAS) == ((2 << 5) | (3 << 2) | 3)

        when:
        sensor.setOversampling(3, 4, 2)

        then:
        connection.registers().get(Bme280Minimal.REG_CTRL_HUM) == 2
        connection.registers().get(Bme280Minimal.REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 3)

        when:
        sensor.setMode(1)

        then:
        connection.registers().get(Bme280Minimal.REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 1)

        when:
        sensor.setFilter(3)

        then:
        connection.registers().get(Bme280Minimal.REG_CONFIG) == ((5 << 5) | (3 << 2))

        when:
        sensor.setStandby(6)

        then:
        connection.registers().get(Bme280Minimal.REG_CONFIG) == ((6 << 5) | (3 << 2))

        when:
        connection.setRegister(Bme280Minimal.REG_STATUS, 0x08)

        then:
        sensor.status() == 0x08

        expect:
        Math.abs(sensor.altitude(1013.25d) - 56.07668235692459d) < 0.05
        Math.abs(sensor.seaLevelPressure(56.07668235692459d) - 1013.25d) < 0.05
        Math.abs(sensor.dewPoint() - 21.191706255732008d) < 0.05
        sensor.chipId() == 0x60

        when:
        sensor.reset()
        def sawReset = connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bme280Minimal.REG_RESET && (it[1] & 0xFF) == Bme280Minimal.RESET_CMD
        }

        then:
        sawReset
        connection.registers().get(Bme280Minimal.REG_CTRL_HUM) == 2
        connection.registers().get(Bme280Minimal.REG_CONFIG) == ((6 << 5) | (3 << 2))
        connection.registers().get(Bme280Minimal.REG_CTRL_MEAS) == ((3 << 5) | (4 << 2) | 1)
    }

    def "SPI masks write addresses"() {
        given: "Per specs/environmental/bme280.md's SPI Register-address protocol:\n" +
                "BME280's I2C register addresses already have bit 7 set, so SPI reads\n" +
                "use the same value unmasked; only writes differ, clearing bit 7\n" +
                "(reg & 0x7F)."
        def connection = new MockConnection()
        connection.setRegister(Bme280Minimal.REG_ID, 0x60)
        connection.setRegister(Bme280Minimal.REG_CALIB,
                0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
                0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
                0x70, 0x17, 0x00, 0x4B)
        connection.setRegister(Bme280Minimal.REG_CAL_H2, 0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E)
        connection.setRegister(Bme280Minimal.REG_DATA, 0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00)

        def expectedT = 25.08d
        def expectedP = 1006.5325390625d
        def expectedH = 79.0869140625d

        when: "construction writes ctrl_hum/ctrl_meas/config"
        def sensor = new Bme280Full(connection, 0x76, Bme280Minimal.BUS_SPI)

        then: "on SPI these must all be masked (bit 7 cleared), never the raw I2C address"
        [Bme280Minimal.REG_CTRL_HUM, Bme280Minimal.REG_CTRL_MEAS, Bme280Minimal.REG_CONFIG].every { reg ->
            connection.writes().any { it.length == 2 && (it[0] & 0xFF) == (reg & 0x7F) } &&
            !connection.writes().any { it.length == 2 && (it[0] & 0xFF) == reg }
        }

        expect: "reads stay unmasked - calibration/data preloading and read-based\n" +
                "assertions behave exactly as in I2C mode"
        Math.abs(sensor.temperature() - expectedT) < 0.01
        Math.abs(sensor.pressure() - expectedP) < 0.01
        Math.abs(sensor.humidity() - expectedH) < 0.01

        when: "reset() also routes every write through writeReg"
        sensor.reset()

        then: "the soft-reset command itself must be masked too"
        connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == (Bme280Minimal.REG_RESET & 0x7F) && (it[1] & 0xFF) == Bme280Minimal.RESET_CMD
        }
        !connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bme280Minimal.REG_RESET
        }
    }
}
