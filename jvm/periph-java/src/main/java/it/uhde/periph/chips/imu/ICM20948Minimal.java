package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * ICM-20948 — 9-axis MotionTracking device (TDK InvenSense), minimal driver.
 *
 * <p>Provides 3-axis acceleration and 3-axis angular rate with no configuration
 * beyond the connection. The registers are organised in four user banks selected
 * through {@code REG_BANK_SEL} (0x7F); the driver caches the selected bank and
 * always returns to bank 0 after a bank 1–3 access. VDDIO is 1.71–1.95 V only.
 * SPI and the DMP are out of scope. The magnetometer is part of {@link ICM20948Full}.
 *
 * <p>I²C address: 0x68 (AD0=GND), 0x69 (AD0=VDDIO).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Gyroscope ±250 dps, low-pass filter 51.2 Hz (GYRO_DLPFCFG=3)</li>
 *   <li>Accelerometer ±2 g, low-pass filter 50.4 Hz (ACCEL_DLPFCFG=3)</li>
 *   <li>Both output data rates 102.3 Hz (divider 10)</li>
 *   <li>Clock auto (CLKSEL=1), all six axes and the temperature sensor on</li>
 *   <li>FIFO, DMP, I²C master and all interrupts off</li>
 * </ul>
 */
public class ICM20948Minimal {

    // Bank 0
    protected static final int REG_WHO_AM_I         = 0x00;
    protected static final int REG_USER_CTRL        = 0x03;
    protected static final int REG_LP_CONFIG        = 0x05;
    protected static final int REG_PWR_MGMT_1       = 0x06;
    protected static final int REG_PWR_MGMT_2       = 0x07;
    protected static final int REG_INT_PIN_CFG      = 0x0F;
    protected static final int REG_INT_ENABLE       = 0x10;
    protected static final int REG_INT_ENABLE_1     = 0x11;
    protected static final int REG_INT_ENABLE_2     = 0x12;
    protected static final int REG_I2C_MST_STATUS   = 0x17;
    protected static final int REG_INT_STATUS       = 0x19;
    protected static final int REG_INT_STATUS_1     = 0x1A;
    protected static final int REG_ACCEL_XOUT_H     = 0x2D;
    protected static final int REG_GYRO_XOUT_H      = 0x33;
    protected static final int REG_TEMP_OUT_H       = 0x39;
    protected static final int REG_EXT_SLV_SENS_DATA_00 = 0x3B;
    protected static final int REG_FIFO_EN_2        = 0x67;
    protected static final int REG_FIFO_RST         = 0x68;
    protected static final int REG_FIFO_MODE        = 0x69;
    protected static final int REG_FIFO_COUNTH      = 0x70;
    protected static final int REG_FIFO_R_W         = 0x72;
    protected static final int REG_BANK_SEL         = 0x7F;

    // Bank 1
    protected static final int REG_XA_OFFS_H        = 0x14;
    protected static final int REG_TIMEBASE_CORRECTION_PLL = 0x28;

    // Bank 2
    protected static final int REG_GYRO_SMPLRT_DIV    = 0x00;
    protected static final int REG_GYRO_CONFIG_1      = 0x01;
    protected static final int REG_GYRO_CONFIG_2      = 0x02;
    protected static final int REG_XG_OFFS_USRH       = 0x03;
    protected static final int REG_ACCEL_SMPLRT_DIV_1 = 0x10;
    protected static final int REG_ACCEL_SMPLRT_DIV_2 = 0x11;
    protected static final int REG_ACCEL_INTEL_CTRL   = 0x12;
    protected static final int REG_ACCEL_WOM_THR      = 0x13;
    protected static final int REG_ACCEL_CONFIG       = 0x14;
    protected static final int REG_ACCEL_CONFIG_2     = 0x15;

    // Bank 3
    protected static final int REG_I2C_MST_CTRL  = 0x01;
    protected static final int REG_I2C_SLV0_ADDR = 0x03;
    protected static final int REG_I2C_SLV0_REG  = 0x04;
    protected static final int REG_I2C_SLV0_CTRL = 0x05;
    protected static final int REG_I2C_SLV4_ADDR = 0x13;
    protected static final int REG_I2C_SLV4_REG  = 0x14;
    protected static final int REG_I2C_SLV4_CTRL = 0x15;
    protected static final int REG_I2C_SLV4_DO   = 0x16;
    protected static final int REG_I2C_SLV4_DI   = 0x17;

    protected static final int WHO_AM_I_VALUE = 0xEA;

