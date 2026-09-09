package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bme280Test {

    private static void assertClose(double expected, double actual, double tol) {
        assertTrue(Math.abs(actual - expected) < tol,
                () -> "expected ~" + expected + " but was " + actual);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Bme280Minimal.REG_ID, 0x60);
        // Calibration NVM block 1 (26 bytes from 0x88), BMP280 datasheet worked
        // example, plus digH1=75 at 0xA1.
        connection.setRegister(Bme280Minimal.REG_CALIB,
                0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
                0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
                0x70, 0x17, 0x00, 0x4B);
        // Calibration NVM block 2 (7 bytes from 0xE1): digH2=384, digH3=0,
        // digH4=301, digH5=50, digH6=30.
        connection.setRegister(Bme280Minimal.REG_CAL_H2, 0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E);
        // ADC burst (8 bytes from 0xF7): adcP=415148, adcT=519888, adcH=32768.
        connection.setRegister(Bme280Minimal.REG_DATA, 0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00);

        final double expectedT = 25.08;
        final double expectedP = 1006.5325390625;
        final double expectedH = 79.0869140625;

        Bme280Full sensor = new Bme280Full(connection);

        // This driver's default ctrlMeas (0x25) already encodes forced mode
        // (osrs_t=x1, osrs_p=x1, mode=1), written directly at construction.
        assertEquals(0x01, connection.registers().get(Bme280Minimal.REG_CTRL_HUM));
        assertEquals(0x25, connection.registers().get(Bme280Minimal.REG_CTRL_MEAS));
        assertEquals(0x00, connection.registers().get(Bme280Minimal.REG_CONFIG));

        assertClose(expectedT, sensor.temperature(), 0.01);
        assertClose(expectedP, sensor.pressure(), 0.01);
        assertClose(expectedH, sensor.humidity(), 0.01);

        int lastCtrlMeas = connection.registers().get(Bme280Minimal.REG_CTRL_MEAS);
        assertEquals(1, lastCtrlMeas & 0x03);

        sensor.configure(2, 3, 1, 3, 2, 5);
        assertEquals(1, connection.registers().get(Bme280Minimal.REG_CTRL_HUM));
        assertEquals((5 << 5) | (2 << 2), connection.registers().get(Bme280Minimal.REG_CONFIG));
        assertEquals((2 << 5) | (3 << 2) | 3, connection.registers().get(Bme280Minimal.REG_CTRL_MEAS));

        sensor.setOversampling(3, 4, 2);
        assertEquals(2, connection.registers().get(Bme280Minimal.REG_CTRL_HUM));
        assertEquals((3 << 5) | (4 << 2) | 3, connection.registers().get(Bme280Minimal.REG_CTRL_MEAS));

        sensor.setMode(1);
        assertEquals((3 << 5) | (4 << 2) | 1, connection.registers().get(Bme280Minimal.REG_CTRL_MEAS));

        sensor.setFilter(3);
        assertEquals((5 << 5) | (3 << 2), connection.registers().get(Bme280Minimal.REG_CONFIG));

        sensor.setStandby(6);
        assertEquals((6 << 5) | (3 << 2), connection.registers().get(Bme280Minimal.REG_CONFIG));

        connection.setRegister(Bme280Minimal.REG_STATUS, 0x08);
        assertEquals(0x08, sensor.status());

        assertClose(56.07668235692459, sensor.altitude(1013.25), 0.05);
        assertClose(1013.25, sensor.seaLevelPressure(56.07668235692459), 0.05);
        assertClose(21.191706255732008, sensor.dewPoint(), 0.05);

        assertEquals(0x60, sensor.chipId());

        sensor.reset();
        boolean sawReset = connection.writes().stream()
                .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == Bme280Minimal.REG_SOFT_RST
                        && (w[1] & 0xFF) == Bme280Minimal.RESET_CMD);
        assertTrue(sawReset, "expected a soft-reset command write");
        assertEquals(2, connection.registers().get(Bme280Minimal.REG_CTRL_HUM));
        assertEquals((6 << 5) | (3 << 2), connection.registers().get(Bme280Minimal.REG_CONFIG));
        assertEquals((3 << 5) | (4 << 2) | 1, connection.registers().get(Bme280Minimal.REG_CTRL_MEAS));
    }

    @Test
    void spiMasksWriteAddresses() throws Exception {
        // Per specs/environmental/bme280.md's SPI Register-address protocol:
        // BME280's I2C register addresses already have bit 7 set, so SPI
        // reads use the same value unmasked; only writes differ, clearing
        // bit 7 (reg & 0x7F).
        MockConnection connection = new MockConnection();
        connection.setRegister(Bme280Minimal.REG_ID, 0x60);
        connection.setRegister(Bme280Minimal.REG_CALIB,
                0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
                0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
                0x70, 0x17, 0x00, 0x4B);
        connection.setRegister(Bme280Minimal.REG_CAL_H2, 0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E);
        connection.setRegister(Bme280Minimal.REG_DATA, 0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00);

        final double expectedT = 25.08;
        final double expectedP = 1006.5325390625;
        final double expectedH = 79.0869140625;

        Bme280Full sensor = new Bme280Full(connection, 0x76, Bme280Minimal.BUS_SPI);

        // Construction writes ctrl_hum/ctrl_meas/config - on SPI these must
        // all be masked (bit 7 cleared), never the raw I2C address.
        for (int reg : new int[]{Bme280Minimal.REG_CTRL_HUM, Bme280Minimal.REG_CTRL_MEAS, Bme280Minimal.REG_CONFIG}) {
            final int r = reg;
            boolean sawMasked = connection.writes().stream()
                    .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == (r & 0x7F));
            assertTrue(sawMasked, "spi init should write masked address 0x" + Integer.toHexString(r & 0x7F));
            boolean sawUnmasked = connection.writes().stream()
                    .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == r);
            assertTrue(!sawUnmasked, "spi init should never write unmasked address 0x" + Integer.toHexString(r));
        }

        // Reads stay unmasked - calibration/data preloading and read-based
        // assertions behave exactly as in I2C mode.
        assertClose(expectedT, sensor.temperature(), 0.01);
        assertClose(expectedP, sensor.pressure(), 0.01);
        assertClose(expectedH, sensor.humidity(), 0.01);

        // reset() also routes every write through writeReg, so the
        // soft-reset command itself must be masked too.
        sensor.reset();
        boolean sawMaskedReset = connection.writes().stream()
                .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == (Bme280Minimal.REG_SOFT_RST & 0x7F)
                        && (w[1] & 0xFF) == Bme280Minimal.RESET_CMD);
        assertTrue(sawMaskedReset, "spi reset() should mask the soft-reset write address");
        boolean sawUnmaskedReset = connection.writes().stream()
                .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == Bme280Minimal.REG_SOFT_RST);
        assertTrue(!sawUnmaskedReset, "spi should never write the unmasked soft-reset address (0xE0)");
    }
}
