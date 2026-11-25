package it.uhde.periph.chips.imu

import it.uhde.periph.connection.MockConnection
import spock.lang.Specification

class QMI8658ASpec extends Specification {

    static final double G = 9.80665
    static final double D2R = Math.PI / 180.0

    /** I2C fake that models the CTRL9 handshake, the self-test Avail flag and the FIFO_DATA port. */
    static class QmiMock extends MockConnection {
        final Map<Integer, Integer> regs = [:]
        final List<int[]> log = []            // {byte0, byte1, ...}
        final List<int[]> cmds = new ArrayList<>();           // {cmd, cal1..cal4h (8 values)}
        final LinkedList<Integer> fifo = new LinkedList<>()

        QmiMock() { regs.put(0x00, 0x05); }

        void set(int reg, int... values) {
            for (int i = 0; i < values.length; i++) regs.put(reg + i, values[i] & 0xFF)
        }

        void setLe(int reg, int... vals) {
            for (int i = 0; i < vals.length; i++) set(reg + 2 * i, vals[i] & 0xFF, (vals[i] >> 8) & 0xFF)
        }

        boolean wrote(int... bytes) {
            for (int[] w : log) if (Arrays.equals(w, bytes)) return true
            return false
        }

        /** Last value written to a register, -1 if never written. */
        int last(int reg) {
            int v = -1
            for (int[] w : log) if (w.length == 2 && w[0] == reg) v = w[1]
            return v
        }

        @Override
        void write(byte[] data) {
            int[] e = new int[data.length]
            for (int i = 0; i < data.length; i++) e[i] = data[i] & 0xFF
            log.add(e)
            for (int i = 1; i < data.length; i++) regs.put(e[0] + i - 1, e[i])
            if (data.length == 2 && (e[0] == 0x03 || e[0] == 0x04)) {        // self-test start / end
                int st = regs.getOrDefault(0x2D, 0)
                regs.put(0x2D, (e[1] & 0x80) != 0 ? (st | 0x01) : (st & ~0x01))
            }
            if (data.length == 2 && e[0] == 0x0A) {
                int st = regs.getOrDefault(0x2D, 0)
                if (e[1] != 0) {
                    int[] c = new int[9]
                    c[0] = e[1]
                    for (int i = 0; i < 8; i++) c[i + 1] = regs.getOrDefault(0x0B + i, 0)
                    cmds.add(c)
                    regs.put(0x2D, st | 0x80)
                } else {
                    regs.put(0x2D, st & ~0x80)
                }
            }
        }

        @Override
        byte[] writeRead(byte[] data, int n) {
            byte[] out = new byte[n]
            int reg = data[0] & 0xFF
            for (int i = 0; i < n; i++) {
                out[i] = (byte) (reg == 0x17 ? (fifo.isEmpty() ? 0 : fifo.poll()) : regs.getOrDefault(reg + i, 0))
            }
            return out
        }

        int[] lastCmd() { return cmds.get(cmds.size() - 1); }
        int cal(int[] cmd, int i) { return cmd[i + 1]; }
    }


    QmiMock mock
    QMI8658AFull s

    private void make() {
        mock = new QmiMock()
        s = new QMI8658AFull(mock)
    }

    def "init sequence"() {
        when:
        make()
        assert (mock.log.get(0) as List) == [0x60, 0xB0]
        assert mock.wrote(0x02, 0x40) && mock.wrote(0x09, 0x80) && mock.wrote(0x03, 0x05)
        assert mock.wrote(0x04, 0x45) && mock.wrote(0x06, 0x77)
        assert (mock.log.get(mock.log.size() - 1) as List) == [0x08, 0x03]

        then:
        noExceptionThrown()
    }

    def "WHO_AM_I mismatch throws"() {
        when:
        new QMI8658AMinimal(new MockConnection())

        then:
        thrown(IOException)
    }

    def "accel gyro conversion"() {
        when:
        make()
        mock.setLe(0x35, 16384, -8192, 0)
        double[] a = s.accel()
        assert Math.abs(G - (a[0])) <= 1e-3
        assert Math.abs(-G / 2 - (a[1])) <= 1e-3
        assert Math.abs(0.0 - (a[2])) <= 1e-3
        mock.setLe(0x3B, 128, -256, 64)
        double[] g = s.gyro()
        assert Math.abs(D2R - (g[0])) <= 1e-6
        assert Math.abs(-2 * D2R - (g[1])) <= 1e-6
        assert Math.abs(0.5 * D2R - (g[2])) <= 1e-6

        then:
        noExceptionThrown()
    }

