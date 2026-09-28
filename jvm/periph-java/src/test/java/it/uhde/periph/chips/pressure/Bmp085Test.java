package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bmp085Test {

    private static void preloadCalibration(MockConnection connection) {
        // Datasheet worked example: AC1=408, AC2=-72, AC3=-14383, AC4=32741,
        // AC5=32757, AC6=23153, B1=6190, B2=4, MB=-32768, MC=-8711, MD=2868.
        connection.setRegister(0xAA,
            0x01, 0x98, // AC1 = 408
            0xFF, 0xB8, // AC2 = -72
            0xC7, 0xD1, // AC3 = -14383
            0x7F, 0xE5, // AC4 = 32741
            0x7F, 0xF5, // AC5 = 32757
            0x5A, 0x71, // AC6 = 23153
            0x18, 0x2E, // B1 = 6190
            0x00, 0x04, // B2 = 4
            0x80, 0x00, // MB = -32768
            0xDD, 0xF9, // MC = -8711
            0x0B, 0x34); // MD = 2868
        connection.setRegister(0xD0, 0x55); // chip ID (constructor verifies it)
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

    @Test
    void constructionAndReadings() throws Exception {
        MockConnection connection = newConnection();
        Bmp085Minimal chip = new Bmp085Minimal(connection);

        connection.setRegister(0xF6, 0x6C, 0xFA); // UT = 27898
        assertEquals(15.0, chip.temperature(), 1e-9);
        assertEquals(0x2E, lastWriteTo(connection, 0xF4));

        connection.setRegister(0xF6, 0x6C, 0xFA);
        assertEquals(82080.0, chip.pressure(), 1e-6);
    }

    @Test
    void constructionBadChipIdThrows() {
        MockConnection connection = new MockConnection();
        preloadCalibration(connection);
        connection.setRegister(0xD0, 0x00); // wrong chip ID
        assertThrows(IOException.class, () -> new Bmp085Minimal(connection));
    }

    @Test
    void constructionBadCalibrationThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(0xD0, 0x55);
        int[] zeros = new int[22];
        connection.setRegister(0xAA, zeros); // every word 0x0000
        assertThrows(IOException.class, () -> new Bmp085Minimal(connection));

        MockConnection connection2 = new MockConnection();
        connection2.setRegister(0xD0, 0x55);
        int[] ffff = new int[22];
        java.util.Arrays.fill(ffff, 0xFF);
        connection2.setRegister(0xAA, ffff); // every word raw 0xFFFF
        assertThrows(IOException.class, () -> new Bmp085Minimal(connection2));
    }

    @Test
    void oversampling() throws Exception {
        MockConnection connection = newConnection();
        Bmp085Full full = new Bmp085Full(connection);
        assertEquals(0, full.oversampling());
        full.setOversampling(2);
        assertEquals(2, full.oversampling());

        connection.setRegister(0xF6, 0x6C, 0xFA, 0x00);
        full.pressure();
        assertEquals(0xB4, lastWriteTo(connection, 0xF4)); // 0x34 | (2<<6)
    }

    @Test
    void altitudeAndSeaLevelPressure() throws Exception {
        MockConnection connection = newConnection();
        Bmp085Full full = new Bmp085Full(connection);

        connection.setRegister(0xF6, 0x6C, 0xFA);
        assertTrue(full.altitude() > 0);

        connection.setRegister(0xF6, 0x6C, 0xFA);
        assertEquals(82080.0, full.seaLevelPressure(0.0), 1e-6);
    }

    @Test
    void chipIdAndReset() throws Exception {
        MockConnection connection = newConnection();
        Bmp085Full full = new Bmp085Full(connection);

        assertEquals(0x55, full.chipId());

        full.reset();
        assertEquals(0xB6, lastWriteTo(connection, 0xE0));
    }
}
