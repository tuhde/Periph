package it.uhde.periph.chips.imu;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/**
 * BNO055 — 9-axis absolute-orientation sensor (Bosch Sensortec), minimal driver.
 *
 * <p>The constructor waits for the chip to boot (up to 650 ms), checks {@code CHIP_ID} (0xA0),
 * selects normal power mode and {@code UNIT_SEL} = 0x86 (Android orientation, Euler angles in
 * radians, angular rate in rad/s, acceleration in m/s², temperature in °C), then enters NDOF
 * fusion mode (100 Hz absolute orientation, internal oscillator).
 *
 * <p>I²C address 0x29 (COM3 high, datasheet default) or 0x28 (COM3 low). The chip stretches the
 * I²C clock; the host bus must support clock stretching.
 */
public class BNO055Minimal {

    protected static final int REG_CHIP_ID         = 0x00;
    protected static final int REG_PAGE_ID         = 0x07;
    protected static final int REG_ACC_DATA        = 0x08;
    protected static final int REG_MAG_DATA        = 0x0E;
    protected static final int REG_GYR_DATA        = 0x14;
    protected static final int REG_EUL_DATA        = 0x1A;
    protected static final int REG_QUA_DATA        = 0x20;
    protected static final int REG_LIA_DATA        = 0x28;
    protected static final int REG_GRV_DATA        = 0x2E;
    protected static final int REG_TEMP            = 0x34;
    protected static final int REG_CALIB_STAT      = 0x35;
    protected static final int REG_ST_RESULT       = 0x36;
    protected static final int REG_INT_STA         = 0x37;
    protected static final int REG_SYS_STATUS      = 0x39;
    protected static final int REG_UNIT_SEL        = 0x3B;
    protected static final int REG_OPR_MODE        = 0x3D;
    protected static final int REG_PWR_MODE        = 0x3E;
    protected static final int REG_SYS_TRIGGER     = 0x3F;
    protected static final int REG_TEMP_SOURCE     = 0x40;
    protected static final int REG_AXIS_MAP_CONFIG = 0x41;
    protected static final int REG_AXIS_MAP_SIGN   = 0x42;
    protected static final int REG_ACC_OFFSET      = 0x55;
    protected static final int REG_INT_MSK         = 0x0F;  // page 1
    protected static final int REG_INT_EN          = 0x10;  // page 1
    protected static final int REG_UNIQUE_ID       = 0x50;  // page 1

    protected static final int CHIP_ID_VALUE = 0xA0;
    protected static final int UNIT_SEL_SI   = 0x86;
    protected static final int MODE_CONFIG_V = 0x00;
    protected static final int MODE_NDOF_V   = 0x0C;

    protected final RegisterConnection connection;
    protected int mode = MODE_NDOF_V;

    /**
     * Wait for boot, verify {@code CHIP_ID} and enter NDOF.
     *
     * @param connection register connection bound to the device (I²C 0x28 / 0x29)
     * @throws IOException on bus error or if {@code CHIP_ID} is not 0xA0 within 650 ms
     */
    public BNO055Minimal(RegisterConnection connection) throws IOException {
        this.connection = connection;
        initialize();
    }

    /** Boot wait, identity check and NDOF entry (also used by {@code reset()}). */
    protected void initialize() throws IOException {
        long deadline = System.currentTimeMillis() + 650;
        int id = -1;
        IOException last = null;
        while (true) {
            try {
                id = r1(REG_CHIP_ID);
                last = null;
                if (id == CHIP_ID_VALUE) break;
            } catch (IOException e) {
                last = e;                          // the chip does not ACK while booting
            }
            if (System.currentTimeMillis() >= deadline) {
                if (last != null) throw last;
                throw new IOException("BNO055 CHIP_ID: expected 0xA0, got 0x" + Integer.toHexString(id));
            }
            sleepMs(10);
        }
        w(REG_PWR_MODE, 0x00);
        w(REG_PAGE_ID, 0x00);
        w(REG_UNIT_SEL, UNIT_SEL_SI);
        w(REG_OPR_MODE, MODE_NDOF_V);
        mode = MODE_NDOF_V;
        sleepMs(7);
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

    /** Signed little-endian 16-bit value at {@code offset}. */
    protected static int le16(byte[] b, int offset) {
        return (short) (((b[offset + 1] & 0xFF) << 8) | (b[offset] & 0xFF));
    }

    protected double[] vector(int reg, double scale) throws IOException {
        byte[] b = connection.read(reg, 6);
        return new double[]{le16(b, 0) / scale, le16(b, 2) / scale, le16(b, 4) / scale};
    }

    /**
     * Read the fused Euler angles (one burst of 0x1A–0x1F, raw / 900).
     *
     * @return {heading, roll, pitch} in rad
     * @throws IOException on bus error
     */
    public double[] euler() throws IOException {
        return vector(REG_EUL_DATA, 900.0);
    }
}