    protected static final double[] ACCEL_SENSITIVITY = {16384.0, 8192.0, 4096.0, 2048.0};
    protected static final double[] GYRO_SENSITIVITY  = {131.0, 65.5, 32.8, 16.4};
    protected static final double G = 9.80665;
    protected static final double DEG_TO_RAD = Math.PI / 180.0;

    protected final RegisterConnection connection;
    protected int bank = 0;
    protected int accelFs = 0;
    protected int gyroFs = 0;
    protected int accelDlpf = 3;
    protected int gyroDlpf = 3;
    protected boolean accelFilter = true;
    protected boolean gyroFilter = true;
    protected boolean lpEn = false;
    protected boolean lpSuspended = false;

    /**
     * Verify {@code WHO_AM_I}, reset the device and write the default configuration.
     *
     * @param connection register connection bound to the device (0x68 or 0x69)
     * @throws IOException on bus error or if {@code WHO_AM_I} is not 0xEA
     */
    public ICM20948Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        selectBank(0, true);
        int who = rd1(0, REG_WHO_AM_I);
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("ICM20948 WHO_AM_I: expected 0x" +
                    Integer.toHexString(WHO_AM_I_VALUE) + ", got 0x" + Integer.toHexString(who));
        }
        wr1(0, REG_PWR_MGMT_1, 0x80);      // DEVICE_RESET
        sleepMs(100);
        bank = 0;                          // reset returns to bank 0
        wr1(0, REG_PWR_MGMT_1, 0x01);      // SLEEP = 0, CLKSEL = 1
        wr1(0, REG_PWR_MGMT_2, 0x00);      // all axes on
        writeGyroConfig();
        writeAccelConfig();
        wr1(2, REG_GYRO_SMPLRT_DIV, 0x0A);
        wr1(2, REG_ACCEL_SMPLRT_DIV_1, 0x00);
        wr1(2, REG_ACCEL_SMPLRT_DIV_2, 0x0A);
        sleepMs(35);
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void selectBank(int newBank, boolean force) throws IOException {
        if (force || newBank != bank) {
            connection.write(REG_BANK_SEL, new byte[]{(byte) (newBank << 4)});
            bank = newBank;
        }
    }

    protected byte[] rd(int b, int reg, int length) throws IOException {
        selectBank(b, false);
        byte[] data = connection.read(reg, length);
        if (b != 0) selectBank(0, false);
        return data;
    }

    protected void wr(int b, int reg, byte[] data) throws IOException {
        selectBank(b, false);
        connection.write(reg, data);
        if (b != 0) selectBank(0, false);
    }

    protected int rd1(int b, int reg) throws IOException {
        return rd(b, reg, 1)[0] & 0xFF;
    }

    protected void wr1(int b, int reg, int value) throws IOException {
        wr(b, reg, new byte[]{(byte) value});
    }

    protected void writeGyroConfig() throws IOException {
        wr1(2, REG_GYRO_CONFIG_1, (gyroDlpf << 3) | (gyroFs << 1) | (gyroFilter ? 1 : 0));
    }

    protected void writeAccelConfig() throws IOException {
        wr1(2, REG_ACCEL_CONFIG, (accelDlpf << 3) | (accelFs << 1) | (accelFilter ? 1 : 0));
    }

    /** Signed big-endian 16-bit value at {@code offset}. */
    protected static int be16(byte[] b, int offset) {
        return (short) (((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF));
    }

    /** Signed little-endian 16-bit value at {@code offset}. */
    protected static int le16(byte[] b, int offset) {
        return (short) (((b[offset + 1] & 0xFF) << 8) | (b[offset] & 0xFF));
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return {x, y, z} acceleration in m/s²
     * @throws IOException on bus error
     */
    public double[] accel() throws IOException {
        byte[] b = rd(0, REG_ACCEL_XOUT_H, 6);
        double sens = ACCEL_SENSITIVITY[accelFs];
        return new double[]{be16(b, 0) / sens * G, be16(b, 2) / sens * G, be16(b, 4) / sens * G};
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return {x, y, z} angular rate in rad/s
     * @throws IOException on bus error
     */
    public double[] gyro() throws IOException {
        byte[] b = rd(0, REG_GYRO_XOUT_H, 6);
        double sens = GYRO_SENSITIVITY[gyroFs];
        return new double[]{be16(b, 0) / sens * DEG_TO_RAD, be16(b, 2) / sens * DEG_TO_RAD,
                be16(b, 4) / sens * DEG_TO_RAD};
    }
}
