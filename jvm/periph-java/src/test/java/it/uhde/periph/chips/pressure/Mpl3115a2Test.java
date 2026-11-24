package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Mpl3115a2Test {

    /** RST (0x04) and OST (0x02) are self-clearing in CTRL_REG1 on the real chip. */
    private static class Mock extends MockConnection {
        @Override
        public void write(byte[] data) throws IOException {
            super.write(data);
            if (data.length == 2 && (data[0] & 0xFF) == 0x26) setRegister(0x26, data[1] & ~0x06 & 0xFF);
        }
    }

    private Mock c;
    private Mpl3115a2Full s;

    private void make() {
        c = new Mock();
        c.setRegister(0x0C, 0xC4);
        c.setRegister(0x06, 0x0E);
        c.setRegister(0x01, 0x62, 0xF3, 0x80, 0x19, 0x80);   // 101326.0 Pa, 25.5 °C
        s = new Mpl3115a2Full(c);
    }

    private int last(int reg, int idx) {
        int v = -1;
        for (byte[] w : c.writes()) if (w.length > idx && (w[0] & 0xFF) == reg) v = w[idx] & 0xFF;
        return v;
    }

    @Test
    void initConfiguresChip() throws IOException {
        make();
        assertTrue(c.writes().isEmpty());
        s.init();
        assertEquals(0x07, last(0x13, 1));
        assertEquals(0x38, last(0x26, 1));
    }

    @Test
    void initRejectsWrongChip() {
        make();
        c.setRegister(0x0C, 0x00);
        assertThrows(IOException.class, () -> s.init());
    }

    @Test
    void readConverts() throws IOException {
        make();
        s.init();
        double[] m = s.read();
        assertEquals(101326.0, m[0], 1e-6);
        assertEquals(25.5, m[1], 1e-6);
        assertEquals(0x3A, last(0x26, 1));
        c.setRegister(0x04, 0xE7, 0x00);
        assertEquals(-25.0, s.readTemperature(), 1e-6);
    }

    @Test
    void altitudeAndBackToBarometer() throws IOException {
        make();
        s.init();
        c.setRegister(0x01, 0x00, 0x7B, 0x30);
        assertEquals(123.1875, s.readAltitude(), 1e-6);
        assertEquals(0xBA, last(0x26, 1));
        c.setRegister(0x01, 0xFF, 0x9C, 0x00);
        assertEquals(-100.0, s.readAltitude(), 1e-6);
        c.setRegister(0x01, 0x62, 0xF3, 0x80);
        assertEquals(101326.0, s.read()[0], 1e-6);
    }

    @Test
    void oversample() throws IOException {
        make();
        s.init();
        s.setOversample(16);
        assertEquals(16, s.getOversample());
        assertEquals(0x20, last(0x26, 1));
        assertThrows(IllegalArgumentException.class, () -> s.setOversample(3));
    }

    @Test
    void configurationWrites() throws IOException {
        make();
        s.init();
        s.setSeaLevelPressure(101326);
        assertEquals(0xC5, last(0x14, 1));
        assertEquals(0xE7, last(0x14, 2));
        s.setOffsets(-8, 1, -3);
        assertEquals(0xFE, last(0x2B, 1));
        assertEquals(16, last(0x2C, 1));
        assertEquals(0xFD, last(0x2D, 1));
        assertThrows(IllegalArgumentException.class, () -> s.setOffsets(1000, 0, 0));
        s.setTarget(100000, 200, -5, 3);
        assertEquals(0xC3, last(0x16, 1));
        assertEquals(0x50, last(0x16, 2));
        assertEquals(0xFB, last(0x18, 1));
        assertEquals(0x64, last(0x19, 2));
        assertEquals(3, last(0x1B, 1));
    }

    @Test
    void continuousAndDelta() throws IOException {
        make();
        s.init();
        s.startContinuous(2);
        assertEquals(2, last(0x27, 1) & 0x0F);
        assertEquals(0x39, last(0x26, 1));
        assertThrows(IllegalArgumentException.class, () -> s.startContinuous(16));
        assertTrue(s.dataReady());
        double[] v = s.readContinuous();
        assertEquals(101326.0, v[0], 1e-6);
        assertEquals(25.5, v[1], 1e-6);
        s.stopContinuous();
        assertEquals(0x38, last(0x26, 1));
        c.setRegister(0x07, 0x00, 0x00, 0x40, 0x00, 0x10);
        double[] d = s.readDelta();
        assertEquals(1.0, d[0], 1e-6);
        assertEquals(0.0625, d[1], 1e-6);
    }

    @Test
    void minMaxAndFifo() throws IOException {
        make();
        s.init();
        c.setRegister(0x1C, 0x62, 0xF3, 0x80, 0x19, 0x80);
        assertEquals(101326.0, s.readMinMax()[0], 1e-6);
        s.clearMinMax();
        assertEquals(0, last(0x1C, 3));
        s.fifoSetup(1, 16);
        assertEquals(0x50, last(0x0F, 1));
        assertThrows(IllegalArgumentException.class, () -> s.fifoSetup(2, 0));
        c.setRegister(0x0D, 0xC2);
        assertArrayEquals(new int[]{1, 1, 2}, s.fifoStatus());
        c.setRegister(0x0E, 0x62, 0xF3, 0x80, 0x19, 0x80);
        List<double[]> samples = s.fifoRead();
        assertEquals(2, samples.size());
        assertEquals(101326.0, samples.get(0)[0], 1e-6);
    }

    @Test
    void interrupts() throws Exception {
        make();
        s.init();
        s.setInterruptPins(true, true, false, false);
        assertEquals(0x30, last(0x28, 1));
        s.enableInterrupt(Mpl3115a2Full.SOURCE_TTH, 1);
        assertNotEquals(0, last(0x29, 1) & 0x04);
        assertNotEquals(0, last(0x2A, 1) & 0x04);
        s.enableInterrupt(Mpl3115a2Full.SOURCE_DRDY, 2);
        assertEquals(0, last(0x2A, 1) & 0x80);
        s.disableInterrupt(Mpl3115a2Full.SOURCE_TTH);
        assertEquals(0, last(0x29, 1) & 0x04);
        assertThrows(IllegalArgumentException.class, () -> s.enableInterrupt(1, 3));
        c.setRegister(0x12, 0x84);
        assertEquals(0x84, s.pollInterrupt());

        c.setRegister(0x2A, 0x04);
        int[] got = {-1};
        s.onInterrupt(status -> got[0] = status, 1);
        Thread.sleep(300);
        s.offInterrupt();
        assertEquals(0x04, got[0]);
    }

    @Test
    void identityAndReset() throws IOException {
        make();
        s.init();
        assertEquals(0xC4, s.whoAmI());
        c.setRegister(0x11, 0x01);
        assertTrue(s.isActive());
        s.reset();
        assertEquals(0x38, last(0x26, 1));
    }
}
