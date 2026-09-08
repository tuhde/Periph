package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bmp180Test {

    private static void preloadCalibration(MockConnection connection) {
        // Datasheet worked example (Figure 4, page 15): AC1=408, AC2=-72,
        // AC3=-14383, AC4=32741, AC5=32757, AC6=23153, B1=6190, B2=4,
        // MB=-32768, MC=-8711, MD=2868.
        connection.setRegister(Bmp180Minimal.REG_CAL_START,
                0x01, 0x98,  // AC1 = 408
                0xFF, 0xB8,  // AC2 = -72
                0xC7, 0xD1,  // AC3 = -14383
                0x7F, 0xE5,  // AC4 = 32741
                0x7F, 0xF5,  // AC5 = 32757
                0x5A, 0x71,  // AC6 = 23153
                0x18, 0x2E,  // B1 = 6190
                0x00, 0x04,  // B2 = 4
                0x80, 0x00,  // MB = -32768
                0xDD, 0xF9,  // MC = -8711
                0x0B, 0x34); // MD = 2868
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        preloadCalibration(connection);
        // Unlike Python/C++/JS/Rust, the Java constructor verifies the chip
        // ID register before reading calibration.
        connection.setRegister(Bmp180Minimal.REG_ID, 0x55);

        Bmp180Full sensor = new Bmp180Full(connection);

        // pressure() re-reads OUT_MSB for both UT (2 bytes) and UP (3 bytes)
        // from the same register within one call, and this mock always
        // returns the register map's current contents - it cannot hand back
        // a different UT then a different UP within a single call. So UT
        // and the top 16 bits of UP are necessarily the same value here
        // (0x6CFA = 27898); the expected T/p below are computed from the
        // real compensation formula with UT=UP=27898, not the datasheet's
        // mismatched worked example.
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA);
        assertEquals(15.0, sensor.temperature());

        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA);
        assertEquals(820.8, sensor.pressure(), 1e-6);

        // chipId(): expect 0x55.
        connection.setRegister(Bmp180Minimal.REG_ID, 0x55);
        assertEquals(0x55, sensor.chipId());

        // oversampling()/setOversampling()
        assertEquals(0, sensor.oversampling());
        sensor.setOversampling(Bmp180Full.OSS_STANDARD);
        assertEquals(1, sensor.oversampling());
        sensor.setOversampling(0);

        // altitude(): pressure() re-reads UT/UP internally.
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA);
        assertEquals(1741.7604174, sensor.altitude(), 0.5);

        // seaLevelPressure(altitudeM=100)
        connection.setRegister(Bmp180Minimal.REG_OUT_MSB, 0x6C, 0xFA);
        assertEquals(830.599010429, sensor.seaLevelPressure(100), 0.5);

        // reset(): writes soft-reset command, then re-reads calibration coefficients.
        preloadCalibration(connection);
        sensor.reset();
        boolean sawSoftReset = connection.writes().stream()
                .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == Bmp180Minimal.REG_SOFT_RST && (w[1] & 0xFF) == 0xB6);
        assertTrue(sawSoftReset, "reset should write the soft-reset command");
        long calReads = connection.writes().stream()
                .filter(w -> w.length == 1 && (w[0] & 0xFF) == Bmp180Minimal.REG_CAL_START)
                .count();
        assertTrue(calReads >= 2, "reset should re-read calibration");

        // Invalid calibration data (a coefficient of 0x0000) throws at construction.
        MockConnection badConnection = new MockConnection();
        badConnection.setRegister(Bmp180Minimal.REG_ID, 0x55);
        badConnection.setRegister(Bmp180Minimal.REG_CAL_START,
                0x00, 0x00, // AC1 = 0 (invalid)
                0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
                0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34);
        assertThrows(IOException.class, () -> new Bmp180Full(badConnection));
    }
}
