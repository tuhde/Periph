@CompileStatic
package it.uhde.periph.chips.accelerometer

import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * BMA150 — 3-axis MEMS accelerometer (Bosch Sensortec) — minimal interface.
 *
 * Triaxial low-g accelerometer with 10-bit digital output and ±2/±4/±8 *g*
 * selectable full-scale range. Communicates over I²C at the fixed address
 * 0x38.
 *
 * Default configuration (baked in at construction):
 * - Range ±2 *g* (256 LSB/g)
 * - Bandwidth 100 Hz
 * - Calibration bits 7:5 of `RANGE_BW` (0x14) preserved
 * - `shadow_dis` = 0 (LSB-then-MSB ordering enforced)
 */
class Bma150Minimal {

    // Register map (0x00..0x15).
    static final int REG_CHIP_ID          = 0x00
    static final int REG_VERSION          = 0x01
    static final int REG_ACC_X_LSB        = 0x02
    static final int REG_ACC_X_MSB        = 0x03
    static final int REG_ACC_Y_LSB        = 0x04
    static final int REG_ACC_Y_MSB        = 0x05
    static final int REG_ACC_Z_LSB        = 0x06
    static final int REG_ACC_Z_MSB        = 0x07
    static final int REG_TEMP             = 0x08
    static final int REG_STATUS           = 0x09
    static final int REG_CTRL             = 0x0A
    static final int REG_INT_CTRL         = 0x0B
    static final int REG_LG_THRES         = 0x0C
    static final int REG_LG_DUR           = 0x0D
    static final int REG_HG_THRES         = 0x0E
    static final int REG_HG_DUR           = 0x0F
    static final int REG_ANY_MOTION_THRES = 0x10
    static final int REG_HYST_DUR         = 0x11
    static final int REG_CUSTOMER_1       = 0x12
    static final int REG_CUSTOMER_2       = 0x13
    static final int REG_RANGE_BW         = 0x14
    static final int REG_CONFIG           = 0x15

    static final int CHIP_ID_VALUE = 0x02
    static final int CHIP_ID_MASK  = 0x07

    static final int RANGE_2G_MASK = 0x00
    static final int RANGE_4G_MASK = 0x08
    static final int RANGE_8G_MASK = 0x10

    static final int BW_25    = 0x00
    static final int BW_50    = 0x01
    static final int BW_100   = 0x02
    static final int BW_190   = 0x03
    static final int BW_375   = 0x04
    static final int BW_750   = 0x05
    static final int BW_1500  = 0x06

    static final float FULL_SCALE_2G = 256.0f
    static final float FULL_SCALE_4G = 128.0f
    static final float FULL_SCALE_8G = 64.0f

    protected final RegisterConnection connection
    protected int rangeG = 2

    Bma150Minimal(RegisterConnection connection) {
        this.connection = connection
        this.rangeG = 2
        int chipId = readReg(REG_CHIP_ID)
        if ((chipId & CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw new IOException("BMA150 CHIP_ID: expected 0x" +
                    Integer.toHexString(CHIP_ID_VALUE) + ", got 0x" +
                    Integer.toHexString(chipId & CHIP_ID_MASK))
        }
        int rb = readReg(REG_RANGE_BW)
        writeReg(REG_RANGE_BW, (rb & 0xE0) | RANGE_2G_MASK | BW_100)
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected byte[] readBurst(int reg, int n) {
        return connection.read(reg, n)
    }

    /**
     * Read 3-axis linear acceleration in *g*.
     *
     * @return array of three doubles — X, Y, Z acceleration in <i>g</i>
     */
    double[] read() {
        byte[] raw = readBurst(REG_ACC_X_LSB, 6)
        int rx = Register.toSigned(((raw[1] & 0xFF) << 2) | ((raw[0] & 0xC0) >> 6), 10)
        int ry = Register.toSigned(((raw[3] & 0xFF) << 2) | ((raw[2] & 0xC0) >> 6), 10)
        int rz = Register.toSigned(((raw[5] & 0xFF) << 2) | ((raw[4] & 0xC0) >> 6), 10)
        double scale
        switch (rangeG) {
            case 4:  scale = FULL_SCALE_4G; break
            case 8:  scale = FULL_SCALE_8G; break
            case 2:
            default: scale = FULL_SCALE_2G; break
        }
        return [rx / scale, ry / scale, rz / scale] as double[]
    }
}
