package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.Connection

/**
 * L3GD20H (and L3GD20) three-axis MEMS gyroscope — minimal driver.
 *
 * Provides angular rate readings on the X, Y, and Z axes with no
 * configuration beyond the connection. I²C address is 0x6A (SA0/SDO=GND) or
 * 0x6B (SA0/SDO=VCC). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 * Default configuration: 95 Hz ODR, default bandwidth, ±250 dps
 * full scale, BDU=1, all axes enabled, 250 ms startup delay.
 *
 * @param connection I²C connection bound to the chip.
 * @param spi true for SPI bus, false for I²C.
 */
@CompileStatic
class L3gd20hMinimal {
    static final int REG_WHO_AM_I      = 0x0F
    static final int REG_CTRL_REG1     = 0x20
    static final int REG_CTRL_REG2     = 0x21
    static final int REG_CTRL_REG3     = 0x22
    static final int REG_CTRL_REG4     = 0x23
    static final int REG_CTRL_REG5     = 0x24
    static final int REG_OUT_TEMP      = 0x26
    static final int REG_STATUS        = 0x27
    static final int REG_OUT_X_L       = 0x28
    static final int REG_OUT_X_H       = 0x29
    static final int REG_OUT_Y_L       = 0x2A
    static final int REG_OUT_Y_H       = 0x2B
    static final int REG_OUT_Z_L       = 0x2C
    static final int REG_OUT_Z_H       = 0x2D
    static final int REG_FIFO_CTRL     = 0x2E
    static final int REG_FIFO_SRC      = 0x2F
    static final int REG_INT1_CFG      = 0x30
    static final int REG_INT1_SRC      = 0x31
    static final int REG_INT1_TSH_XH   = 0x32
    static final int REG_INT1_TSH_XL   = 0x33
    static final int REG_INT1_TSH_YH   = 0x34
    static final int REG_INT1_TSH_YL   = 0x35
    static final int REG_INT1_TSH_ZH   = 0x36
    static final int REG_INT1_TSH_ZL   = 0x37
    static final int REG_INT1_DURATION = 0x38

    static final int WHO_AM_I_L3GD20  = 0xD4
    static final int WHO_AM_I_L3GD20H = 0xD7
    static final int CTRL_REG1_DEFAULT = 0x0F
    static final int CTRL_REG4_DEFAULT = 0x80

    protected final Connection connection
    protected final boolean spi
    protected int fullScale = 250

    /**
     * Construct the driver.
     *
     * @param connection I²C connection bound to the chip.
     * @param spi true for SPI bus, false for I²C.
     * @throws IOException on I²C error or wrong chip ID.
     */
    L3gd20hMinimal(Connection connection, boolean spi) throws IOException {
        this.connection = connection
        this.spi = spi
        byte[] id = connection.writeRead([(byte) REG_WHO_AM_I] as byte[], 1)
        int who = id[0] & 0xFF
        if (who != WHO_AM_I_L3GD20 && who != WHO_AM_I_L3GD20H) {
            throw new IOException("L3GD20H not found: WHO_AM_I expected 0xD4 or 0xD7, got 0x" + Integer.toHexString(who))
        }
        connection.write([(byte) REG_CTRL_REG4, (byte) CTRL_REG4_DEFAULT] as byte[])
        connection.write([(byte) REG_CTRL_REG1, (byte) CTRL_REG1_DEFAULT] as byte[])
        Thread.sleep(250)
    }

    protected void writeReg(int reg, int value) throws IOException {
        int addr = spi ? (reg & 0x3F) : reg
        connection.write([(byte) addr, (byte) (value & 0xFF)] as byte[])
    }

    static float sensitivity(int fullScale) {
        switch (fullScale) {
            case 250:  return 8.75e-3f
            case 500:  return 17.5e-3f
            case 2000: return 70.0e-3f
            default:   return 8.75e-3f
        }
    }

    static short int16Le(byte[] data, int offset) {
        int v = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
        return (short) v
    }

    /**
     * Read angular rate on all three axes as a single burst transaction.
     *
     * Burst-reads OUT_X_L through OUT_Z_H (registers 0x28–0x2D, 6 bytes,
     * little-endian), unpacks the three signed 16-bit values, and converts
     * them to rad/s using the current full-scale sensitivity.
     *
     * @return float[3] {x, y, z} angular rates in rad/s.
     * @throws IOException on I²C error.
     */
    float[] gyro() throws IOException {
        byte[] raw
        if (spi) {
            connection.write([(byte) ((REG_OUT_X_L | 0xC0) & 0xFF)] as byte[])
            raw = connection.read(6)
        } else {
            raw = connection.writeRead([(byte) (REG_OUT_X_L | 0x80)] as byte[], 6)
        }
        float sens = sensitivity(fullScale)
        float k = Math.PI / 180.0f
        return [
            int16Le(raw, 0) * sens * k,
            int16Le(raw, 2) * sens * k,
            int16Le(raw, 4) * sens * k
        ] as float[]
    }
}