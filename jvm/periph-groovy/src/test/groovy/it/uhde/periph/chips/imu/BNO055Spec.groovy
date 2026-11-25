package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntConsumer

class BNO055Spec extends Specification {

    MockConnection mock
    BNO055Full s

    static int[] le(int... vals) {
        int[] out = new int[vals.length * 2]
        for (int i = 0; i < vals.length; i++) {
            out[2 * i] = vals[i] & 0xFF
            out[2 * i + 1] = (vals[i] >> 8) & 0xFF
        }
        return out
    }

    static MockConnection fresh() {
        MockConnection m = new MockConnection()
        m.setRegister(0x00, 0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15)
        return m
    }

    def setup() {
        mock = fresh()
        s = new BNO055Full(mock)
    }

    /** Values written to a register with a single-byte payload, in order. */
    List<Integer> writesTo(int reg) {
        List<Integer> out = []
        for (byte[] w : mock.writes()) if (w.length == 2 && (w[0] & 0xFF) == reg) out << (w[1] & 0xFF)
        return out
    }

    def "init sequence"() {
        expect:
        def w2 = mock.writes().findAll { it.length == 2 }
        w2.any { (it[0] & 0xFF) == 0x3E && it[1] == 0 }
        w2.any { (it[0] & 0xFF) == 0x07 && it[1] == 0 }
        w2.any { (it[0] & 0xFF) == 0x3B && (it[1] & 0xFF) == 0x86 }
        (w2.last()[0] & 0xFF) == 0x3D && w2.last()[1] == 0x0C
    }

    def "wrong chip id throws"() {
        when:
        def bad = new MockConnection()
        bad.setRegister(0x00, 0x55)
        new BNO055Minimal(bad)

        then:
        thrown(IOException)
    }

    def "euler"() {
        given:
        def m = new BNO055Minimal(fresh())

        when:
        def mc = fresh()
        mc.setRegister(0x1A, le(1414, -900, 450))
        def e = new BNO055Minimal(mc).euler()

        then:
        Math.abs(e[0] - 1414 / 900.0d) < 1e-9
        Math.abs(e[1] + 1.0d) < 1e-9
        Math.abs(e[2] - 0.5d) < 1e-9
    }

    def "vectors"() {
        when:
        mock.setRegister(0x20, le(16384, -8192, 0, 4096))
        def q = s.quaternion()
        mock.setRegister(0x28, le(100, -250, 981))
        def lin = s.linearAcceleration()
        mock.setRegister(0x2E, le(0, 0, 981))
        def grav = s.gravity()
        mock.setRegister(0x08, le(-100, 200, 300))
        def acc = s.acceleration()
        mock.setRegister(0x0E, le(160, -32, 16))
        def mag = s.magneticField()
        mock.setRegister(0x14, le(900, 0, -450))
        def gyr = s.angularRate()
        mock.setRegister(0x34, 0xE7)

        then:
        q == [1.0d, -0.5d, 0.0d, 0.25d] as double[]
        Math.abs(lin[1] + 2.5d) < 1e-9 && Math.abs(lin[2] - 9.81d) < 1e-9
        Math.abs(grav[2] - 9.81d) < 1e-9
        acc == [-1.0d, 2.0d, 3.0d] as double[]
        mag == [10.0d, -2.0d, 1.0d] as double[]
        gyr == [1.0d, 0.0d, -0.5d] as double[]
        s.temperature() == -25
    }

    def "calibration status"() {
        when:
        mock.setRegister(0x35, 0xE4)
        def a = s.calibrationStatus()
        def notCal = s.isCalibrated()
        mock.setRegister(0x35, 0xFF)

        then:
        a == [3, 2, 1, 0] as int[]
        !notCal
        s.isCalibrated()
    }

    def "modes"() {
        when:
        mock.writes().clear()
        s.setMode(BNO055Full.MODE_IMU)

        then:
        writesTo(0x3D) == [0x00, 0x08]

        when:
        mock.setRegister(0x3D, 0x08)

        then:
        s.getMode() == 8

        when:
        s.setMode(13)

        then:
        thrown(IllegalArgumentException)
    }

