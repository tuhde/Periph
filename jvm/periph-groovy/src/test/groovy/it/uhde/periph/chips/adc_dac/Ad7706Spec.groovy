package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ad7706Spec extends Specification {

    def "init and minimal reads"() {
        given:
        def connection = new MockConnection()
        def sensor = new Ad7706Minimal(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)

        expect:
        connection.writes()[0] == [0x20, 0x0C] as byte[]
        connection.writes()[1] == [0x10, 0x40] as byte[]
        connection.writes()[2] == [0x08] as byte[]

        when:
        connection.setRegister(0x38, 0xC0, 0x00)

        then:
        sensor.readRaw() == 0xC000
        Math.abs(sensor.readVoltage() - 1.25f) < 1e-6f
    }

    def "three channels have independent state"() {
        // Regression tests for driver bugs found while writing this test:
        // configure() only updated the shared gain/bipolar/buffered fields for
        // channel 1, and configureClock() was hardcoded to always write
        // Channel 1's Clock Register.
        given:
        def connection = new MockConnection()
        def full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)

        when:
        full.configure(2, 4, false, true, 250)
        def n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x21, 0x0E] as byte[]
        connection.writes()[n - 1] == [0x11, 0x16] as byte[]

        when:
        full.configure(3, 8, true, false, 500)
        n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x23, 0x0F] as byte[]
        connection.writes()[n - 1] == [0x13, 0x18] as byte[]

        when:
        connection.setRegister(0x39, 0x80, 0x00)

        then:
        Math.abs(full.readVoltage(2) - 0.3125f) < 1e-6f

        when:
        connection.setRegister(0x3B, 0xE0, 0x00)

        then:
        Math.abs(full.readVoltage(3) - 0.234375f) < 1e-6f

        when:
        connection.setRegister(0x38, 0xC0, 0x00)

        then:
        Math.abs(full.readVoltage(1) - 1.25f) < 1e-6f

        when:
        full.configure(4, 1, true, false, 50)

        then:
        thrown(IllegalArgumentException)
    }

    def "calibration uses the configured channel's own state"() {
        given:
        def connection = new MockConnection()
        def full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)
        full.configure(3, 8, true, false, 500)

        when:
        full.selfCalibrate(3)
        def n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x13, 0x58] as byte[]
    }

    def "calibration registers and power control"() {
        given:
        def connection = new MockConnection()
        def full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)

        when:
        connection.setRegister(0x6B, 0x12, 0x34, 0x56)

        then:
        full.getOffsetCalibration(3) == 0x123456

        when:
        full.setOffsetCalibration(0xABCDEF, 3)

        then:
        connection.writes().last() == [0x63, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF] as byte[]

        when:
        full.standby()

        then:
        connection.writes().last() == [0x04] as byte[]

        when:
        full.wakeup()
        def n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x00] as byte[]
        connection.writes()[n - 1] == [0x08] as byte[]
    }

    def "reset"() {
        given:
        def connection = new MockConnection()
        def full = new Ad7706Full(connection, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ)

        when:
        full.reset()

        then:
        thrown(IllegalStateException)

        when:
        def calls = []
        Ad7706ResetPin pin = { boolean high -> calls << high } as Ad7706ResetPin
        def connection2 = new MockConnection()
        def withReset = new Ad7706Full(connection2, 2.5f, Ad7706Minimal.MCLK_4_9152MHZ, pin)
        withReset.reset()

        then:
        calls == [false, true]
    }
}
