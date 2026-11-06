package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class ICM20948Spec extends Specification {

    /** I2C fake that models the ICM-20948 user banks (REG_BANK_SEL = 0x7F). */
    static class BankedMock extends MockConnection {
        int bank = 0
        final Map<Integer, Integer> regs = [:]
        final List<List<Integer>> log = []   // [bank, byte0, byte1, ...]

        BankedMock() {
            set(0, 0x00, 0xEA)
            set(0, 0x17, 0x40)   // I2C_MST_STATUS: SLV4_DONE
        }

        void set(int b, int reg, int... values) {
            values.eachWithIndex { int v, int i -> regs[(b << 8) | (reg + i)] = v & 0xFF }
        }

        void set(int b, int reg, List<Integer> values) {
            set(b, reg, values as int[])
        }

        int get(int b, int reg) { regs.getOrDefault((b << 8) | reg, 0) }

        @Override
        void write(byte[] data) {
            List<Integer> entry = [bank]
            data.each { entry << (it & 0xFF) }
            log << entry
            if ((data[0] & 0xFF) == 0x7F) {
                bank = (data[1] & 0xFF) >> 4
                return
            }
            for (int i = 1; i < data.length; i++) regs[(bank << 8) | ((data[0] & 0xFF) + i - 1)] = data[i] & 0xFF
        }

        @Override
        byte[] writeRead(byte[] data, int n) {
            log << [bank, data[0] & 0xFF]
            byte[] out = new byte[n]
            for (int i = 0; i < n; i++) out[i] = (byte) get(bank, (data[0] & 0xFF) + i)
            out
        }

        /** Register writes (without bank selects) that landed in the given bank. */
        List<List<Integer>> writesIn(int b) {
            log.findAll { it[0] == b && it.size() > 2 && it[1] != 0x7F }.collect { it.drop(1) }
        }

        List<Integer> lastIn(int b) { writesIn(b).last() }

        boolean wrote(int b, List<Integer> bytes) { writesIn(b).any { it == bytes } }
    }

    private static List<Integer> s16(int v) { [(v >> 8) & 0xFF, v & 0xFF] }

    private static List<Integer> s16le(int v) { [v & 0xFF, (v >> 8) & 0xFF] }

    def "init writes sequence"() {
        given:
        def m = new BankedMock()

        when:
        new ICM20948Full(m)

        then:
        m.log == [
                [0, 0x7F, 0x00], [0, 0x00], [0, 0x06, 0x80], [0, 0x06, 0x01], [0, 0x07, 0x00],
                [0, 0x7F, 0x20], [2, 0x01, 0x19], [2, 0x7F, 0x00],
                [0, 0x7F, 0x20], [2, 0x14, 0x19], [2, 0x7F, 0x00],
                [0, 0x7F, 0x20], [2, 0x00, 0x0A], [2, 0x7F, 0x00],
                [0, 0x7F, 0x20], [2, 0x10, 0x00], [2, 0x7F, 0x00],
                [0, 0x7F, 0x20], [2, 0x11, 0x0A], [2, 0x7F, 0x00],
        ]
        m.bank == 0
    }

    def "WHO_AM_I mismatch throws"() {
        given:
        def m = new BankedMock()
        m.set(0, 0x00, 0x00)

        when:
        new ICM20948Full(m)

        then:
        thrown(IOException)
    }

    def "accel gyro and configuration"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)

        when:
        m.set(0, 0x2D, s16(16384) + s16(-8192) + s16(4096))
        def a = s.accel()
        m.set(0, 0x33, s16(131) + s16(-131) + s16(262))
        def g = s.gyro()

        then:
        Math.abs(a[0] - 9.80665) < 1e-9
        Math.abs(a[1] + 4.903325) < 1e-9
        Math.abs(a[2] - 2.4516625) < 1e-9
        Math.abs(g[0] - Math.toRadians(1)) < 1e-9
        Math.abs(g[1] - Math.toRadians(-1)) < 1e-9
        Math.abs(g[2] - Math.toRadians(2)) < 1e-9

        when:
        s.configureGyro(2)

        then:
        m.lastIn(2) == [0x01, 0x1D]

        when:
        m.set(0, 0x33, s16(328) + s16(0) + s16(0))

        then:
        Math.abs(s.gyro()[0] - Math.toRadians(10)) < 1e-9

        when:
        s.configureAccel(1)

        then:
        m.lastIn(2) == [0x14, 0x1B]

        when:
        m.set(0, 0x2D, s16(8192) + s16(0) + s16(0))

        then:
        Math.abs(s.accel()[0] - 9.80665) < 1e-9

        when:
        s.configureFilters(5, 2, false, true)
        def w2 = m.writesIn(2)

        then:
        w2[-2] == [0x01, (5 << 3) | (2 << 1)]
        w2[-1] == [0x14, (2 << 3) | (1 << 1) | 1]

        when:
        s.configureFilters(3, 3, true, true)
        s.configureSampleRate(9, 0x123)
        w2 = m.writesIn(2)

        then:
        w2[-3] == [0x00, 9]
        w2[-2] == [0x10, 0x01]
        w2[-1] == [0x11, 0x23]
        m.bank == 0
    }

    def "burst raw and temperature"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)
        s.configureAccel(1)
        s.configureGyro(2)

        when:
        m.set(0, 0x2D, s16(8192) + s16(0) + s16(0) + s16(262) + s16(0) + s16(0) + s16(340))
        def sample = s.readAll()

        then:
        Math.abs(sample.accel[0] - 9.80665) < 1e-9
        Math.abs(sample.gyro[0] - Math.toRadians(262 / 32.8d)) < 1e-9
        Math.abs(sample.tempC - (340 / 333.87d + 21.0)) < 1e-9

        when:
        m.set(0, 0x39, s16(340))

        then:
        Math.abs(s.temperature() - (340 / 333.87d + 21.0)) < 1e-9

        when:
        m.set(0, 0x2D, s16(100) + s16(-200) + s16(300))
        m.set(0, 0x33, s16(-50) + s16(60) + s16(-70))

        then:
        s.accelRaw() == [100, -200, 300] as int[]
        s.gyroRaw() == [-50, 60, -70] as int[]

        when:
        m.set(0, 0x1A, 0x01)

        then:
        s.dataReady()

        when:
        m.set(0, 0x1A, 0x00)

        then:
        !s.dataReady()
    }

    def "power and offsets"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)

        when:
        s.setSensors(false, true)

        then:
        m.lastIn(0) == [0x07, 0x38]

        when:
        s.setSensors(true, false)

        then:
        m.lastIn(0) == [0x07, 0x07]

        when:
        s.setSensors(true, true)

        then:
        m.lastIn(0) == [0x07, 0x00]

        when:
        s.setSleep(true)

        then:
        m.lastIn(0) == [0x06, 0x41]

        when:
        s.setSleep(false)

        then:
        m.lastIn(0) == [0x06, 0x01]

        when:
        s.setGyroOffset(Math.toRadians(1), 0.0d, Math.toRadians(-1))

        then:
        m.lastIn(2) == [0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF]

        when:
        s.setAccelOffset(0x1234, -1, 0)
        def w1 = m.writesIn(1)

        then:
        w1[-3] == [0x14, 0x24, 0x68]
        w1[-2] == [0x17, 0xFF, 0xFE]

        when:
        m.set(1, 0x14, [0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02])
        m.set(1, 0x28, 0xF6)

        then:
        s.getAccelOffset() == [0x1234, -1, 1] as int[]
        Math.abs(s.readTimebaseCorrection() - (-10 * 0.079d)) < 1e-9
        m.bank == 0
    }

    def "fifo"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)

        when:
        m.set(0, 0x70, 0x03, 0x45)

        then:
        s.fifoCount() == 0x345

        when:
        m.set(0, 0x70, 0x00, 0x02)
        m.set(0, 0x72, 0xAA, 0xBB)

        then:
        s.readFifo(-1) == [0xAA, 0xBB] as byte[]
        s.readFifo(1) == [0xAA] as byte[]

        when:
        m.set(0, 0x70, 0x00, 0x00)

        then:
        s.readFifo(-1).length == 0

        when:
        s.enableFifo(true, true, false)
        def w0 = m.writesIn(0)

        then:
        m.wrote(0, [0x67, 0x1E])
        m.wrote(0, [0x03, 0x40])
        w0[-2] == [0x68, 0x1F]
        w0[-1] == [0x68, 0x00]

        when:
        s.setFifoMode(true)

        then:
        m.lastIn(0) == [0x69, 0x1F]

        when:
        s.setFifoMode(false)

        then:
        m.lastIn(0) == [0x69, 0x00]
    }

    def "interrupts and wake on motion"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)

        when:
        s.setInterruptPin(true, false, true, true)

        then:
        m.lastIn(0) == [0x0F, 0xB0]

        when:
        s.enableInterrupt(ICM20948Full.SOURCE_DATA_READY)

        then:
        m.lastIn(0) == [0x11, 0x01]

        when:
        s.enableInterrupt(ICM20948Full.SOURCE_PLL_READY)

        then:
        m.lastIn(0) == [0x10, 0x04]

        when:
        s.disableInterrupt(ICM20948Full.SOURCE_PLL_READY)

        then:
        m.lastIn(0) == [0x10, 0x00]

        when:
        s.enableInterrupt(ICM20948Full.SOURCE_FIFO_OVERFLOW)

        then:
        m.lastIn(0) == [0x12, 0x1F]

        when:
        s.enableInterrupt(0x80)

        then:
        thrown(IllegalArgumentException)

        when:
        m.set(0, 0x19, 0x09, 0x01, 0x02)

        then:
        s.pollInterrupt() == (0x01 | 0x02 | 0x04 | 0x10)

        when:
        m.set(0, 0x19, 0x04, 0x00, 0x00)

        then:
        s.pollInterrupt() == 0x08

        when:
        s.setWakeOnMotion(200, true)

        then:
        m.wrote(2, [0x13, 50])
        m.wrote(2, [0x12, 0x03])
        m.lastIn(0) == [0x10, 0x08]
    }

    def "low power guard"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)
        m.set(0, 0x06, 0x01)

        when:
        s.setLowPower(true, true, 2, 3)

        then:
        m.wrote(0, [0x05, 0x70])
        m.lastIn(0) == [0x06, 0x21]
        m.wrote(2, [0x15, 0x02])
        m.wrote(2, [0x02, 0x03])

        when:
        m.log.clear()
        s.configureGyro(0)
        def w0 = m.writesIn(0)

        then:
        w0.first() == [0x06, 0x01]
        w0.last() == [0x06, 0x21]

        when:
        s.setLowPower(false, false, 0, 0)

        then:
        (m.get(0, 0x06) & 0x20) == 0
    }

    def "magnetometer"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)

        when:
        s.mag()

        then:
        thrown(IllegalStateException)

        when:
        s.magRaw()

        then:
        thrown(IllegalStateException)

        when:
        s.magDataReady()

        then:
        thrown(IllegalStateException)

        when:
        m.set(3, 0x17, 0x09)   // slave-4 read returns WIA2 = 0x09
        s.enableMag()
        def w3 = m.writesIn(3)

        then:
        m.bank == 0
        m.wrote(3, [0x01, 0x07])
        m.wrote(3, [0x16, 0x01]) && m.wrote(3, [0x14, 0x32])
        m.wrote(3, [0x16, 0x08]) && m.wrote(3, [0x14, 0x31])
        w3[-3] == [0x03, 0x8C]
        w3[-2] == [0x04, 0x10]
        w3[-1] == [0x05, 0x89]
        (m.get(0, 0x03) & 0x20) == 0x20

        when:
        m.set(0, 0x3B, [0x01] + s16le(1000) + s16le(-500) + s16le(250) + [0x00, 0x00])
        def mag = s.mag()

        then:
        Math.abs(mag[0] - 150.0) < 1e-9
        Math.abs(mag[1] + 75.0) < 1e-9
        Math.abs(mag[2] - 37.5) < 1e-9
        s.magRaw() == [1000, -500, 250] as int[]
        s.magDataReady()

        when:
        m.set(0, 0x3B, 0x00)

        then:
        !s.magDataReady()

        when:
        m.set(0, 0x3B, [0x01] + s16le(1) + s16le(2) + s16le(3) + [0x00, 0x08])
        s.mag()

        then:
        thrown(IOException)

        when:
        s.magSetMode(2)
        m.set(3, 0x17, 0x5A)

        then:
        m.wrote(3, [0x16, 0x02])
        s.auxReadByte(0x1E, 0x0A) == 0x5A
        m.wrote(3, [0x13, 0x9E])

        when:
        s.auxWriteByte(0x1E, 0x0B, 0xA5)

        then:
        m.wrote(3, [0x13, 0x1E]) && m.wrote(3, [0x16, 0xA5])
        m.bank == 0
    }

    def "magnetometer errors"() {
        when:
        new ICM20948Full(new BankedMock()).enableMag()   // WIA2 reads 0 -> mismatch

        then:
        thrown(IOException)

        when:
        def nack = new BankedMock()
        nack.set(0, 0x17, 0x10)
        new ICM20948Full(nack).auxReadByte(0x0C, 0x01)

        then:
        thrown(IOException)
    }

    def "self test and reset"() {
        given:
        def m = new BankedMock()
        def s = new ICM20948Full(m)

        when:
        def r = s.selfTest()

        then:
        r[0] == [0, 0, 0] as double[]
        r[1] == [0, 0, 0] as double[]
        m.bank == 0

        when:
        s.reset()

        then:
        m.lastIn(0) == [0x06, 0x80]
    }
}
