package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Lps28dfwSpec extends Specification {

    static MockConnection newConnection() {
        def c = new MockConnection()
        c.setRegister(0x0F, 0xB4) // WHO_AM_I
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

    def "construction verifies WHO_AM_I and reads pressure/temperature"() {
        given:
        def connection = newConnection()

        when:
        def chip = new Lps28dfwMinimal(connection)

        then:
        lastWriteTo(connection, 0x11) == 0x18
        lastWriteTo(connection, 0x10) == 0x22

        when:
        connection.setRegister(0x28, 0x00, 0x54, 0x3F) // 1013.25 hPa

        then:
        Math.abs(chip.readPressure() - 1013.25) < 0.001

        when:
        connection.setRegister(0x2B, 0x2E, 0x09) // 23.5 C

        then:
        Math.abs(chip.readTemperature() - 23.5) < 0.001
    }

    def "construction with bad WHO_AM_I throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(0x0F, 0x00)

        when:
        new Lps28dfwMinimal(connection)

        then:
        thrown(IOException)
    }

    def "negative pressure and temperature (sign extension)"() {
        given:
        def connection = newConnection()
        def chip = new Lps28dfwMinimal(connection)

        when:
        connection.setRegister(0x28, 0x00, 0xE0, 0xFC) // -50.0 hPa

        then:
        Math.abs(chip.readPressure() - (-50.0)) < 0.001

        when:
        connection.setRegister(0x2B, 0x18, 0xFC) // -10.0 C

        then:
        Math.abs(chip.readTemperature() - (-10.0)) < 0.001
    }

    def "configure and read (Mode 2)"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)

        when:
        full.configure(Lps28dfwFull.ODR_50_HZ, Lps28dfwFull.AVG_64, 1, true, 1)

        then:
        lastWriteTo(connection, 0x11) == 0x78
        lastWriteTo(connection, 0x10) == 0x2C

        when:
        connection.setRegister(0x28, 0x00, 0x80, 0x3E, 0x21, 0x07) // 2000 hPa mode2, 18.25 C
        def r = full.read()

        then:
        Math.abs(r[0] - 2000.0) < 0.001
        Math.abs(r[1] - 18.25) < 0.001
    }

    def "isDataReady reflects STATUS.P_DA"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)

        when:
        connection.setRegister(0x27, 0x01)

        then:
        full.isDataReady()

        when:
        connection.setRegister(0x27, 0x00)

        then:
        !full.isDataReady()
    }

    def "readOneshot polls P_DA and restores ODR"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)
        connection.setRegister(0x10, 0x22) // saved CTRL_REG1 (ODR=4)
        connection.setRegister(0x11, 0x18) // saved CTRL_REG2
        connection.setRegister(0x27, 0x01) // P_DA already set
        connection.setRegister(0x28, 0x00, 0x80, 0x3E)
        connection.setRegister(0x2B, 0xD0, 0x07)

        when:
        def r = full.readOneshot()

        then:
        Math.abs(r[0] - 1000.0) < 0.001
        Math.abs(r[1] - 20.0) < 0.001
        lastWriteTo(connection, 0x10) == 0x22
    }

    def "setOffset and softreset"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)

        when:
        full.setOffset(-0.5) // Mode 1 default: -0.5*4096 = -2048 = 0xF800

        then:
        lastWriteTo(connection, 0x1A) == 0x00
        lastWriteTo(connection, 0x1B) == 0xF8

        when: "CTRL_REG2 is currently 0x18 from construction"
        full.softreset()

        then:
        lastWriteTo(connection, 0x11) == 0x1A
    }

    def "fifoConfigure always passes through Bypass first (regression)"() {
        // Regression: switching directly to a non-bypass mode must still
        // write FIFO_CTRL=0x00 (Bypass) first, per the spec's FIFO reset
        // procedure -- the buggy version only did this when the target
        // mode itself was Bypass.
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)

        when:
        full.fifoConfigure(Lps28dfwFull.FIFO_CONTINUOUS, 50, true)
        def fifoCtrlWrites = connection.writes().findAll { it.length == 2 && (it[0] & 0xFF) == 0x14 }.collect { it[1] & 0xFF }

        then:
        fifoCtrlWrites.size() >= 2
        fifoCtrlWrites[0] == 0x00
        fifoCtrlWrites[-1] == 0x0A
        lastWriteTo(connection, 0x15) == 50
    }

    def "fifoRead and fifoLevel"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)
        connection.setRegister(0x78,
            0x00, 0x80, 0x3E, // 1000.0 hPa
            0x00, 0x20, 0x3F, // 1010.0 hPa
            0x00, 0xC0, 0x3F) // 1020.0 hPa

        when:
        def samples = full.fifoRead(3)

        then:
        samples.length == 3
        Math.abs(samples[0] - 1000.0) < 0.01
        Math.abs(samples[1] - 1010.0) < 0.01
        Math.abs(samples[2] - 1020.0) < 0.01

        when:
        connection.setRegister(0x25, 42)

        then:
        full.fifoLevel() == 42
    }

    def "setThreshold packs 15-bit THS_P and enables PHE/PLE"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)
        connection.setRegister(0x0B, 0x00)

        when:
        full.setThreshold(1020.0, true, true) // Mode 1: 1020*16=16320=0x3FC0

        then:
        lastWriteTo(connection, 0x0C) == 0xC0
        lastWriteTo(connection, 0x0D) == 0x3F
        lastWriteTo(connection, 0x0B) == 0x03
    }

    def "chipId reads WHO_AM_I"() {
        given:
        def connection = newConnection()
        def full = new Lps28dfwFull(connection)

        expect:
        full.chipId() == 0xB4
    }
}
