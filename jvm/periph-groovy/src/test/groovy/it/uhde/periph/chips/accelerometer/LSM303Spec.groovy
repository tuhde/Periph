package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class LSM303Spec extends Specification {

    def newMag() {
        def c = new MockConnection()
        c.setRegister(LSM303Minimal.REG_IRA_M, 0x48, 0x34, 0x33)
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

    def "initialization writes default registers"() {
        given:
        def accel = new MockConnection()
        def mag = newMag()

        when:
        new LSM303Minimal(accel, mag)

        then:
        // Init writes CTRL1_A=0x57 (100 Hz, all axes), CTRL4_A=0x88 (BDU+HR, ±2 g),
        // CRA=0x10 (15 Hz), CRB=0x20 (±1.3 gauss), MR=0x00 (continuous).
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x57
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0x88
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x10
        lastWriteTo(mag, LSM303Minimal.REG_CRB_M) == 0x20
        lastWriteTo(mag, LSM303Minimal.REG_MR_M) == 0x00
    }

    def "construction with bad identity throws"() {
        given:
        def accel = new MockConnection()
        def mag = new MockConnection()
        mag.setRegister(LSM303Minimal.REG_IRA_M, 0xFF, 0xFF, 0xFF)

        when:
        new LSM303Minimal(accel, mag)

        then:
        thrown(IOException)
    }

    def "accel conversion"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())
        // raw_x = 0x1000 -> >>4 = 256 counts -> 0.256 g at ±2 g high-resolution.
        accel.setRegister(LSM303Minimal.REG_OUT_X_L_A | 0x80, 0x00, 0x10, 0x00, 0x00, 0x00, 0xF0)

        when:
        def xyz = full.readAcceleration()

        then:
        xyz[0] == 0.256d
        xyz[1] == 0.0d
        xyz[2] == -0.256d

        when:
        def raw = full.readAccelerationRaw()

        then:
        raw[0] == 256
        raw[1] == 0
        raw[2] == -256
    }

    def "mag X/Z/Y reorder and per-axis gains"() {
        given:
        def accel = new MockConnection()
        def mag = newMag()
        def full = new LSM303Full(accel, mag)
        // Wire order is X, Z, Y, high byte first. X=1100, Z=-980, Y=550 counts.
        mag.setRegister(LSM303Minimal.REG_OUT_X_H_M, 0x04, 0x4C, 0xFC, 0x2C, 0x02, 0x26)

        when:
        def xyz = full.readMagnetic()

        then:
        xyz[0] == 100.0d       // 1100 / 1100 * 100
        xyz[1] == 50.0d        // 550 / 1100 * 100 (reordered)
        xyz[2] == -100.0d      // -980 / 980 * 100 (Z gain differs)

        when:
        def raw = full.readMagneticRaw()

        then:
        raw[0] == 1100
        raw[1] == 550
        raw[2] == -980
    }

    def "setRange 16 writes CTRL4 0xB8"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setRange(16)

        then:
        // (0x88 & 0xCF) | (3 << 4) = 0xB8
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0xB8

        when:
        full.setRange(5)

        then:
        thrown(IllegalArgumentException)
    }

    def "setDataRate encoding and mode validation"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setDataRate(50)

        then:
        // (0x57 & 0x0F) | (0x4 << 4) = 0x47
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x47

        when:
        full.setDataRate(42)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setDataRate(1620)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setDataRate(5376)

        then:
        thrown(IllegalArgumentException)
    }

    def "1344 Hz rejected in low-power mode"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setMode(0)
        full.setDataRate(1344)

        then:
        thrown(IllegalArgumentException)
    }

    def "setMode sets LPen / HR bits"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setMode(0)

        then:
        // LPen=1 in CTRL1: 0x57 -> 0x5F; HR=0 in CTRL4: 0x88 -> 0x80
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x5F
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0x80

        when:
        full.setMode(2)

        then:
        // back to high-resolution: 0x57 / 0x88
        lastWriteTo(accel, LSM303Minimal.REG_CTRL1_A) == 0x57
        lastWriteTo(accel, LSM303Minimal.REG_CTRL4_A) == 0x88

        when:
        full.setMode(3)

        then:
        thrown(IllegalArgumentException)
    }

    def "FIFO ctrl encoding"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setFifo(2, 16, false)

        then:
        // (2 << 6) | 16 = 0x90; FIFO_EN -> CTRL5 |= 0x40
        lastWriteTo(accel, LSM303Minimal.REG_FIFO_CTRL_A) == 0x90
        lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A) == 0x40

        when:
        full.setFifo(0, 0, false)

        then:
        // bypass: 0x00; CTRL5 &= ~0x40 -> 0x00
        lastWriteTo(accel, LSM303Minimal.REG_FIFO_CTRL_A) == 0x00
        lastWriteTo(accel, LSM303Minimal.REG_CTRL5_A) == 0x00

        when:
        full.setFifo(4, 0, false)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setFifo(1, 32, false)

        then:
        thrown(IllegalArgumentException)
    }

    def "free-fall CFG 0x95 with threshold / duration encoding at ±16 g / 100 Hz"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setFreeFall(1, 0.35d, 30.0d)

        then:
        // ±2 g, 100 Hz: THS = round(0.35*128/2) = 22, DUR = round(30*100/1000) = 3.
        lastWriteTo(accel, LSM303Minimal.REG_INT1_CFG_A) == 0x95
        lastWriteTo(accel, LSM303Minimal.REG_INT1_THS_A) == 22
        lastWriteTo(accel, LSM303Minimal.REG_INT1_DUR_A) == 3

        when:
        full.setRange(16)
        full.setFreeFall(2, 0.35d, 30.0d)

        then:
        // ±16 g at 100 Hz: THS = round(0.35*128/16) = 3.
        lastWriteTo(accel, LSM303Minimal.REG_INT2_CFG_A) == 0x95
        lastWriteTo(accel, LSM303Minimal.REG_INT2_THS_A) == 3
        lastWriteTo(accel, LSM303Minimal.REG_INT2_DUR_A) == 3
    }

    def "click axis-mask spreading"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.setClick(1, 0, 0.5d, 10.0d, 20.0d, 50.0d)

        then:
        // single=1 (X) spreads XS=bit 1.
        lastWriteTo(accel, LSM303Minimal.REG_CLICK_CFG_A) == 0x02

        when:
        full.setClick(0, 2, 0.5d, 10.0d, 20.0d, 50.0d)

        then:
        // double=2 (Y) spreads YD=bit 2.
        lastWriteTo(accel, LSM303Minimal.REG_CLICK_CFG_A) == 0x04
        // ±2 g, 100 Hz: THS=round(0.5*128/2)=32, TL=1, LAT=2, WIN=5.
        lastWriteTo(accel, LSM303Minimal.REG_CLICK_THS_A) == 32
        lastWriteTo(accel, LSM303Minimal.REG_TIME_LIMIT_A) == 1
        lastWriteTo(accel, LSM303Minimal.REG_TIME_LAT_A) == 2
        lastWriteTo(accel, LSM303Minimal.REG_TIME_WIN_A) == 5

        when:
        full.setClick(8, 0, 0.5d, 10.0d, 20.0d, 50.0d)

        then:
        thrown(IllegalArgumentException)
    }

    def "INT routing writes CTRL3 / CTRL6 bits"() {
        given:
        def accel = new MockConnection()
        def full = new LSM303Full(accel, newMag())

        when:
        full.enableInterrupt(LSM303Full.SOURCE_DATA_READY, 1)

        then:
        lastWriteTo(accel, LSM303Minimal.REG_CTRL3_A) == 0x10

        when:
        full.enableInterrupt(LSM303Full.SOURCE_IA1, 2)

        then:
        // CTRL6 |= I2_INT1 (0x40) -> 0x40
        lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A) == 0x40

        when:
        full.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY, 1)

        then:
        // SOURCE_MAG_DATA_READY is on the dedicated DRDY pin and is not routable.
        thrown(IllegalArgumentException)

        when:
        full.enableInterrupt(LSM303Full.SOURCE_BOOT, 1)

        then:
        // SOURCE_BOOT is INT2-only.
        thrown(IllegalArgumentException)

        when:
        full.enableInterrupt(LSM303Full.SOURCE_BOOT, 2)

        then:
        // CTRL6 |= BOOT_I1 (0x10) -> 0x50
        lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A) == 0x50

        when:
        full.disableInterrupt(LSM303Full.SOURCE_IA1, 2)

        then:
        // CTRL6 &= ~0x40 -> 0x10
        lastWriteTo(accel, LSM303Minimal.REG_CTRL6_A) == 0x10
    }

    def "pollInterrupt combines the status registers"() {
        given:
        def accel = new MockConnection()
        def mag = newMag()
        def full = new LSM303Full(accel, mag)
        full.enableInterrupt(LSM303Full.SOURCE_DATA_READY, 1)
        full.enableInterrupt(LSM303Full.SOURCE_WATERMARK, 1)
        full.enableInterrupt(LSM303Full.SOURCE_IA1, 1)
        full.enableInterrupt(LSM303Full.SOURCE_IA2, 1)
        full.enableInterrupt(LSM303Full.SOURCE_CLICK, 1)
        accel.setRegister(LSM303Minimal.REG_STATUS_A, 0x08)          // ZYXDA
        accel.setRegister(LSM303Minimal.REG_FIFO_SRC_A, 0x80)        // WTM, not OVRN
        accel.setRegister(LSM303Minimal.REG_INT1_SRC_A, 0x40)        // IA
        accel.setRegister(LSM303Minimal.REG_INT2_SRC_A, 0x40)        // IA
        accel.setRegister(LSM303Minimal.REG_CLICK_SRC_A, 0x40)       // IA
        mag.setRegister(LSM303Minimal.REG_SR_M, 0x01)                // DRDY

        when:
        def status = full.pollInterrupt()

        then:
        status == 0xBB
    }

    def "mag gain / rate / mode registers"() {
        given:
        def accel = new MockConnection()
        def mag = newMag()
        def full = new LSM303Full(accel, mag)

        when:
        full.setMagRange(400)

        then:
        // (3 + 1) << 5 = 0x80
        lastWriteTo(mag, LSM303Minimal.REG_CRB_M) == 0x80

        when:
        full.setMagDataRate(30.0d)

        then:
        // (0x10 & 0x80) | (5 << 2) = 0x14
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x14

        when:
        full.setMagMode(2)

        then:
        lastWriteTo(mag, LSM303Minimal.REG_MR_M) == 0x02

        when:
        full.setMagMode(0)

        then:
        lastWriteTo(mag, LSM303Minimal.REG_MR_M) == 0x00

        when:
        full.setMagMode(3)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setMagRange(999)

        then:
        thrown(IllegalArgumentException)

        when:
        full.setMagDataRate(4.0d)

        then:
        thrown(IllegalArgumentException)
    }

    def "temperature enable and /8 conversion"() {
        given:
        def accel = new MockConnection()
        def mag = newMag()
        def full = new LSM303Full(accel, mag)
        // raw = 0x0800 >> 4 = 128 -> 128 / 8 = 16 °C.
        mag.setRegister(LSM303Minimal.REG_TEMP_H_M, 0x08, 0x00)

        when:
        def temp = full.readTemperatureDelta()

        then:
        temp == 16.0d
        // TEMP_EN set by RMW: 0x10 | 0x80 = 0x90.
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x90

        when:
        full.enableTemperature(false)

        then:
        lastWriteTo(mag, LSM303Minimal.REG_CRA_M) == 0x10
    }

    def "flat heading is 45 deg"() {
        given:
        def accel = new MockConnection()
        def mag = newMag()
        def full = new LSM303Full(accel, mag)
        // X = Y counts (both use the XY gain) -> atan2(1, 1) = 45°.
        mag.setRegister(LSM303Minimal.REG_OUT_X_H_M, 0x02, 0x26, 0x00, 0x00, 0x02, 0x26)

        when:
        def hdg = full.heading(false)

        then:
        Math.abs(hdg - 45.0d) < 1e-6
    }
}