    def "calibration blob"() {
        given:
        byte[] blob = new byte[22]
        for (int i = 0; i < 22; i++) { blob[i] = (byte) (i + 1); mock.setRegister(0x55 + i, i + 1) }

        when:
        mock.writes().clear()
        def got = s.getCalibration()

        then:
        got == blob
        writesTo(0x3D) == [0x00, 0x0C]

        when:
        mock.writes().clear()
        s.setCalibration(blob)
        def data = mock.writes().findAll { it.length == 2 && (it[0] & 0xFF) >= 0x55 && (it[0] & 0xFF) <= 0x6A }

        then:
        data.size() == 22
        (0..21).every { (data[it][0] & 0xFF) == 0x55 + it && data[it][1] == blob[it] }
        writesTo(0x3D) == [0x00, 0x0C]

        when:
        s.setCalibration(new byte[21])

        then:
        thrown(IllegalArgumentException)
    }

    def "configuration"() {
        when:
        mock.writes().clear()
        s.setAxisRemap(0x21, 0x02)

        then:
        writesTo(0x41) == [0x21]
        writesTo(0x42) == [0x02]
        writesTo(0x3D) == [0x00, 0x0C]

        when:
        s.setPowerMode("low_power")

        then:
        writesTo(0x3E) == [1]

        when:
        s.setPowerMode("bogus")

        then:
        thrown(IllegalArgumentException)

        when:
        s.setTemperatureSource("gyroscope")

        then:
        writesTo(0x40) == [1]

        when:
        mock.writes().clear()
        s.useExternalCrystal(true)
        s.useExternalCrystal(false)

        then:
        writesTo(0x3F) == [0x80, 0x00]
    }

    def "self test, status, revisions, unique id"() {
        when:
        mock.setRegister(0x36, 0x0D)
        mock.writes().clear()
        def st = s.selfTest()

        then:
        st == [true, false, true, true] as boolean[]
        writesTo(0x3F) == [0x01]
        writesTo(0x3D) == [0x00, 0x0C]

        when:
        mock.setRegister(0x39, 1, 3)

        then:
        s.systemStatus() == [1, 3] as int[]
        s.revisions() == [0xA0, 0xFB, 0x32, 0x0F, 0x0311, 0x15] as int[]

        when:
        byte[] uid = new byte[16]
        for (int i = 0; i < 16; i++) { uid[i] = (byte) i; mock.setRegister(0x50 + i, i) }
        mock.writes().clear()
        def got = s.uniqueId()

        then:
        got == uid
        writesTo(0x07) == [1, 0]
    }

    def "interrupts"() {
        when:
        mock.setRegister(0x10, 0)
        mock.setRegister(0x0F, 0)
        mock.writes().clear()
        s.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY | BNO055Full.SOURCE_GYR_DRDY)

        then:
        writesTo(0x10) == [0x11]
        writesTo(0x0F) == [0x11]
        writesTo(0x07) == [1, 0]

        when:
        mock.setRegister(0x10, 0x11)
        mock.setRegister(0x0F, 0x11)
        mock.writes().clear()
        s.disableInterrupt(BNO055Full.SOURCE_GYR_DRDY)
        mock.setRegister(0x37, 0x41)

        then:
        writesTo(0x10) == [0x01]
        writesTo(0x0F) == [0x01]
        s.pollInterrupt() == 0x41
    }

    def "polling fallback delivers status"() {
        when:
        mock.setRegister(0x37, 0x41)
        def seen = new AtomicInteger()
        s.onInterrupt({ int v -> seen.set(v) } as IntConsumer)
        Thread.sleep(250)
        s.offInterrupt()

        then:
        seen.get() == 0x41
    }

    def "reset"() {
        when:
        mock.writes().clear()
        s.reset()

        then:
        writesTo(0x3F)[0] == 0x20
        writesTo(0x3D).last() == 0x0C
        writesTo(0x3B)[0] == 0x86
    }
}
