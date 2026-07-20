package it.uhde.periph.chips.imu

import it.uhde.periph.transport.Transport
import groovy.transform.CompileStatic

import java.io.IOException

/**
 * MPU-6050 — 6-axis MotionTracking device (accelerometer + gyroscope), minimal driver.
 *
 * <p>Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the transport. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization.
 */
@CompileStatic
class Mpu6050Minimal {

    protected static final int REG_SMPLRT_DIV   = 0x19
    protected static final int REG_CONFIG       = 0x1A
    protected static final int REG_GYRO_CONFIG  = 0x1B
    protected static final int REG_ACCEL_CONFIG = 0x1C
    protected static final int REG_FIFO_EN      = 0x23
    protected static final int REG_INT_STATUS   = 0x3A
    protected static final int REG_ACCEL_XOUT_H = 0x3B
    protected static final int REG_TEMP_OUT_H   = 0x41
    protected static final int REG_GYRO_XOUT_H  = 0x43
    protected static final int REG_USER_CTRL    = 0x6A
    protected static final int REG_PWR_MGMT_1   = 0x6B
    protected static final int REG_PWR_MGMT_2   = 0x6C
    protected static final int REG_FIFO_COUNTH  = 0x72
    protected static final int REG_FIFO_R_W     = 0x74
    protected static final int REG_WHO_AM_I     = 0x75

    protected static final int WHO_AM_I_VALUE = 0x68

    protected static final double[] ACCEL_SENSITIVITY = [16384.0d, 8192.0d, 4096.0d, 2048.0d] as double[]
    protected static final double[] GYRO_SENSITIVITY  = [131.0d, 65.5d, 32.8d, 16.4d] as double[]

    protected final Transport transport
    protected int accelFs = 0
    protected int gyroFs = 0

    Mpu6050Minimal(Transport transport) {
        this.transport = transport
        writeReg(REG_PWR_MGMT_1, 0x80)
        Thread.sleep(100)
        writeReg(REG_PWR_MGMT_1, 0x01)
        int who = readReg(REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("MPU6050 WHO_AM_I: expected 0x${Integer.toHexString(WHO_AM_I_VALUE)}, got 0x${Integer.toHexString(who)}")
        }
        writeReg(REG_GYRO_CONFIG, 0x00)
        writeReg(REG_ACCEL_CONFIG, 0x00)
        writeReg(REG_CONFIG, 0x03)
        writeReg(REG_SMPLRT_DIV, 0x04)
        Thread.sleep(35)
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return array [x, y, z] in m/s².
     */
    double[] accel() {
        byte[] buf = transport.writeRead([(byte) REG_ACCEL_XOUT_H] as byte[], 6)
        int ax = (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF))
        int ay = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF))
        int az = (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        double sens = ACCEL_SENSITIVITY[accelFs]
        return [ax / sens * 9.80665d, ay / sens * 9.80665d, az / sens * 9.80665d] as double[]
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return array [x, y, z] in rad/s.
     */
    double[] gyro() {
        byte[] buf = transport.writeRead([(byte) REG_GYRO_XOUT_H] as byte[], 6)
        int gx = (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF))
        int gy = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF))
        int gz = (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        double sens = GYRO_SENSITIVITY[gyroFs]
        return [gx / sens * Math.PI / 180.0d,
                gy / sens * Math.PI / 180.0d,
                gz / sens * Math.PI / 180.0d] as double[]
    }

    protected void writeReg(int reg, int val) {
        transport.write([(byte) reg, (byte) val] as byte[])
    }

    protected int readReg(int reg) {
        byte[] b = transport.writeRead([(byte) reg] as byte[], 1)
        return b[0] & 0xFF
    }

    protected int readReg16Signed(int reg) {
        byte[] b = transport.writeRead([(byte) reg] as byte[], 2)
        int v = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
        if (v > 32767) v -= 65536
        return v
    }
}
