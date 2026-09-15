package it.uhde.periph.chips.gyroscope;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * L3G4200D three-axis MEMS gyroscope — minimal driver.
 *
 * <p>Provides angular rate readings on the X, Y, and Z axes with no
 * configuration beyond the connection. I²C address is 0x68 (SA0=GND) or
 * 0x69 (SA0=VDD). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 * <p>Default configuration: 100 Hz ODR, 12.5 Hz LPF2 cutoff, ±250 dps
 * full scale, BDU=1, all axes enabled, FIFO disabled, HPF disabled.
 *
 * @param connection Configured I²C or SPI connection bound to the device.
 * @param spi        Pass true for SPI bus.
 */
public class L3g4200dMinimal {

    /** Register addresses. */
    protected static final int REG_WHO_AM_I    = 0x0F;
    protected static final int REG_CTRL_REG1   = 0x20;
    protected static final int REG_CTRL_REG2   = 0x21;
    protected static final int REG_CTRL_REG3   = 0x22;
    protected static final int REG_CTRL_REG4   = 0x23;
    protected static final int REG_CTRL_REG5   = 0x24;
    protected static final int REG_OUT_TEMP    = 0x26;
    protected static final int REG_STATUS      = 0x27;
    protected static final int REG_OUT_X_L     = 0x28;
    protected static final int REG_OUT_X_H     = 0x29;
    protected static final int REG_OUT_Y_L     = 0x2A;
    protected static final int REG_OUT_Y_H     = 0x2B;
    protected static final int REG_OUT_Z_L     = 0x2C;
    protected static final int REG_OUT_Z_H     = 0x2D;
    protected static final int REG_FIFO_CTRL   = 0x2E;
    protected static final int REG_FIFO_SRC    = 0x2F;
    protected static final int REG_INT1_CFG    = 0x30;
    protected static final int REG_INT1_SRC    = 0x31;
    protected static final int REG_INT1_THS_XH = 0x32;
    protected static final int REG_INT1_THS_XL = 0x33;
    protected static final int REG_INT1_THS_YH = 0x34;
    protected static final int REG_INT1_THS_YL = 0x35;
    protected static final int REG_INT1_THS_ZH = 0x36;
    protected static final int REG_INT1_THS_ZL = 0x37;
    protected static final int REG_INT1_DURATION = 0x38;

    protected static final int WHO_AM_I_EXPECTED = 0xD3;
    protected static final int CTRL_REG1_DEFAULT = 0x0F;
    protected static final int CTRL_REG4_DEFAULT = 0x80;

    protected final Connection connection;
    protected final boolean spi;
    protected int fullScale = 250;

    /**
     * Construct the driver.
     *
     * @param connection I²C connection bound to the chip.
     * @param spi        true for SPI bus, false for I²C.
     * @throws IOException on I²C error or wrong chip ID.
     */
    public L3g4200dMinimal(Connection connection, boolean spi) throws IOException {
        this.connection = connection;
        this.spi = spi;
        byte[] id = connection.writeRead(new byte[] { (byte) REG_WHO_AM_I }, 1);
        if ((id[0] & 0xFF) != WHO_AM_I_EXPECTED) {
            throw new IOException("L3G4200D not found: WHO_AM_I expected 0x"
                    + Integer.toHexString(WHO_AM_I_EXPECTED) + ", got 0x"
                    + Integer.toHexString(id[0] & 0xFF));
        }
        connection.write(new byte[] { (byte) REG_CTRL_REG4, (byte) CTRL_REG4_DEFAULT });
        connection.write(new byte[] { (byte) REG_CTRL_REG1, (byte) CTRL_REG1_DEFAULT });
    }

    protected void writeReg(int reg, int value) throws IOException {
        int addr = spi ? (reg & 0x3F) : reg;
        connection.write(new byte[] { (byte) addr, (byte) (value & 0xFF) });
    }

    /** Sensitivity per full-scale range, dps/digit. */
    protected static float sensitivity(int fullScale) {
        switch (fullScale) {
            case 250:  return 8.75e-3f;
            case 500:  return 17.5e-3f;
            case 2000: return 70.0e-3f;
            default:   return 8.75e-3f;
        }
    }

    protected static short int16Le(byte[] data, int offset) {
        int v = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        return (short) v;
    }

    /**
     * Read angular rate on all three axes as a single burst transaction.
     *
     * <p>Burst-reads OUT_X_L through OUT_Z_H (registers 0x28–0x2D, 6 bytes,
     * little-endian), unpacks the three signed 16-bit values, and converts
     * them to rad/s using the current full-scale sensitivity.
     *
     * @return float[3] {x, y, z} angular rates in rad/s.
     * @throws IOException on I²C error.
     */
    public float[] angularRate() throws IOException {
        byte[] raw;
        if (spi) {
            // SPI: write the reg with READ=1, MS=1, then read n bytes.
            connection.write(new byte[] { (byte) ((REG_OUT_X_L | 0xC0) & 0xFF) });
            raw = connection.read(6);
        } else {
            // I²C: write_read with auto-increment bit set.
            raw = connection.writeRead(new byte[] { (byte) (REG_OUT_X_L | 0x80) }, 6);
        }
        float sens = sensitivity(fullScale);
        float k = (float) (Math.PI / 180.0);
        float x = int16Le(raw, 0) * sens * k;
        float y = int16Le(raw, 2) * sens * k;
        float z = int16Le(raw, 4) * sens * k;
        return new float[] { x, y, z };
    }
}