    def "raw read all temperature"() {
        when:
        make()
        mock.setLe(0x33, 0x1980, 2048, 0, -2048, 16, 32, -64)
        assert (s.accelRaw() as List) == ([2048, 0, -2048] as int[] as List)
        assert (s.gyroRaw() as List) == ([16, 32, -64] as int[] as List)
        def sm = s.readAll()
        assert Math.abs(25.5 - (sm.tempC)) <= 1e-3
        assert Math.abs(2048.0 / 16384 * G - (sm.accel[0])) <= 1e-3
        assert Math.abs(-64.0 / 128 * D2R - (sm.gyro[2])) <= 1e-6
        assert Math.abs(25.5 - (s.temperature())) <= 1e-3

        then:
        noExceptionThrown()
    }

    def "timestamp data ready"() {
        when:
        make()
        mock.set(0x30, 1, 2, 3)
        assert (0x030201) == (s.timestamp())
        mock.set(0x2E, 0x00)
        assert !(s.dataReady())
        mock.set(0x2E, 0x02)
        assert s.dataReady()

        then:
        noExceptionThrown()
    }

    def "configure updates sensitivity"() {
        when:
        make()
        s.configureAccel(2, 4)
        assert (0x24) == (mock.last(0x03))
        mock.setLe(0x35, 4096, 0, 0)
        assert Math.abs(G - (s.accel()[0])) <= 1e-3
        s.configureGyro(7, 3)
        assert (0x73) == (mock.last(0x04))
        mock.setLe(0x3B, 16, 0, 0)
        assert Math.abs(D2R - (s.gyro()[0])) <= 1e-6
        try { s.configureAccel(4, 5); assert false } catch (IllegalArgumentException ignored) { }
        s.configureFilters(true, 3, false, 1)
        assert (0x27) == (mock.last(0x06))

        then:
        noExceptionThrown()
    }

    def "sensors and power"() {
        when:
        make()
        s.setSensors(true, false, false)
        assert (0x01) == (mock.last(0x08))
        s.setSensors(true, true, true)
        assert (0x13) == (mock.last(0x08))
        s.setSensors(true, true, false)
        s.setPowerDown(true)
        assert (0x41) == (mock.last(0x02))
        assert (0x00) == (mock.last(0x08))
        s.setPowerDown(false)
        assert (0x40) == (mock.last(0x02))

        then:
        noExceptionThrown()
    }

    def "sync sample"() {
        when:
        make()
        s.setSyncSample(true)
        assert (0x12) == (mock.lastCmd()[0])
        assert (1) == (mock.cal(mock.lastCmd(), 0))
        assert mock.wrote(0x08, 0x83)
        mock.regs.put(0x2D, 0x03)
        mock.setLe(0x35, 16384, 0, 0)
        assert Math.abs(G - (s.accel()[0])) <= 1e-3
        s.setSyncSample(false)
        assert (0x03) == (mock.last(0x08))
        assert (0) == (mock.cal(mock.lastCmd(), 0))

        then:
        noExceptionThrown()
    }

    def "fifo"() {
        when:
        make()
        s.configureFifo(2, 1, 16)
        assert mock.wrote(0x14, 0x06) && mock.wrote(0x13, 0x10)
        mock.set(0x16, 0x00)
        mock.set(0x15, 12)
        assert (24) == (s.fifoCount())
        assert (0) == (s.fifoStatus())
        for (int i = 0; i < 24; i++) mock.fifo.add(i)
        mock.cmds.clear()
        def data = s.readFifo(-1)
        assert (24) == (data.length)
        assert (23) == (data[23])
        assert (1) == (mock.cmds.size())
        assert (0x05) == (mock.cmds.get(0)[0])
        assert mock.wrote(0x02, 0x00)
        assert (0x40) == (mock.last(0x02))
        assert (0x06) == (mock.last(0x14))
        mock.fifo.clear()
        for (int i = 0; i < 24; i++) mock.fifo.add(i)
        assert (10) == (s.readFifo(10).length)
        s.resetFifo()
        assert (0x04) == (mock.lastCmd()[0])
        s.configureFifo(0, 3, 0)
        assert (0x03) == (mock.last(0x08))

        then:
        noExceptionThrown()
    }

