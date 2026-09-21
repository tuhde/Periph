package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * ADXL362 — 3-axis MEMS accelerometer (Analog Devices) — minimal interface (SPI).
 *
 * Reads X, Y, Z acceleration in *g* with sensible defaults; no configuration
 * is required beyond the connection. The ADXL362 is SPI-only — there is no
 * I²C mode.
 *
 * Default configuration (baked in at construction):
 * - ±2 g measurement range
 * - 100 Hz output data rate, ODR/4 antialiasing bandwidth
 * - Normal noise mode (POWER_CTL.LOW_NOISE=00)
 * - Continuous measurement mode (POWER_CTL.MEASURE=10)
 * - FIFO disabled
 * - No interrupts mapped; INT1/INT2 high-impedance
 */
@CompileStatic
class Adxl362Minimal {

    protected static final int CMD_WRITE_REG = 0x0A
    protected static final int CMD_READ_REG  = 0x0B
    protected static final int CMD_READ_FIFO = 0x0D
    protected static final int SOFT_RESET_KEY = 0x52

    protected static final double[] SENSITIVITY_G_PER_LSB = [0.001d, 0.002d, 0.004255d] as double[]

    protected static final int REG_DEVID_AD        = 0x00
    protected static final int REG_DEVID_MST       = 0x01
    protected static final int REG_PARTID          = 0x02
    protected static final int REG_XDATA           = 0x08
    protected static final int REG_YDATA           = 0x09
    protected static final int REG_ZDATA           = 0x0A
    protected static final int REG_STATUS          = 0x0B
    protected static final int REG_FIFO_ENTRIES_L  = 0x0C
    protected static final int REG_FIFO_ENTRIES_H  = 0x0D
    protected static final int REG_XDATA_L         = 0x0E
    protected static final int REG_XDATA_H         = 0x0F
    protected static final int REG_YDATA_L         = 0x10
    protected static final int REG_YDATA_H         = 0x11
    protected static final int REG_ZDATA_L         = 0x12
    protected static final int REG_ZDATA_H         = 0x13
    protected static final int REG_TEMP_L          = 0x14
    protected static final int REG_TEMP_H          = 0x15
    protected static final int REG_SOFT_RESET      = 0x1F
    protected static final int REG_THRESH_ACT_L    = 0x20
    protected static final int REG_THRESH_ACT_H    = 0x21
    protected static final int REG_TIME_ACT        = 0x22
    protected static final int REG_THRESH_INACT_L  = 0x23
    protected static final int REG_THRESH_INACT_H  = 0x24
    protected static final int REG_TIME_INACT_L    = 0x25
    protected static final int REG_TIME_INACT_H    = 0x26
    protected static final int REG_ACT_INACT_CTL   = 0x27
    protected static final int REG_FIFO_CONTROL    = 0x28
    protected static final int REG_FIFO_SAMPLES    = 0x29
    protected static final int REG_INTMAP1         = 0x2A
    protected static final int REG_INTMAP2         = 0x2B
    protected static final int REG_FILTER_CTL      = 0x2C
    protected static final int REG_POWER_CTL       = 0x2D
    protected static final int REG_SELF_TEST       = 0x2E

    protected static final int DEVID_AD_VALUE  = 0xAD
    protected static final int DEVID_MST_VALUE = 0x1D
    protected static final int PARTID_VALUE    = 0xF2

    protected static final int FILTER_CTL_DEFAULT = 0x13
    protected static final int POWER_CTL_MEASURE  = 0x02

    protected static final int STATUS_AWAKE     = 0x40
    protected static final int STATUS_DATA_READY = 0x01

    protected final Connection connection
    protected int rangeBits = 0x00
    protected float odrHz = 100.0f

    Adxl362Minimal(Connection connection) {
        this.connection = connection
        init()
    }

    void init() {
        sleepMs(5)
        byte[] ids = readBurst(REG_DEVID_AD, 3)
        if ((ids[0] & 0xFF) != DEVID_AD_VALUE) {
            throw new IOException("ADXL362 DEVID_AD: expected 0x" +
                    Integer.toHexString(DEVID_AD_VALUE) + ", got 0x" +
                    Integer.toHexString(ids[0] & 0xFF))
        }
        if ((ids[1] & 0xFF) != DEVID_MST_VALUE) {
            throw new IOException("ADXL362 DEVID_MST: expected 0x" +
                    Integer.toHexString(DEVID_MST_VALUE) + ", got 0x" +
                    Integer.toHexString(ids[1] & 0xFF))
        }
        if ((ids[2] & 0xFF) != PARTID_VALUE) {
            throw new IOException("ADXL362 PARTID: expected 0x" +
                    Integer.toHexString(PARTID_VALUE) + ", got 0x" +
                    Integer.toHexString(ids[2] & 0xFF))
        }
        writeReg(REG_FILTER_CTL, FILTER_CTL_DEFAULT)
        writeReg(REG_POWER_CTL, POWER_CTL_MEASURE)
        sleepMs(40)
    }

    /**
     * Read 3-axis linear acceleration.
     * @return (x, y, z) acceleration in *g*.
     */
    float[] read() {
        byte[] raw = readBurst(REG_XDATA_L, 6)
        int rx = signExtend12(((raw[1] & 0x0F) << 8) | (raw[0] & 0xFF))
        int ry = signExtend12(((raw[3] & 0x0F) << 8) | (raw[2] & 0xFF))
        int rz = signExtend12(((raw[5] & 0x0F) << 8) | (raw[4] & 0xFF))
        float sens = sensitivity()
        return new float[] { rx * sens, ry * sens, rz * sens } as float[]
    }

    protected float sensitivity() {
        if (rangeBits == 0x40) return (float) SENSITIVITY_G_PER_LSB[1]
        if (rangeBits == 0x80 || rangeBits == 0xC0) return (float) SENSITIVITY_G_PER_LSB[2]
        return (float) SENSITIVITY_G_PER_LSB[0]
    }

    protected static int signExtend12(int v) {
        int x = v & 0x0FFF
        return (x & 0x0800) != 0 ? (short) (x | 0xF000) : x
    }

    protected static void sleepMs(int ms) {
        try { Thread.sleep(ms) }
        catch (InterruptedException e) { Thread.currentThread().interrupt() }
    }

    protected void writeReg(int reg, int value) {
        connection.write(new byte[] {
            (byte) CMD_WRITE_REG, (byte) (reg & 0x3F), (byte) (value & 0xFF),
        } as byte[])
    }

    protected int readReg(int reg) {
        byte[] out = readBurst(reg, 1)
        return out[0] & 0xFF
    }

    protected byte[] readBurst(int reg, int len) {
        return connection.writeRead(
            new byte[] { (byte) CMD_READ_REG, (byte) (reg & 0x3F) } as byte[], len)
    }

    protected byte[] readFifo(int len) {
        return connection.writeRead(new byte[] { (byte) CMD_READ_FIFO } as byte[], len)
    }

    protected int readFifoEntries() {
        int lo = readReg(REG_FIFO_ENTRIES_L)
        int hi = readReg(REG_FIFO_ENTRIES_H)
        return lo | ((hi & 0x03) << 8)
    }
}