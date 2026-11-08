package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * QMI8658A — 6-axis inertial measurement unit (QST), minimal driver.
 *
 * <p>Provides 3-axis acceleration and 3-axis angular rate with no configuration beyond the
 * connection. The constructor runs the datasheet initialisation sequence: soft reset,
 * {@code WHO_AM_I} check, address auto-increment on, little-endian reads, CTRL9 handshake by
 * polling, both sensors on. I3C and 3-wire SPI are out of scope.
 *
 * <p>I²C address: 0x6B (SA0=GND), 0x6A (SA0=VDDIO); SPI uses read bit 0x80 and no multi-byte bit.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Accelerometer ±2 g, gyroscope ±256 dps</li>
 *   <li>Both output data rates code 5 (224.2 Hz in 6DOF mode)</li>
 *   <li>Both low-pass filters on, mode 3 (13.37 % of ODR)</li>
 *   <li>FIFO bypass (data-ready active), all INT pins high-Z, engines off</li>
 * </ul>
 */
public class QMI8658AMinimal {

    protected static final int REG_WHO_AM_I      = 0x00;
    protected static final int REG_REVISION_ID   = 0x01;
    protected static final int REG_CTRL1         = 0x02;
    protected static final int REG_CTRL2         = 0x03;
    protected static final int REG_CTRL3         = 0x04;
    protected static final int REG_CTRL5         = 0x06;
    protected static final int REG_CTRL7         = 0x08;
    protected static final int REG_CTRL8         = 0x09;
    protected static final int REG_CTRL9         = 0x0A;
    protected static final int REG_CAL1_L        = 0x0B;
    protected static final int REG_FIFO_WTM_TH   = 0x13;
    protected static final int REG_FIFO_CTRL     = 0x14;
    protected static final int REG_FIFO_SMPL_CNT = 0x15;
    protected static final int REG_FIFO_STATUS   = 0x16;
    protected static final int REG_FIFO_DATA     = 0x17;
    protected static final int REG_STATUSINT     = 0x2D;
    protected static final int REG_STATUS0       = 0x2E;
    protected static final int REG_TIMESTAMP     = 0x30;
    protected static final int REG_TEMP_L        = 0x33;
    protected static final int REG_AX_L          = 0x35;
    protected static final int REG_GX_L          = 0x3B;
    protected static final int REG_COD_STATUS    = 0x46;
    protected static final int REG_FW_VERSION    = 0x49;
    protected static final int REG_RESET_STATUS  = 0x4D;
    protected static final int REG_DVX_L         = 0x51;
    protected static final int REG_TAP_STATUS    = 0x59;
    protected static final int REG_STEP_CNT      = 0x5A;
    protected static final int REG_RESET         = 0x60;

    protected static final int WHO_AM_I_VALUE = 0x05;
    protected static final int RESET_CMD = 0xB0;

    protected static final double G = 9.80665;
    protected static final double DEG_TO_RAD = Math.PI / 180.0;

    protected final RegisterConnection connection;
    protected int ctrl1 = 0x40;
    protected int ctrl7 = 0x03;
    protected int ctrl8 = 0x80;
    protected int accelFs = 0;
    protected int gyroFs = 4;
    protected int accelOdr = 5;
    protected int gyroOdr = 5;
    protected double accelSens = 16384.0;
    protected double gyroSens = 128.0;
    protected int fifoCtrl = 0;
    protected int fifoWtm = 0;
    protected boolean sync = false;

    /**
     * Reset the device, verify {@code WHO_AM_I} and write the default configuration.
     *
     * @param connection register connection bound to the device (I²C 0x6B / 0x6A, or SPI)
     * @throws IOException on bus error or if {@code WHO_AM_I} is not 0x05
     */
    public QMI8658AMinimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        sleepMs(15);
        w(REG_RESET, RESET_CMD);
        sleepMs(15);
        r1(REG_RESET_STATUS);                     // 0x80 after reset, informational
        int who = r1(REG_WHO_AM_I);
        if (who != WHO_AM_I_VALUE) {
            throw new IOException("QMI8658A WHO_AM_I: expected 0x05, got 0x" + Integer.toHexString(who));
        }
        w(REG_CTRL1, ctrl1);                      // ADDR_AI = 1, BE = 0
        w(REG_CTRL8, ctrl8);                      // CTRL9 handshake by polling
        updateSens();
        w(REG_CTRL2, 0x05);
        w(REG_CTRL3, 0x45);
        w(REG_CTRL5, 0x77);
        w(REG_CTRL7, ctrl7);
        sleepMs(165);                             // gyroscope turn-on
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void w(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) value});
    }

    protected int r1(int reg) throws IOException {
        return connection.read(reg, 1)[0] & 0xFF;
    }

    protected void updateSens() {
        accelSens = 16384 >> accelFs;
        gyroSens = 2048 >> gyroFs;
    }

    /** Read sensor-data registers; {@link QMI8658AFull} overrides this for SyncSample locking. */
    protected byte[] readData(int reg, int length) throws IOException {
        return connection.read(reg, length);
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
        byte[] b = readData(REG_AX_L, 6);
        return new double[]{le16(b, 0) / accelSens * G, le16(b, 2) / accelSens * G, le16(b, 4) / accelSens * G};
    }

    /**
     * Read 3-axis angular rate.
     *
     * @return {x, y, z} angular rate in rad/s
     * @throws IOException on bus error
     */
    public double[] gyro() throws IOException {
        byte[] b = readData(REG_GX_L, 6);
        return new double[]{le16(b, 0) / gyroSens * DEG_TO_RAD, le16(b, 2) / gyroSens * DEG_TO_RAD,
                le16(b, 4) / gyroSens * DEG_TO_RAD};
    }
}
