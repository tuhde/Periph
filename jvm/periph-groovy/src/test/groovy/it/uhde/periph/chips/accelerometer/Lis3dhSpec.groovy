package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class Lis3dhSpec extends Specification {

    def newConnection() {
        def c = new MockConnection()
        c.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33)
        // Sub-address for the I²C multi-byte auto-increment is reg | 0x80:
        // X = +1.000 g, Y = 0, Z = -0.500 g at 12-bit / ±2 g (1 mg/LSB).
        c.setRegister(Lis3dhMinimal.REG_OUT_X_L | 0x80,
                0x80, 0x3E,
                0x00, 0x00,
                0xC0, 0xE0)
        return c
    }

    def lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes.get(i)
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def readSubAddressUsed(MockConnection connection, int sub) {
        for (def w : connection.writes()) {
            if (w.length == 1 && (w[0] & 0xFF) == sub) return true
        }
        return false
    }

    def writeWithBitSeen(MockConnection connection, int reg, int bit) {
        for (def w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg && (w[1] & bit) != 0) return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Initialization sequence
    // -------------------------------------------------------------------------

    def "init sequence writes the control registers"() {
        given:
        def connection = newConnection()

        when:
        new Lis3dhMinimal(connection)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG2) == 0x00
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3) == 0x00
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x00
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6) == 0x00
        lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL) == 0x00
        lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG) == 0x00
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0x88
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x57
    }

    def "init sequence order is the datasheet order"() {
        given:
        def connection = newConnection()
        def expected = [
                Lis3dhMinimal.REG_CTRL_REG2,
                Lis3dhMinimal.REG_CTRL_REG3,
                Lis3dhMinimal.REG_CTRL_REG5,
                Lis3dhMinimal.REG_CTRL_REG6,
                Lis3dhMinimal.REG_FIFO_CTRL,
                Lis3dhMinimal.REG_TEMP_CFG,
                Lis3dhMinimal.REG_CTRL_REG4,
                Lis3dhMinimal.REG_CTRL_REG1]

        when:
        new Lis3dhMinimal(connection)
        def seen = []
        for (def w : connection.writes()) {
            if (w.length != 2) continue
            seen.add(w[0] & 0xFF)
        }

        then:
        seen == expected
    }

    def "init does not touch the reserved registers"() {
        given:
        def connection = newConnection()

        when:
        new Lis3dhMinimal(connection)

        then:
        for (def w : connection.writes()) {
            if (w.length != 2) continue
            def reg = w[0] & 0xFF
            assert !(reg <= 0x06)
            assert reg != 0x0E
            assert !(reg >= 0x10 && reg <= 0x1D)
            assert reg != Lis3dhMinimal.REG_CTRL_REG0
        }
    }

    def "construction with a wrong WHO_AM_I throws"() {
        given:
        def connection = new MockConnection()
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x44)

        when:
        new Lis3dhMinimal(connection)

        then:
        thrown(IOException)
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    def "read uses the auto-increment sub-address"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhMinimal(connection)

        when:
        chip.read()

        then:
        readSubAddressUsed(connection, Lis3dhMinimal.REG_OUT_X_L | 0x80)
    }

    def "read scales to grams"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhMinimal(connection)

        when:
        def xyz = chip.read()

        then:
        xyz[0] == 1.0d
        xyz[1] == 0.0d
        xyz[2] == -0.5d
    }

    def "read raw returns signed counts"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        def raw = chip.readRaw()

        then:
        raw[0] == 1000
        raw[1] == 0
        raw[2] == -500
    }

    def "status and new data"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_STATUS, 0x0F)

        expect:
        chip.newDataAvailable()
        chip.readStatus() == 0x0F
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    def "set range updates the FS field"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setRange(8)

        then:
        // (0x88 & ~0x30) | (2 << 4) = 0xA8
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0xA8
    }

    def "set range rejects unknown ranges"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setRange(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "set data rate updates the ODR field"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setDataRate(50)

        then:
        // (0x57 & 0x0F) | 0x40 = 0x47
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x47
    }

    def "set data rate validates the mode combination"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setDataRate(1620)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setDataRate(5376)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setDataRate(999)

        then:
        thrown(IllegalArgumentException)
    }

    def "set mode updates LPen and HR"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setMode(Lis3dhMinimal.MODE_LOW_POWER)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x5F
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0x80

        when:
        chip.setMode(Lis3dhMinimal.MODE_NORMAL)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x57
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0x80

        when:
        chip.setMode(Lis3dhMinimal.MODE_HIGH_RESOLUTION)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x57
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0x88
    }

    def "set mode rejects unknown modes"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setMode(7)

        then:
        thrown(IllegalArgumentException)
    }

    def "low-power mode shifts eight bits"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        chip.setMode(Lis3dhMinimal.MODE_LOW_POWER)
        // 8-bit: 0x1000 >> 8 = 16 counts; ±2 g low power is 16 mg/LSB.
        connection.setRegister(Lis3dhMinimal.REG_OUT_X_L | 0x80,
                0x00, 0x10,
                0x00, 0x00,
                0x00, 0x00)

        when:
        def xyz = chip.read()

        then:
        xyz[0] == 16 * 16 / 1000.0d
    }

    def "set axes updates the axis bits"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setAxes(true, false, true)

        then:
        // (0x57 & 0xF8) | 0x05 = 0x55
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x55
    }

    def "power down clears the ODR field"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.powerDown()

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x07
    }

    // -------------------------------------------------------------------------
    // Temperature and auxiliary ADC
    // -------------------------------------------------------------------------

    def "temperature enables the ADC and returns a relative delta"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_OUT_ADC3_H, 0xF0)

        when:
        def temp = chip.readTemperatureDelta()

        then:
        temp == -16
        // ADC_EN + TEMP_EN
        lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG) == 0xC0
    }

    def "ADC reads the auxiliary channels"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        // Channel 1: raw16 = 0x0400 = 1024 -> 16 counts at 10-bit.
        connection.setRegister(0x08 | 0x80, 0x00, 0x04)

        when:
        def volts = chip.readAdc(1)

        then:
        volts == 1.200d + 16 * (0.400d / 512.0d)
    }

    def "ADC channel 3 is rejected while the temperature sensor is on"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_TEMP_CFG, 0x40)

        when:
        chip.readAdc(3)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.readAdc(4)

        then:
        thrown(IllegalArgumentException)
    }

    def "enable ADC toggles the ADC enable bit"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.enableAdc(true)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG) == 0x80

        when:
        chip.enableAdc(false)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_TEMP_CFG) == 0x00
    }

    // -------------------------------------------------------------------------
    // High-pass filter
    // -------------------------------------------------------------------------

    def "high-pass configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setHighPass(3, 2, true, true, true, true)

        then:
        // (3 << 6) | (2 << 4) | FDS | HPCLICK | HP_IA2 | HP_IA1 = 0xEF
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG2) == 0xEF
    }

    def "high-pass rejects invalid codes"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setHighPass(4, 0, false, false, false, false)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setHighPass(0, 4, false, false, false, false)

        then:
        thrown(IllegalArgumentException)
    }

    def "reference register"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_REFERENCE, 0x42)

        expect:
        chip.resetHighPass() == 0x42

        when:
        chip.setReference(0x7F)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_REFERENCE) == 0x7F

        when:
        chip.setReference(256)

        then:
        thrown(IllegalArgumentException)
    }

    // -------------------------------------------------------------------------
    // FIFO
    // -------------------------------------------------------------------------

    def "FIFO configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setFifo(2, 16, false)

        then:
        // (2 << 6) | 16 = 0x90
        lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL) == 0x90
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x40

        when:
        chip.setFifo(0, 0, true)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_FIFO_CTRL) == 0x20
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x00
    }

    def "FIFO level and status"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0xE3)

        when:
        def status = chip.readFifoStatus()

        then:
        chip.fifoLevel() == 3
        status.watermark
        status.overrun
        status.empty
        status.level == 3
    }

    def "read FIFO drains the sample sets"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0x03)

        when:
        def samples = chip.readFifo()

        then:
        samples.size() == 3
        samples[0][0] == 1.0d
        samples[2][2] == -0.5d
    }

    def "read FIFO never reads more than the FIFO holds"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0x02)

        expect:
        chip.readFifo(10).size() == 2
    }

    // -------------------------------------------------------------------------
    // Inertial interrupt generators
    // -------------------------------------------------------------------------

    def "motion interrupt configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setMotionInterrupt(1, 0x35, 0.5d, 10.0d, true)

        then:
        // AOI + 0x35
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG) == 0xB5
        // round(0.5 g * 1000 / 16 mg) = 31
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_THS) == 31
        // round(10 ms * 100 Hz / 1000) = 1
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_DUR) == 1
    }

    def "motion interrupt uses the generator 2 registers"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setMotionInterrupt(2, 0x01, 0.16d, 0.0d, false)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_INT2_CFG) == 0x01
        // round(0.16 g * 1000 / 16 mg) = 10
        lastWriteTo(connection, Lis3dhMinimal.REG_INT2_THS) == 10

        when:
        chip.setMotionInterrupt(3, 0x01, 0.16d, 0.0d, false)

        then:
        thrown(IllegalArgumentException)
    }

    def "free-fall configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setFreeFall(1, 0.35d, 60.0d)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG) == 0x95
        // round(0.35 g * 1000 / 16 mg) = 22
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_THS) == 22
        // round(60 ms * 100 Hz / 1000) = 6
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_DUR) == 6
    }

    def "orientation configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setOrientation(1, false, false)

        then:
        // 6D movement recognition with all six axis zones.
        lastWriteTo(connection, Lis3dhMinimal.REG_INT1_CFG) == 0x7F
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x00

        when:
        chip.setOrientation(2, true, true)

        then:
        // 6D position recognition with the four 4D zones.
        lastWriteTo(connection, Lis3dhMinimal.REG_INT2_CFG) == 0xCF
        // D4D_INT2
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x01
    }

    def "latch configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setLatch(1, true)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x08

        when:
        chip.setLatch(2, true)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG5) == 0x0A
    }

    def "read interrupt source returns the source byte"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_INT1_SRC, 0x60)
        connection.setRegister(Lis3dhMinimal.REG_INT2_SRC, 0x40)

        expect:
        chip.readInterruptSource(1) == 0x60
        chip.readInterruptSource(2) == 0x40

        when:
        chip.readInterruptSource(3)

        then:
        thrown(IllegalArgumentException)
    }

    // -------------------------------------------------------------------------
    // Click detection
    // -------------------------------------------------------------------------

    def "click configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setClick(0, 0x04, 0.8d, 20.0d, 40.0d, 200.0d, true)

        then:
        // Double click on Z: ZD is bit 5.
        lastWriteTo(connection, Lis3dhMinimal.REG_CLICK_CFG) == 0x20
        // round(0.8 g * 1000 / 16 mg) = 50, latched -> 0x80
        lastWriteTo(connection, Lis3dhMinimal.REG_CLICK_THS) == 0xB2
        lastWriteTo(connection, Lis3dhMinimal.REG_TIME_LIMIT) == 2
        lastWriteTo(connection, Lis3dhMinimal.REG_TIME_LATENCY) == 4
        lastWriteTo(connection, Lis3dhMinimal.REG_TIME_WINDOW) == 20
    }

    def "click source is read back"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_CLICK_SRC, 0x48)

        expect:
        chip.readClickSource() == 0x48
    }

    // -------------------------------------------------------------------------
    // Activity function
    // -------------------------------------------------------------------------

    def "activity configuration"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setActivity(0.5d, 100.0d)

        then:
        // round(0.5 g * 1000 / 16 mg) = 31
        lastWriteTo(connection, Lis3dhMinimal.REG_ACT_THS) == 31
        // round((100 ms * 100 Hz / 1000 - 1) / 8) = 1
        lastWriteTo(connection, Lis3dhMinimal.REG_ACT_DUR) == 1
    }

    def "activity rejects a zero threshold"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setActivity(0.001d, 100.0d)

        then:
        thrown(IllegalArgumentException)
    }

    // -------------------------------------------------------------------------
    // Interrupt routing and polling
    // -------------------------------------------------------------------------

    def "interrupt polarity"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setInterruptPolarity(true)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6) == 0x02

        when:
        chip.setInterruptPolarity(false)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6) == 0x00
    }

    def "enable interrupt routes to the right register"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3) == 0x80

        when:
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 2)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6) == 0x80

        when:
        // INT1-only sources ignore the pin.
        chip.enableInterrupt(Lis3dhSource.SOURCE_DATA_READY, 2)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3) == 0x90

        when:
        // INT2-only sources ignore the pin.
        chip.enableInterrupt(Lis3dhSource.SOURCE_ACTIVITY, 1)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG6) == 0x88
    }

    def "disable interrupt clears the routing bit"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)

        when:
        chip.disableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG3) == 0x00
    }

    def "enable interrupt rejects unknown sources and pins"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.enableInterrupt(0x8000, 1)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 3)

        then:
        thrown(IllegalArgumentException)
    }

    def "poll interrupt combines the enabled sources"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        connection.setRegister(Lis3dhMinimal.REG_STATUS, 0x08)
        connection.setRegister(Lis3dhMinimal.REG_INT1_SRC, 0x40)
        connection.setRegister(Lis3dhMinimal.REG_CLICK_SRC, 0x40)

        expect:
        // Nothing enabled -> nothing reported.
        chip.pollInterrupt() == 0

        when:
        chip.enableInterrupt(Lis3dhSource.SOURCE_DATA_READY, 1)
        chip.enableInterrupt(Lis3dhSource.SOURCE_IA1, 1)
        chip.enableInterrupt(Lis3dhSource.SOURCE_CLICK, 1)
        def status = chip.pollInterrupt()

        then:
        status == (Lis3dhSource.SOURCE_DATA_READY |
                Lis3dhSource.SOURCE_IA1 |
                Lis3dhSource.SOURCE_CLICK)
    }

    def "poll interrupt reports the FIFO flags"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)
        chip.enableInterrupt(Lis3dhSource.SOURCE_WATERMARK, 1)
        chip.enableInterrupt(Lis3dhSource.SOURCE_OVERRUN, 1)
        connection.setRegister(Lis3dhMinimal.REG_FIFO_SRC, 0xC0)

        expect:
        chip.pollInterrupt() == (Lis3dhSource.SOURCE_WATERMARK | Lis3dhSource.SOURCE_OVERRUN)
    }

    // -------------------------------------------------------------------------
    // System functions
    // -------------------------------------------------------------------------

    def "SDO pull-up"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.setSdoPullup(false)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG0) == 0x90

        when:
        chip.setSdoPullup(true)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG0) == 0x10
    }

    def "reboot sets BOOT and re-runs the init sequence"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        when:
        chip.reboot()

        then:
        writeWithBitSeen(connection, Lis3dhMinimal.REG_CTRL_REG5, 0x80)
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x57
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0x88
    }

    def "self test detects the expected delta"() {
        given:
        def connection = new SelfTestConnection()
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33)
        def chip = new Lis3dhFull(connection)

        expect:
        chip.selfTest(1)
    }

    def "self test fails without a delta"() {
        given:
        def connection = newConnection()
        def chip = new Lis3dhFull(connection)

        expect:
        !chip.selfTest(1)

        when:
        chip.selfTest(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "self test restores the configuration"() {
        given:
        def connection = new SelfTestConnection()
        connection.setRegister(Lis3dhMinimal.REG_WHO_AM_I, 0x33)
        def chip = new Lis3dhFull(connection)

        when:
        chip.selfTest(1)

        then:
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG1) == 0x57
        lastWriteTo(connection, Lis3dhMinimal.REG_CTRL_REG4) == 0x88
    }

    /**
     * Mock that reports a self-test step: the acceleration jumps by 100
     * counts as soon as {@code CTRL_REG4.ST} is set, so the self-test delta
     * lands inside the datasheet's 17..360 LSb window.
     */
    static class SelfTestConnection extends MockConnection {

        @Override
        byte[] writeRead(byte[] data, int n) throws IOException {
            int reg = data[0] & 0xFF
            if (n == 6 && (reg & 0x7F) == Lis3dhMinimal.REG_OUT_X_L) {
                int ctrl4 = registers().getOrDefault(Lis3dhMinimal.REG_CTRL_REG4, 0)
                byte[] out = new byte[6]
                if ((ctrl4 & 0x06) != 0) {
                    // 100 counts at 10-bit: raw16 = 100 << 6 = 0x1900.
                    out[1] = (byte) 0x19
                    out[3] = (byte) 0x19
                    out[5] = (byte) 0x19
                }
                writes().add(data.clone())
                return out
            }
            return super.writeRead(data, n)
        }
    }
}
