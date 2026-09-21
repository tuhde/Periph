package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mpu9250Test {

    // AK8963 register constants (private in MPU9250Full, so mirrored here).
    private static final int AK8963_REG_CNTL1 = 0x0A;
    private static final int AK8963_REG_ASAX  = 0x10;
    private static final int AK8963_REG_ASAY  = 0x11;
    private static final int AK8963_REG_ASAZ  = 0x12;
    private static final int AK8963_REG_HXL   = 0x03;

    // Encode a signed 16-bit value as its two big-endian bytes.
    private static int[] s16(int value) {
        return new int[]{(value >> 8) & 0xFF, value & 0xFF};
    }

    // Encode a signed 16-bit value as its two little-endian bytes.
    private static int[] s16le(int value) {
        return new int[]{value & 0xFF, (value >> 8) & 0xFF};
    }

    private static byte[] lastWrite(MockConnection connection) {
        var writes = connection.writes();
        return writes.get(writes.size() - 1);
    }

    private static MPU9250Full newInitializedSensor(MockConnection connection, MockConnection magConnection) throws IOException {
        connection.setRegister(MPU9250Minimal.REG_WHO_AM_I, MPU9250Minimal.WHO_AM_I_VALUE);
        return new MPU9250Full(connection, magConnection);
    }

    @Test
    void initWritesSequence() throws IOException {
        MockConnection connection = new MockConnection();
        newInitializedSensor(connection, new MockConnection());

        // MPU9250 additionally writes ACCEL_CONFIG2, unlike MPU6050.
        byte[][] expected = {
                {(byte) MPU9250Minimal.REG_PWR_MGMT_1, (byte) 0x80},
                {(byte) MPU9250Minimal.REG_PWR_MGMT_1, (byte) 0x01},
                {(byte) MPU9250Minimal.REG_WHO_AM_I},
                {(byte) MPU9250Minimal.REG_GYRO_CONFIG, (byte) 0x00},
                {(byte) MPU9250Minimal.REG_ACCEL_CONFIG, (byte) 0x00},
                {(byte) MPU9250Minimal.REG_ACCEL_CONFIG2, (byte) 0x00},
                {(byte) MPU9250Minimal.REG_CONFIG, (byte) 0x03},
                {(byte) MPU9250Minimal.REG_SMPLRT_DIV, (byte) 0x04},
        };
        var writes = connection.writes();
        assertEquals(expected.length, writes.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], writes.get(i), "write[" + i + "]");
        }
    }

    @Test
    void whoAmIMismatchThrows() {
        MockConnection connection = new MockConnection();
        connection.setRegister(MPU9250Minimal.REG_WHO_AM_I, 0x00);
        assertThrows(IOException.class, () -> new MPU9250Minimal(connection));
    }

    @Test
    void fullApi() throws IOException {
        MockConnection connection = new MockConnection();
        MockConnection magConnection = new MockConnection();
        MPU9250Full sensor = newInitializedSensor(connection, magConnection);

        // accel(): raw (16384, -8192, 4096) at default ACCEL_FS_SEL=0 (16384 LSB/g).
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, concat(s16(16384), s16(-8192), s16(4096)));
        double[] a = sensor.accel();
        assertEquals(9.80665, a[0], 1e-9);
        assertEquals(-4.903325, a[1], 1e-9);
        assertEquals(2.4516625, a[2], 1e-9);

        // gyro(): raw (131, -131, 262) at default GYRO_FS_SEL=0 -> (1, -1, 2) dps.
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, concat(s16(131), s16(-131), s16(262)));
        double[] g = sensor.gyro();
        assertEquals(Math.toRadians(1), g[0], 1e-9);
        assertEquals(Math.toRadians(-1), g[1], 1e-9);
        assertEquals(Math.toRadians(2), g[2], 1e-9);

        sensor.configureGyro(2);
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_GYRO_CONFIG, (byte) (2 << 3)}, lastWrite(connection));
        // Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, concat(s16(328), s16(0), s16(0)));
        double[] g2 = sensor.gyro();
        assertEquals(Math.toRadians(10), g2[0], 1e-6);

        sensor.configureAccel(1);
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_ACCEL_CONFIG, (byte) (1 << 3)}, lastWrite(connection));
        // Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, concat(s16(8192), s16(0), s16(0)));
        double[] a2 = sensor.accel();
        assertEquals(9.80665, a2[0], 1e-6);

        sensor.configureDlpf(5, 2);
        var dlpfWrites = connection.writes();
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_CONFIG, (byte) 5}, dlpfWrites.get(dlpfWrites.size() - 2));
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_ACCEL_CONFIG2, (byte) 2}, lastWrite(connection));

        sensor.configureSampleRate(9);
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_SMPLRT_DIV, (byte) 9}, lastWrite(connection));

        // temperature(): raw=340 -> 340/333.87 + 21.0.
        connection.setRegister(MPU9250Minimal.REG_TEMP_OUT_H, s16(340));
        assertEquals(340.0 / 333.87 + 21.0, sensor.temperature(), 1e-9);

        // accelRaw() / gyroRaw()
        connection.setRegister(MPU9250Minimal.REG_ACCEL_XOUT_H, concat(s16(100), s16(-200), s16(300)));
        assertArrayEquals(new int[]{100, -200, 300}, sensor.accelRaw());
        connection.setRegister(MPU9250Minimal.REG_GYRO_XOUT_H, concat(s16(-50), s16(60), s16(-70)));
        assertArrayEquals(new int[]{-50, 60, -70}, sensor.gyroRaw());

        // dataReady()
        connection.setRegister(MPU9250Minimal.REG_INT_STATUS, 0x01);
        assertTrue(sensor.dataReady());
        connection.setRegister(MPU9250Minimal.REG_INT_STATUS, 0x00);
        assertFalse(sensor.dataReady());

        // setSleep(): PWR_MGMT_1 is 0x01 in the register map after init.
        sensor.setSleep(true);
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_PWR_MGMT_1, (byte) 0x41}, lastWrite(connection));
        sensor.setSleep(false);
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_PWR_MGMT_1, (byte) 0x01}, lastWrite(connection));

        // fifoCount()
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x03, 0x45);
        assertEquals(((0x03 & 0x1F) << 8) | 0x45, sensor.fifoCount());

        // readFifo()
        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x00, 0x02);
        connection.setRegister(MPU9250Minimal.REG_FIFO_R_W, 0xAA, 0xBB);
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB}, sensor.readFifo());

        connection.setRegister(MPU9250Minimal.REG_FIFO_COUNTH, 0x00, 0x00);
        assertEquals(0, sensor.readFifo().length);

        // enableFifo(gyro=true, accel=true, temp=false): FIFO_EN write, then a
        // USER_CTRL read (whose writeRead phase also appends a bytes([reg])
        // entry), then the USER_CTRL write.
        sensor.enableFifo(true, true, false);
        var writes = connection.writes();
        int n = writes.size();
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_FIFO_EN, (byte) ((1 << 3) | (1 << 4))}, writes.get(n - 3));
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_USER_CTRL}, writes.get(n - 2));
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_USER_CTRL, (byte) 0x40}, writes.get(n - 1));

        // resetFifo(): USER_CTRL is 0x40 in the register map after enableFifo().
        sensor.resetFifo();
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_USER_CTRL, (byte) 0x44}, lastWrite(connection));
    }

    @Test
    void magApi() throws IOException, InterruptedException {
        MockConnection connection = new MockConnection();
        MockConnection magConnection = new MockConnection();
        MPU9250Full sensor = newInitializedSensor(connection, magConnection);

        // enableMag(): INT_PIN_CFG write (on the primary connection), AK8963
        // CNTL1 power-down, CNTL1 fuse ROM access, ASAX/ASAY/ASAZ reads,
        // CNTL1 power-down, then CNTL1 mode write (bits=16 -> 0x10 | mode) -
        // all on magConnection.
        magConnection.setRegister(AK8963_REG_ASAX, 200);
        magConnection.setRegister(AK8963_REG_ASAY, 100);
        magConnection.setRegister(AK8963_REG_ASAZ, 50);
        sensor.enableMag(16, 6);
        assertArrayEquals(new byte[]{(byte) MPU9250Minimal.REG_INT_PIN_CFG, (byte) 0x22}, lastWrite(connection));

        var magWrites = magConnection.writes();
        assertEquals(7, magWrites.size());
        assertArrayEquals(new byte[]{(byte) AK8963_REG_CNTL1, (byte) 0x00}, magWrites.get(0));
        assertArrayEquals(new byte[]{(byte) AK8963_REG_CNTL1, (byte) 0x0F}, magWrites.get(1));
        assertArrayEquals(new byte[]{(byte) AK8963_REG_ASAX}, magWrites.get(2));
        assertArrayEquals(new byte[]{(byte) AK8963_REG_ASAY}, magWrites.get(3));
        assertArrayEquals(new byte[]{(byte) AK8963_REG_ASAZ}, magWrites.get(4));
        assertArrayEquals(new byte[]{(byte) AK8963_REG_CNTL1, (byte) 0x00}, magWrites.get(5));
        assertArrayEquals(new byte[]{(byte) AK8963_REG_CNTL1, (byte) 0x16}, magWrites.get(6));  // 16-bit | mode=6

        // mag(): raw (1000, -500, 250) with scale factors derived from ASAX/ASAY/ASAZ
        // above: (200-128)/256+1=1.28125, (100-128)/256+1=0.890625, (50-128)/256+1=0.6953125.
        magConnection.setRegister(AK8963_REG_HXL,
                concat(s16le(1000), s16le(-500), s16le(250), new int[]{0x00}));
        double[] m = sensor.mag();
        assertEquals(1000 * 0.15 * 1.28125, m[0], 1e-9);
        assertEquals(-500 * 0.15 * 0.890625, m[1], 1e-9);
        assertEquals(250 * 0.15 * 0.6953125, m[2], 1e-9);

        // magRaw()
        magConnection.setRegister(AK8963_REG_HXL,
                concat(s16le(111), s16le(-222), s16le(333), new int[]{0x00}));
        assertArrayEquals(new int[]{111, -222, 333}, sensor.magRaw());
    }

    @Test
    void magNotEnabledThrows() throws IOException {
        MPU9250Full sensor = newInitializedSensor(new MockConnection(), new MockConnection());
        assertThrows(IllegalStateException.class, sensor::mag);
        assertThrows(IllegalStateException.class, sensor::magRaw);
    }

    private static int[] concat(int[]... parts) {
        int total = 0;
        for (int[] p : parts) total += p.length;
        int[] out = new int[total];
        int i = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }
}
