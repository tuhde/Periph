package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the LSM303 driver using two {@link MockConnection}s. */
class LSM303Test {

    private static MockConnection[] mocks() {
        MockConnection accel = new MockConnection();
        MockConnection mag = new MockConnection();
        mag.setRegister(0x0A, 0x48, 0x34, 0x33);
        accel.setRegister(0x28 | 0x80, 0x10, 0x00, 0x20, 0x00, 0x30, 0x00);
        mag.setRegister(0x03, 0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4);
        return new MockConnection[]{accel, mag};
    }

    private static int lastWrite(MockConnection mock, int reg) {
        int value = -1;
        for (byte[] w : mock.writes()) {
            if (w.length == 2 && (w[0] & 0xFF) == reg) value = w[1] & 0xFF;
        }
        return value;
    }

    @Test
    void initAndRead() throws Exception {
        MockConnection[] m = mocks();
        LSM303Minimal chip = new LSM303Minimal(m[0], m[1]);
        assertEquals(0x88, lastWrite(m[0], 0x23));
        assertEquals(0x57, lastWrite(m[0], 0x20));
        assertEquals(0x20, lastWrite(m[1], 0x01));
        assertEquals(0x00, lastWrite(m[1], 0x02));

        double[] a = chip.readAcceleration();
        assertEquals(0.001, a[0], 1e-9);
        assertEquals(0.002, a[1], 1e-9);
        assertEquals(0.003, a[2], 1e-9);

        double[] mag = chip.readMagnetic();
        assertEquals(100.0, mag[0], 1e-6);
        assertEquals(-100.0, mag[1], 1e-6);
        assertEquals(100.0, mag[2], 1e-6);
    }

    @Test
    void wrongIdentityThrows() {
        MockConnection accel = new MockConnection();
        MockConnection mag = new MockConnection();
        assertThrows(java.io.IOException.class, () -> new LSM303Minimal(accel, mag));
    }

    @Test
    void fullConfiguration() throws Exception {
        MockConnection[] m = mocks();
        LSM303Full chip = new LSM303Full(m[0], m[1]);

        chip.setRange(4);
        double[] a = chip.readAcceleration();
        assertEquals(0.002, a[0], 1e-9);
        assertEquals(0x10, lastWrite(m[0], 0x23) & 0x30);

        chip.setDataRate(400);
        assertEquals(0x07, (lastWrite(m[0], 0x20) >> 4) & 0x0F);
        assertThrows(IllegalArgumentException.class, () -> chip.setDataRate(5376));

        chip.setMode(LSM303Minimal.MODE_NORMAL);
        assertEquals(0, lastWrite(m[0], 0x23) & 0x08);
        chip.setRange(2);

        chip.setMotionInterrupt(1, 0x3F, 0.5, 0, true);
        assertEquals(32, lastWrite(m[0], 0x32));
        assertEquals(0x80, lastWrite(m[0], 0x30) & 0x80);

        chip.setFreeFall(1, 0.35, 30);
        assertEquals(0x95, lastWrite(m[0], 0x30));

        chip.setClick(0, 0x04, 0.5, 10, 20, 50);
        assertEquals(0x20, lastWrite(m[0], 0x38) & 0x20);

        chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1);
        assertEquals(0x10, lastWrite(m[0], 0x22) & 0x10);
        chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 2);
        assertEquals(0x80, lastWrite(m[0], 0x25) & 0x80);
        assertThrows(IllegalArgumentException.class,
                () -> chip.enableInterrupt(LSM303Source.SOURCE_MAG_DATA_READY, 1));

        chip.setMagRange(470);
        assertEquals(0xA0, lastWrite(m[1], 0x01));
        m[1].setRegister(0x03, 0x01, 0x90, 0x01, 0x8F, 0x01, 0x8C);
        assertEquals(100.0, chip.readMagnetic()[0], 1e-6);

        chip.enableTemperature(true);
        chip.setMagDataRate(30);
        assertEquals(0x14, lastWrite(m[1], 0x00) & 0x1C);
        assertEquals(0x80, lastWrite(m[1], 0x00) & 0x80);

        assertArrayEquals(new byte[]{0x48, 0x34, 0x33}, chip.readMagIdentity());

        m[1].setRegister(0x31, 0x00, 0x80);
        assertEquals(1.0, chip.readTemperatureDelta(), 1e-9);
    }
}
