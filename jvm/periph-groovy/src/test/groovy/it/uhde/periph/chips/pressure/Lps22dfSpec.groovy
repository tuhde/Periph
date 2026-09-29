package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Lps22dfSpec extends Specification {

    static MockConnection newConnection() {
        def c = new MockConnection()
        c.setRegister(Lps22dfMinimal.REG_WHO_AM_I, Lps22dfMinimal.CHIP_ID)
        return c
    }

    static int lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes[i]
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def "construction sequence"() {
        given:
        def connection = newConnection()

        when:
        new Lps22dfMinimal(connection)

        then:
        def writes = connection.writes()
        writes.size() == 4
        (writes[0][0] & 0xFF) == Lps22dfMinimal.REG_WHO_AM_I
        (writes[1][1] & 0xFF) == 0x04 // SWRESET
        (writes[2][1] & 0xFF) == (3 << 3) // CTRL_REG1 default
        (writes[3][1] & 0xFF) == 0x08 // BDU
    }

    def "construction with bad chip ID throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Lps22dfMinimal.REG_WHO_AM_I, 0x00)

        when:
        new Lps22dfMinimal(connection)

        then:
        thrown(IOException)
    }

    def "pressure and temperature"() {
        given:
        def connection = newConnection()
        def chip = new Lps22dfMinimal(connection)

        when:
        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x01 | 0x02)
        connection.setRegister(Lps22dfMinimal.REG_PRESS_OUT_XL, 0x00, 0x80, 0x0C) // raw=819200 -> 20000 Pa

        then:
        Math.abs(chip.pressure() - 20000.0d) < 1e-3

        when:
        connection.setRegister(Lps22dfMinimal.REG_TEMP_OUT_L, 0x2E, 0x09) // raw=2350 -> 23.5 degC

        then:
        Math.abs(chip.temperature() - 23.5d) < 1e-3

        when: "negative values"
        connection.setRegister(Lps22dfMinimal.REG_PRESS_OUT_XL, 0x00, 0xC0, 0xF9) // raw=-409600 -> -10000 Pa

        then:
        Math.abs(chip.pressure() - (-10000.0d)) < 1e-3

        when:
        connection.setRegister(Lps22dfMinimal.REG_TEMP_OUT_L, 0x0C, 0xFE) // raw=-500 -> -5.0 degC

        then:
        Math.abs(chip.temperature() - (-5.0d)) < 1e-3
    }

    // Regression: temperature() must poll STATUS.T_DA before reading TEMP_OUT,
    // exactly like pressure() polls STATUS.P_DA -- it previously read
    // TEMP_OUT_L/H unconditionally with no STATUS check at all.
    def "temperature polls STATUS first"() {
        given:
        def connection = newConnection()
        def chip = new Lps22dfMinimal(connection)
        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x02)
        connection.setRegister(Lps22dfMinimal.REG_TEMP_OUT_L, 0x2E, 0x09)

        when:
        chip.temperature()
        def writes = connection.writes()
        int n = writes.size()

        then:
        (writes[n - 2][0] & 0xFF) == Lps22dfMinimal.REG_STATUS
        (writes[n - 1][0] & 0xFF) == Lps22dfMinimal.REG_TEMP_OUT_L
    }

    def "configure writes registers"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when:
        full.configure(Lps22dfFull.ODR_50_HZ, Lps22dfFull.AVG_16, true, 1, true)

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG1) == ((Lps22dfFull.ODR_50_HZ << 3) | Lps22dfFull.AVG_16)
        lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG2) == (0x10 | 0x20 | 0x08)
    }

    def "oneshot writes sequence and waits"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)
        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x01)

        when:
        full.oneshot()
        def writes = connection.writes()
        int n = writes.size()

        then:
        (writes[n - 3][0] & 0xFF) == Lps22dfMinimal.REG_CTRL_REG1
        (writes[n - 3][1] & 0xFF) == 0x00
        (writes[n - 2][0] & 0xFF) == Lps22dfMinimal.REG_CTRL_REG2
        (writes[n - 2][1] & 0xFF) == 0x09
        (writes[n - 1][0] & 0xFF) == Lps22dfMinimal.REG_STATUS
    }

    def "altitude at zero pressure"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)
        connection.setRegister(Lps22dfMinimal.REG_STATUS, 0x01)
        connection.setRegister(Lps22dfMinimal.REG_PRESS_OUT_XL, 0x00, 0x00, 0x00) // raw=0 -> 0 Pa

        expect:
        Math.abs(full.altitude(101325.0d) - 44330.0d) < 1.0
    }

    def "softwareReset writes CTRL_REG2"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when:
        full.softwareReset()

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG2) == 0x04
    }

    def "setPressureOffset"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when: "-12.34 hPa -> raw = round(-12.34*4096) = -50545 -> wraps to 14991 (0x3A8F)"
        full.setPressureOffset(-1234.0d) // Pa

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_RPDS_L) == 0x8F
        lastWriteTo(connection, Lps22dfMinimal.REG_RPDS_H) == 0x3A
    }

    def "setPressureThreshold"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when: "900.0 hPa -> raw = round(900*16) & 0x7FFF = 14400 = 0x3840"
        full.setPressureThreshold(90000.0d) // Pa

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_THS_P_L) == 0x40
        lastWriteTo(connection, Lps22dfMinimal.REG_THS_P_H) == 0x38
    }

    def "configureInterrupt"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when:
        full.configureInterrupt(true, true, true, true, true, true, true, true)

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG3) == (0x08 | 0x02 | 0x01)
        lastWriteTo(connection, Lps22dfMinimal.REG_CTRL_REG4) == (0x40 | 0x20 | 0x10 | 0x04 | 0x02 | 0x01)
    }

    def "configurePressureEvent"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when:
        full.configurePressureEvent(true, true, true)

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG) == (0x01 | 0x02 | 0x04)
    }

    def "autozero, autorefp, resetReference"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when:
        full.autozero()

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG) == 0x20

        when:
        full.autorefp()

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG) == 0x80

        when:
        full.resetReference()

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_INTERRUPT_CFG) == 0x50
    }

    def "referencePressure"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)
        connection.setRegister(Lps22dfMinimal.REG_REF_P_L, 0x00, 0x10) // raw=4096 -> 100.0 Pa

        expect:
        Math.abs(full.referencePressure() - 100.0d) < 1e-3
    }

    def "FIFO mode and watermark"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)

        when:
        full.setFifoMode(Lps22dfFull.FIFO_CONT_TO_FIFO)

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_FIFO_CTRL) == ((1 << 2) | 3)

        when:
        full.setFifoWatermark(100)

        then:
        lastWriteTo(connection, Lps22dfMinimal.REG_FIFO_WTM) == 100
    }

    def "readFifo"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)
        connection.setRegister(Lps22dfMinimal.REG_FIFO_STATUS1, 2)
        connection.setRegister(Lps22dfMinimal.REG_FIFO_PRESS_XL,
            0x00, 0x80, 0x0C, // sample 0: raw=819200 -> 20000 Pa
            0x00, 0xC0, 0xF9) // sample 1: raw=-409600 -> -10000 Pa

        when:
        double[] out = new double[4]
        int n = full.readFifo(out)

        then:
        n == 2
        Math.abs(out[0] - 20000.0d) < 1e-3
        Math.abs(out[1] - (-10000.0d)) < 1e-3
    }

    def "readFifo truncates to out buffer length"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)
        connection.setRegister(Lps22dfMinimal.REG_FIFO_STATUS1, 5)
        connection.setRegister(Lps22dfMinimal.REG_FIFO_PRESS_XL,
            0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C,
            0x00, 0x80, 0x0C, 0x00, 0x80, 0x0C)

        expect:
        full.readFifo(new double[2]) == 2
    }

    def "interruptSource"() {
        given:
        def connection = newConnection()
        def full = new Lps22dfFull(connection)
        connection.setRegister(Lps22dfMinimal.REG_INT_SOURCE, 0x85) // BOOT_ON | IA | PH

        expect:
        full.interruptSource() == 0x85
    }

    def "SPI addressing"() {
        given:
        def connection = new MockConnection()
        // The mock's register map is keyed by the literal address byte sent,
        // so for SPI (read addresses have bit 7 set) the fixture must be
        // preloaded at the shifted address.
        connection.setRegister(Lps22dfMinimal.REG_WHO_AM_I | 0x80, Lps22dfMinimal.CHIP_ID)

        when:
        new Lps22dfMinimal(connection, 0x5C, Lps22dfMinimal.BUS_SPI)
        def writes = connection.writes()

        then:
        (writes[0][0] & 0xFF) == (Lps22dfMinimal.REG_WHO_AM_I | 0x80)
        (writes[1][0] & 0xFF) == (Lps22dfMinimal.REG_CTRL_REG2 & 0x7F)
    }
}
