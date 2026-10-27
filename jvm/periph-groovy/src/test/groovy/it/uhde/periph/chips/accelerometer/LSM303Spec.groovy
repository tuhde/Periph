package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntConsumer

class LSM303Spec extends Specification {

    /** Fake INT pin: records the armed handler and trigger, and lets the test fire an edge. */
    static class FakePin implements InputPin {
        EdgeHandler handler
        EdgeTrigger trigger

        @Override
        void onEdge(EdgeHandler h, EdgeTrigger t) {
            handler = h
            trigger = t
        }

        @Override
        void offEdge(EdgeHandler h) {
            if (handler != null && handler.is(h)) handler = null
        }

        @Override
        boolean isHigh() { false }

        @Override
        void close() { }
    }

    /** Accelerometer mock; the burst address is reg | 0x80 (the mock does not auto-increment). */
    static MockConnection accelMock() {
        return new MockConnection()
    }

    /** Magnetometer mock with a valid identity. */
    static MockConnection magMock() {
        def m = new MockConnection()
        m.setRegister(0x0A, 0x48, 0x34, 0x33)
        return m
    }

    static int lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes[i]
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def "init writes the control registers"() {
        given:
        def accel = accelMock()
        def mag = magMock()

        when:
        new LSM303Minimal(accel, mag)

        then:
        lastWriteTo(accel, 0x23) == 0x88   // CTRL_REG4_A
        lastWriteTo(accel, 0x20) == 0x57   // CTRL_REG1_A
        lastWriteTo(accel, 0x24) == 0x00   // CTRL_REG5_A
        lastWriteTo(mag, 0x00) == 0x10     // CRA_REG_M
        lastWriteTo(mag, 0x01) == 0x20     // CRB_REG_M
        lastWriteTo(mag, 0x02) == 0x00     // MR_REG_M out of sleep
    }

    def "magnetometer identity mismatch throws IOException"() {
        given:
        def mag = new MockConnection()
        mag.setRegister(0x0A, 0x48, 0x34, 0x00)

        when:
        new LSM303Minimal(accelMock(), mag)

        then:
        thrown(IOException)
    }

    def "read acceleration uses the burst address and left-justified data"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Minimal(accel, magMock())
        // Burst address 0x28 | 0x80 = 0xA8; LSB first. 0x4000 -> 1024 counts (1 mg/LSB, HR).
        accel.setRegister(0xA8, 0x00, 0x40, 0x00, 0xF0, 0x00, 0x10)

        when:
        double[] g = chip.readAcceleration()

