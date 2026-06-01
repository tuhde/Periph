package it.uhde.periph.chips.imu;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * MPU-6050 — 6-axis MotionTracking device (accelerometer + gyroscope), minimal driver.
 *
 * <p>Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the transport. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization.
 *
 * <p>Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Gyroscope full-scale: ±250 dps (FS_SEL=0)</li>
 *   <li>Accelerometer full-scale: ±2 g (AFS_SEL=0)</li>
 *   <li>DLPF: 44 Hz bandwidth (DLPF_CFG=3)</li>
 *   <li>Sample rate: 200 Hz (SMPLRT_DIV=4)</li>
 *   <li>Clock: PLL with gyro X reference (CLKSEL=1)</li>
 * </ul>
 */
public class Mpu6050Minimal {

    protected static final int REG_SMPLRT_DIV   = 0x19;
    protected static final int REG_CONFIG       = 0x1A;
    protected static final int REG_GYRO_CONFIG  = 0x1B;
    protected static final int REG_ACCEL_CONFIG = 0x1C;
    protected static final int REG_FIFO_EN      = 0x23;
    protected static final int REG_INT_STATUS   = 0x3A;
    protected static final int REG_ACCEL_XOUT_H = 0x3B;
    protected static final int REG_TEMP_OUT_H   = 0x41;
    protected static final int REG_GYRO_XOUT_H  = 0x43;
    protected static final int REG_USER_CTRL    = 0x6A;
    protected static final int REG_PWR_MGMT_1   = 0x6B;
    protected static final int REG_PWR_MGMT_2   = 0x6C;
    protected static final int REG_FIFO_COUNTH  = 0x72;
    protected static final int REG_FIFO_COUNTL  = 0x73;
    protected static final int REG_FIFO_R_W     = 0x74;
    protected static final int REG_WHO_AM_I     = 0x75;

    protected static final int WHO_AM_I_VALUE = 0x68;

    protected static final double[] ACCEL_SENSITIVITY = {16384.0, 8192.0, 4096.0, 2048.0};
    protected static final double[] GYRO_SENSITIVITY  = {131.0, 65.5, 32.8, 16.4};

    protected final Transport transport;
    protected int accelFs = 0;
    protected int gyroFs = 0;

    public Mpu6050Minimal(Transport transport) throws IOException {
        this.transport = transport;
        writeReg(REG_PWR_MGMT_1, 0x80);
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        writeReg(REG_PWR_MGMT_1, 0x01);
        int who = readReg(REG_WHO_AM_I);
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("MPU6050 WHO_AM_I: expected 0x" +
                    Integer.toHexString(WHO_AM_I_VALUE) + ", got 0x" + Integer.toHexString(who));
        }
        writeReg(REG_GYRO_CONFIG, 0x00);
        writeReg(REG_ACCEL_CONFIG, 0x00);
        writeReg(REG_CONFIG, 0x03);
        writeReg(REG_SMPLRT_DIV, 0x04);
        try { Thread.sleep(35); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return array [x, y, z] in m/s².
     * @throws IOException on I²C error.
     */
    public double[] accel() throws IOException {
        byte[] buf = transport.writeRead(new byte[]{(byte) REG_ACCEL_XOUT_H}, 6);
        int ax = (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF));
        int ay = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF));
        int az = (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF));
        double sens = ACCEL_SENSITIVITY[accelFs];
        return new double[]{ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665};
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return array [x, y, z] in rad/s.
     * @throws IOException on I²C error.
     */
    public double[] gyro() throws IOException {
        byte[] buf = transport.writeRead(new byte[]{(byte) REG_GYRO_XOUT_H}, 6);
        int gx = (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF));
        int gy = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF));
        int gz = (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF));
        double sens = GYRO_SENSITIVITY[gyroFs];
        return new double[]{gx / sens * Math.PI / 180.0,
                            gy / sens * Math.PI / 180.0,
                            gz / sens * Math.PI / 180.0};
    }

    protected void writeReg(int reg, int val) throws IOException {
        transport.write(new byte[]{(byte) reg, (byte) val});
    }

    protected int readReg(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) reg}, 1);
        return b[0] & 0xFF;
    }

    protected int readReg16Signed(int reg) throws IOException {
        byte[] b = transport.writeRead(new byte[]{(byte) reg}, 2);
        return (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
    }
}
