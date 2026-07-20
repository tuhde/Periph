package it.uhde.periph.chips.imu

import it.uhde.periph.transport.Transport
import groovy.transform.CompileStatic

/**
 * MPU-6050 — full driver. Extends {@link Mpu6050Minimal} with configuration,
 * temperature, raw data access, data-ready polling, sleep/standby, and FIFO management.
 */
@CompileStatic
class Mpu6050Full extends Mpu6050Minimal {

    Mpu6050Full(Transport transport) {
        super(transport)
    }

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    void configureGyro(int fullScale = 0) {
        gyroFs = fullScale & 0x03
        writeReg(REG_GYRO_CONFIG, (fullScale & 0x03) << 3)
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    void configureAccel(int fullScale = 0) {
        accelFs = fullScale & 0x03
        writeReg(REG_ACCEL_CONFIG, (fullScale & 0x03) << 3)
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param dlpf filter setting 0–6 (0=260/256 Hz … 6=5/5 Hz).
     */
    void configureDlpf(int dlpf = 3) {
        writeReg(REG_CONFIG, dlpf & 0x07)
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255.
     */
    void configureSampleRate(int divider = 4) {
        writeReg(REG_SMPLRT_DIV, divider & 0xFF)
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
        byte[] buf = transport.writeRead([(byte) REG_ACCEL_XOUT_H] as byte[], 6)
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
        byte[] buf = transport.writeRead([(byte) REG_GYRO_XOUT_H] as byte[], 6)
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
        writeReg(REG_PWR_MGMT_1, val)
    }

    /**
     * Put individual axes into standby mode.
     */
    void setStandby(boolean xa = false, boolean ya = false, boolean za = false,
                    boolean xg = false, boolean yg = false, boolean zg = false) {
        int val = ((xa ? 1 : 0) << 5) | ((ya ? 1 : 0) << 4) | ((za ? 1 : 0) << 3) |
                  ((xg ? 1 : 0) << 2) | ((yg ? 1 : 0) << 1) | (zg ? 1 : 0)
        writeReg(REG_PWR_MGMT_2, val)
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–1024).
     */
    int fifoCount() {
        byte[] buf = transport.writeRead([(byte) REG_FIFO_COUNTH] as byte[], 2)
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
        return transport.writeRead([(byte) REG_FIFO_R_W] as byte[], count)
    }

    /**
     * Configure and enable FIFO sources.
     */
    void enableFifo(boolean gyro = true, boolean accel = true, boolean temp = false) {
        int fifoEn = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4)
        writeReg(REG_FIFO_EN, fifoEn)
        int userCtrl = readReg(REG_USER_CTRL)
        writeReg(REG_USER_CTRL, userCtrl | 0x40)
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     */
    void resetFifo() {
        int userCtrl = readReg(REG_USER_CTRL)
        writeReg(REG_USER_CTRL, userCtrl | 0x04)
    }
}
