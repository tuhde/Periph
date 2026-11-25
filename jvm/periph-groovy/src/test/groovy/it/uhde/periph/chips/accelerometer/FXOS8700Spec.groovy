package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class FXOS8700Spec extends Specification {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection()
        c.setRegister(0x0D, 0xC7)
        return c
    }

    /** Data bytes of the last write to reg, or null. */
    private static byte[] lastWrite(MockConnection c, int reg) {
        byte[] out = null
        for (byte[] w : c.writes()) {
            if (w.length >= 2 && (w[0] & 0xFF) == reg) {
                out = Arrays.copyOfRange(w, 1, w.length)
            }
        }
        return out
    }

    private static int lastByte(MockConnection c, int reg) {
        byte[] v = lastWrite(c, reg)
        return v == null ? -1 : v[0] & 0xFF
    }

    private static boolean wasRead(MockConnection c, int reg) {
        return c.writes().any { byte[] w -> w.length == 1 && (w[0] & 0xFF) == reg }
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1e-9
    }

    /** Clears M_CTRL_REG1.m_ost once read, like the hardware. */
    static class OneShotMock extends MockConnection {
        @Override
        byte[] writeRead(byte[] data, int n) {
            byte[] out = super.writeRead(data, n)
            if ((data[0] & 0xFF) == 0x5B) {
                setRegister(0x5B, registers().getOrDefault(0x5B, 0) & ~0x20)
            }
            return out
        }
    }

    /** Returns shifted accelerometer data while CTRL_REG2.st is set. */
    static class SelfTestMock extends MockConnection {
        private final int[] delta

        SelfTestMock(int[] delta) {
            this.delta = delta
        }

        @Override
        byte[] writeRead(byte[] data, int n) {
            byte[] out = super.writeRead(data, n)
            if ((data[0] & 0xFF) == 0x01 && n == 6 && (registers().getOrDefault(0x2B, 0) & 0x80) != 0) {
                for (int axis = 0; axis < 3; axis++) {
                    int raw = (delta[axis] << 2) & 0xFFFF
                    out[2 * axis] = (byte) (raw >> 8)
                    out[2 * axis + 1] = (byte) raw
                }
            }
            return out
        }
    }

    def "initialization sequence"() {
        given:
        def c = newConnection()

        when:
        new FXOS8700Full(c)
        def seq = c.writes().findAll { it.length == 2 }.collect { [it[0] & 0xFF, it[1] & 0xFF] }

        then:
        seq == [[0x2B, 0x40], [0x2A, 0x00], [0x5B, 0x1F], [0x5C, 0x20], [0x0E, 0x00], [0x2B, 0x00], [0x2A, 0x11]]
    }

    def "WHO_AM_I is verified"() {
        given:
        def pre = new MockConnection()
        pre.setRegister(0x0D, 0xC4)
        def bad = new MockConnection()
        bad.setRegister(0x0D, 0x1A)

        when:
        new FXOS8700Minimal(pre)

        then:
        noExceptionThrown()

        when:
        new FXOS8700Minimal(bad)

        then:
        thrown(IOException)
        lastByte(bad, 0x2A) == -1
    }

    def "a NACK on the reset write is tolerated"() {
        given:
        def c = newConnection()
        c.failNextWrites(1, 1)

        when:
        new FXOS8700Minimal(c)

        then:
        noExceptionThrown()
    }

    def "measurements and conversions"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)
        c.setRegister(0x01, 0x40, 0x00, 0xC0, 0x00, 0x00, 0x04)
        c.setRegister(0x07, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)
        c.setRegister(0x33, 0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4)

        expect:
        close(chip.readAccel()[0], 1.0)
        close(chip.readAccel()[1], -1.0)
        close(chip.readAccel()[2], 1 / 4096.0d)
        Arrays.equals(chip.readRawAccel(), [4096, -4096, 1] as int[])
        close(chip.readMag()[0], 10.0)
        close(chip.readMag()[1], -10.0)
        close(chip.readMag()[2], 50.0)
        Arrays.equals(chip.readRawMag(), [100, -100, 500] as int[])
        close(chip.readHybrid()[0], 1.0)
        close(chip.readHybrid()[5], 50.0)

        when:
        c.setRegister(0x0B, 0)
        chip.setRange(4)

        then:
        close(chip.readAccel()[0], 2.0)

        when:
        chip.setRange(8)

        then:
        close(chip.readAccel()[0], 4.0)
        lastByte(c, 0x0E) == 0x02

        when:
        chip.setRange(3)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setLowNoise(true)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setRegister(0x51, 0xFB)

        then:
        close(chip.readTemperature(), -5 * 0.96d)

        when:
        chip.setMode(0)
        chip.readTemperature()

        then:
        thrown(IllegalStateException)

        when:
        chip.readHybrid()

        then:
        thrown(IllegalStateException)
    }

    def "data rate changes go through STANDBY"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)
        int before = c.writes().size()

        when:
        chip.setDataRate(100.0d)
        def w = c.writes().subList(before, c.writes().size())
                .findAll { it.length == 2 && (it[0] & 0xFF) == 0x2A }.collect { it[1] & 0xFF }

        then:
        w == [0x10, 0x18, 0x19]

        when:
        chip.setDataRate(77.0d)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setDataRate(1.56d)

        then:
        ((lastByte(c, 0x2A) >> 3) & 7) == 7
    }

    def "configuration registers"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)

        when:
        chip.setLowNoise(true)

        then:
        (lastByte(c, 0x2A) & 0x05) == 0x05

        when:
        chip.setResolution(8)
        c.setRegister(0x01, 0x40)
        c.setRegister(0x03, 0xC0)
        c.setRegister(0x05, 0x20)

        then:
        (lastByte(c, 0x2A) & 0x02) == 0x02
        close(chip.readAccel()[0], 1.0)

        when:
        chip.setResolution(14)
        chip.setAccelOversampling(2, 3)
        chip.setMagOversampling(5, 2)
        chip.setHighPassOutput(true, 2)

        then:
        (lastByte(c, 0x2B) & 0x1B) == 0x1A
        (lastByte(c, 0x5B) & 0x1C) == (5 << 2)
        (lastByte(c, 0x5D) & 0x70) == (2 << 4)
        (lastByte(c, 0x0E) & 0x10) == 0x10
        (lastByte(c, 0x0F) & 0x03) == 2
    }

    def "offsets"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)

        when:
        chip.setAccelOffset(0.06d, -0.06d, 1.0d)

        then:
        Arrays.equals(lastWrite(c, 0x2F), [30, 0xE2, 127] as byte[])
        close(chip.getAccelOffset()[0], 0.06)
        close(chip.getAccelOffset()[2], 0.254)

        when:
        chip.setMagOffset(60.0d, -60.0d, 0.0d)

        then:
        Arrays.equals(lastWrite(c, 0x3F), [0x04, 0xB0, 0xFB, 0x50, 0x00, 0x00] as byte[])
        close(chip.getMagOffset()[0], 60.0)
        close(chip.getMagOffset()[1], -60.0)
    }

    def "magnetometer helpers"() {
        given:
        def c = new OneShotMock()
        c.setRegister(0x0D, 0xC7)
        def chip = new FXOS8700Full(c)

        when:
        chip.setMagAutoCalibration(true)

        then:
        (lastByte(c, 0x5B) & 0x80) == 0x80

        when:
        chip.setMagAutoCalibration(false)
        chip.resetMagMinMax()

        then:
        (lastByte(c, 0x5B) & 0x80) == 0
        (lastByte(c, 0x5C) & 0x04) == 0x04

        when:
        chip.triggerMagReset()
        chip.setMagReset(2)

        then:
        (lastByte(c, 0x5B) & 0x40) == 0x40
        (lastByte(c, 0x5C) & 0x03) == 2

        when:
        c.setRegister(0x4B, 0xFF, 0x9C)
        c.setRegister(0x45, 0x00, 0x64)
        double[] mm = chip.readMagMinMax()

        then:
        close(mm[0], -10.0)
        close(mm[3], 10.0)
        chip.measureMagOnce().length == 3
    }

    def "tilt-compensated heading"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)
        chip.setMode(0)
        c.setRegister(0x01, 0, 0, 0, 0, 0x40, 0)
        c.setRegister(0x33, 0x01, 0xF4, 0, 0, 0, 0)

        expect:
        Math.abs(chip.heading(0.0d)) < 1e-6
        Math.abs(chip.heading(10.0d) - 10.0) < 1e-6

        when:
        c.setRegister(0x33, 0x00, 0x00, 0xFE, 0x0C, 0, 0)

        then:
        Math.abs(chip.heading(0.0d) - 90.0) < 1e-6
    }

    def "FIFO configuration and draining"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)
        c.setRegister(0x00, 0x42)
        c.setRegister(0x01, 0x40)

        when:
        chip.setFifo(1, 16)

        then:
        lastByte(c, 0x09) == 0x50

        when:
        int before = c.writes().size()
        chip.setFifo(2, 8)
        def f = c.writes().subList(before, c.writes().size())
                .findAll { it.length == 2 && (it[0] & 0xFF) == 0x09 }.collect { it[1] & 0xFF }

        then:
        f == [0x00, 0x88]
        chip.fifoLevel() == 2
        Arrays.equals(chip.readFifoStatus(), [0, 1, 2] as int[])
        chip.readFifo(0).length == 2
        close(chip.readFifo(0)[0][0], 1.0)

        when:
        chip.setResolution(8)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setFifoTrigger(true, false, false, true)

        then:
        lastByte(c, 0x0A) == 0x24

        when:
        chip.setFifo(4, 0)

        then:
        thrown(IllegalArgumentException)
    }

    def "detector registers"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)

        when:
        chip.setFreefallMotion(true, 7, 0.3d, 60.0d, true, false)

        then:
        lastByte(c, 0x15) == 0xF8
        lastByte(c, 0x17) == 5
        lastByte(c, 0x18) == 6

        when:
        chip.setFreefallMotion(false, 3, 0.2d, 0.0d, false, true)

        then:
        lastByte(c, 0x15) == 0x18
        lastByte(c, 0x17) == 0x83

        when:
        chip.setTransient(6, 0.5d, 40.0d, true, true)

        then:
        lastByte(c, 0x1D) == 0x1D
        lastByte(c, 0x1F) == 8
        lastByte(c, 0x20) == 4

        when:
        chip.setPulse(1, 4, [0.5d, 0.5d, 1.0d] as double[], 30.0d, 100.0d, 300.0d, true, false, true, true)

        then:
        lastByte(c, 0x21) == 0x61
        Arrays.equals(lastWrite(c, 0x23), [8, 8, 16] as byte[])
        lastByte(c, 0x26) == 3
        lastByte(c, 0x27) == 5
        lastByte(c, 0x28) == 15
        (lastByte(c, 0x0F) & 0x30) == 0x10

        when:
        chip.setPulse(1, 0, [0.5d, 0.5d, 0.5d] as double[], 30.0d, 100.0d, 300.0d, false, false, false, false)

        then:
        lastByte(c, 0x26) == 12
        (lastByte(c, 0x0F) & 0x30) == 0x20

        when:
        chip.setOrientation(true, 100.0d, 45, 4, 4, 2)

        then:
        lastByte(c, 0x11) == 0xC0
        lastByte(c, 0x12) == 10
        lastByte(c, 0x14) == 0x84
        lastByte(c, 0x13) == 0x84

        when:
        chip.setOrientation(true, 0.0d, 75, 7, 4, 2)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setOrientation(true, 0.0d, 50, 4, 4, 2)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setRegister(0x10, 0x80 | 0x40 | (3 << 1) | 1)

        then:
        Arrays.equals(chip.readOrientation(), [1, 1, 3, 1] as int[])

        when:
        chip.setAutoSleep(true, 12.5d, 10.0d, FXOS8700Full.SOURCE_FF_MT | FXOS8700Full.SOURCE_PULSE)

        then:
        (lastByte(c, 0x2A) >> 6) == 1
        lastByte(c, 0x29) == 31
        (lastByte(c, 0x2B) & 0x04) == 0x04
        (lastByte(c, 0x2C) & 0x7C) == 0x18

        when:
        chip.setAutoSleep(true, 50.0d, 10.0d, 0x01)

        then:
        thrown(IllegalArgumentException)

        when:
        chip.setFifoGate(true)
        chip.setInterruptPins(true, true)

        then:
        (lastByte(c, 0x2C) & 0x83) == 0x83
    }

    def "interrupt routing and polling"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)

        when:
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT | FXOS8700Full.SOURCE_ORIENTATION, 1)

        then:
        lastByte(c, 0x2D) == 0x14
        lastByte(c, 0x2E) == 0x14

        when:
        chip.enableInterrupt(FXOS8700Full.SOURCE_FF_MT, 2)
        chip.disableInterrupt(FXOS8700Full.SOURCE_ORIENTATION)

        then:
        lastByte(c, 0x2E) == 0x10
        lastByte(c, 0x2D) == 0x04

        when:
        chip.enableInterrupt(1, 3)

        then:
        thrown(IllegalArgumentException)

        when:
        c.setRegister(0x0C, 0x14)

        then:
        chip.pollInterrupt() == 0x14
        wasRead(c, 0x16)
        wasRead(c, 0x10)
    }

    def "raw registers, status helpers and reset"() {
        given:
        def c = newConnection()
        def chip = new FXOS8700Full(c)

        when:
        chip.writeRegister(0x5F, [1, 2] as byte[])
        c.setRegister(0x00, 0x08)
        c.setRegister(0x0B, 0x02)

        then:
        Arrays.equals(chip.readRegister(0x5F, 2), [1, 2] as byte[])
        chip.newAccelData()
        !chip.newMagData()
        chip.readSystemMode() == 2

        when:
        c.setRegister(0x0B, 0x00)
        chip.reset()
        chip.readHybrid()

        then:
        thrown(IllegalStateException)

        when:
        chip.init()

        then:
        chip.readHybrid().length == 6
    }

    def "self-test"() {
        given:
        def c = new SelfTestMock([200, 300, 1300] as int[])
        c.setRegister(0x0D, 0xC7)
        def weak = new SelfTestMock([10, 10, 10] as int[])
        weak.setRegister(0x0D, 0xC7)

        expect:
        Arrays.equals(new FXOS8700Full(c).selfTest(), [1, 200, 300, 1300] as int[])
        lastByte(c, 0x2A) == 0x11
        new FXOS8700Full(weak).selfTest()[0] == 0
    }
}
