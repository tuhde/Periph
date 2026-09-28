package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Ad7705Spec extends Specification {

    def "init and minimal reads"() {
        given: "mclkHz=4915200 -> CLKDIV=1,CLK=1,FS=00 (50 Hz) -> Clock reg = 0x0C " +
               "(matches the spec's own worked example). Setup reg = " +
               "MODE_SELF_CAL|GAIN_1|BIPOLAR|UNBUFFERED|FSYNC_RUN = 0x40"
        def connection = new MockConnection()
        def sensor = new Ad7705Minimal(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)

        expect:
        connection.writes()[0] == [0x20, 0x0C] as byte[]
        connection.writes()[1] == [0x10, 0x40] as byte[]
        connection.writes()[2] == [0x08] as byte[]

        when:
        new Ad7705Minimal(connection, 2.5f, 123)

        then:
        thrown(IllegalArgumentException)

        when: "readRaw / readVoltage: Channel 1, gain 1, bipolar. Data Register CH1 " +
              "read comm = 0x38. code=0xC000 (49152) -> 1.25 V"
        connection.setRegister(0x38, 0xC0, 0x00)

        then:
        sensor.readRaw() == 0xC000
        Math.abs(sensor.readVoltage() - 1.25f) < 1e-6f
    }

    def "configure channel 2 is independent of channel 1"() {
        // Regression test for a driver bug found while writing this test:
        // configure() only updated the shared gain/bipolar/buffered fields when
        // channel==1, so readVoltage(2) silently converted using channel 1's
        // gain/bipolar instead of channel 2's. A second, separate bug:
        // configureClock() was hardcoded to always write Channel 1's Clock
        // Register, even when configuring channel 2.
        given:
        def connection = new MockConnection()
        def full = new Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)

        when: "configure(2, gain=4, bipolar=false, buffered=true, 250 Hz): " +
              "Clock reg CH2 (comm=0x21) -> 0x0E, Setup reg CH2 (comm=0x11) -> 0x16"
        full.configure(2, 4, false, true, 250)
        def n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x21, 0x0E] as byte[]
        connection.writes()[n - 1] == [0x11, 0x16] as byte[]

        when: "Data Register CH2 read comm = 0x39. code=0x8000, gain=4, unipolar -> 0.3125 V"
        connection.setRegister(0x39, 0x80, 0x00)

        then:
        Math.abs(full.readVoltage(2) - 0.3125f) < 1e-6f

        when: "channel 1 was never configured -> still uses the ctor default (gain 1, bipolar)"
        connection.setRegister(0x38, 0xC0, 0x00)

        then:
        Math.abs(full.readVoltage(1) - 1.25f) < 1e-6f

        when:
        full.configure(3, 1, true, false, 50)

        then:
        thrown(IllegalArgumentException)

        when:
        full.configure(1, 3, true, false, 50)

        then:
        thrown(IllegalArgumentException)
    }

    def "calibration uses the configured channel's own state"() {
        given:
        def connection = new MockConnection()
        def full = new Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)
        full.configure(2, 4, false, true, 250)

        when: "selfCalibrate(2): setup = MODE_SELF_CAL|GAIN_4|UNIPOLAR|BUFFERED = 0x56 " +
              "-- channel 2's configured state, not channel 1's defaults"
        full.selfCalibrate(2)
        def n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x11, 0x56] as byte[]

        when: "systemCalibrateZero(1): channel 1's untouched defaults"
        full.systemCalibrateZero(1)
        n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x10, (byte) 0x80] as byte[] // MODE_ZERO_SYS|GAIN_1|BIPOLAR

        when:
        full.systemCalibrateFull(1)
        n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x10, (byte) 0xC0] as byte[] // MODE_FULL_SYS|GAIN_1|BIPOLAR
    }

    def "calibration registers and power control"() {
        given:
        def connection = new MockConnection()
        def full = new Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)

        when: "Zero-Scale reg CH1 read comm = 0x68"
        connection.setRegister(0x68, 0x12, 0x34, 0x56)

        then:
        full.getOffsetCalibration(1) == 0x123456

        when:
        full.setOffsetCalibration(0xABCDEF, 1)

        then:
        connection.writes().last() == [0x60, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF] as byte[]

        when: "Full-Scale reg CH1 read comm = 0x78"
        connection.setRegister(0x78, 0x01, 0x02, 0x03)

        then:
        full.getGainCalibration(1) == 0x010203

        when:
        full.setGainCalibration(0x040506, 1)

        then:
        connection.writes().last() == [0x70, 0x04, 0x05, 0x06] as byte[]

        when: "standby(): comm(COMM,WRITE,CH1)|STBY_SLEEP = 0x04"
        full.standby()

        then:
        connection.writes().last() == [0x04] as byte[]

        when: "wakeup(): comm(COMM,WRITE,CH1)|STBY_RUN = 0x00, then wait_drdy"
        full.wakeup()
        def n = connection.writes().size()

        then:
        connection.writes()[n - 2] == [0x00] as byte[]
        connection.writes()[n - 1] == [0x08] as byte[]
    }

    def "reset"() {
        given:
        def connection = new MockConnection()
        def full = new Ad7705Full(connection, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ)

        when:
        full.reset()

        then:
        thrown(IllegalStateException)

        when: "NOTE: unlike Python/C++/Node.js/Go, Ad7705Minimal's constructor here " +
              "does not accept a resetPin at all, so RESET is never auto-pulsed " +
              "before configuration -- only an explicit reset() call pulses it. " +
              "Left as-is -- a bigger, cross-language structural fix than this " +
              "test suite targets."
        def calls = []
        Ad7705ResetPin pin = { boolean high -> calls << high } as Ad7705ResetPin
        def connection2 = new MockConnection()
        def withReset = new Ad7705Full(connection2, 2.5f, Ad7705Minimal.MCLK_4_9152MHZ, pin)
        withReset.reset()

        then:
        calls == [false, true]
    }
}
