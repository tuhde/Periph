package it.uhde.periph.chips.imu

import it.uhde.periph.connection.RegisterConnection

import groovy.transform.CompileStatic

/**
 * MPU-9250 full interface — extends MPU9250Minimal with complete functionality.
 *
 * Adds gyroscope and accelerometer full-scale configuration, DLPF settings,
 * sample rate control, temperature reading, magnetometer (AK8963) support,
 * raw data access, data-ready polling, sleep/standby control, and FIFO management.
 *
 * Default I²C address: 0x68 (AD0=GND), 0x69 (AD0=VCC).
 *
 * The AK8963 magnetometer sits behind the MPU-9250's I²C bypass (BYPASS_EN)
 * as its own device at address 0x0C, so it needs its own connection bound to
 * that address on the same bus — it cannot be reached through the connection
 * already bound to the MPU-9250's own address. Construct that second
 * connection the same way as the primary one (e.g. {@code new
 * I2CConnection(1, 0x0C)} alongside {@code new I2CConnection(1, 0x68)}) and
 * pass both in.
 */
@CompileStatic
class MPU9250Full extends MPU9250Minimal {

    private static final int AK8963_REG_WIA      = 0x00
    private static final int AK8963_REG_ST1      = 0x02
    private static final int AK8963_REG_HXL      = 0x03
    private static final int AK8963_REG_ST2      = 0x09
    private static final int AK8963_REG_CNTL1    = 0x0A
    private static final int AK8963_REG_CNTL2    = 0x0B
    private static final int AK8963_REG_ASAX     = 0x10
    private static final int AK8963_REG_ASAY     = 0x11
    private static final int AK8963_REG_ASAZ     = 0x12
    private static final int AK8963_WIA_VALUE    = 0x48

    private static final double MAG_SENSITIVITY_14BIT = 0.6
    private static final double MAG_SENSITIVITY_16BIT = 0.15

    private boolean magEnabled = false
    private int magBits = 16
    private double magScaleX = 1.0
    private double magScaleY = 1.0
    private double magScaleZ = 1.0
    private final RegisterConnection magConnection

    /**
     * @param connection RegisterConnection (I²C or SMBus) pointing at the MPU-9250.
     * @param magConnection RegisterConnection (I²C or SMBus) bound to the AK8963's address
     *                      (0x0C), on the same bus as {@code connection}.
     */
    MPU9250Full(RegisterConnection connection, RegisterConnection magConnection) throws IOException {
        super(connection)
        this.magConnection = magConnection
    }

    /**
     * Set gyroscope full-scale range.
     *
     * @param fullScale Range selector 0–3 (0=±250, 1=±500, 2=±1000, 3=±2000 dps).
     */
    void configureGyro(int fullScale = 0) throws IOException {
        gyroFs = fullScale & 0x03
        connection.write(REG_GYRO_CONFIG, [(byte) ((fullScale & 0x03) << 3)] as byte[])
    }

    /**
     * Set accelerometer full-scale range.
     *
     * @param fullScale Range selector 0–3 (0=±2g, 1=±4g, 2=±8g, 3=±16g).
     */
    void configureAccel(int fullScale = 0) throws IOException {
        accelFs = fullScale & 0x03
        connection.write(REG_ACCEL_CONFIG, [(byte) ((fullScale & 0x03) << 3)] as byte[])
    }

    /**
     * Set digital low-pass filter bandwidth.
     *
     * @param gyroDlpf   Gyro filter setting 0–7 (0=250 Hz, 1=184 Hz, 2=92 Hz, 3=41 Hz, 4=20 Hz, 5=10 Hz, 6=5 Hz, 7=3600 Hz).
     * @param accelDlpf  Accel filter setting 0–7 (0=218.1 Hz, 1=218.1 Hz, 2=99 Hz, 3=44.8 Hz, 4=21.2 Hz, 5=10.2 Hz, 6=5.05 Hz, 7=420 Hz).
     */
    void configureDlpf(int gyroDlpf = 3, int accelDlpf = 3) throws IOException {
        connection.write(REG_CONFIG, [(byte) (gyroDlpf & 0x07)] as byte[])
        connection.write(REG_ACCEL_CONFIG2, [(byte) (accelDlpf & 0x07)] as byte[])
    }

    /**
     * Set sample rate divider.
     *
     * @param divider SMPLRT_DIV value 0–255; output rate = 1 kHz / (1 + divider) when DLPF is active.
     */
    void configureSampleRate(int divider = 4) throws IOException {
        connection.write(REG_SMPLRT_DIV, [(byte) (divider & 0xFF)] as byte[])
    }

    /**
     * Read die temperature.
     *
     * @return temperature in °C.
     */
    double temperature() throws IOException {
        int raw = readReg16Signed(REG_TEMP_OUT_H)
        return raw / 333.87 + 21.0
    }

