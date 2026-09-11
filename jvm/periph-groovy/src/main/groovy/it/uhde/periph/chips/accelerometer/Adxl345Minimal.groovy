package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * ADXL345 — 3-axis MEMS accelerometer (Analog Devices) — minimal interface.
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
 * is required beyond the connection. Supports I²C and SPI.
 *
 * Default configuration (baked in at construction):
 * - Full-resolution mode (3.9 mg/LSB at any range)
 * - ±2 g measurement range
 * - 100 Hz output data rate, normal power
 * - FIFO bypass, all interrupts disabled, no offsets
 *
 * I²C address is 0x53 (SDO=GND) or 0x1D (SDO=VDDIO). SPI mode uses
 * CPOL=1/CPHA=1 (mode 3), max 5 MHz, MSB first, CS active low.
 */
@CompileStatic
class Adxl345Minimal {

    /** Bus type: I²C (default). */
    public static final int BUS_I2C = 0
    /** Bus type: SPI — writes prepend a command byte (R/W|MB|A5..A0). */
    public static final int BUS_SPI = 1

    protected static final int REG_DEVID         = 0x00
    protected static final int REG_THRESH_TAP    = 0x1D
    protected static final int REG_OFSX          = 0x1E
    protected static final int REG_OFSY          = 0x1F
    protected static final int REG_OFSZ          = 0x20
    protected static final int REG_DUR           = 0x21
    protected static final int REG_LATENT        = 0x22
    protected static final int REG_WINDOW        = 0x23
    protected static final int REG_THRESH_ACT    = 0x24
    protected static final int REG_THRESH_INACT  = 0x25
    protected static final int REG_TIME_INACT    = 0x26
    protected static final int REG_ACT_INACT_CTL = 0x27
    protected static final int REG_THRESH_FF     = 0x28
    protected static final int REG_TIME_FF       = 0x29
    protected static final int REG_TAP_AXES      = 0x2A
    protected static final int REG_BW_RATE       = 0x2C
    protected static final int REG_POWER_CTL     = 0x2D
    protected static final int REG_INT_ENABLE    = 0x2E
    protected static final int REG_INT_MAP       = 0x2F
    protected static final int REG_INT_SOURCE    = 0x30
    protected static final int REG_DATA_FORMAT   = 0x31
    protected static final int REG_DATAX0        = 0x32
    protected static final int REG_FIFO_CTL      = 0x38
    protected static final int REG_FIFO_STATUS   = 0x39

    protected static final int DEVID_VALUE        = 0xE5
    protected static final int DATA_FORMAT_DEFAULT = 0x08
    protected static final int BW_RATE_DEFAULT     = 0x0A
    protected static final int POWER_CTL_DEFAULT   = 0x08
    protected static final double FULL_RES_SCALE_G_PER_LSB = 3.9e-3

    protected static final int[][] RATE_CODES = [
        [0x0F, 3200], [0x0E, 1600], [0x0D, 800], [0x0C, 400], [0x0B, 200],
        [0x0A,  100], [0x09,   50], [0x08,  25], [0x07,  12], [0x06,   6],
    ] as int[][]

    protected final Connection connection
    protected final int busType
    protected int rangeBits = 0
    protected boolean fullRes = true

    Adxl345Minimal(Connection connection) {
        this(connection, BUS_I2C)
    }

    Adxl345Minimal(Connection connection, int busType) {
        this.connection = connection
        this.busType = busType
        writeReg(REG_DATA_FORMAT, DATA_FORMAT_DEFAULT)
        writeReg(REG_BW_RATE, BW_RATE_DEFAULT)
        writeReg(REG_POWER_CTL, POWER_CTL_DEFAULT)
        int devid = readReg(REG_DEVID)
        if (devid != DEVID_VALUE) {
            throw new IOException("ADXL345 DEVID: expected 0x" +
                    Integer.toHexString(DEVID_VALUE) + ", got 0x" +
                    Integer.toHexString(devid))
        }
    }

    private static int cmdByte(int reg, boolean read, boolean multi) {
        int addr = reg & 0x3F
        if (multi) addr |= 0x40
        if (read)  addr |= 0x80
        return addr
    }

    protected void writeReg(int reg, int value) throws IOException {
        if (busType == BUS_SPI) {
            int cmd = cmdByte(reg, false, false)
            connection.write(new byte[]{(byte) cmd, (byte) value} as byte[])
        } else {
            connection.write(new byte[]{(byte) reg, (byte) value} as byte[])
        }
    }

    protected int readReg(int reg) throws IOException {
        if (busType == BUS_SPI) {
            int cmd = cmdByte(reg, true, false)
            return connection.writeRead(new byte[]{(byte) cmd} as byte[], 1)[0] & 0xFF
        }
        return connection.writeRead(new byte[]{(byte) reg} as byte[], 1)[0] & 0xFF
    }

    protected byte[] readBurst(int reg, int n) throws IOException {
        if (busType == BUS_SPI) {
            int cmd = cmdByte(reg, true, n > 1)
            return connection.writeRead(new byte[]{(byte) cmd} as byte[], n)
        }
        return connection.writeRead(new byte[]{(byte) reg} as byte[], n)
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * @return array of three doubles — X, Y, Z acceleration in *g*
     */
    double[] read() throws IOException {
        byte[] raw = readBurst(REG_DATAX0, 6)
        int rx = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8)
        int ry = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8)
        int rz = (raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8)
        if ((rx & 0x8000) != 0) rx -= 0x10000
        if ((ry & 0x8000) != 0) ry -= 0x10000
        if ((rz & 0x8000) != 0) rz -= 0x10000
        double scale = fullRes
                ? FULL_RES_SCALE_G_PER_LSB
                : new double[]{3.9e-3, 7.8e-3, 15.6e-3, 31.2e-3}[rangeBits & 0x03]
        return new double[]{rx * scale, ry * scale, rz * scale}
    }
}