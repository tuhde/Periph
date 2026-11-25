package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BNO055Test {

    private MockConnection mock;
    private BNO055Full s;

    private static int[] le(int... vals) {
        int[] out = new int[vals.length * 2];
        for (int i = 0; i < vals.length; i++) {
            out[2 * i] = vals[i] & 0xFF;
            out[2 * i + 1] = (vals[i] >> 8) & 0xFF;
        }
        return out;
    }

    private static MockConnection fresh() {
        MockConnection m = new MockConnection();
        m.setRegister(0x00, 0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15);
        return m;
    }

    private void make() throws IOException {
        mock = fresh();
        s = new BNO055Full(mock);
    }

    /** Values written to a register with a single-byte payload, in order. */
    private List<Integer> writesTo(int reg) {
        List<Integer> out = new ArrayList<>();
        for (byte[] w : mock.writes()) if (w.length == 2 && (w[0] & 0xFF) == reg) out.add(w[1] & 0xFF);
        return out;
    }

    @Test
    void initSequence() throws IOException {
        make();
        List<byte[]> w2 = new ArrayList<>();
        for (byte[] w : mock.writes()) if (w.length == 2) w2.add(w);
        assertTrue(w2.stream().anyMatch(w -> w[0] == 0x3E && w[1] == 0x00));
        assertTrue(w2.stream().anyMatch(w -> w[0] == 0x07 && w[1] == 0x00));
        assertTrue(w2.stream().anyMatch(w -> (w[0] & 0xFF) == 0x3B && (w[1] & 0xFF) == 0x86));
        byte[] last = w2.get(w2.size() - 1);
        assertEquals(0x3D, last[0]);
        assertEquals(0x0C, last[1]);
    }

    @Test
    void wrongChipId() {
        MockConnection bad = new MockConnection();
        bad.setRegister(0x00, 0x55);
        assertThrows(IOException.class, () -> new BNO055Minimal(bad));
    }

    @Test
    void euler() throws IOException {
        mock = fresh();
        BNO055Minimal m = new BNO055Minimal(mock);
        mock.setRegister(0x1A, le(1414, -900, 450));
        double[] e = m.euler();
        assertEquals(1414 / 900.0, e[0], 1e-9);
        assertEquals(-1.0, e[1], 1e-9);
        assertEquals(0.5, e[2], 1e-9);
    }

    @Test
    void vectors() throws IOException {
        make();
        mock.setRegister(0x20, le(16384, -8192, 0, 4096));
        assertArrayEquals(new double[]{1.0, -0.5, 0.0, 0.25}, s.quaternion(), 1e-9);
        mock.setRegister(0x28, le(100, -250, 981));
        assertArrayEquals(new double[]{1.0, -2.5, 9.81}, s.linearAcceleration(), 1e-9);
        mock.setRegister(0x2E, le(0, 0, 981));
        assertEquals(9.81, s.gravity()[2], 1e-9);
        mock.setRegister(0x08, le(-100, 200, 300));
        assertArrayEquals(new double[]{-1.0, 2.0, 3.0}, s.acceleration(), 1e-9);
        mock.setRegister(0x0E, le(160, -32, 16));
        assertArrayEquals(new double[]{10.0, -2.0, 1.0}, s.magneticField(), 1e-9);
        mock.setRegister(0x14, le(900, 0, -450));
        assertArrayEquals(new double[]{1.0, 0.0, -0.5}, s.angularRate(), 1e-9);
        mock.setRegister(0x34, 0xE7);
        assertEquals(-25, s.temperature());
    }

    @Test
    void calibrationStatus() throws IOException {
        make();
        mock.setRegister(0x35, 0xE4);
        assertArrayEquals(new int[]{3, 2, 1, 0}, s.calibrationStatus());
        assertFalse(s.isCalibrated());
        mock.setRegister(0x35, 0xFF);
        assertTrue(s.isCalibrated());
    }

    @Test
    void modes() throws IOException {
        make();
        mock.writes().clear();
        s.setMode(BNO055Full.MODE_IMU);
        assertEquals(List.of(0x00, 0x08), writesTo(0x3D));
        mock.setRegister(0x3D, 0x08);
        assertEquals(8, s.getMode());
        assertThrows(IllegalArgumentException.class, () -> s.setMode(13));
    }

    @Test
    void calibrationBlob() throws IOException {
        make();
        byte[] blob = new byte[22];
        for (int i = 0; i < 22; i++) blob[i] = (byte) (i + 1);
        for (int i = 0; i < 22; i++) mock.setRegister(0x55 + i, i + 1);
        mock.writes().clear();
        assertArrayEquals(blob, s.getCalibration());
        assertEquals(List.of(0x00, 0x0C), writesTo(0x3D));
        mock.writes().clear();
        s.setCalibration(blob);
        int n = 0;
        for (byte[] w : mock.writes()) {
            int reg = w[0] & 0xFF;
            if (w.length == 2 && reg >= 0x55 && reg <= 0x6A) {
                assertEquals(0x55 + n, reg);
                assertEquals(blob[n], w[1]);
                n++;
            }
        }
        assertEquals(22, n);
        assertEquals(List.of(0x00, 0x0C), writesTo(0x3D));
        assertThrows(IllegalArgumentException.class, () -> s.setCalibration(new byte[21]));
    }

    @Test
    void configuration() throws IOException {
        make();
        mock.writes().clear();
        s.setAxisRemap(0x21, 0x02);
        assertEquals(List.of(0x21), writesTo(0x41));
        assertEquals(List.of(0x02), writesTo(0x42));
        assertEquals(List.of(0x00, 0x0C), writesTo(0x3D));
        s.setPowerMode("low_power");
        assertEquals(List.of(1), writesTo(0x3E));
        assertThrows(IllegalArgumentException.class, () -> s.setPowerMode("bogus"));
        s.setTemperatureSource("gyroscope");
        assertEquals(List.of(1), writesTo(0x40));
        mock.writes().clear();
        s.useExternalCrystal(true);
        s.useExternalCrystal(false);
        assertEquals(List.of(0x80, 0x00), writesTo(0x3F));
    }

    @Test
    void selfTestStatusRevisionsUniqueId() throws IOException {
        make();
        mock.setRegister(0x36, 0x0D);
        mock.writes().clear();
        assertArrayEquals(new boolean[]{true, false, true, true}, s.selfTest());
        assertEquals(List.of(0x01), writesTo(0x3F));
        assertEquals(List.of(0x00, 0x0C), writesTo(0x3D));
        mock.setRegister(0x39, 1, 3);
        assertArrayEquals(new int[]{1, 3}, s.systemStatus());
        assertArrayEquals(new int[]{0xA0, 0xFB, 0x32, 0x0F, 0x0311, 0x15}, s.revisions());
        byte[] uid = new byte[16];
        for (int i = 0; i < 16; i++) { uid[i] = (byte) i; mock.setRegister(0x50 + i, i); }
        mock.writes().clear();
        assertArrayEquals(uid, s.uniqueId());
        assertEquals(List.of(1, 0), writesTo(0x07));
    }

    @Test
    void interrupts() throws IOException {
        make();
        mock.setRegister(0x10, 0);
        mock.setRegister(0x0F, 0);
        mock.writes().clear();
        s.enableInterrupt(BNO055Full.SOURCE_ACC_BSX_DRDY | BNO055Full.SOURCE_GYR_DRDY);
        assertEquals(List.of(0x11), writesTo(0x10));
        assertEquals(List.of(0x11), writesTo(0x0F));
        assertEquals(List.of(1, 0), writesTo(0x07));
        mock.setRegister(0x10, 0x11);
        mock.setRegister(0x0F, 0x11);
        mock.writes().clear();
        s.disableInterrupt(BNO055Full.SOURCE_GYR_DRDY);
        assertEquals(List.of(0x01), writesTo(0x10));
        assertEquals(List.of(0x01), writesTo(0x0F));
        mock.setRegister(0x37, 0x41);
        assertEquals(0x41, s.pollInterrupt());
    }

    @Test
    void pollingFallbackDeliversStatus() throws Exception {
        make();
        mock.setRegister(0x37, 0x41);
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        s.onInterrupt(seen::set);
        Thread.sleep(250);
        s.offInterrupt();
        assertEquals(0x41, seen.get());
    }

    @Test
    void reset() throws IOException {
        make();
        mock.writes().clear();
        s.reset();
        assertEquals(0x20, (int) writesTo(0x3F).get(0));
        List<Integer> om = writesTo(0x3D);
        assertEquals(0x0C, (int) om.get(om.size() - 1));
        assertEquals(0x86, (int) writesTo(0x3B).get(0));
    }
}