    /**
     * Initialize AK8963 magnetometer via I²C bypass mode.
     *
     * @param bits Output resolution, 14 or 16.
     * @param mode Operation mode (1=single, 2=8 Hz continuous, 6=100 Hz continuous).
     */
    void enableMag(int bits = 16, int mode = 6) throws IOException, InterruptedException {
        connection.write(REG_INT_PIN_CFG, [(byte) (0x22)] as byte[])
        Thread.sleep(10)

        magConnection.write(AK8963_REG_CNTL1, [(byte) (0x00)] as byte[])
        Thread.sleep(10)

        magConnection.write(AK8963_REG_CNTL1, [(byte) (0x0F)] as byte[])
        Thread.sleep(10)

        int asax = ak8963Read(AK8963_REG_ASAX)
        int asay = ak8963Read(AK8963_REG_ASAY)
        int asaz = ak8963Read(AK8963_REG_ASAZ)

        magScaleX = (asax - 128) / 256.0 + 1.0
        magScaleY = (asay - 128) / 256.0 + 1.0
        magScaleZ = (asaz - 128) / 256.0 + 1.0

        magConnection.write(AK8963_REG_CNTL1, [(byte) (0x00)] as byte[])
        Thread.sleep(10)

        int cntl1Val = 0
        if (bits == 16) {
            cntl1Val |= 0x10
        }
        cntl1Val |= (mode & 0x0F)
        magConnection.write(AK8963_REG_CNTL1, [(byte) (cntl1Val)] as byte[])
        Thread.sleep(10)

        magEnabled = true
        magBits = bits
    }

    /**
     * Read 3-axis magnetic field.
     *
     * @return array [x, y, z] in µT.
     * @throws IllegalStateException if magnetometer has not been enabled via enableMag().
     */
    double[] mag() throws IOException {
        if (!magEnabled) {
            throw new IllegalStateException("Magnetometer not enabled. Call enableMag() first.")
        }
        byte[] buf = magConnection.read(AK8963_REG_HXL, 7)
        int mx = (short) ((buf[1] & 0xFF) << 8 | (buf[0] & 0xFF))
        int my = (short) ((buf[3] & 0xFF) << 8 | (buf[2] & 0xFF))
        int mz = (short) ((buf[5] & 0xFF) << 8 | (buf[4] & 0xFF))
        // ST2 at buf[6] must be read to unlock next measurement

        double sens = (magBits == 16) ? MAG_SENSITIVITY_16BIT : MAG_SENSITIVITY_14BIT
        return [mx * sens * magScaleX,
                my * sens * magScaleY,
                mz * sens * magScaleZ] as double[]
    }

    /**
     * Read raw 3-axis accelerometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     */
    int[] accelRaw() throws IOException {
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
    int[] gyroRaw() throws IOException {
        byte[] buf = connection.read(REG_GYRO_XOUT_H, 6)
        return [
            (short) (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)),
            (short) (((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF)),
            (short) (((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF))
        ] as int[]
    }

    /**
     * Read raw 3-axis magnetometer values.
     *
     * @return array [x, y, z] as raw 16-bit signed values.
     * @throws IllegalStateException if magnetometer has not been enabled via enableMag().
     */
    int[] magRaw() throws IOException {
        if (!magEnabled) {
            throw new IllegalStateException("Magnetometer not enabled. Call enableMag() first.")
        }
        // ST2 (buf[6]) is not used but must be read to unlock the next measurement.
        byte[] buf = magConnection.read(AK8963_REG_HXL, 7)
        return [
            (short) ((buf[1] & 0xFF) << 8 | (buf[0] & 0xFF)),
            (short) ((buf[3] & 0xFF) << 8 | (buf[2] & 0xFF)),
            (short) ((buf[5] & 0xFF) << 8 | (buf[4] & 0xFF))
        ] as int[]
    }

    /**
     * Check if new sensor data is available.
     *
     * @return true when RAW_DATA_RDY_INT is set in INT_STATUS.
     */
    boolean dataReady() throws IOException {
        return (readReg(REG_INT_STATUS) & 0x01) != 0
    }

    /**
     * Set or clear the SLEEP bit in PWR_MGMT_1.
     *
     * @param sleep true to enter sleep mode, false to wake.
     */
    void setSleep(boolean sleep = true) throws IOException {
        int val = readReg(REG_PWR_MGMT_1)
        if (sleep) {
            val |= 0x40
        } else {
            val &= ~0x40
        }
        connection.write(REG_PWR_MGMT_1, [(byte) (val)] as byte[])
    }

    /**
     * Read the number of bytes in the FIFO buffer.
     *
     * @return FIFO byte count (0–512).
     */
    int fifoCount() throws IOException {
        byte[] buf = connection.read(REG_FIFO_COUNTH, 2)
        return ((buf[0] & 0x1F) << 8) | (buf[1] & 0xFF)
    }

    /**
     * Read all available data from the FIFO buffer.
     *
     * @return FIFO data bytes.
     */
    byte[] readFifo() throws IOException {
        int count = fifoCount()
        if (count == 0) return new byte[0]
        return connection.read(REG_FIFO_R_W, count)
    }

    /**
     * Configure and enable FIFO sources.
     *
     * @param gyro  Enable gyroscope data in FIFO.
     * @param accel Enable accelerometer data in FIFO.
     * @param temp  Enable temperature data in FIFO.
     */
    void enableFifo(boolean gyro = true, boolean accel = true, boolean temp = false) throws IOException {
        int fifoEn = ((accel ? 1 : 0) << 3) | ((temp ? 1 : 0) << 2) | ((gyro ? 1 : 0) << 4)
        connection.write(REG_FIFO_EN, [(byte) (fifoEn)] as byte[])
        int userCtrl = readReg(REG_USER_CTRL)
        connection.write(REG_USER_CTRL, [(byte) (userCtrl | 0x40)] as byte[])
    }

    /**
     * Reset the FIFO buffer by setting FIFO_RST in USER_CTRL.
     */
    void resetFifo() throws IOException {
        int userCtrl = readReg(REG_USER_CTRL)
        connection.write(REG_USER_CTRL, [(byte) (userCtrl | 0x04)] as byte[])
    }

    private int ak8963Read(int reg) throws IOException {
        byte[] b = magConnection.read(reg, 1)
        return b[0] & 0xFF
    }

}