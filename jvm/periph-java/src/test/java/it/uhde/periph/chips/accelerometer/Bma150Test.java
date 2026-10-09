package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bma150Test {

    private static MockConnection newConnection() {
        MockConnection c = new MockConnection();
        c.setRegister(Bma150Minimal.REG_CHIP_ID, 0x02);
        c.setRegister(Bma150Minimal.REG_RANGE_BW, 0x00);
        // raw_x = 0x200 -> -2 g; raw_y = 0; raw_z = 0x100 -> 1 g.
        c.setRegister(Bma150Minimal.REG_ACC_X_LSB, 0x00, 0x80);
        c.setRegister(Bma150Minimal.REG_ACC_Y_LSB, 0x00, 0x00);
        c.setRegister(Bma150Minimal.REG_ACC_Z_LSB, 0x00, 0x40);
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
    void constructionAndRead() throws IOException {
        MockConnection connection = newConnection();
        Bma150Minimal accel = new Bma150Minimal(connection);
        // Init writes RANGE_BW = (0x00 & 0xE0) | 0x00 | 0x02 = 0x02.
        assertEquals(0x02, lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW));

        double[] xyz = accel.read();
        assertEquals(-2.0, xyz[0], 1e-9);
        assertEquals(0.0, xyz[1], 1e-9);
        assertEquals(1.0, xyz[2], 1e-9);
    }

    @Test
    void constructionBadChipIdThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(Bma150Minimal.REG_CHIP_ID, 0xFF);
        assertThrows(IOException.class, () -> new Bma150Minimal(connection));
    }

    @Test
    void rangeAndBandwidth() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);

        full.setRange(4);
        // (0x02 & 0xE0) | 0x08 | (0x02 & 0x07) = 0x0A
        assertEquals(0x0A, lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW));

        connection.setRegister(Bma150Minimal.REG_RANGE_BW, 0x0A);
        full.setBandwidth(190);
        // (0x0A & 0xF8) | 0x03 = 0x0B
        assertEquals(0x0B, lastWriteTo(connection, Bma150Minimal.REG_RANGE_BW));
    }

    @Test
    void readRaw() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        int[] raw = full.readRaw();
        assertEquals(-512, raw[0]);
        assertEquals(0, raw[1]);
        assertEquals(256, raw[2]);
    }

    @Test
    void readTemperature() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        connection.setRegister(Bma150Minimal.REG_TEMP, 0x40);
        assertEquals(2.0, full.readTemperature(), 1e-9);
    }

    @Test
    void lowGAndHighG() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);

        // setLowG(0.4, 40): with range=2 -> round(0.4 * 255 / 2) = 51.
        full.setLowG(0.4, 40, 0, 0);
        assertEquals(51, lastWriteTo(connection, Bma150Minimal.REG_LG_THRES));
        assertEquals(40, lastWriteTo(connection, Bma150Minimal.REG_LG_DUR));
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) & 0x01) != 0);

        // setHighG(4.0, 2): with range=2 -> round(4.0 * 255 / 2) = 510 -> 255.
        full.setHighG(4.0, 2, 0, 0);
        assertEquals(255, lastWriteTo(connection, Bma150Minimal.REG_HG_THRES));
        assertEquals(2, lastWriteTo(connection, Bma150Minimal.REG_HG_DUR));
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) & 0x02) != 0);
    }

    @Test
    void anyMotion() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        full.setAnyMotion(0.5, 3);
        // scale = 256/256 = 1.0; round(0.5 / 0.0156) = 32.
        assertEquals(32, lastWriteTo(connection, Bma150Minimal.REG_ANY_MOTION_THRES));
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_HYST_DUR) & 0xC0) == 0x40);
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CONFIG) & 0x40) != 0);
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_INT_CTRL) & 0x40) != 0);
    }

    @Test
    void latchAndClear() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        full.setLatch(true);
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CONFIG) & 0x10) != 0);
        full.clearInterrupt();
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x40) != 0);
    }

    @Test
    void sleepWakeAndWakeUp() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        full.sleep();
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x01) != 0);
        full.wake();
        assertEquals(0, lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x01);
        connection.setRegister(Bma150Minimal.REG_CONFIG, 0x00);
        full.setWakeUp(true, 80);
        int cfg = lastWriteTo(connection, Bma150Minimal.REG_CONFIG);
        assertTrue((cfg & 0x03) == 0x03 && (cfg & 0x06) == 0x02);
    }

    @Test
    void softReset() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        full.softReset();
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CTRL) & 0x02) != 0);
    }

    @Test
    void selfTest() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        connection.setRegister(Bma150Minimal.REG_STATUS, 0x80);
        assertTrue(full.selfTest());
    }

    @Test
    void versionAndCustomer() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        connection.setRegister(Bma150Minimal.REG_VERSION, 0xAB);
        int[] v = full.readVersion();
        assertEquals(0x0A, v[0]);
        assertEquals(0x0B, v[1]);
        connection.setRegister(Bma150Minimal.REG_CUSTOMER_1, 0xA5);
        assertEquals(0xA5, full.readCustomer(0));
        full.writeCustomer(1, 0x5A);
        assertEquals(0x5A, lastWriteTo(connection, Bma150Minimal.REG_CUSTOMER_2));
    }

    @Test
    void setShadow() throws IOException {
        MockConnection connection = newConnection();
        Bma150Full full = new Bma150Full(connection);
        full.setShadow(true);
        assertTrue((lastWriteTo(connection, Bma150Minimal.REG_CONFIG) & 0x08) != 0);
        full.setShadow(false);
        assertEquals(0, lastWriteTo(connection, Bma150Minimal.REG_CONFIG) & 0x08);
    }
}
