package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.MockConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mpu6050Test {

    // Encode a signed 16-bit value as its two big-endian bytes.
    private static int[] s16(int value) {
        return new int[]{(value >> 8) & 0xFF, value & 0xFF};
    }

    private static byte[] lastWrite(MockConnection connection) {
        var writes = connection.writes();
        return writes.get(writes.size() - 1);
    }

    private static MPU6050Full newInitializedSensor(MockConnection connection) throws IOException {
        connection.setRegister(MPU6050Minimal.REG_WHO_AM_I, MPU6050Minimal.WHO_AM_I_VALUE);
        return new MPU6050Full(connection);
    }

    @Test
    void initWritesSequence() throws IOException {
        MockConnection connection = new MockConnection();
        newInitializedSensor(connection);

        byte[][] expected = {
                {(byte) MPU6050Minimal.REG_PWR_MGMT_1, (byte) 0x80},
                {(byte) MPU6050Minimal.REG_PWR_MGMT_1, (byte) 0x01},
                {(byte) MPU6050Minimal.REG_WHO_AM_I},
                {(byte) MPU6050Minimal.REG_GYRO_CONFIG, (byte) 0x00},
                {(byte) MPU6050Minimal.REG_ACCEL_CONFIG, (byte) 0x00},
                {(byte) MPU6050Minimal.REG_CONFIG, (byte) 0x03},
                {(byte) MPU6050Minimal.REG_SMPLRT_DIV, (byte) 0x04},
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
        connection.setRegister(MPU6050Minimal.REG_WHO_AM_I, 0x00);
        assertThrows(IOException.class, () -> new MPU6050Minimal(connection));
    }

    @Test
    void fullApi() throws IOException {
        MockConnection connection = new MockConnection();
        MPU6050Full sensor = newInitializedSensor(connection);

        // accel(): raw (16384, -8192, 4096) at default AFS_SEL=0 (16384 LSB/g).
        connection.setRegister(MPU6050Minimal.REG_ACCEL_XOUT_H, concat(s16(16384), s16(-8192), s16(4096)));
        double[] a = sensor.accel();
        assertEquals(9.80665, a[0], 1e-9);
        assertEquals(-4.903325, a[1], 1e-9);
        assertEquals(2.4516625, a[2], 1e-9);

        // gyro(): raw (131, -131, 262) at default FS_SEL=0 -> (1, -1, 2) dps.
        connection.setRegister(MPU6050Minimal.REG_GYRO_XOUT_H, concat(s16(131), s16(-131), s16(262)));
        double[] g = sensor.gyro();
        assertEquals(Math.toRadians(1), g[0], 1e-9);
        assertEquals(Math.toRadians(-1), g[1], 1e-9);
        assertEquals(Math.toRadians(2), g[2], 1e-9);

        sensor.configureGyro(2);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_GYRO_CONFIG, (byte) (2 << 3)}, lastWrite(connection));
        // Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
        connection.setRegister(MPU6050Minimal.REG_GYRO_XOUT_H, concat(s16(328), s16(0), s16(0)));
        double[] g2 = sensor.gyro();
        assertEquals(Math.toRadians(10), g2[0], 1e-6);

        sensor.configureAccel(1);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_ACCEL_CONFIG, (byte) (1 << 3)}, lastWrite(connection));
        // Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
        connection.setRegister(MPU6050Minimal.REG_ACCEL_XOUT_H, concat(s16(8192), s16(0), s16(0)));
        double[] a2 = sensor.accel();
        assertEquals(9.80665, a2[0], 1e-6);

        sensor.configureDlpf(5);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_CONFIG, (byte) 5}, lastWrite(connection));

        sensor.configureSampleRate(9);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_SMPLRT_DIV, (byte) 9}, lastWrite(connection));

        // temperature(): raw=340 -> 340/340 + 36.53 = 37.53 degC.
        connection.setRegister(MPU6050Minimal.REG_TEMP_OUT_H, s16(340));
        assertEquals(37.53, sensor.temperature(), 1e-9);

        // accelRaw() / gyroRaw()
        connection.setRegister(MPU6050Minimal.REG_ACCEL_XOUT_H, concat(s16(100), s16(-200), s16(300)));
        assertArrayEquals(new int[]{100, -200, 300}, sensor.accelRaw());
        connection.setRegister(MPU6050Minimal.REG_GYRO_XOUT_H, concat(s16(-50), s16(60), s16(-70)));
        assertArrayEquals(new int[]{-50, 60, -70}, sensor.gyroRaw());

        // dataReady()
        connection.setRegister(MPU6050Minimal.REG_INT_STATUS, 0x01);
        assertTrue(sensor.dataReady());
        connection.setRegister(MPU6050Minimal.REG_INT_STATUS, 0x00);
        assertFalse(sensor.dataReady());

        // setSleep(): PWR_MGMT_1 is 0x01 in the register map after init.
        sensor.setSleep(true);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_PWR_MGMT_1, (byte) 0x41}, lastWrite(connection));
        sensor.setSleep(false);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_PWR_MGMT_1, (byte) 0x01}, lastWrite(connection));

        // setStandby(xa=true, zg=true)
        sensor.setStandby(true, false, false, false, false, true);
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_PWR_MGMT_2, (byte) 0x21}, lastWrite(connection));

        // fifoCount()
        connection.setRegister(MPU6050Minimal.REG_FIFO_COUNTH, 0x03, 0x45);
        assertEquals(((0x03 & 0x1F) << 8) | 0x45, sensor.fifoCount());

        // readFifo()
        connection.setRegister(MPU6050Minimal.REG_FIFO_COUNTH, 0x00, 0x02);
        connection.setRegister(MPU6050Minimal.REG_FIFO_R_W, 0xAA, 0xBB);
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB}, sensor.readFifo());

        connection.setRegister(MPU6050Minimal.REG_FIFO_COUNTH, 0x00, 0x00);
        assertEquals(0, sensor.readFifo().length);

        // enableFifo(gyro=true, accel=true, temp=false): FIFO_EN write, then a
        // USER_CTRL read (whose writeRead phase also appends a bytes([reg])
        // entry), then the USER_CTRL write.
        sensor.enableFifo(true, true, false);
        var writes = connection.writes();
        int n = writes.size();
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_FIFO_EN, (byte) ((1 << 3) | (1 << 4))}, writes.get(n - 3));
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_USER_CTRL}, writes.get(n - 2));
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_USER_CTRL, (byte) 0x40}, writes.get(n - 1));

        // resetFifo(): USER_CTRL is 0x40 in the register map after enableFifo().
        sensor.resetFifo();
        assertArrayEquals(new byte[]{(byte) MPU6050Minimal.REG_USER_CTRL, (byte) 0x44}, lastWrite(connection));
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
