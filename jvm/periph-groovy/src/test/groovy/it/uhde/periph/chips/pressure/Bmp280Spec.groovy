package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Bmp280Spec extends Specification {

    private static void preloadCalibration(MockConnection connection) {
        // Spec's Data Conversion "Validation" worked example (datasheet page 23):
        // dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477, dig_P2=-10685,
        // dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7, dig_P7=15500,
        // dig_P8=-14600, dig_P9=6000. Calibration NVM is little-endian.
        connection.setRegister(Bmp280Minimal.REG_CALIB,
                0x70, 0x6B,  // dig_T1 = 27504
                0x43, 0x67,  // dig_T2 = 26435
                0x18, 0xFC,  // dig_T3 = -1000
                0x7D, 0x8E,  // dig_P1 = 36477
                0x43, 0xD6,  // dig_P2 = -10685
                0xD0, 0x0B,  // dig_P3 = 3024
                0x27, 0x0B,  // dig_P4 = 2855
                0x8C, 0x00,  // dig_P5 = 140
                0xF9, 0xFF,  // dig_P6 = -7
                0x8C, 0x3C,  // dig_P7 = 15500
                0xF8, 0xC6,  // dig_P8 = -14600
                0x70, 0x17)  // dig_P9 = 6000
    }

    private static void preloadData(MockConnection connection) {
        // UT=519888, UP=415148 (same worked example), one 6-byte burst.
        connection.setRegister(Bmp280Minimal.REG_DATA,
                0x65, 0x5A, 0xC0,  // adc_P = 415148
                0x7E, 0xED, 0x00)  // adc_T = 519888
    }

    def "full API"() {
        given: "Unlike Python/C++/JS/Rust/Go, the Groovy driver doesn't write default\n" +
                "CTRL_MEAS/CONFIG at construction - it only verifies the chip ID and\n" +
                "reads calibration; readRawData() writes CTRL_MEAS unconditionally on\n" +
                "every call (always forced mode)."
        def connection = new MockConnection()
        connection.setRegister(Bmp280Minimal.REG_ID, 0x58)
        preloadCalibration(connection)
        preloadData(connection)

        when:
        def sensor = new Bmp280Full(connection)

        then:
        noExceptionThrown()

        when: "temperature(): worked example -> T = 25.08 degC"
        def temp = sensor.temperature()

        then:
        Math.abs(temp - 25.08d) < 1e-3
        connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CTRL_MEAS && (it[1] & 0xFF) == 0x25
        }

        when: "pressure(): worked example -> p = 25767233/256/100 = 1006.5325... hPa"
        def pres = sensor.pressure()

        then:
        Math.abs(pres - 1006.5325390625d) < 1e-2

        when: "chipId(): expect 0x58"

        then:
        sensor.chipId() == 0x58

        when: "status(): raw status byte"
        connection.setRegister(Bmp280Minimal.REG_STATUS, 0x09)

        then:
        sensor.status() == 0x09

        when: "configure(osrsT=2, osrsP=3, mode=3, filter=2, tSb=4)"
        sensor.configure(2, 3, 3, 2, 4)
        def configureConfig = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CONFIG }.last()
        def configureCtrl = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }.last()

        then:
        configureConfig[1] == (byte) 0x88
        configureCtrl[1] == (byte) 0x4F

        when: "setOversampling(4, 5): mode bits preserved (3) -> ctrlMeas=0x97"
        sensor.setOversampling(4, 5)
        def setOversamplingWrite = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }.last()

        then:
        setOversamplingWrite[1] == (byte) 0x97

        when: "setMode(1): oversampling bits preserved -> ctrlMeas=0x95"
        sensor.setMode(1)
        def setModeWrite = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }.last()

        then:
        setModeWrite[1] == (byte) 0x95

        when: "setFilter(3): standby bits preserved -> config=0x8C"
        sensor.setFilter(3)
        def setFilterWrite = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CONFIG }.last()

        then:
        setFilterWrite[1] == (byte) 0x8C

        when: "setStandby(6): filter bits preserved -> config=0xCC"
        sensor.setStandby(6)
        def setStandbyWrite = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CONFIG }.last()

        then:
        setStandbyWrite[1] == (byte) 0xCC

        when: "altitude(): pressure() re-triggers + re-reads the same DATA bytes"
        def alt = sensor.altitude()

        then:
        Math.abs(alt - 56.07668235692459d) < 0.5d

        when: "seaLevelPressure(altitudeM=200)"
        def slp = sensor.seaLevelPressure(200.0d)

        then:
        Math.abs(slp - 1030.736388797547d) < 0.5d

        when: "reset(): writes soft-reset command, re-reads calibration, re-applies\n" +
                "current ctrlMeas/config (0x95, 0xCC from above)"
        preloadCalibration(connection)
        sensor.reset()
        def sawSoftReset = connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_SOFT_RST && (it[1] & 0xFF) == 0xB6
        }
        def calReads = connection.writes().findAll {
            it.length == 1 && (it[0] & 0xFF) == Bmp280Minimal.REG_CALIB
        }.size()
        def reappliedConfig = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CONFIG }.last()
        def reappliedCtrl = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CTRL_MEAS }.last()

        then:
        sawSoftReset
        calReads >= 2
        reappliedConfig[1] == (byte) 0xCC
        reappliedCtrl[1] == (byte) 0x95
    }

    def "SPI masks write addresses"() {
        given: "Per specs/pressure/bmp280.md's SPI Register-address protocol:\n" +
                "BMP280's I2C register addresses already have bit 7 set (0x88-0xFC), so\n" +
                "SPI reads use the same value unmasked; only writes differ, clearing\n" +
                "bit 7 (reg & 0x7F)."
        def connection = new MockConnection()
        connection.setRegister(Bmp280Minimal.REG_ID, 0x58)
        preloadCalibration(connection)
        preloadData(connection)

        when:
        def sensor = new Bmp280Full(connection, 0x76, Bmp280Minimal.BUS_SPI)

        then:
        noExceptionThrown()

        when: "temperature() triggers a forced-mode write to CTRL_MEAS"
        def temp = sensor.temperature()

        then: "on SPI the write address must have bit 7 cleared (0xF4 & 0x7F = 0x74)"
        Math.abs(temp - 25.08d) < 1e-3
        connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == (Bmp280Minimal.REG_CTRL_MEAS & 0x7F)
        }
        !connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CTRL_MEAS
        }

        when: "reads stay unmasked - pressure() still works via the same register constants"
        def pres = sensor.pressure()

        then:
        Math.abs(pres - 1006.5325390625d) < 1e-2

        when: "configure() also routes through writeReg, so CONFIG/CTRL_MEAS writes stay masked"
        sensor.configure(2, 3, 3, 2, 4)

        then:
        connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == (Bmp280Minimal.REG_CONFIG & 0x7F)
        }
        !connection.writes().any {
            it.length == 2 && (it[0] & 0xFF) == Bmp280Minimal.REG_CONFIG
        }
    }
}