    def "motion engines"() {
        when:
        make()
        s.configureAnyMotion(0.25, 3, 0x07, false)
        assert (2) == (mock.cmds.size())
        def c1 = mock.cmds.get(0)
        def c2 = mock.cmds.get(1)
        assert (0x0E) == (c1[0])
        assert (0x0E) == (c2[0])
        assert (8) == (mock.cal(c1, 0))
        assert (4) == (mock.cal(c1, 3))
        assert (0xF7) == (mock.cal(c1, 6))
        assert (0x10) == (mock.cal(c1, 7))
        assert (3) == (mock.cal(c2, 0))
        assert (30) == (mock.cal(c2, 1))
        assert (150) == (mock.cal(c2, 2))
        assert (600 & 0xFF) == (mock.cal(c2, 4))
        assert (600 >> 8) == (mock.cal(c2, 5))
        assert (0x20) == (mock.cal(c2, 7))
        assert mock.wrote(0x08, 0x00)
        assert (0x03) == (mock.last(0x08))
        s.configureSignificantMotion(100, 300)
        assert (100) == (mock.cal(mock.lastCmd(), 2))
        assert (300 & 0xFF) == (mock.cal(mock.lastCmd(), 4))
        assert (1) == (mock.cal(mock.lastCmd(), 5))
        s.enableMotion(true, true, false)
        assert (0x86) == (mock.last(0x09))
        s.enableMotion(false, false, false)
        assert (0x80) == (mock.last(0x09))

        then:
        noExceptionThrown()
    }

    def "tap and pedometer"() {
        when:
        make()
        s.configureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4)
        assert (2) == (mock.cmds.size())
        def t1 = mock.cmds.get(0)
        def t2 = mock.cmds.get(1)
        assert (0x0C) == (t1[0])
        assert (20) == (mock.cal(t1, 0))
        assert (0) == (mock.cal(t1, 1))
        assert (50) == (mock.cal(t1, 2))
        assert (250) == (mock.cal(t1, 4))
        assert (0x10) == (mock.cal(t1, 7))
        assert (8) == (mock.cal(t2, 0))
        assert (32) == (mock.cal(t2, 1))
        assert (0x33) == (mock.cal(t2, 2))
        assert (0x03) == (mock.cal(t2, 3))
        assert (0x9A) == (mock.cal(t2, 4))
        assert (0x01) == (mock.cal(t2, 5))
        assert (0x20) == (mock.cal(t2, 7))
        s.enableTap(true)
        assert (0x81) == (mock.last(0x09))
        mock.set(0x59, 0xA2)
        def ts = s.tapStatus()
        assert (2) == (ts.count)
        assert (2) == (ts.axis)
        assert ts.negative
        s.enableTap(false)

        mock.cmds.clear()
        s.configurePedometer(50, 200, 100, 200, 20, 10, 0, 4)
        def p1 = mock.cmds.get(0)
        def p2 = mock.cmds.get(1)
        assert (0x0D) == (p1[0])
        assert (50) == (mock.cal(p1, 0))
        assert (205) == (mock.cal(p1, 2))
        assert (102) == (mock.cal(p1, 4))
        assert (200) == (mock.cal(p2, 0))
        assert (20) == (mock.cal(p2, 2))
        assert (10) == (mock.cal(p2, 3))
        assert (4) == (mock.cal(p2, 5))
        s.enablePedometer(true)
        assert (0x90) == (mock.last(0x09))
        mock.set(0x5A, 0x34, 0x12, 0x00)
        assert (0x1234) == (s.stepCount())
        s.resetStepCount()
        assert (0x0F) == (mock.lastCmd()[0])

