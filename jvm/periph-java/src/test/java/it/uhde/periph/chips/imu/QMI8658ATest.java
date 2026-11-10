package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QMI8658ATest {

    static final double G = 9.80665;
    static final double D2R = Math.PI / 180.0;

    /** I²C fake that models the CTRL9 handshake, the self-test Avail flag and the FIFO_DATA port. */
    static class QmiMock extends MockConnection {
        final Map<Integer, Integer> regs = new HashMap<>();
        final List<int[]> log = new ArrayList<>();            // {byte0, byte1, ...}
        final List<int[]> cmds = new ArrayList<>();           // {cmd, cal1..cal4h (8 values)}
        final Deque<Integer> fifo = new ArrayDeque<>();

        QmiMock() { regs.put(0x00, 0x05); }

        void set(int reg, int... values) {
            for (int i = 0; i < values.length; i++) regs.put(reg + i, values[i] & 0xFF);
        }

        void setLe(int reg, int... vals) {
            for (int i = 0; i < vals.length; i++) set(reg + 2 * i, vals[i] & 0xFF, (vals[i] >> 8) & 0xFF);
        }

        boolean wrote(int... bytes) {
            for (int[] w : log) if (Arrays.equals(w, bytes)) return true;
            return false;
        }

        /** Last value written to a register, -1 if never written. */
        int last(int reg) {
            int v = -1;
            for (int[] w : log) if (w.length == 2 && w[0] == reg) v = w[1];
            return v;
        }

        @Override
        public void write(byte[] data) {
            int[] e = new int[data.length];
            for (int i = 0; i < data.length; i++) e[i] = data[i] & 0xFF;
            log.add(e);
            for (int i = 1; i < data.length; i++) regs.put(e[0] + i - 1, e[i]);
            if (data.length == 2 && (e[0] == 0x03 || e[0] == 0x04)) {        // self-test start / end
                int st = regs.getOrDefault(0x2D, 0);
                regs.put(0x2D, (e[1] & 0x80) != 0 ? (st | 0x01) : (st & ~0x01));
            }
            if (data.length == 2 && e[0] == 0x0A) {
                int st = regs.getOrDefault(0x2D, 0);
                if (e[1] != 0) {
                    int[] c = new int[9];
                    c[0] = e[1];
                    for (int i = 0; i < 8; i++) c[i + 1] = regs.getOrDefault(0x0B + i, 0);
                    cmds.add(c);
                    regs.put(0x2D, st | 0x80);
                } else {
                    regs.put(0x2D, st & ~0x80);
                }
            }
        }

        @Override
        public byte[] writeRead(byte[] data, int n) {
            byte[] out = new byte[n];
            int reg = data[0] & 0xFF;
            for (int i = 0; i < n; i++) {
                out[i] = reg == 0x17 ? (byte) (fifo.isEmpty() ? 0 : fifo.poll())
                                     : (byte) (int) regs.getOrDefault(reg + i, 0);
            }
            return out;
        }

        int[] lastCmd() { return cmds.get(cmds.size() - 1); }
        int cal(int[] cmd, int i) { return cmd[i + 1]; }
    }

    private QmiMock mock;
    private QMI8658AFull s;

    private void make() throws IOException {
        mock = new QmiMock();
        s = new QMI8658AFull(mock);
    }

    @Test
    void initSequence() throws IOException {
        make();
        assertArrayEquals(new int[]{0x60, 0xB0}, mock.log.get(0));
        assertTrue(mock.wrote(0x02, 0x40));
        assertTrue(mock.wrote(0x09, 0x80));
        assertTrue(mock.wrote(0x03, 0x05));
        assertTrue(mock.wrote(0x04, 0x45));
        assertTrue(mock.wrote(0x06, 0x77));
        assertArrayEquals(new int[]{0x08, 0x03}, mock.log.get(mock.log.size() - 1));
    }

    @Test
    void whoAmIMismatchThrows() {
        MockConnection bad = new MockConnection();
        assertThrows(IOException.class, () -> new QMI8658AMinimal(bad));
    }

    @Test
    void accelGyroConversion() throws IOException {
        make();
        mock.setLe(0x35, 16384, -8192, 0);
        double[] a = s.accel();
        assertEquals(G, a[0], 1e-3);
        assertEquals(-G / 2, a[1], 1e-3);
        assertEquals(0.0, a[2], 1e-3);
        mock.setLe(0x3B, 128, -256, 64);
        double[] g = s.gyro();
        assertEquals(D2R, g[0], 1e-6);
        assertEquals(-2 * D2R, g[1], 1e-6);
        assertEquals(0.5 * D2R, g[2], 1e-6);
    }

    @Test
    void rawReadAllTemperature() throws IOException {
        make();
        mock.setLe(0x33, 0x1980, 2048, 0, -2048, 16, 32, -64);
        assertArrayEquals(new int[]{2048, 0, -2048}, s.accelRaw());
        assertArrayEquals(new int[]{16, 32, -64}, s.gyroRaw());
        QMI8658AFull.Sample sm = s.readAll();
        assertEquals(25.5, sm.tempC, 1e-3);
        assertEquals(2048.0 / 16384 * G, sm.accel[0], 1e-3);
        assertEquals(-64.0 / 128 * D2R, sm.gyro[2], 1e-6);
        assertEquals(25.5, s.temperature(), 1e-3);
    }

    @Test
    void timestampDataReady() throws IOException {
        make();
        mock.set(0x30, 1, 2, 3);
        assertEquals(0x030201, s.timestamp());
        mock.set(0x2E, 0x00);
        assertFalse(s.dataReady());
        mock.set(0x2E, 0x02);
        assertTrue(s.dataReady());
    }

    @Test
    void configureUpdatesSensitivity() throws IOException {
        make();
        s.configureAccel(2, 4);
        assertEquals(0x24, mock.last(0x03));
        mock.setLe(0x35, 4096, 0, 0);
        assertEquals(G, s.accel()[0], 1e-3);
        s.configureGyro(7, 3);
        assertEquals(0x73, mock.last(0x04));
        mock.setLe(0x3B, 16, 0, 0);
        assertEquals(D2R, s.gyro()[0], 1e-6);
        assertThrows(IllegalArgumentException.class, () -> s.configureAccel(4, 5));
        s.configureFilters(true, 3, false, 1);
        assertEquals(0x27, mock.last(0x06));
    }

    @Test
    void sensorsAndPower() throws IOException {
        make();
        s.setSensors(true, false, false);
        assertEquals(0x01, mock.last(0x08));
        s.setSensors(true, true, true);
        assertEquals(0x13, mock.last(0x08));
        s.setSensors(true, true, false);
        s.setPowerDown(true);
        assertEquals(0x41, mock.last(0x02));
        assertEquals(0x00, mock.last(0x08));
        s.setPowerDown(false);
        assertEquals(0x40, mock.last(0x02));
    }

    @Test
    void syncSample() throws IOException {
        make();
        s.setSyncSample(true);
        assertEquals(0x12, mock.lastCmd()[0]);
        assertEquals(1, mock.cal(mock.lastCmd(), 0));
        assertTrue(mock.wrote(0x08, 0x83));
        mock.regs.put(0x2D, 0x03);
        mock.setLe(0x35, 16384, 0, 0);
        assertEquals(G, s.accel()[0], 1e-3);
        s.setSyncSample(false);
        assertEquals(0x03, mock.last(0x08));
        assertEquals(0, mock.cal(mock.lastCmd(), 0));
    }

    @Test
    void fifo() throws IOException {
        make();
        s.configureFifo(2, 1, 16);
        assertTrue(mock.wrote(0x14, 0x06) && mock.wrote(0x13, 0x10));
        mock.set(0x16, 0x00);
        mock.set(0x15, 12);
        assertEquals(24, s.fifoCount());
        assertEquals(0, s.fifoStatus());
        for (int i = 0; i < 24; i++) mock.fifo.add(i);
        mock.cmds.clear();
        byte[] data = s.readFifo(-1);
        assertEquals(24, data.length);
        assertEquals(23, data[23]);
        assertEquals(1, mock.cmds.size());
        assertEquals(0x05, mock.cmds.get(0)[0]);
        assertTrue(mock.wrote(0x02, 0x00));
        assertEquals(0x40, mock.last(0x02));
        assertEquals(0x06, mock.last(0x14));
        mock.fifo.clear();
        for (int i = 0; i < 24; i++) mock.fifo.add(i);
        assertEquals(10, s.readFifo(10).length);
        s.resetFifo();
        assertEquals(0x04, mock.lastCmd()[0]);
        s.configureFifo(0, 3, 0);
        assertEquals(0x03, mock.last(0x08));
    }

    @Test
    void motionEngines() throws IOException {
        make();
        s.configureAnyMotion(0.25, 3, 0x07, false);
        assertEquals(2, mock.cmds.size());
        int[] c1 = mock.cmds.get(0);
        int[] c2 = mock.cmds.get(1);
        assertEquals(0x0E, c1[0]);
        assertEquals(0x0E, c2[0]);
        assertEquals(8, mock.cal(c1, 0));
        assertEquals(4, mock.cal(c1, 3));
        assertEquals(0xF7, mock.cal(c1, 6));
        assertEquals(0x10, mock.cal(c1, 7));
        assertEquals(3, mock.cal(c2, 0));
        assertEquals(30, mock.cal(c2, 1));
        assertEquals(150, mock.cal(c2, 2));
        assertEquals(600 & 0xFF, mock.cal(c2, 4));
        assertEquals(600 >> 8, mock.cal(c2, 5));
        assertEquals(0x20, mock.cal(c2, 7));
        assertTrue(mock.wrote(0x08, 0x00));
        assertEquals(0x03, mock.last(0x08));
        s.configureSignificantMotion(100, 300);
        assertEquals(100, mock.cal(mock.lastCmd(), 2));
        assertEquals(300 & 0xFF, mock.cal(mock.lastCmd(), 4));
        assertEquals(1, mock.cal(mock.lastCmd(), 5));
        s.enableMotion(true, true, false);
        assertEquals(0x86, mock.last(0x09));
        s.enableMotion(false, false, false);
        assertEquals(0x80, mock.last(0x09));
    }

    @Test
    void tapAndPedometer() throws IOException {
        make();
        s.configureTap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4);
        assertEquals(2, mock.cmds.size());
        int[] t1 = mock.cmds.get(0);
        int[] t2 = mock.cmds.get(1);
        assertEquals(0x0C, t1[0]);
        assertEquals(20, mock.cal(t1, 0));
        assertEquals(0, mock.cal(t1, 1));
        assertEquals(50, mock.cal(t1, 2));
        assertEquals(250, mock.cal(t1, 4));
        assertEquals(0x10, mock.cal(t1, 7));
        assertEquals(8, mock.cal(t2, 0));
        assertEquals(32, mock.cal(t2, 1));
        assertEquals(0x33, mock.cal(t2, 2));
        assertEquals(0x03, mock.cal(t2, 3));
        assertEquals(0x9A, mock.cal(t2, 4));
        assertEquals(0x01, mock.cal(t2, 5));
        assertEquals(0x20, mock.cal(t2, 7));
        s.enableTap(true);
        assertEquals(0x81, mock.last(0x09));
        mock.set(0x59, 0xA2);
        QMI8658AFull.TapStatus ts = s.tapStatus();
        assertEquals(2, ts.count);
        assertEquals(2, ts.axis);
        assertTrue(ts.negative);
        s.enableTap(false);

        mock.cmds.clear();
        s.configurePedometer(50, 200, 100, 200, 20, 10, 0, 4);
        int[] p1 = mock.cmds.get(0);
        int[] p2 = mock.cmds.get(1);
        assertEquals(0x0D, p1[0]);
        assertEquals(50, mock.cal(p1, 0));
        assertEquals(205, mock.cal(p1, 2));
        assertEquals(102, mock.cal(p1, 4));
        assertEquals(200, mock.cal(p2, 0));
        assertEquals(20, mock.cal(p2, 2));
        assertEquals(10, mock.cal(p2, 3));
        assertEquals(4, mock.cal(p2, 5));
        s.enablePedometer(true);
        assertEquals(0x90, mock.last(0x09));
        mock.set(0x5A, 0x34, 0x12, 0x00);
        assertEquals(0x1234, s.stepCount());
        s.resetStepCount();
        assertEquals(0x0F, mock.lastCmd()[0]);
    }

    @Test
    void wakeOnMotion() throws IOException {
        make();
        s.setWakeOnMotion(100, 2, 1, 5);
        assertTrue(mock.wrote(0x03, 0x0C));
        assertEquals(0x08, mock.lastCmd()[0]);
        assertEquals(100, mock.cal(mock.lastCmd(), 0));
        assertEquals(0xC5, mock.cal(mock.lastCmd(), 1));
        assertTrue(mock.wrote(0x08, 0x01));
        assertTrue((mock.last(0x02) & 0x10) != 0);
        s.setWakeOnMotion(0, 1, 0, 0);
        assertEquals(0, mock.cal(mock.lastCmd(), 0));
        assertEquals(0, mock.last(0x02) & 0x18);
    }

    @Test
    void offsetsCalibrationSelfTest() throws IOException {
        make();
        s.setAccelOffset(G, 0.0, -G);
        int[] c = mock.lastCmd();
        assertEquals(0x09, c[0]);
        assertEquals(0x00, mock.cal(c, 0));
        assertEquals(0x10, mock.cal(c, 1));
        assertEquals(0x00, mock.cal(c, 4));
        assertEquals(0xF0, mock.cal(c, 5));
        s.setGyroOffset(D2R, 0.0, 0.0);
        c = mock.lastCmd();
        assertEquals(0x0A, c[0]);
        assertEquals(32, mock.cal(c, 0));
        mock.set(0x46, 0x00);
        QMI8658AFull.Calibration cal = s.calibrateGyro();
        assertTrue(cal.success);
        assertEquals(0xA2, mock.lastCmd()[0]);
        mock.set(0x46, 0x01);
        cal = s.calibrateGyro();
        assertFalse(cal.success);
        assertEquals(1, cal.codStatus);
        mock.setLe(0x51, 1000, 2000, 3000);
        assertArrayEquals(new int[]{1000, 2000, 3000}, s.getGyroGains());
        s.setGyroGains(1000, 2000, 3000);
        c = mock.lastCmd();
        assertEquals(0xAA, c[0]);
        assertEquals(0xE8, mock.cal(c, 0));
        assertEquals(0x03, mock.cal(c, 1));
        assertEquals(0xB8, mock.cal(c, 4));
        assertEquals(0x0B, mock.cal(c, 5));

        mock.setLe(0x51, 2048, 2048, -2048);
        QMI8658AFull.SelfTestResult r = s.selfTestAccel();
        assertTrue(r.passed);
        assertEquals(G, r.values[0], 1e-3);
        mock.setLe(0x51, 16 * 400, 16 * 400, 16 * 400);
        r = s.selfTestGyro();
        assertTrue(r.passed);
        assertEquals(400 * D2R, r.values[0], 1e-3);
        mock.setLe(0x51, 16 * 100, 16 * 400, 16 * 400);
        assertFalse(s.selfTestGyro().passed);
    }

    @Test
    void deviceInfoPullupsReset() throws IOException {
        make();
        mock.set(0x01, 0x7C);
        mock.set(0x49, 1, 2, 0, 3);
        mock.set(0x4B, 3);
        mock.set(0x51, 1, 2, 3, 4, 5, 6);
        QMI8658AFull.DeviceInfo info = s.deviceInfo();
        assertEquals(0x7C, info.revision);
        assertArrayEquals(new byte[]{1, 2, 3}, info.firmware);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, info.usid);
        assertEquals(0x10, mock.lastCmd()[0]);
        s.setPullups(false, true, true, false);
        assertEquals(0x11, mock.lastCmd()[0]);
        assertEquals(0x09, mock.cal(mock.lastCmd(), 0));
        s.reset();
        assertTrue(mock.wrote(0x60, 0xB0));
    }

    @Test
    void interrupts() throws IOException {
        make();
        s.setInterruptPins(1, 1);
        assertTrue((mock.last(0x09) & 0x40) != 0);
        s.enableInterrupt(QMI8658AFull.SOURCE_TAP);
        assertTrue((mock.last(0x09) & 0x01) != 0 && (mock.last(0x02) & 0x08) != 0);
        s.enableInterrupt(QMI8658AFull.SOURCE_DATA_READY);
        assertTrue((mock.last(0x02) & 0x10) != 0);
        s.disableInterrupt(QMI8658AFull.SOURCE_DATA_READY);
        assertEquals(0, mock.last(0x02) & 0x10);
        assertTrue((mock.last(0x08) & 0x20) != 0);
        s.disableInterrupt(QMI8658AFull.SOURCE_TAP);
        assertEquals(0, mock.last(0x02) & 0x08);
        s.setInterruptPins(2, 2);
        mock.set(0x2E, 0x01, 0xA2);
        mock.set(0x16, 0xC0);
        int expect = QMI8658AFull.SOURCE_DATA_READY | QMI8658AFull.SOURCE_FIFO_WATERMARK
                | QMI8658AFull.SOURCE_FIFO_FULL | QMI8658AFull.SOURCE_ANY_MOTION
                | QMI8658AFull.SOURCE_SIGNIFICANT_MOTION | QMI8658AFull.SOURCE_TAP;
        assertEquals(expect, s.pollInterrupt());
    }
}
