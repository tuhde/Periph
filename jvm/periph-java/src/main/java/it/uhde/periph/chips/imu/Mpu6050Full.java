package it.uhde.periph.chips.imu;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * MPU-6050 — full driver. Extends {@link Mpu6050Minimal} with configuration,
 * temperature, raw data access, data-ready polling, sleep/standby, and FIFO management.
 */
public class Mpu6050Full extends Mpu6050Minimal {

    public Mpu6050Full(Transport transport) throws IOException {
        super(transport);
    }

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     * @throws IOException on I²C error.
     */
    public void configureGyro(int fullScale) throws IOException {
        gyroFs = fullScale & 0x03;
        writeReg(REG_GYRO_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     * @throws IOException on I²C error.
     */
    public void configureAccel(int fullScale) throws IOException {
        accelFs = fullScale & 0x03;
        writeReg(REG_ACCEL_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param dlpf filter setting 0–6 (0=260/256 Hz … 6=5/5 Hz).
     * @throws IOException on I²C error.
     */
    public void configureDlpf(int dlpf) throws IOException {
        writeReg(REG_CONFIG, dlpf & 0x07);
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255.
     * @throws IOException on I²C error.
     */
    public void configureSampleRate(int divider) throws IOException {
        writeReg(REG_SMPLRT_DIV, divider & 0xFF);
    }

    /**
     * Read die temperature.
     *
     * @return temperature in °C.
     * @throws IOException on I²C error.
     */
    public double temperature() throws IOException {
        int raw = readReg16Signed(REG_TEMP_OUT_H);
        return raw / 340.0 + 36.53;
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     * @throws IOException on I²C error.
     */
    public int[] accelRaw() throws IOException {
        byte[] buf = transport.writeRead(new byte[]{(byte) REG_ACCEL_XOUT_H}, 6);
        return new int[]{
                (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)),
                (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF)),
                (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        };
    }

    /**
     * Read raw 3-axis gyroscope values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     * @throws IOException on I²C error.
     */
    public int[] gyroRaw() throws IOException {
        byte[] buf = transport.writeRead(new byte[]{(byte) REG_GYRO_XOUT_H}, 6);
        return new int[]{
                (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)),
                (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF)),
                (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        };
    }

    /**
     * Check if new sensor data is available.
     *
     * @return true when DATA_RDY_INT is set in INT_STATUS.
     * @throws IOException on I²C error.
     */
    public boolean dataReady() throws IOException {
        return (readReg(REG_INT_STATUS) & 0x01) != 0;
    }

    /**
     * Set or clear the SLEEP bit in PWR_MGMT_1.
     *
     * @param sleep true to enter sleep mode, false to wake.
     * @throws IOException on I²C error.
     */
    public void setSleep(boolean sleep) throws IOException {
        int val = readReg(REG_PWR_MGMT_1);
        if (sleep) {
            val |= 0x40;
        } else {
            val &= ~0x40;
        }
        writeReg(REG_PWR_MGMT_1, val);
    }

    /**
     * Put individual axes into standby mode.
     *
     * @param xa X accelerometer standby.
     * @param ya Y accelerometer standby.
     * @param za Z accelerometer standby.
     * @param xg X gyroscope standby.
     * @param yg Y gyroscope standby.
     * @param zg Z gyroscope standby.
     * @throws IOException on I²C error.
     */
    public void setStandby(boolean xa, boolean ya, boolean za,
                           boolean xg, boolean yg, boolean zg) throws IOException {
        int val = ((xa ? 1 : 0) << 5) | ((ya ? 1 : 0) << 4) | ((za ? 1 : 0) << 3) |
                  ((xg ? 1 : 0) << 2) | ((yg ? 1 : 0) << 1) | (zg ? 1 : 0);
        writeReg(REG_PWR_MGMT_2, val);
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–1024).
     * @throws IOException on I²C error.
     */
    public int fifoCount() throws IOException {
        byte[] buf = transport.writeRead(new byte[]{(byte) REG_FIFO_COUNTH}, 2);
        return ((buf[0] & 0x1F) << 8) | (buf[1] & 0xFF);
    }

    /**
     * Read all available data from the FIFO buffer.
     *
     * @return FIFO data as byte array.
     * @throws IOException on I²C error.
     */
    public byte[] readFifo() throws IOException {
        int count = fifoCount();
        if (count == 0) return new byte[0];
        return transport.writeRead(new byte[]{(byte) REG_FIFO_R_W}, count);
    }

    /**
     * Configure and enable FIFO sources.
     *
     * @param gyro  enable gyroscope data in FIFO.
     * @param accel enable accelerometer data in FIFO.
     * @param temp  enable temperature data in FIFO.
     * @throws IOException on I²C error.
     */
    public void enableFifo(boolean gyro, boolean accel, boolean temp) throws IOException {
        int fifoEn = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4);
        writeReg(REG_FIFO_EN, fifoEn);
        int userCtrl = readReg(REG_USER_CTRL);
        writeReg(REG_USER_CTRL, userCtrl | 0x40);
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     *
     * @throws IOException on I²C error.
     */
    public void resetFifo() throws IOException {
        int userCtrl = readReg(REG_USER_CTRL);
        writeReg(REG_USER_CTRL, userCtrl | 0x04);
    }
}
