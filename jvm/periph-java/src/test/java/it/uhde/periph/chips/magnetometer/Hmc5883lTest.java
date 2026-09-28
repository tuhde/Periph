package it.uhde.periph.chips.magnetometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hmc5883lTest {

    private static int lastWriteTo(MockConnection connection, int reg) {
        var writes = connection.writes();
        for (int i = writes.size() - 1; i >= 0; i--) {
            byte[] w = writes.get(i);
            if (w.length == 2 && (w[0] & 0xFF) == reg) return w[1] & 0xFF;
        }
        return -1;
    }

    @Test
    void constructionAndMagneticField() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lMinimal chip = new Hmc5883lMinimal(connection);
        assertEquals(0x70, lastWriteTo(connection, 0x00));
        assertEquals(0x20, lastWriteTo(connection, 0x01));
        assertEquals(0x00, lastWriteTo(connection, 0x02));

        // X,Z,Y wire order -> (x,y,z), gain=1 (1090 LSb/Gauss)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0); // x=1000,z=-500,y=2000
        Double[] xyz = chip.magneticField();
        assertEquals((1000 / 1090.0) * 1e-4, xyz[0], 1e-9);
        assertEquals((2000 / 1090.0) * 1e-4, xyz[1], 1e-9);
        assertEquals((-500 / 1090.0) * 1e-4, xyz[2], 1e-9);
    }

    @Test
    void magneticFieldOverflowReturnsNull() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lMinimal chip = new Hmc5883lMinimal(connection);
        connection.setRegister(0x03, 0xF0, 0x00, 0x03, 0xE8, 0x03, 0xE8); // x overflow, z/y=1000
        Double[] xyz = chip.magneticField();
        assertNull(xyz[0]);
        assertTrue(xyz[1] != null);
    }

    @Test
    void configureAndSetGain() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lFull full = new Hmc5883lFull(connection);

        full.configure(30, 4, 5);
        assertEquals(0x54, lastWriteTo(connection, 0x00)); // MA=10,DO=101
        assertEquals(0xA0, lastWriteTo(connection, 0x01)); // GN=101
        assertEquals(5, full.gain);

        assertThrows(IllegalArgumentException.class, () -> full.configure(30, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> full.configure(100, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> full.configure(30, 4, 8));

        full.setGain(2);
        assertEquals(2 << 5, lastWriteTo(connection, 0x01));
        assertEquals(2, full.gain);
        assertThrows(IllegalArgumentException.class, () -> full.setGain(9));
    }

    @Test
    void setMode() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lFull full = new Hmc5883lFull(connection);

        full.setMode("continuous");
        assertEquals(0b00, lastWriteTo(connection, 0x02));
        full.setMode("single");
        assertEquals(0b01, lastWriteTo(connection, 0x02));
        full.setMode("idle");
        assertEquals(0b10, lastWriteTo(connection, 0x02));
        assertThrows(IllegalArgumentException.class, () -> full.setMode("bogus"));
    }

    @Test
    void dataReadyAndStatus() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lFull full = new Hmc5883lFull(connection);

        connection.setRegister(0x09, 0x01);
        assertTrue(full.dataReady());
        assertEquals(0x01, full.status());
        connection.setRegister(0x09, 0x02); // LOCK set, RDY clear
        assertFalse(full.dataReady());
    }

    @Test
    void singleMeasurement() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lFull full = new Hmc5883lFull(connection);
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0);
        Double[] xyz = full.singleMeasurement();
        assertEquals(0x01, lastWriteTo(connection, 0x02));
        assertEquals((1000 / (double) full.gainLsbPerGauss) * 1e-4, xyz[0], 1e-9);
    }

    @Test
    void identify() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lFull full = new Hmc5883lFull(connection);
        connection.setRegister(0x0A, 0x48, 0x34, 0x33);
        int[] id = full.identify();
        assertEquals(0x48, id[0]);
        assertEquals(0x34, id[1]);
        assertEquals(0x33, id[2]);
    }

    @Test
    void selfTestSetsAndRestoresBias() throws Exception {
        MockConnection connection = new MockConnection();
        Hmc5883lFull full = new Hmc5883lFull(connection);
        connection.setRegister(0x00, 0x70); // current Config A (post-init)
        connection.setRegister(0x03, 0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0);

        Double[] xyz = full.selfTest(true);
        java.util.List<Integer> writesA = new java.util.ArrayList<>();
        for (byte[] w : connection.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == 0x00) writesA.add(w[1] & 0xFF);
        }
        assertTrue(writesA.contains(0x71)); // 0x70|0b01
        assertEquals(0x70, writesA.get(writesA.size() - 1));
        assertEquals((1000 / (double) full.gainLsbPerGauss) * 1e-4, xyz[0], 1e-9);
    }
}
