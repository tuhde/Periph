package it.uhde.periph.chips.gyroscope

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * L3G4200D three-axis MEMS gyroscope — minimal driver.
 *
 * Provides angular rate readings on the X, Y, and Z axes with no
 * configuration beyond the connection. I²C address is 0x68 (SA0=GND) or
 * 0x69 (SA0=VDD). SPI uses Mode 3 (CPOL=CPHA=1) by default.
 *
 * Default configuration: 100 Hz ODR, 12.5 Hz LPF2 cutoff, ±250 dps
 * full scale, BDU=1, all axes enabled, FIFO disabled, HPF disabled.
 */
@CompileStatic
class L3g4200dMinimal {

    protected static final int REG_WHO_AM_I    = 0x0F
    protected static final int REG_CTRL_REG1   = 0x20
    protected static final int REG_CTRL_REG2   = 0x21
    protected static final int REG_CTRL_REG3   = 0x22
    protected static final int REG_CTRL_REG4   = 0x23
    protected static final int REG_CTRL_REG5   = 0x24
    protected static final int REG_OUT_TEMP    = 0x26
    protected static final int REG_STATUS      = 0x27
    protected static final int REG_OUT_X_L     = 0x28
    protected static final int REG_OUT_X_H     = 0x29
    protected static final int REG_OUT_Y_L     = 0x2A
    protected static final int REG_OUT_Y_H     = 0x2B
    protected static final int REG_OUT_Z_L     = 0x2C
    protected static final int REG_OUT_Z_H     = 0x2D
    protected static final int REG_FIFO_CTRL   = 0x2E
    protected static final int REG_FIFO_SRC    = 0x2F
    protected static final int REG_INT1_CFG    = 0x30
    protected static final int REG_INT1_SRC    = 0x31
    protected static final int REG_INT1_THS_XH = 0x32
    protected static final int REG_INT1_THS_XL = 0x33
    protected static final int REG_INT1_THS_YH = 0x34
    protected static final int REG_INT1_THS_YL = 0x35
    protected static final int REG_INT1_THS_ZH = 0x36
    protected static final int REG_INT1_THS_ZL = 0x37
    protected static final int REG_INT1_DURATION = 0x38

    protected static final int WHO_AM_I_EXPECTED = 0xD3
    protected static final int CTRL_REG1_DEFAULT = 0x0F
    protected static final int CTRL_REG4_DEFAULT = 0x80

    protected final Connection connection
    protected final boolean spi
    protected int fullScaleDps = 250

    L3g4200dMinimal(Connection connection, boolean spi) throws Exception {
        this.connection = connection
        this.spi = spi
        byte[] id = connection.writeRead([REG_WHO_AM_I as byte] as byte[], 1)
        if ((id[0] & 0xFF) != WHO_AM_I_EXPECTED) {
            throw new IOException("L3G4200D not found: WHO_AM_I expected 0x"
                    + Integer.toHexString(WHO_AM_I_EXPECTED) + ", got 0x" + Integer.toHexString(id[0] & 0xFF))
        }
        connection.write([REG_CTRL_REG4 as byte, CTRL_REG4_DEFAULT as byte] as byte[])
        connection.write([REG_CTRL_REG1 as byte, CTRL_REG1_DEFAULT as byte] as byte[])
    }

    protected void writeReg(int reg, int value) throws Exception {
        int addr = spi ? (reg & 0x3F) : reg
        connection.write([addr as byte, (value & 0xFF) as byte] as byte[])
    }

    protected static float sensitivity(int fullScale) {
        switch (fullScale) {
            case 250:  return 8.75e-3f
            case 500:  return 17.5e-3f
            case 2000: return 70.0e-3f
            default:   return 8.75e-3f
        }
    }

    protected static int int16Le(byte[] data, int offset) {
        int v = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
        if (v >= 0x8000) v -= 0x10000
        return v
    }

    float[] angularRate() throws Exception {
        byte[] raw
        if (spi) {
            connection.write([(byte) ((REG_OUT_X_L | 0xC0) & 0xFF)] as byte[])
            raw = connection.read(6)
        } else {
            raw = connection.writeRead([(byte) (REG_OUT_X_L | 0x80)] as byte[], 6)
        }
        float sens = sensitivity(fullScaleDps)
        float k = (float) (Math.PI / 180.0)
        float x = int16Le(raw, 0) * sens * k
        float y = int16Le(raw, 2) * sens * k
        float z = int16Le(raw, 4) * sens * k
        return [x, y, z] as float[]
    }
}
