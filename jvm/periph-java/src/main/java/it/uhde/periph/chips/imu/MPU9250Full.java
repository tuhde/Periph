package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MPU-9250 full interface — extends MPU9250Minimal with complete functionality.
 *
 * <p>Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, magnetometer (AK8963) support,
 * raw data access, data-ready polling, sleep/standby control, and FIFO management.
 *
 * <p>Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 */
public class MPU9250Full extends MPU9250Minimal {

    private static final int AK8963_ADDR = 0x0C;

    private static final int AK8963_REG_WIA      = 0x00;
    private static final int AK8963_REG_ST1      = 0x02;
    private static final int AK8963_REG_HXL      = 0x03;
    private static final int AK8963_REG_ST2      = 0x09;
    private static final int AK8963_REG_CNTL1    = 0x0A;
    private static final int AK8963_REG_CNTL2    = 0x0B;
    private static final int AK8963_REG_ASAX     = 0x10;
    private static final int AK8963_REG_ASAY     = 0x11;
    private static final int AK8963_REG_ASAZ     = 0x12;
    private static final int AK8963_WIA_VALUE    = 0x48;

    private static final double MAG_SENSITIVITY_14BIT = 0.6;
    private static final double MAG_SENSITIVITY_16BIT = 0.15;

    private boolean magEnabled = false;
    private int magBits = 16;
    private double magScaleX = 1.0;
    private double magScaleY = 1.0;
    private double magScaleZ = 1.0;

