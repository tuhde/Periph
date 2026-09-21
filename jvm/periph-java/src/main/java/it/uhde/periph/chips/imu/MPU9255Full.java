package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MPU-9255 full interface — extends MPU9255Minimal with complete functionality.
 *
 * <p>Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, magnetometer (AK8963) support,
 * wake-on-motion, raw data access, data-ready polling, sleep/standby control,
 * and FIFO management.
 *
 * <p>Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 *
 * <p>The AK8963 magnetometer sits behind the MPU-9255's I²C bypass (BYPASS_EN)
 * as its own device at address 0x0C, so it needs its own connection bound to
 * that address on the same bus — it cannot be reached through the connection
 * already bound to the MPU-9255's own address. Construct that second
 * connection the same way as the primary one (e.g. {@code new
 * I2CConnection(1, 0x0C)} alongside {@code new I2CConnection(1, 0x68)}) and
 * pass both in.
 */
public class MPU9255Full extends MPU9255Minimal {

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

    private static final double[] LPOSC_TABLE = {
        0.24, 0.49, 0.98, 1.95, 3.91, 7.81, 15.63, 31.25,
        62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0,
    };

    private final Connection magConnection;
    private boolean magEnabled = false;
    private int magBits = 16;
    private double magScaleX = 1.0;
    private double magScaleY = 1.0;
    private double magScaleZ = 1.0;

    /**
     * @param connection Configured I²C or SPI connection pointing at the MPU-9255.
     * @param magConnection Configured I²C connection bound to the AK8963's address
     *                      (0x0C), on the same bus as {@code connection}.
     */
    public MPU9255Full(Connection connection, Connection magConnection) throws IOException {
        super(connection);
        this.magConnection = magConnection;
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
        // ST2 (buf[6]) is not used but must be read to unlock the next measurement.
        byte[] buf = ak8963ReadBurst(AK8963_REG_HXL, 7);
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

    /**
     * Configure wake-on-motion and enter accelerometer-only low-power mode.
     *
     * @param thresholdMg Motion threshold in milligrams (4–1020 mg, quantized to 4 mg steps).
     * @param odrHz       Wake-up output data rate in Hz (0.24–500 Hz).
     * @throws IOException on I²C error.
     */
    public void configureWakeOnMotion(int thresholdMg, float odrHz) throws IOException {
        int thresholdLsb = (thresholdMg + 2) / 4;
        if (thresholdLsb < 1) thresholdLsb = 1;
        if (thresholdLsb > 255) thresholdLsb = 255;

        int bestSel = 0;
        double bestDiff = Math.abs(odrHz - LPOSC_TABLE[0]);
        for (int sel = 1; sel < LPOSC_TABLE.length; sel++) {
            double diff = Math.abs(odrHz - LPOSC_TABLE[sel]);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSel = sel;
            }
        }

        writeReg(REG_PWR_MGMT_1, 0x01);
        writeReg(REG_PWR_MGMT_2, 0x07);
        writeReg(REG_ACCEL_CONFIG2, 0x09);
        writeReg(REG_INT_ENABLE, 0x40);
        writeReg(REG_MOT_DETECT_CTRL, 0xC0);
        writeReg(REG_WOM_THR, thresholdLsb & 0xFF);
        writeReg(REG_LP_ACCEL_ODR, bestSel & 0x0F);
        writeReg(REG_PWR_MGMT_1, 0x21);
    }

    /**
     * Check if a wake-on-motion interrupt has fired.
     *
     * @return true when WOM_INT (bit 6) is set in INT_STATUS. Reading
     *         INT_STATUS clears the interrupt.
     * @throws IOException on I²C error.
     */
    public boolean motionDetected() throws IOException {
        return (readReg(REG_INT_STATUS) & 0x40) != 0;
    }

    private void ak8963Write(int reg, int val) throws IOException {
        magConnection.write(new byte[]{(byte) reg, (byte) val});
    }

    private int ak8963Read(int reg) throws IOException {
        byte[] b = magConnection.writeRead(new byte[]{(byte) reg}, 1);
        return b[0] & 0xFF;
    }

    private byte[] ak8963ReadBurst(int reg, int len) throws IOException {
        return magConnection.writeRead(new byte[]{(byte) reg}, len);
    }
}