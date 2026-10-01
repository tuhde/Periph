package it.uhde.periph.chips.imu

import it.uhde.periph.connection.RegisterConnection
import groovy.transform.CompileStatic

/**
 * MPU-6050 — full driver. Extends {@link MPU6050Minimal} with configuration,
 * temperature, raw data access, data-ready polling, sleep/standby, and FIFO management.
 */
@CompileStatic
class MPU6050Full extends MPU6050Minimal {

    MPU6050Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    void configureGyro(int fullScale = 0) {
        gyroFs = fullScale & 0x03
        connection.write(REG_GYRO_CONFIG, [(byte) ((fullScale & 0x03) << 3)] as byte[])
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    void configureAccel(int fullScale = 0) {
        accelFs = fullScale & 0x03
        connection.write(REG_ACCEL_CONFIG, [(byte) ((fullScale & 0x03) << 3)] as byte[])
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param dlpf filter setting 0–6 (0=260/256 Hz … 6=5/5 Hz).
     */
    void configureDlpf(int dlpf = 3) {
        connection.write(REG_CONFIG, [(byte) (dlpf & 0x07)] as byte[])
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255.
     */
    void configureSampleRate(int divider = 4) {
        connection.write(REG_SMPLRT_DIV, [(byte) (divider & 0xFF)] as byte[])
    }

    /**
     * Read die temperature.
     *
     * @return temperature in °C.
     */
    double temperature() {
        int raw = readReg16Signed(REG_TEMP_OUT_H)
        return raw / 340.0d + 36.53d
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     */
    int[] accelRaw() {
        byte[] buf = connection.read(REG_ACCEL_XOUT_H, 6)
        return [
            (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)),
            (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF)),
            (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        ] as int[]
    }

    /**
     * Read raw 3-axis gyroscope values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     */
    int[] gyroRaw() {
        byte[] buf = connection.read(REG_GYRO_XOUT_H, 6)
        return [
            (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)),
            (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF)),
            (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        ] as int[]
    }

    /**
     * Check if new sensor data is available.
     *
     * @return true when DATA_RDY_INT is set in INT_STATUS.
     */
    boolean dataReady() {
        return (readReg(REG_INT_STATUS) & 0x01) != 0
    }

    /**
     * Set or clear the SLEEP bit in PWR_MGMT_1.
     *
     * @param sleep true to enter sleep mode, false to wake.
     */
    void setSleep(boolean sleep = true) {
        int val = readReg(REG_PWR_MGMT_1)
        if (sleep) {
            val |= 0x40
        } else {
            val &= ~0x40
        }
        connection.write(REG_PWR_MGMT_1, [(byte) (val)] as byte[])
    }

    /**
     * Put individual axes into standby mode.
     */
    void setStandby(boolean xa = false, boolean ya = false, boolean za = false,
                    boolean xg = false, boolean yg = false, boolean zg = false) {
        int val = ((xa ? 1 : 0) << 5) | ((ya ? 1 : 0) << 4) | ((za ? 1 : 0) << 3) |
                  ((xg ? 1 : 0) << 2) | ((yg ? 1 : 0) << 1) | (zg ? 1 : 0)
        connection.write(REG_PWR_MGMT_2, [(byte) (val)] as byte[])
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–1024).
     */
    int fifoCount() {
        byte[] buf = connection.read(REG_FIFO_COUNTH, 2)
        return ((buf[0] & 0x1F) << 8) | (buf[1] & 0xFF)
    }

    /**
     * Read all available data from the FIFO buffer.
     *
     * @return FIFO data as byte array.
     */
    byte[] readFifo() {
        int count = fifoCount()
        if (count == 0) return new byte[0]
        return connection.read(REG_FIFO_R_W, count)
    }

    /**
     * Configure and enable FIFO sources.
     */
    void enableFifo(boolean gyro = true, boolean accel = true, boolean temp = false) {
        int fifoEn = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4)
        connection.write(REG_FIFO_EN, [(byte) (fifoEn)] as byte[])
        int userCtrl = readReg(REG_USER_CTRL)
        connection.write(REG_USER_CTRL, [(byte) (userCtrl | 0x40)] as byte[])
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     */
    void resetFifo() {
        int userCtrl = readReg(REG_USER_CTRL)
        connection.write(REG_USER_CTRL, [(byte) (userCtrl | 0x04)] as byte[])
    }
}
