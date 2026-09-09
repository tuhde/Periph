package it.uhde.periph.chips.environmental;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bme680Test {

    private static void assertClose(double expected, double actual, double tol) {
        assertTrue(Math.abs(actual - expected) < tol,
                () -> "expected ~" + expected + " but was " + actual);
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        connection.setRegister(Bme680Minimal.REG_ID, 0x61);
        // Calibration block 1 (23 bytes from 0x8A). No published worked example
        // exists for BME680 - these are self-consistent, hand-derived values
        // used to check every language's translation against the same formula.
        connection.setRegister(Bme680Minimal.REG_CAL_BLOCK1,
                0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
                0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E);
        // Calibration block 2 (14 bytes from 0xE1).
        connection.setRegister(Bme680Minimal.REG_CAL_BLOCK2,
                0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E);
        // Single-byte calibration: resHeatVal=50, resHeatRange=2, rangeSwitchingError=0.
        connection.setRegister(Bme680Minimal.REG_RES_HEAT_VAL, 0x32);
        connection.setRegister(Bme680Minimal.REG_RES_HEAT_RANGE, 0x20);
        connection.setRegister(Bme680Minimal.REG_RANGE_SW_ERR, 0x00);
        // ADC burst (13 bytes from 0x1F): pressAdc=415148, tempAdc=419888,
        // humAdc=20000, gasAdc=400, gasRange=5, gasValid=1, heatStab=1.
        connection.setRegister(Bme680Minimal.REG_PRESS_MSB,
                0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20, 0x00, 0x00, 0x00, 0x64, 0x35);

        final double expectedT = 1.23;
        final double expectedP = 969.4;
        final double expectedH = 39.826;
        final double expectedGas = 271155.0;

        Bme680Full sensor = new Bme680Full(connection);

        assertEquals(0x01, connection.registers().get(Bme680Minimal.REG_CTRL_HUM));
        assertEquals((1 << 5) | (1 << 2) | 0, connection.registers().get(Bme680Minimal.REG_CTRL_MEAS));
        assertEquals(0x00, connection.registers().get(Bme680Minimal.REG_CONFIG));
        assertEquals(0x52, connection.registers().get(0x5A));
        assertEquals(0x65, connection.registers().get(0x64));
        assertEquals((1 << 4) | 0, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_1));

        sensor.setHeater(300, 200);
        assertEquals(0x4E, connection.registers().get(0x5A));
        assertEquals(0x72, connection.registers().get(0x64));

        sensor.setHeaterProfile(4, 280, 50);
        assertEquals(0x49, connection.registers().get(0x5A + 4));
        assertEquals(0x32, connection.registers().get(0x64 + 4));

        assertClose(expectedT, sensor.temperature(), 0.01);
        assertClose(expectedP, sensor.pressure(), 0.1);
        assertClose(expectedH, sensor.humidity(), 0.01);
        assertClose(expectedGas, sensor.gasResistance(), 1.0);

        int lastCtrlMeas = connection.registers().get(Bme680Minimal.REG_CTRL_MEAS);
        assertEquals(1, lastCtrlMeas & 0x03);

        sensor.configure(2, 3, 1, 0, 3);
        assertEquals(1, connection.registers().get(Bme680Minimal.REG_CTRL_HUM));
        assertEquals(3 << 2, connection.registers().get(Bme680Minimal.REG_CONFIG));
        assertEquals((2 << 5) | (3 << 2) | 0, connection.registers().get(Bme680Minimal.REG_CTRL_MEAS));

        sensor.setOversampling(3, 4, 2);
        assertEquals(2, connection.registers().get(Bme680Minimal.REG_CTRL_HUM));
        assertEquals((3 << 5) | (4 << 2) | 0, connection.registers().get(Bme680Minimal.REG_CTRL_MEAS));

        sensor.setFilter(5);
        assertEquals(5 << 2, connection.registers().get(Bme680Minimal.REG_CONFIG));

        sensor.selectHeaterProfile(2);
        assertEquals((1 << 4) | 2, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_1));

        sensor.setGasEnabled(false);
        assertEquals(2, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_1));
        sensor.setGasEnabled(true);
        assertEquals((1 << 4) | 2, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_1));

        sensor.setHeaterOff(true);
        assertEquals(0x08, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_0));
        sensor.setHeaterOff(false);
        assertEquals(0x00, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_0));

        double[] all = sensor.readAll();
        assertClose(expectedT, all[0], 0.01);
        assertClose(expectedP, all[1], 0.1);
        assertClose(expectedH, all[2], 0.01);
        assertClose(expectedGas, all[3], 1.0);

        assertTrue(sensor.gasValid());
        assertTrue(sensor.heaterStable());

        connection.setRegister(Bme680Minimal.REG_MEAS_STATUS, 0xA0);
        assertEquals(0xA0, sensor.status());

        assertEquals(0x61, sensor.chipId());

        sensor.reset();
        boolean sawReset = connection.writes().stream()
                .anyMatch(w -> w.length == 2 && (w[0] & 0xFF) == Bme680Minimal.REG_RESET
                        && (w[1] & 0xFF) == Bme680Minimal.RESET_CMD);
        assertTrue(sawReset, "expected a soft-reset command write");
        assertEquals(2, connection.registers().get(Bme680Minimal.REG_CTRL_HUM));
        assertEquals(5 << 2, connection.registers().get(Bme680Minimal.REG_CONFIG));
        assertEquals((3 << 5) | (4 << 2) | 0, connection.registers().get(Bme680Minimal.REG_CTRL_MEAS));
        assertEquals((1 << 4) | 2, connection.registers().get(Bme680Minimal.REG_CTRL_GAS_1));
    }
}