        then:
        Math.abs(g[0] - 1.024d) < 1e-9
        Math.abs(g[1] - (-0.256d)) < 1e-9
        Math.abs(g[2] - 0.256d) < 1e-9
    }

    def "read magnetic reorders X Z Y and uses the Z gain"() {
        given:
        def mag = magMock()
        def chip = new LSM303Minimal(accelMock(), mag)
        // Wire order X, Z, Y, high byte first: X=1100, Z=980, Y=-2200.
        mag.setRegister(0x03, 0x04, 0x4C, 0x03, 0xD4, 0xF7, 0x68)

        when:
        double[] ut = chip.readMagnetic()

        then:
        Math.abs(ut[0] - 100.0d) < 1e-9
        Math.abs(ut[1] - (-200.0d)) < 1e-9
        Math.abs(ut[2] - 100.0d) < 1e-9
    }

    def "full scale and range check"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setRange(4)

        then:
        lastWriteTo(accel, 0x23) == 0x98   // FS=01, HR and BDU kept

        when:
        accel.setRegister(0xA8, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)
        double x = chip.readAcceleration()[0]

        then:
        Math.abs(x - 2.048d) < 1e-9        // HR ±4 g = 2 mg/LSB

        when:
        chip.setRange(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "data rate depends on the mode"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setDataRate(1620)

        then:
        thrown(IllegalArgumentException)   // high-resolution mode

        when:
        chip.setMode(0)
        chip.setDataRate(1620)

        then:
        lastWriteTo(accel, 0x20) == 0x8F   // LPen=1, ODR=1000

        when:
        chip.setDataRate(1344)

        then:
        thrown(IllegalArgumentException)   // 1344 Hz is not valid in low-power mode
    }

    def "power down and axis enable"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setAxes(true, false, true)

        then:
        lastWriteTo(accel, 0x20) == 0x55

        when:
        chip.powerDown()

        then:
        lastWriteTo(accel, 0x20) == 0x05
    }

    def "status and FIFO"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())
        accel.setRegister(0x27, 0x08)

        expect:
        chip.newDataAvailable()
        chip.readStatus() == 0x08

        when:
        chip.setFifo(1, 16, false)

        then:
        lastWriteTo(accel, 0x2E) == 0x50   // FM=01, WTM=16
        lastWriteTo(accel, 0x24) == 0x40   // FIFO_EN set

        when:
        accel.setRegister(0x2F, 0xC5)      // WTM=1, OVRN=1, EMPTY=0, FSS=5

        then:
        chip.readFifoStatus() == [1, 1, 0, 5] as int[]
        chip.fifoLevel() == 5

        when:
        accel.setRegister(0xA8, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00)

        then:
        chip.readFifo(2).length == 2       // requested 2, FSS=5
        chip.readFifo().length == 5        // all unread sets
    }

    def "high-pass writes CTRL_REG2 and reset reads REFERENCE"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setHighPass(1, 2, true, true, false, true)

        then:
        lastWriteTo(accel, 0x21) == 0x6D

        when:
        accel.setRegister(0x26, 0x5A)

        then:
        chip.resetHighPass() == 0x5A
    }

    def "motion interrupt threshold and duration"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setMotionInterrupt(1, 0x3F, 1.0d, 100.0d, true)

        then:
        lastWriteTo(accel, 0x30) == 0xBF   // AOI | all six events
        lastWriteTo(accel, 0x32) == 64     // 1 g * 128 / 2 g
        lastWriteTo(accel, 0x33) == 10     // 100 ms * 100 Hz
    }

    def "free fall uses AND of low X Y Z"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setFreeFall()

        then:
        lastWriteTo(accel, 0x30) == 0x95
        lastWriteTo(accel, 0x32) == 22     // round(0.35 * 128 / 2)
        lastWriteTo(accel, 0x33) == 3      // round(30 * 100 / 1000)
    }

    def "orientation and latch"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setOrientation(2, true, true)

        then:
        lastWriteTo(accel, 0x34) == 0xFF   // position, 6D, all events
        lastWriteTo(accel, 0x24) == 0x01   // D4D_INT2

        when:
        chip.setLatch(1, true)

        then:
        lastWriteTo(accel, 0x24) == 0x09   // LIR_INT1 | D4D_INT2

        when:
        accel.setRegister(0x31, 0x40)

        then:
        chip.readInterruptSource(1) == 0x40
    }

    def "click encodes axis masks"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.setClick(4, 4, 0.8d, 20.0d, 40.0d, 200.0d)

        then:
        lastWriteTo(accel, 0x38) == 0x30   // single Z = bit4, double Z = bit5
        lastWriteTo(accel, 0x3A) == 51
        lastWriteTo(accel, 0x3B) == 2
        lastWriteTo(accel, 0x3C) == 4
        lastWriteTo(accel, 0x3D) == 20

        when:
        chip.setClick(1, 2, 0.5d, 10.0d, 20.0d, 50.0d)

        then:
        lastWriteTo(accel, 0x38) == 0x09   // single X = bit0, double Y = bit3
    }

    def "routing sources to INT1 and INT2"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.enableInterrupt(LSM303Full.SOURCE_IA1, 2)

        then:
        lastWriteTo(accel, 0x25) == 0x40   // I2_INT1

        when:
        chip.disableInterrupt(LSM303Full.SOURCE_IA1, 2)

        then:
        lastWriteTo(accel, 0x25) == 0x00

        when:
        chip.enableInterrupt(LSM303Full.SOURCE_DATA_READY)

        then:
        lastWriteTo(accel, 0x22) == 0x10   // I1_DRDY1

        when:
        chip.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY)

        then:
        thrown(IllegalArgumentException)
    }

    def "poll reports enabled sources and magnetometer DRDY"() {
        given:
        def accel = accelMock()
        def mag = magMock()
        def chip = new LSM303Full(accel, mag)
        chip.enableInterrupt(LSM303Full.SOURCE_DATA_READY)
        chip.enableInterrupt(LSM303Full.SOURCE_IA1)
        accel.setRegister(0x27, 0x08)
        accel.setRegister(0x31, 0x40)

        expect:
        chip.pollInterrupt() == 0x0009

        when:
        mag.setRegister(0x09, 0x01)

        then:
        chip.pollInterrupt() == 0x0089
    }

    def "magnetometer configuration"() {
        given:
        def mag = magMock()
        def chip = new LSM303Full(accelMock(), mag)

        when:
        chip.setMagRange(810)

        then:
        lastWriteTo(mag, 0x01) == 0xE0     // GN=111

        when:
        mag.setRegister(0x03, 0x00, 0xE6, 0x00, 0xCD, 0x00, 0x00)  // X=230, Z=205

        then:
        Math.abs(chip.readMagnetic()[0] - 100.0d) < 1e-9
        Math.abs(chip.readMagnetic()[2] - 100.0d) < 1e-9

        when:
        chip.setMagDataRate(220.0d)

        then:
        lastWriteTo(mag, 0x00) == 0x1C     // DO=111, TEMP_EN still off

        when:
        chip.setMagDataRate(3.7d)

        then:
        thrown(IllegalArgumentException)
    }

    def "magnetometer status and identity"() {
        given:
        def mag = magMock()
        def chip = new LSM303Full(accelMock(), mag)
        mag.setRegister(0x09, 0x03)

        expect:
        chip.magDataReady()
        chip.readMagStatus() == [1, 1] as int[]
        chip.readMagIdentity() == [0x48, 0x34, 0x33] as byte[]
    }

    def "temperature delta enables the sensor on first use"() {
        given:
        def mag = magMock()
        def chip = new LSM303Full(accelMock(), mag)
        mag.setRegister(0x31, 0x01, 0x00)  // +256 raw -> 16 LSB -> 2 degC

        when:
        double dT = chip.readTemperatureDelta()

        then:
        Math.abs(dT - 2.0d) < 1e-9
        lastWriteTo(mag, 0x00) == 0x90     // TEMP_EN set, DO=100 kept

        when:
        mag.setRegister(0x31, 0xFC, 0x00)  // -1024 raw -> -64 LSB -> -8 degC

        then:
        Math.abs(chip.readTemperatureDelta() + 8.0d) < 1e-9
    }

    def "flat heading and wrap"() {
        given:
        def mag = magMock()
        def chip = new LSM303Full(accelMock(), mag)
        mag.setRegister(0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x4C)  // Y=+1100

        when:
        double h = chip.heading(false)

        then:
        Math.abs(h - 90.0d) < 1e-6

        when:
        mag.setRegister(0x03, 0xFB, 0xB4, 0x00, 0x00, 0x00, 0x00)  // X=-1100
        h = chip.heading(false)

        then:
        Math.abs(h - 180.0d) < 1e-6

        when:
        mag.setRegister(0x03, 0x00, 0x00, 0x00, 0x00, 0xFB, 0xB4)  // Y=-1100
        h = chip.heading(false)

        then:
        Math.abs(h - 270.0d) < 1e-6
    }

    def "tilt compensated heading with gravity on Z matches flat"() {
        given:
        def accel = accelMock()
        def mag = magMock()
        def chip = new LSM303Full(accel, mag)
        accel.setRegister(0xA8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40)  // gravity on +Z
        mag.setRegister(0x03, 0x00, 0x00, 0x00, 0x00, 0x04, 0x4C)    // Y=+1100

        expect:
        Math.abs(chip.heading() - 90.0d) < 1e-6
    }

    def "reboot sets the BOOT bit"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())

        when:
        chip.reboot()

        then:
        (lastWriteTo(accel, 0x24) & 0x80) == 0x80
    }

    def "on_interrupt fires on a pin edge and honours polarity"() {
        given:
        def accel = accelMock()
        def chip = new LSM303Full(accel, magMock())
        chip.enableInterrupt(LSM303Full.SOURCE_IA1)
        accel.setRegister(0x31, 0x40)
        def pin = new FakePin()
        def got = new AtomicInteger(-1)

        when:
        chip.onInterrupt({ int v -> got.set(v) } as IntConsumer, 1, pin)
        pin.handler.onEdge()

        then:
        pin.trigger == EdgeTrigger.RISING
        got.get() == LSM303Full.SOURCE_IA1

        when:
        chip.offInterrupt(1)

        then:
        pin.handler == null

        when:
        chip.setInterruptPolarity(true)
        chip.onInterrupt({ int v -> got.set(v) } as IntConsumer, 1, pin)

        then:
        pin.trigger == EdgeTrigger.FALLING

        cleanup:
        chip.offInterrupt()
    }

    def "invalid pins and generators are rejected"() {
        given:
        def chip = new LSM303Full(accelMock(), magMock())

        when:
        chip.readInterruptSource(3)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.onInterrupt({ int v -> } as IntConsumer, 0)

        then:
        thrown(IllegalArgumentException)
    }
}
