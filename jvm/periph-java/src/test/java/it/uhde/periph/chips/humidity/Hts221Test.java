package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class Hts221Test {

    /** Mock that clears CTRL_REG2.BOOT like the real chip and can expose an INT pin. */
    static class ChipMock extends MockConnection {
        boolean stuckBoot = false;
        InputPin pin = null;

        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            if (!stuckBoot && data.length == 2 && (data[0] & 0xFF) == 0x21) {
                registers().put(0x21, (data[1] & 0xFF) & ~0x80);
            }
        }

        @Override
        public InputPin intPin() { return pin; }
    }

    static class FakePin implements InputPin {
        EdgeHandler handler;
        EdgeTrigger trigger;
        @Override public void onEdge(EdgeHandler h, EdgeTrigger t) { handler = h; trigger = t; }
        @Override public void offEdge(EdgeHandler h) { if (handler == h) handler = null; }
        @Override public boolean isHigh() { return false; }
        @Override public void close() {}
    }

    private ChipMock mock;

    private ChipMock make() {
        ChipMock m = new ChipMock();
        // Calibration block at 0xB0 (sub-address with auto-increment):
        // H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
        // H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
        m.setRegister(0xB0, 40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 0x2C, 0x01, 0xF4, 0x01);
        m.setRegister(0x0F, 0xBC);
        m.setRegister(0x27, 0x03);
        // Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
        m.setRegister(0xA8, 0x00, 0x50, 0x90, 0x01);
        mock = m;
        return m;
    }

    private static boolean wrote(MockConnection m, int reg, int value) {
        for (byte[] w : m.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg && (w[1] & 0xFF) == value) return true;
        }
        return false;
    }

    @Test
    void constructorReadsCalibrationAndStartsOneShot() throws IOException {
        make();
        new Hts221Minimal(mock);
        assertTrue(wrote(mock, 0x20, 0x84));
        assertEquals(0xB0, mock.writes().get(0)[0] & 0xFF);
    }

    @Test
    void readConvertsWithCalibration() throws IOException {
        make();
        Hts221Minimal s = new Hts221Minimal(mock);
        double[] v = s.read();
        assertEquals(15.0, v[0], 1e-3);
        assertEquals(30.0, v[1], 1e-3);
        assertTrue(wrote(mock, 0x21, 0x01));
    }

    @Test
    void humidityIsClamped() throws IOException {
        make();
        mock.setRegister(0xB6, 0x00, 0x00, 0, 0, 0x00, 0x10); // H0_T0_OUT=0, H1_T0_OUT=0x1000
        Hts221Minimal s = new Hts221Minimal(mock);
        mock.setRegister(0xA8, 0xFF, 0x7F, 0, 0);
        assertEquals(100.0, s.read()[1], 1e-6);
        make();
        s = new Hts221Minimal(mock);
        mock.setRegister(0xA8, 0x00, 0x80, 0, 0);
        assertEquals(0.0, s.read()[1], 1e-6);
    }

    @Test
    void readTimesOutWhenNeverReady() throws IOException {
        make();
        mock.setRegister(0x27, 0x00);
        Hts221Minimal s = new Hts221Minimal(mock);
        assertThrows(IOException.class, s::read);
    }

    @Test
    void calibrationTemperatureMsbBits() throws IOException {
        make();
        mock.setRegister(0xB5, 0x0D); // T0 bits 9:8 = 01, T1 bits 9:8 = 11
        Hts221Full f = new Hts221Full(mock);
        double[] cal = f.readCalibration();
        assertEquals((256 + 80) / 8.0, cal[2], 1e-9);
        assertEquals((768 + 160) / 8.0, cal[3], 1e-9);
    }

    @Test
    void singleValuesRawAndContinuous() throws IOException {
        make();
        Hts221Full f = new Hts221Full(mock);
        assertEquals(30.0, f.readHumidity(), 1e-3);
        assertEquals(15.0, f.readTemperature(), 1e-3);
        assertEquals(0x5000, f.readRawHumidity());
        assertEquals(400, f.readRawTemperature());
        mock.setRegister(0xA8, 0x00, 0xF0, 0, 0);
        assertEquals(-4096, f.readRawHumidity());
        mock.setRegister(0xA8, 0x00, 0x50, 0x90, 0x01);
        double[] c = f.readContinuous();
        assertEquals(15.0, c[0], 1e-3);
        assertEquals(30.0, c[1], 1e-3);
    }

    @Test
    void calibrationValues() throws IOException {
        make();
        double[] cal = new Hts221Full(mock).readCalibration();
        assertArrayEquals(new double[]{20.0, 40.0, 10.0, 20.0, 0x4000, 0x6000, 300, 500}, cal, 1e-9);
    }

    @Test
    void outputDataRate() throws IOException {
        make();
        Hts221Full f = new Hts221Full(mock);
        double[] hz = {12.5, 7, 1, 0};
        int[] ctrl = {0x87, 0x86, 0x85, 0x84};
        for (int i = 0; i < hz.length; i++) {
            f.setOutputDataRate(hz[i]);
            assertEquals(ctrl[i], mock.registers().get(0x20));
            assertEquals(hz[i], f.getOutputDataRate(), 1e-9);
        }
        assertThrows(IllegalArgumentException.class, () -> f.setOutputDataRate(5));
    }

    @Test
    void averaging() throws IOException {
        make();
        Hts221Full f = new Hts221Full(mock);
        int[][] cases = {{256, 512, 0x3F}, {2, 4, 0x00}, {16, 32, 0x1B}};
        for (int[] c : cases) {
            f.setAveraging(c[0], c[1]);
            assertEquals(c[2], mock.registers().get(0x10));
            assertArrayEquals(new int[]{c[0], c[1]}, f.getAveraging());
        }
        int[][] bad = {{3, 32}, {16, 2}, {1, 32}, {16, 1024}};
        for (int[] b : bad) {
            assertThrows(IllegalArgumentException.class, () -> f.setAveraging(b[0], b[1]));
        }
    }

    @Test
    void bduPowerHeaterDrdy() throws IOException {
        make();
        Hts221Full f = new Hts221Full(mock);
        f.setBlockDataUpdate(false);
        assertEquals(0x80, mock.registers().get(0x20));
        f.setBlockDataUpdate(true);
        f.powerDown();
        assertEquals(0x04, mock.registers().get(0x20));
        f.powerUp();
        assertEquals(0x84, mock.registers().get(0x20));
        f.setHeater(true);
        assertTrue(f.isHeaterOn());
        f.setHeater(false);
        assertFalse(f.isHeaterOn());
        f.configureDrdy(true, true, false);
        assertEquals(0x04, mock.registers().get(0x22));
        f.configureDrdy(true, false, true);
        assertEquals(0xC4, mock.registers().get(0x22));
        f.configureDrdy(false, true, false);
        assertEquals(0x00, mock.registers().get(0x22));
    }

    @Test
    void rebootRereadsCalibrationAndTimesOut() throws IOException {
        make();
        Hts221Full f = new Hts221Full(mock);
        mock.setRegister(0xB0, 60);
        f.reboot();
        assertEquals(30.0, f.readCalibration()[0], 1e-9);
        mock.stuckBoot = true;
        assertThrows(IOException.class, f::reboot);
    }

    @Test
    void identityStatusAndDewPoint() throws IOException {
        make();
        Hts221Full f = new Hts221Full(mock);
        assertEquals(0xBC, f.whoAmI());
        assertEquals(0x03, f.status());
        assertTrue(f.dataReady());
        assertEquals(0x03, f.pollInterrupt());
        double g = Math.log(0.30) + 17.62 * 15.0 / (243.12 + 15.0);
        assertEquals(243.12 * g / (17.62 - g), f.dewPoint(), 1e-9);
    }

    @Test
    void interruptViaPin() throws Exception {
        make();
        FakePin pin = new FakePin();
        mock.pin = pin;
        Hts221Full f = new Hts221Full(mock);
        AtomicInteger got = new AtomicInteger(-1);
        f.onInterrupt(got::set);
        assertEquals(0x04, mock.registers().get(0x22));
        assertEquals(EdgeTrigger.RISING, pin.trigger);
        pin.handler.onEdge();
        assertEquals(0x03, got.get());
        f.offInterrupt();
        assertNull(pin.handler);
        assertEquals(0x00, mock.registers().get(0x22));
    }

    @Test
    void interruptPollingFallback() throws Exception {
        make();
        Hts221Full f = new Hts221Full(mock);
        AtomicInteger got = new AtomicInteger(-1);
        f.onInterrupt(got::set);
        long end = System.currentTimeMillis() + 1000;
        while (got.get() < 0 && System.currentTimeMillis() < end) Thread.sleep(10);
        f.offInterrupt();
        assertEquals(0x03, got.get());
    }
}
