package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Adxl345Spec extends Specification {

    static MockConnection newConnection() {
        def c = new MockConnection()
        c.setRegister(Adxl345Minimal.REG_DEVID, Adxl345Minimal.DEVID_VALUE)
        c.setRegister(Adxl345Minimal.REG_DATAX0, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00) // x=1,y=2,z=3
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

    def "construction and read"() {
        given:
        def connection = newConnection()

        when:
        def accel = new Adxl345Minimal(connection)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) == 0x08
        lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE) == 0x0A
        lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) == 0x08

        when:
        double[] xyz = accel.read()

        then:
        Math.abs(xyz[0] - 0.0039d) < 1e-9
        Math.abs(xyz[1] - 0.0078d) < 1e-9
        Math.abs(xyz[2] - 0.0117d) < 1e-9
    }

    def "construction with bad DEVID throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Adxl345Minimal.REG_DEVID, 0x00)

        when:
        new Adxl345Minimal(connection)

        then:
        thrown(IOException)
    }

    def "range, data rate, low power, offset"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)

        when:
        full.setRange(4)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) == (0x08 | 0x01)

        when:
        connection.setRegister(Adxl345Minimal.REG_BW_RATE, 0x0A)
        full.setDataRate(100.0d)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE) == 0x0A

        when:
        full.setLowPower(true)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE) & 0x10) == 0x10

        when:
        full.setOffset(0.5d, -0.5d, 0.0d)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_OFSX) == 32
        lastWriteTo(connection, Adxl345Minimal.REG_OFSY) == 0xE0
        lastWriteTo(connection, Adxl345Minimal.REG_OFSZ) == 0
    }

    // Regression: RATE_CODES was declared with integer rates, truncating
    // 12.5 Hz and 6.25 Hz to 12 and 6 -- close enough to tip the "nearest
    // rate" vote near that boundary. At 9.0 Hz the true nearest is 6.25 Hz
    // (diff 2.75) over 12.5 Hz (diff 3.5), but with truncated values both
    // differences round to 3, so the tie (kept-first) wrongly picked 12.5
    // Hz's code (0x07) instead of 6.25 Hz's (0x06).
    def "setDataRate nearest-selection uses fractional rates"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)
        connection.setRegister(Adxl345Minimal.REG_BW_RATE, 0x0A)

        when:
        full.setDataRate(9.0d)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_BW_RATE) & 0x0F) == 0x06
    }

    def "tap and free-fall round to nearest LSB"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)
        connection.setRegister(Adxl345Minimal.REG_INT_ENABLE, 0x00)
        connection.setRegister(Adxl345Minimal.REG_INT_MAP, 0x00)

        when:
        full.setTapDetection(0.5d, 10.0d, 0x07, false)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_THRESH_TAP) == 8
        (lastWriteTo(connection, Adxl345Minimal.REG_INT_ENABLE) & Adxl345Full.INT_SINGLE_TAP) != 0

        when: "0.3g / 62.5mg = 4.8 -> rounds to 5"
        full.setFreeFall(0.3d, 100.0d)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_THRESH_FF) == 5
        lastWriteTo(connection, Adxl345Minimal.REG_TIME_FF) == 20
    }

    def "activity and inactivity"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)
        connection.setRegister(Adxl345Minimal.REG_ACT_INACT_CTL, 0x00)
        connection.setRegister(Adxl345Minimal.REG_INT_ENABLE, 0x00)
        connection.setRegister(Adxl345Minimal.REG_INT_MAP, 0x00)

        when:
        full.setActivity(0.5d, 0x70, true)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_ACT_INACT_CTL) == 0xF0

        when: "time_sec=2.7 -> rounds to 3"
        full.setInactivity(0.5d, 2.7d, 0x07, false)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_TIME_INACT) == 3
        lastWriteTo(connection, Adxl345Minimal.REG_ACT_INACT_CTL) == 0xF7
    }

    def "interrupt routing and source"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)
        connection.setRegister(Adxl345Minimal.REG_INT_ENABLE, 0x00)
        connection.setRegister(Adxl345Minimal.REG_INT_MAP, 0x00)

        when:
        full.setInterrupt(Adxl345Full.INT_WATERMARK, true, 2)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_INT_MAP) & Adxl345Full.INT_WATERMARK) != 0

        when:
        connection.setRegister(Adxl345Minimal.REG_INT_SOURCE, 0x44)

        then:
        full.readInterruptSource() == 0x44
    }

    def "FIFO"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)

        when:
        full.setFifoMode(Adxl345Full.FIFO_STREAM, 16)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_FIFO_CTL) == (Adxl345Full.FIFO_STREAM | 16)

        when:
        connection.setRegister(Adxl345Minimal.REG_FIFO_STATUS, 3)

        then:
        full.fifoCount() == 3

        when:
        double[][] samples = full.readFifo(4)

        then:
        samples.length == 3
        Math.abs(samples[0][0] - 0.0039d) < 1e-9

        when:
        double[][] truncated = full.readFifo(2)

        then:
        truncated.length == 2
    }

    def "sleep, link, auto-sleep, self-test"() {
        given:
        def connection = newConnection()
        def full = new Adxl345Full(connection)

        when:
        full.setSleep(true, 8)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x04) == 0x04

        when:
        full.setSleep(false, 8)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x04) == 0x00

        when:
        full.setLinkMode(true)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x40) == 0x40

        when:
        full.setAutoSleep(true)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_POWER_CTL) & 0x20) == 0x20

        when:
        full.selfTest(true)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) & 0x80) == 0x80

        when:
        full.selfTest(false)

        then:
        (lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) & 0x80) == 0x00
    }

    def "SPI addressing"() {
        given:
        def connection = new MockConnection()
        int spiRd = 0x80
        int spiBurstRd = 0xC0
        connection.setRegister(Adxl345Minimal.REG_DEVID | spiRd, Adxl345Minimal.DEVID_VALUE)
        connection.setRegister(Adxl345Minimal.REG_DATAX0 | spiBurstRd, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03)

        when:
        def accel = new Adxl345Minimal(connection, Adxl345Minimal.BUS_SPI)

        then:
        lastWriteTo(connection, Adxl345Minimal.REG_DATA_FORMAT) == 0x08

        when:
        accel.read()
        boolean sawBurst = connection.writes().any { it.length == 1 && (it[0] & 0xC0) == 0xC0 }

        then:
        sawBurst
    }
}