    public MPU9250Full(Connection connection) throws IOException {
        super(connection);
    }

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     * @throws IOException on I²C error.
     */
    public void configureGyro(int fullScale) throws IOException {
        gyroFs = fullScale & 0x03;
        writeReg(REG_GYRO_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     * @throws IOException on I²C error.
     */
    public void configureAccel(int fullScale) throws IOException {
        accelFs = fullScale & 0x03;
        writeReg(REG_ACCEL_CONFIG, (fullScale & 0x03) << 3);
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param gyroDlpf   Gyro filter setting 0–6 (0=256 Hz, 1=188 Hz, 2=98 Hz, 3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz).
     * @param accelDlpf  Accel filter setting 0–6 (0=460 Hz, 1=184 Hz, 2=92 Hz, 3=42 Hz, 5=20 Hz, 6=10 Hz). Note: 4 is not valid for accel.
     * @throws IOException on I²C error.
     */
    public void configureDlpf(int gyroDlpf, int accelDlpf) throws IOException {
        writeReg(REG_CONFIG, gyroDlpf & 0x07);
        writeReg(REG_ACCEL_CONFIG2, accelDlpf & 0x07);
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider) when DLPF is active.
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
        return raw / 333.87 + 21.0;
    }

    /**
     * Initialize AK8963 magnetometer via I²C bypass mode.
     *
     * @param bits Output resolution, 14 or 16.
     * @param mode Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
     * @throws IOException on I²C error.
     * @throws InterruptedException if interrupted during sleep.
     */
    public void enableMag(int bits, int mode) throws IOException, InterruptedException {
        writeReg(REG_INT_PIN_CFG, 0x22);
        Thread.sleep(10);

        ak8963Write(AK8963_REG_CNTL1, 0x00);
        Thread.sleep(10);

        ak8963Write(AK8963_REG_CNTL1, 0x0F);
        Thread.sleep(10);

        int asax = ak8963Read(AK8963_REG_ASAX);
        int asay = ak8963Read(AK8963_REG_ASAY);
        int asaz = ak8963Read(AK8963_REG_ASAZ);

        magScaleX = (asax - 128) / 256.0 + 1.0;
        magScaleY = (asay - 128) / 256.0 + 1.0;
        magScaleZ = (asaz - 128) / 256.0 + 1.0;

        ak8963Write(AK8963_REG_CNTL1, 0x00);
        Thread.sleep(10);

        int cntl1Val = 0;
        if (bits == 16) {
            cntl1Val |= 0x10;
        }
        cntl1Val |= (mode & 0x0F);
        ak8963Write(AK8963_REG_CNTL1, cntl1Val);
        Thread.sleep(10);

        magEnabled = true;
        magBits = bits;
    }

    /**
     * Read 3-axis magnetic field.
     *
     * @return array [x, y, z] in µT.
     * @throws IOException on I²C error.
     * @throws IllegalStateException if magnetometer has not been enabled via enableMag().
     */
    public double[] mag() throws IOException {
        if (!magEnabled) {
            throw new IllegalStateException("Magnetometer not enabled. Call enableMag() first.");
        }
        byte[] buf = ak8963ReadBurst(AK8963_REG_HXL, 7);
        int mx = (short) ((buf[1] & 0xFF) << 8 | (buf[0] & 0xFF));
        int my = (short) ((buf[3] & 0xFF) << 8 | (buf[2] & 0xFF));
        int mz = (short) ((buf[5] & 0xFF) << 8 | (buf[4] & 0xFF));
        // ST2 at buf[6] must be read to unlock next measurement

        double sens = (magBits == 16) ? MAG_SENSITIVITY_16BIT : MAG_SENSITIVITY_14BIT;
        return new double[]{mx * sens * magScaleX,
                            my * sens * magScaleY,
                            mz * sens * magScaleZ};
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     * @throws IOException on I²C error.
     */
    public int[] accelRaw() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_ACCEL_XOUT_H}, 6);
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
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_GYRO_XOUT_H}, 6);
        return new int[]{
            (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)),
            (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF)),
            (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        };
    }

    /**
     * Read raw 3-axis magnetometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     * @throws IOException on I²C error.
     * @throws IllegalStateException if magnetometer has not been enabled via enableMag().
     */
    public int[] magRaw() throws IOException {
        if (!magEnabled) {
            throw new IllegalStateException("Magnetometer not enabled. Call enableMag() first.");
        }
        byte[] buf = ak8963ReadBurst(AK8963_REG_HXL, 6);
        return new int[]{
            (short) ((buf[1] & 0xFF) << 8 | (buf[0] & 0xFF)),
            (short) ((buf[3] & 0xFF) << 8 | (buf[2] & 0xFF)),
            (short) ((buf[5] & 0xFF) << 8 | (buf[4] & 0xFF))
        };
    }

    /**
     * Check if new sensor data is available.
     *
     * @return true when RAW_DATA_RDY_INT is set in INT_STATUS.
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
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–512).
     * @throws IOException on I²C error.
     */
    public int fifoCount() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_FIFO_COUNTH}, 2);
        return ((buf[0] & 0x1F) << 8) | (buf[1] & 0xFF);
    }

    /**
     * Read all available data from the FIFO buffer.
     *
     * @return FIFO data bytes.
     * @throws IOException on I²C error.
     */
    public byte[] readFifo() throws IOException {
        int count = fifoCount();
        if (count == 0) return new byte[0];
        return connection.writeRead(new byte[]{(byte) REG_FIFO_R_W}, count);
    }

    /**
     * Configure and enable FIFO sources.
     *
     * @param gyro  Enable gyroscope data in FIFO.
     * @param accel Enable accelerometer data in FIFO.
     * @param temp  Enable temperature data in FIFO.
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

    private void ak8963Write(int reg, int val) throws IOException {
        connection.write(new byte[]{(byte) (AK8963_ADDR << 1), (byte) reg, (byte) val});
    }

    private int ak8963Read(int reg) throws IOException {
        byte[] b = connection.writeRead(new byte[]{(byte) ((AK8963_ADDR << 1) | 1), (byte) reg}, 1);
        return b[0] & 0xFF;
    }

    private byte[] ak8963ReadBurst(int reg, int len) throws IOException {
        return connection.writeRead(new byte[]{(byte) ((AK8963_ADDR << 1) | 1), (byte) reg}, len);
    }
}