        then:
        noExceptionThrown()
    }

    def "wake on motion"() {
        when:
        make()
        s.setWakeOnMotion(100, 2, 1, 5)
        assert mock.wrote(0x03, 0x0C)
        assert (0x08) == (mock.lastCmd()[0])
        assert (100) == (mock.cal(mock.lastCmd(), 0))
        assert (0xC5) == (mock.cal(mock.lastCmd(), 1))
        assert mock.wrote(0x08, 0x01)
        assert (mock.last(0x02) & 0x10) != 0
        s.setWakeOnMotion(0, 1, 0, 0)
        assert (0) == (mock.cal(mock.lastCmd(), 0))
        assert (0) == (mock.last(0x02) & 0x18)

        then:
        noExceptionThrown()
    }

    def "offsets calibration self test"() {
        when:
        make()
        s.setAccelOffset(G, 0.0, -G)
        def c = mock.lastCmd()
        assert (0x09) == (c[0])
        assert (0x00) == (mock.cal(c, 0))
        assert (0x10) == (mock.cal(c, 1))
        assert (0x00) == (mock.cal(c, 4))
        assert (0xF0) == (mock.cal(c, 5))
        s.setGyroOffset(D2R, 0.0, 0.0)
        c = mock.lastCmd()
        assert (0x0A) == (c[0])
        assert (32) == (mock.cal(c, 0))
        mock.set(0x46, 0x00)
        def cal = s.calibrateGyro()
        assert cal.success
        assert (0xA2) == (mock.lastCmd()[0])
        mock.set(0x46, 0x01)
        cal = s.calibrateGyro()
        assert !(cal.success)
        assert (1) == (cal.codStatus)
        mock.setLe(0x51, 1000, 2000, 3000)
        assert (s.getGyroGains() as List) == ([1000, 2000, 3000] as int[] as List)
        s.setGyroGains(1000, 2000, 3000)
        c = mock.lastCmd()
        assert (0xAA) == (c[0])
        assert (0xE8) == (mock.cal(c, 0))
        assert (0x03) == (mock.cal(c, 1))
        assert (0xB8) == (mock.cal(c, 4))
        assert (0x0B) == (mock.cal(c, 5))

        mock.setLe(0x51, 2048, 2048, -2048)
        def r = s.selfTestAccel()
        assert r.passed
        assert Math.abs(G - (r.values[0])) <= 1e-3
        mock.setLe(0x51, 16 * 400, 16 * 400, 16 * 400)
        r = s.selfTestGyro()
        assert r.passed
        assert Math.abs(400 * D2R - (r.values[0])) <= 1e-3
        mock.setLe(0x51, 16 * 100, 16 * 400, 16 * 400)
        assert !(s.selfTestGyro().passed)

        then:
        noExceptionThrown()
    }

    def "device info pullups reset"() {
        when:
        make()
        mock.set(0x01, 0x7C)
        mock.set(0x49, 1, 2, 0, 3)
        mock.set(0x4B, 3)
        mock.set(0x51, 1, 2, 3, 4, 5, 6)
        def info = s.deviceInfo()
        assert (0x7C) == (info.revision)
        assert (info.firmware as List) == ([1, 2, 3] as byte[] as List)
        assert (info.usid as List) == ([1, 2, 3, 4, 5, 6] as byte[] as List)
        assert (0x10) == (mock.lastCmd()[0])
        s.setPullups(false, true, true, false)
        assert (0x11) == (mock.lastCmd()[0])
        assert (0x09) == (mock.cal(mock.lastCmd(), 0))
        s.reset()
        assert mock.wrote(0x60, 0xB0)

        then:
        noExceptionThrown()
    }

    def "interrupts"() {
        when:
        make()
        s.setInterruptPins(1, 1)
        assert (mock.last(0x09) & 0x40) != 0
        s.enableInterrupt(QMI8658AFull.SOURCE_TAP)
        assert (mock.last(0x09) & 0x01) != 0 && (mock.last(0x02) & 0x08) != 0
        s.enableInterrupt(QMI8658AFull.SOURCE_DATA_READY)
        assert (mock.last(0x02) & 0x10) != 0
        s.disableInterrupt(QMI8658AFull.SOURCE_DATA_READY)
        assert (0) == (mock.last(0x02) & 0x10)
        assert (mock.last(0x08) & 0x20) != 0
        s.disableInterrupt(QMI8658AFull.SOURCE_TAP)
        assert (0) == (mock.last(0x02) & 0x08)
        s.setInterruptPins(2, 2)
        mock.set(0x2E, 0x01, 0xA2)
        mock.set(0x16, 0xC0)
        def expect = QMI8658AFull.SOURCE_DATA_READY | QMI8658AFull.SOURCE_FIFO_WATERMARK
                | QMI8658AFull.SOURCE_FIFO_FULL | QMI8658AFull.SOURCE_ANY_MOTION
                | QMI8658AFull.SOURCE_SIGNIFICANT_MOTION | QMI8658AFull.SOURCE_TAP
        assert (expect) == (s.pollInterrupt())

        then:
        noExceptionThrown()
    }
}
