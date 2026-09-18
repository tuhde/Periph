package it.uhde.periph.chips.gyroscope;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class L3gd20hTest {

    @Test
    void fullApi() throws Exception {
        MockConnection connection = new MockConnection();
        // Preload WHO_AM_I so init() doesn't throw.
        connection.setRegister(L3gd20hMinimal.REG_WHO_AM_I, 0xD7);

        L3gd20hFull sensor = new L3gd20hFull(connection, false);

        assertEquals(L3gd20hFull.CTRL_REG4_DEFAULT,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG4));
        assertEquals(L3gd20hFull.CTRL_REG1_DEFAULT,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1));

        // raw X=+16 (LE: low=0x10, high=0x00), Y=0, Z=-16 (low=0xF0, high=0xFF).
        // Sub-address for I²C multi-byte auto-increment is reg | 0x80.
        connection.setRegister(L3gd20hMinimal.REG_OUT_X_L | 0x80,
                0x10, 0x00,    // X=+16
                0x00, 0x00,    // Y=0
                0xF0, 0xFF);  // Z=-16
        float[] xyz = sensor.gyro();
        float k = (float) (Math.PI / 180.0);
        assertEquals(16.0f * 0.00875f * k, xyz[0], 1e-6f);
        assertEquals(0.0f, xyz[1], 1e-6f);
        assertEquals(-16.0f * 0.00875f * k, xyz[2], 1e-6f);

        // configure(ODR_190_HZ=1, bw=0, FS_500_DPS=1).
        sensor.configure(L3gd20hFull.ODR_190_HZ, 0, L3gd20hFull.FS_500_DPS);
        assertEquals(L3gd20hFull.CTRL_REG1_DEFAULT | (1 << 6),
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1));
        assertEquals(L3gd20hFull.CTRL_REG4_DEFAULT | (1 << 4),
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG4));

        // gyroRaw(): X=-32768, Y=32767, Z=0.
        connection.setRegister(L3gd20hMinimal.REG_OUT_X_L | 0x80,
                0x00, 0x80,    // X=-32768
                0xFF, 0x7F,    // Y=32767
                0x00, 0x00);  // Z=0
        short[] raw = sensor.gyroRaw();
        assertEquals(-32768, raw[0]);
        assertEquals(32767, raw[1]);
        assertEquals(0, raw[2]);

        // temperature(): OUT_TEMP=0x80 -> -128.
        connection.setRegister(L3gd20hMinimal.REG_OUT_TEMP, 0x80);
        assertEquals(-128, sensor.temperature());

        // dataReady(): STATUS.ZYXDA bit 3.
        connection.setRegister(L3gd20hMinimal.REG_STATUS, 0x08);
        assertTrue(sensor.dataReady());

        // configureHpFilter(mode=1, cutoff=5) -> CTRL_REG2 = (1<<4)|5 = 0x15.
        sensor.configureHpFilter(1, 5);
        assertEquals(0x15,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG2));

        // enableHpFilter(true/false) toggles HPen (bit 4) in CTRL_REG5.
        connection.setRegister(L3gd20hMinimal.REG_CTRL_REG5, 0x00);
        sensor.enableHpFilter(true);
        assertEquals(0x10,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5));
        sensor.enableHpFilter(false);
        assertEquals(0x00,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5));

        // configureFifo(mode=FIFO_FIFO=1, watermark=10).
        connection.setRegister(L3gd20hMinimal.REG_CTRL_REG5, 0x00);
        sensor.configureFifo(L3gd20hFull.FIFO_FIFO, 10);
        assertEquals(0x40,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5));
        assertEquals((1 << 5) | 10,
                (int) connection.registers().get(L3gd20hMinimal.REG_FIFO_CTRL));

        // enableFifo(true) sets FIFO_EN; enableFifo(false) clears it and FIFO_CTRL.
        sensor.enableFifo(true);
        assertEquals(0x40,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5));
        sensor.enableFifo(false);
        assertEquals(0x00,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG5));
        assertEquals(0x00,
                (int) connection.registers().get(L3gd20hMinimal.REG_FIFO_CTRL));

        // fifoLevel(): FIFO_SRC FSS[4:0] = 5.
        connection.setRegister(L3gd20hMinimal.REG_FIFO_SRC, 0x05);
        assertEquals(5, sensor.fifoLevel());

        // readFifo(): 5 samples in FIFO_SRC, then burst-read 5*6 bytes.
        connection.setRegister(L3gd20hMinimal.REG_OUT_X_L | 0x80,
                0x10, 0x00, 0x20, 0x00, 0x30, 0x00,
                0x40, 0x00, 0x50, 0x00, 0x60, 0x00,
                0x70, 0x00, 0x80, 0x00, 0x90, 0x00,
                0xA0, 0x00, 0xB0, 0x00, 0xC0, 0x00,
                0xD0, 0x00, 0xE0, 0x00, 0xF0, 0x00);
        var samples = sensor.readFifo();
        assertEquals(5, samples.size());

        // setPowerMode(NORMAL/SLEEP/POWERDOWN).
        connection.setRegister(L3gd20hMinimal.REG_CTRL_REG1, 0x00);
        sensor.setPowerMode(L3gd20hFull.POWER_NORMAL);
        assertEquals(0x0F,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) & 0x0F);
        sensor.setPowerMode(L3gd20hFull.POWER_SLEEP);
        assertEquals(0x08,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) & 0x0F);
        sensor.setPowerMode(L3gd20hFull.POWER_POWERDOWN);
        assertEquals(0x00,
                (int) connection.registers().get(L3gd20hMinimal.REG_CTRL_REG1) & 0x08);
    }
}
