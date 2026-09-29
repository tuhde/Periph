package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bmp384Test {

    // Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
    // NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
    //      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
    private static void preloadCalibration(MockConnection connection) {
        connection.setRegister(0x31,
            0x10, 0x6C, // T1 u16 LE
            0x50, 0x6C, // T2 u16 LE
            0x03,       // T3 s8
            0x11, 0xF0, // P1 s16 LE
            0x22, 0x03, // P2 s16 LE
            0xF8,       // P3 s8
            0x05,       // P4 s8
            0x40, 0x80, // P5 u16 LE
            0x10, 0x1E, // P6 u16 LE
            0xF0,       // P7 s8
            0x0A,       // P8 s8
            0xE0, 0x0F, // P9 s16 LE
            0xFB,       // P10 s8
            0x02);      // P11 s8
        connection.setRegister(0x00, 0x50); // CHIP_ID
    }

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        preloadCalibration(c);
        return c;
    }

    private static int lastWriteTo(MockConnection connection, int reg) {
        var writes = connection.writes();
        for (int i = writes.size() - 1; i >= 0; i--) {
            byte[] w = writes.get(i);
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF;
        }
        return -1;
    }

    // uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
    // pressure=1447.6955007429672 hPa (computed independently from the same
    // Bosch compensation formula; cross-checked across all language ports).
    private static final int[] PRESS_BYTES = {0x80, 0x8D, 0x5B};
    private static final int[] TEMP_BYTES = {0x00, 0x12, 0x7A};
    private static final double EXPECTED_T_LIN = 23.715563300065696;
    private static final double EXPECTED_PRESSURE_HPA = 1447.6955007429672;

    private static void setBurst(MockConnection connection) {
        connection.setRegister(0x04,
            PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2],
            TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2]);
    }

    @Test
    void constructionWritesDefaultConfig() throws Exception {
        MockConnection connection = newConnection();
        new Bmp384Minimal(connection);
        assertEquals((1 << 3) | 4, lastWriteTo(connection, 0x1C));
        assertEquals(2 << 1, lastWriteTo(connection, 0x1F));
        assertEquals(0x03, lastWriteTo(connection, 0x1D));
        assertEquals((0x03 << 4) | 0x02 | 0x01, lastWriteTo(connection, 0x1B));
    }

    @Test
    void constructionBadChipIdThrows() {
        MockConnection connection = new MockConnection();
        preloadCalibration(connection);
        connection.setRegister(0x00, 0x00); // wrong chip ID
        assertThrows(IOException.class, () -> new Bmp384Minimal(connection));
    }

    @Test
    void temperatureAndPressure() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Minimal chip = new Bmp384Minimal(connection);

        setBurst(connection);
        assertEquals(EXPECTED_T_LIN, chip.temperature(), 1e-6);

        setBurst(connection);
        assertEquals(EXPECTED_PRESSURE_HPA, chip.pressure(), 1e-6);
    }

    @Test
    void forcedModeTemperatureTriggers() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Minimal chip = new Bmp384Minimal(connection);
        chip.mode = Bmp384Minimal.MODE_FORCED;
        setBurst(connection);
        chip.temperature();
        assertEquals((Bmp384Minimal.MODE_FORCED << 4) | 0x02 | 0x01, lastWriteTo(connection, 0x1B));
    }

    @Test
    void configureWritesRegisters() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        full.configure(1, 1, 2, 0x03);
        assertEquals((1 << 3) | 1, lastWriteTo(connection, 0x1C));
        assertEquals(2 << 1, lastWriteTo(connection, 0x1F));
        assertEquals(0x03, lastWriteTo(connection, 0x1D));
    }

    @Test
    void readCombinedBurst() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        setBurst(connection);
        double[] result = full.read();
        assertEquals(EXPECTED_PRESSURE_HPA, result[0], 1e-6);
        assertEquals(EXPECTED_T_LIN, result[1], 1e-6);
    }

    // Regression: read() must trigger a forced measurement exactly like
    // temperature()/pressure()/readForced() do -- it was previously missing
    // this entirely in forced mode.
    @Test
    void readForcedModeTriggers() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        full.setMode(Bmp384Full.MODE_FORCED);
        setBurst(connection);
        full.read();
        assertEquals((Bmp384Full.MODE_FORCED << 4) | 0x02 | 0x01, lastWriteTo(connection, 0x1B));
    }

    @Test
    void readForcedRestoresMode() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        setBurst(connection);
        double[] result = full.readForced();
        assertEquals(EXPECTED_PRESSURE_HPA, result[0], 1e-6);
        assertEquals((Bmp384Full.MODE_NORMAL << 4) | 0x02 | 0x01, lastWriteTo(connection, 0x1B));
    }

    @Test
    void setModeWritesPwrCtrl() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        full.setMode(Bmp384Full.MODE_SLEEP);
        assertEquals((Bmp384Full.MODE_SLEEP << 4) | 0x02 | 0x01, lastWriteTo(connection, 0x1B));
    }

    @Test
    void isDataReady() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        connection.setRegister(0x03, 1 << 5);
        assertTrue(full.isDataReady());
        connection.setRegister(0x03, 0x00);
        assertFalse(full.isDataReady());
    }

    @Test
    void softreset() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        full.softreset();
        assertEquals(0xB6, lastWriteTo(connection, 0x7E));
        assertEquals((Bmp384Full.MODE_NORMAL << 4) | 0x02 | 0x01, lastWriteTo(connection, 0x1B));
    }

    @Test
    void fifoConfigure() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        full.fifoConfigure(true, true, 300, true);
        assertEquals((1 << 4) | (1 << 3) | (1 << 1) | 1, lastWriteTo(connection, 0x17));
        assertEquals(300 & 0xFF, lastWriteTo(connection, 0x15));
        assertEquals((300 >> 8) & 0x01, lastWriteTo(connection, 0x16));
    }

    @Test
    void fifoReadParsesAllFrameTypes() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        int[] fifoBytes = {
            0x84, PRESS_BYTES[0], PRESS_BYTES[1], PRESS_BYTES[2], // pressure
            0x90, TEMP_BYTES[0], TEMP_BYTES[1], TEMP_BYTES[2],    // temperature
            0xA0, 0x01, 0x02, 0x03,                               // sensortime
            0x44,                                                 // error
            0x80,                                                 // empty
            0xFF,                                                 // unknown
        };
        connection.setRegister(0x12, fifoBytes.length & 0xFF, (fifoBytes.length >> 8) & 0x01);
        connection.setRegister(0x14, fifoBytes);
        full.tLin = EXPECTED_T_LIN; // so a lone pressure frame is comparable to the fixture

        Bmp384Full.FifoFrame[] frames = full.fifoRead();
        assertEquals(6, frames.length);
        assertEquals("pressure", frames[0].type);
        assertEquals(EXPECTED_PRESSURE_HPA, frames[0].value, 1e-6);
        assertEquals("temperature", frames[1].type);
        assertEquals(EXPECTED_T_LIN, frames[1].value, 1e-6);
        assertEquals("sensortime", frames[2].type);
        assertEquals(0x030201, frames[2].value, 1e-9);
        assertEquals("error", frames[3].type);
        assertEquals("empty", frames[4].type);
        assertEquals("unknown", frames[5].type);
    }

    @Test
    void fifoReadEmptyFifo() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        connection.setRegister(0x12, 0x00, 0x00);
        assertEquals(0, full.fifoRead().length);
    }

    @Test
    void fifoFlush() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        full.fifoFlush();
        assertEquals(0xB0, lastWriteTo(connection, 0x7E));
    }

    @Test
    void altitudeNegativeForHighPressure() throws Exception {
        MockConnection connection = newConnection();
        Bmp384Full full = new Bmp384Full(connection);
        setBurst(connection);
        // Fixture pressure (1447 hPa) is above the standard sea-level reference.
        assertTrue(full.altitude() < 0);
    }
}
