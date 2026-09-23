package it.uhde.periph.chips.imu

import it.uhde.periph.connection.Connection

import groovy.transform.CompileStatic

/**
 * MPU-9255 — 9-axis MotionTracking device (accelerometer + gyroscope), minimal driver.
 *
 * Provides 3-axis acceleration and 3-axis angular rate readings with no
 * configuration beyond the connection. Performs device reset, WHO_AM_I check,
 * and enables all sensors at defaults during initialization. Magnetometer
 * and wake-on-motion are not included in Minimal — both require non-trivial
 * secondary initialization paths.
 *
 * Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 * The MPU-9255's WHO_AM_I register reads 0x73; the MPU-9250 reads 0x71 at
 * the same address.
 *
 * ## Configuration defaults
 * - Gyroscope full-scale: ±250 dps (GYRO_FS_SEL=0)
 * - Accelerometer full-scale: ±2 g (ACCEL_FS_SEL=0)
 * - Gyroscope DLPF: 41 Hz bandwidth (CONFIG DLPF_CFG=3)
 * - Accelerometer DLPF: 44.8 Hz bandwidth (ACCEL_CONFIG2 A_DLPFCFG=3)
 * - Sample rate: 200 Hz (SMPLRT_DIV=4)
 * - Clock: auto PLL (CLKSEL=1)
 * - All six axes enabled
 */
@CompileStatic
class MPU9255Minimal {

    protected static final int REG_SMPLRT_DIV    = 0x19
    protected static final int REG_CONFIG        = 0x1A
    protected static final int REG_GYRO_CONFIG   = 0x1B
    protected static final int REG_ACCEL_CONFIG  = 0x1C
    protected static final int REG_ACCEL_CONFIG2 = 0x1D
    protected static final int REG_LP_ACCEL_ODR  = 0x1E
    protected static final int REG_WOM_THR       = 0x1F
    protected static final int REG_FIFO_EN       = 0x23
    protected static final int REG_INT_PIN_CFG   = 0x37
    protected static final int REG_INT_ENABLE    = 0x38
    protected static final int REG_INT_STATUS    = 0x3A
    protected static final int REG_ACCEL_XOUT_H  = 0x3B
    protected static final int REG_TEMP_OUT_H    = 0x41
    protected static final int REG_GYRO_XOUT_H   = 0x43
    protected static final int REG_MOT_DETECT_CTRL = 0x69
    protected static final int REG_USER_CTRL     = 0x6A
    protected static final int REG_PWR_MGMT_1    = 0x6B
    protected static final int REG_PWR_MGMT_2    = 0x6C
    protected static final int REG_FIFO_COUNTH   = 0x72
    protected static final int REG_FIFO_COUNTL   = 0x73
    protected static final int REG_FIFO_R_W      = 0x74
    protected static final int REG_WHO_AM_I      = 0x75

    protected static final int WHO_AM_I_VALUE = 0x73

    protected static final double[] ACCEL_SENSITIVITY = [16384.0, 8192.0, 4096.0, 2048.0]
    protected static final double[] GYRO_SENSITIVITY  = [131.0, 65.5, 32.8, 16.4]

    protected final Connection connection
    protected int accelFs = 0
    protected int gyroFs = 0

    MPU9255Minimal(Connection connection) {
        this.connection = connection
        writeReg(REG_PWR_MGMT_1, 0x80)
        Thread.sleep(100)
        writeReg(REG_PWR_MGMT_1, 0x01)
        int who = readReg(REG_WHO_AM_I)
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("MPU9255 WHO_AM_I: expected 0x" +
                    Integer.toHexString(WHO_AM_I_VALUE) + ", got 0x" + Integer.toHexString(who))
        }
        writeReg(REG_GYRO_CONFIG, 0x00)
        writeReg(REG_ACCEL_CONFIG, 0x00)
        writeReg(REG_ACCEL_CONFIG2, 0x03)
        writeReg(REG_CONFIG, 0x03)
        writeReg(REG_SMPLRT_DIV, 0x04)
        Thread.sleep(35)
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return array [x, y, z] in m/s².
     */
    double[] accel() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_ACCEL_XOUT_H}, 6)
        int ax = (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF))
        int ay = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF))
        int az = (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        double sens = ACCEL_SENSITIVITY[accelFs]
        return [ax / sens * 9.80665, ay / sens * 9.80665, az / sens * 9.80665] as double[]
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return array [x, y, z] in rad/s.
     */
    double[] gyro() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_GYRO_XOUT_H}, 6)
        int gx = (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF))
        int gy = (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF))
        int gz = (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        double sens = GYRO_SENSITIVITY[gyroFs]
        return [gx / sens * Math.PI / 180.0,
                gy / sens * Math.PI / 180.0,
                gz / sens * Math.PI / 180.0] as double[]
    }

    protected void writeReg(int reg, int val) throws IOException {
        connection.write(new byte[]{(byte) reg, (byte) val})
    }

    protected int readReg(int reg) throws IOException {
        byte[] b = connection.writeRead(new byte[]{(byte) reg}, 1)
        return b[0] & 0xFF
    }

    protected int readReg16Signed(int reg) throws IOException {
        byte[] b = connection.writeRead(new byte[]{(byte) reg}, 2)
        return (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF))
    }
}