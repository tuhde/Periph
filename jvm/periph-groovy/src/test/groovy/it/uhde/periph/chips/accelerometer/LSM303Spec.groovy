package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class LSM303Spec extends Specification {

    /**
     * Preload the accelerometer output registers with 12-bit counts.
     *
     * The high-resolution mode writes the 12-bit value left-justified into the
     * 16-bit register pair.
     */
    def setAccelOutput(MockConnection conn, int x, int y, int z) {
        conn.setRegister(LSM303Minimal.REG_OUT_X_L_A | LSM303Minimal.ACCEL_AUTO_INCREMENT,
                (x << 4) & 0xFF, ((x << 4) >> 8) & 0xFF,
                (y << 4) & 0xFF, ((y << 4) >> 8) & 0xFF,
                (z << 4) & 0xFF, ((z << 4) >> 8) & 0xFF)
    }

    /**
     * Preload the magnetometer output registers with 12-bit counts.
     *
     * The magnetometer writes the 12-bit value right-justified into the 16-bit
     * register pair and sends X, Z, Y on the wire.
     */
    def setMagOutput(MockConnection conn, int x, int y, int z) {
        conn.setRegister(LSM303Minimal.REG_OUT_X_H_M,
                (x >> 8) & 0xFF, x & 0xFF,
                (z >> 8) & 0xFF, z & 0xFF,
                (y >> 8) & 0xFF, y & 0xFF)
    }

    def newAccelConnection() {
        return new MockConnection()
    }

    def newMagConnection() {
        def c = new MockConnection()
        c.setRegister(LSM303Minimal.REG_IRA_M, 0x48, 0x34, 0x33)
        return c
    }

    /**
     * Return the value of the last write to reg.
     *
     * The mock accumulates every write since construction, so "the register now
     * holds X" has to look at the last write to that register.
     */
    def lastWriteTo(MockConnection connection, int reg) {
        def writes = connection.writes()
        for (int i = writes.size() - 1; i >= 0; i--) {
            def w = writes.get(i)
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF
        }
        return -1
    }

    def "construction writes the defaults"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()

        when:
        new LSM303Minimal(accel, mag)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0x88
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x57
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x10
        lastWriteTo(mag, LSM303Minimal.REG_CRB_M) == 0x20
        lastWriteTo(mag, LSM303Minimal.REG_MR_M) == 0x00
    }

    def "construction with a bad magnetometer identity throws"() {
        given:
        def accel = newAccelConnection()
        def mag = new MockConnection()
        mag.setRegister(LSM303Minimal.REG_IRA_M, 0x00, 0x00, 0x00)

        when:
        new LSM303Minimal(accel, mag)

        then:
        thrown(IOException)
    }

    def "read acceleration"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Minimal(accel, mag)
        setAccelOutput(accel, 1000, -2000, 2000)

        when:
        def xyz = compass.readAcceleration()

        then:
        Math.abs(xyz[0] - 1.0d) < 1e-9
        Math.abs(xyz[1] - (-2.0d)) < 1e-9
        Math.abs(xyz[2] - 2.0d) < 1e-9
    }

    def "read magnetic field"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Minimal(accel, mag)
        setMagOutput(mag, 1000, 3000, 2000)

        when:
        def xyz = compass.readMagnetic()

        then:
        Math.abs(xyz[0] - (1000.0d / 1100.0d * 100.0d)) < 1e-6
        Math.abs(xyz[1] - (3000.0d / 1100.0d * 100.0d)) < 1e-6
        Math.abs(xyz[2] - (2000.0d / 980.0d * 100.0d)) < 1e-6
    }

    def "set range and data rate"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL4_A, 0x88)
        compass.setRange(8)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0xA8

        when:
        compass.setRange(3)

        then:
        thrown(IOException)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        compass.setDataRate(400)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x77

        when:
        compass.setDataRate(123)

        then:
        thrown(IOException)
    }

    def "set mode and axes"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        accel.setRegister(LSM303Minimal.REG_CTRL4_A, 0x88)
        compass.setMode(LSM303Full.MODE_LOW_POWER)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x5F
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0x80

        when:
        compass.setMode(9)

        then:
        thrown(IOException)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        compass.setAxes(true, false, true)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x55
    }

    def "power down and status"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL1_A, 0x57)
        compass.powerDown()

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x07

        when:
        accel.setRegister(LSM303Minimal.REG_STATUS_A, 0x08)

        then:
        compass.newDataAvailable()
        compass.readStatus() == 0x08
    }

    def "high-pass filter"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        // mode=2 (0x80), cutoff=1 (0x10), FDS (0x08), HPCLICK (0x04), HPIS1 (0x01).
        compass.setHighPass(2, 1, true, true, false, true)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL2_A) == 0x9D

        when:
        compass.setHighPass(4, 0, false, false, false, false)

        then:
        thrown(IOException)

        when:
        accel.setRegister(LSM303Minimal.REG_REFERENCE_A, 0x42)

        then:
        compass.resetHighPass() == 0x42

        when:
        compass.setReference(0x37)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_REFERENCE_A) == 0x37
    }

    def "FIFO configuration and status"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL5_A, 0x00)
        compass.setFifo(LSM303Full.FIFO_STREAM, 16, false)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_FIFO_CTRL_A) == 0x90
        lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A) == 0x40

        when:
        compass.setFifo(LSM303Full.FIFO_STREAM, 99, false)

        then:
        thrown(IOException)

        when:
        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0x85)

        then:
        compass.fifoLevel() == 5

        when:
        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0xE7)
        def fs = compass.readFifoStatus()

        then:
        fs[0] == 1
        fs[1] == 1
        fs[2] == 1
        fs[3] == 7

        when:
        setAccelOutput(accel, 1000, -2000, 2000)
        def samples = compass.readFifo(2)

        then:
        samples.length == 2
        Math.abs(samples[0][0] - 1.0d) < 1e-9
    }

    def "inertial interrupt generator"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        // The threshold code is relative to the current range: 0.5 g * 128 / 2.
        compass.setMotionInterrupt(1, 0x02, 0.5, 10, false)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_INT1_CFG_A) == 0x02
        lastWriteTo(accel, LSM303Minimal.REG_INT1_THS_A) == 0x20

        when:
        compass.setMotionInterrupt(3, 0x02, 0.5, 10, false)

        then:
        thrown(IOException)

        when:
        compass.setMotionInterrupt(1, 0x40, 0.5, 10, false)

        then:
        thrown(IOException)

        when:
        compass.setFreeFall(1, 0.35, 30)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_INT1_CFG_A) == 0x95

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL5_A, 0x00)
        compass.setLatch(1, true)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A) == 0x08

        when:
        accel.setRegister(LSM303Minimal.REG_INT1_SOURCE_A, 0x41)

        then:
        compass.readInterruptSource(1) == 0x41
    }

    def "click detector"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        // double & CLICK_AXIS_Z -> 0x20; the threshold is 0.8 g * 128 / 2 = 51.
        compass.setClick(0, 0x04, 0.8, 20, 40, 200)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CLICK_CFG_A) == 0x20
        lastWriteTo(accel, LSM303Minimal.REG_CLICK_THS_A) == 51

        when:
        compass.setClick(0x08, 0, 0.5, 10, 20, 50)

        then:
        thrown(IOException)

        when:
        accel.setRegister(LSM303Minimal.REG_CLICK_SRC_A, 0x42)

        then:
        compass.readClickSource() == 0x42
    }

    def "magnetometer configuration"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        mag.setRegister(LSM303Minimal.REG_CRB_M, 0x20)
        compass.setMagRange(400)

        then:
        lastWriteTo(mag, LSM303Minimal.REG_CRB_M) == 0x80

        when:
        compass.setMagRange(123)

        then:
        thrown(IOException)

        when:
        mag.setRegister(LSM303Minimal.REG_CRA_M, 0x10)
        compass.setMagDataRate(30)

        then:
        // 30 Hz is the sixth entry of [0.75, 1.5, 3, 7.5, 15, 30, 75, 220].
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x14

        when:
        compass.setMagDataRate(12)

        then:
        thrown(IOException)

        when:
        mag.setRegister(LSM303Minimal.REG_MR_M, 0x00)
        compass.setMagMode(LSM303Full.MAG_MODE_SLEEP)

        then:
        lastWriteTo(mag, LSM303Minimal.REG_MR_M) == 0x02

        when:
        mag.setRegister(LSM303Minimal.REG_SR_M, 0x03)
        def ms = compass.readMagStatus()

        then:
        ms[0]
        ms[1]

        when:
        def identity = compass.readMagIdentity()

        then:
        (identity[0] & 0xFF) == 0x48
        (identity[1] & 0xFF) == 0x34
        (identity[2] & 0xFF) == 0x33

        when:
        mag.setRegister(LSM303Minimal.REG_CRA_M, 0x10)
        compass.enableTemperature(true)

        then:
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x90
    }

    def "compass heading"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        // X = 1100 counts (1 gauss), Y = 0 -> heading 0 deg (north).
        setMagOutput(mag, 1100, 0, 0)

        then:
        Math.abs(compass.heading(false) - 0.0d) < 1e-3

        when:
        // X = 0, Y = 1100 counts -> heading 90 deg (east).
        setMagOutput(mag, 0, 1100, 0)

        then:
        Math.abs(compass.heading(false) - 90.0d) < 1e-3

        when:
        // X = 0, Y = -1100 counts -> heading 270 deg (west).
        setMagOutput(mag, 0, -1100, 0)

        then:
        Math.abs(compass.heading(false) - 270.0d) < 1e-3
    }

    def "interrupt routing"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL3_A, 0x00)
        compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL3_A) == 0x80

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL3_A, 0x80)
        compass.disableInterrupt(LSM303Full.SOURCE_CLICK, 1)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL3_A) == 0x00

        when:
        compass.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY, 1)

        then:
        thrown(IOException)

        when:
        compass.enableInterrupt(LSM303Full.SOURCE_CLICK, 3)

        then:
        thrown(IOException)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL6_A, 0x00)
        compass.setInterruptPolarity(true)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A) == 0x02
    }

    def "poll interrupt"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_STATUS_A, 0x08)
        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0x80)
        mag.setRegister(LSM303Minimal.REG_SR_M, 0x00)
        def status = compass.pollInterrupt()

        then:
        (status & LSM303Full.SOURCE_DATA_READY) != 0
        (status & LSM303Full.SOURCE_WATERMARK) != 0
        (status & LSM303Full.SOURCE_OVERRUN) == 0
    }

    def "reboot sets the boot bit"() {
        given:
        def accel = newAccelConnection()
        def mag = newMagConnection()
        def compass = new LSM303Full(accel, mag)

        when:
        accel.setRegister(LSM303Minimal.REG_CTRL5_A, 0x00)
        compass.reboot()

        then:
        // The reboot re-runs init(), which rewrites CTRL5_A afterwards, so
        // look for a write with the BOOT bit set rather than at the last write.
        accel.writes().any { w ->
            w.length == 2 && (w[0] & 0xFF) == LSM303Minimal.REG_CTRL5_A && (w[1] & 0x80) != 0
        }
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x57
    }
}
