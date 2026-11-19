package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MPL115A2Test {

    private static MockConnection preload() {
        // Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
        // ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
        MockConnection c = new MockConnection();
        c.setRegister(MPL115A2Minimal.REG_COEF, 0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8);
        c.setRegister(MPL115A2Minimal.REG_PADC, 0x66, 0x80, 0x7E, 0xC0);
        return c;
    }

    @Test
    void fullApi() throws Exception {
        MockConnection connection = preload();
        MPL115A2Full sensor = new MPL115A2Full(connection);

        double[] k = sensor.coefficients();
        assertEquals(2009.75, k[0], 1e-9);
        assertEquals(-2.375854, k[1], 1e-5);
        assertEquals(-0.92047, k[2], 1e-5);
        assertEquals(0.000790, k[3], 1e-6);

        double p = sensor.readPressure();
        assertEquals(96586.0, p, 30.0);
        assertTrue(connection.writes().stream().anyMatch(
                w -> w.length == 2 && (w[0] & 0xFF) == MPL115A2Minimal.REG_CONVERT && w[1] == 0));

        assertEquals(25.0 + 9 / -5.35, sensor.readTemperature(), 1e-9);
        assertArrayEquals(new int[]{410, 507}, sensor.readRaw());
        double[] both = sensor.read();
        assertEquals(p, both[0], 1e-9);
        assertEquals(25.0 + 9 / -5.35, both[1], 1e-9);
        assertEquals(p, sensor.compensate(410, 507), 1e-9);
        assertTrue(sensor.compensate(0, 0) > 40000 && sensor.compensate(1023, 0) < 130000);
        assertEquals(p / 100.0, sensor.pressureHpa(), 1e-9);
        assertEquals(44330.0 * (1.0 - Math.pow(p / 101325.0, 1.0 / 5.255)), sensor.altitude(), 1e-6);
        assertEquals(0.0, sensor.altitude(p), 1e-9);

        connection.setRegister(MPL115A2Minimal.REG_COEF, 0x00, 0x08, 0, 0, 0, 0, 0, 0);
        sensor.reloadCoefficients();
        assertEquals(1.0, sensor.coefficients()[0], 1e-12);
    }

    @Test
    void minimal() throws Exception {
        MPL115A2Minimal sensor = new MPL115A2Minimal(preload());
        assertEquals(96586.0, sensor.readPressure(), 30.0);
    }
}
