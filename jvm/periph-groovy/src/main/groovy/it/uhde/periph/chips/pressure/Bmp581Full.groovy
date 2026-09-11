package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * BMP581 — full driver. Extends Bmp581Minimal with configuration, FIFO,
 * interrupts, OOR detection, and NVM access.
 */
@CompileStatic
class Bmp581Full extends Bmp581Minimal {

    static final int OSR_1X   = 0
    static final int OSR_2X   = 1
    static final int OSR_4X   = 2
    static final int OSR_8X   = 3
    static final int OSR_16X  = 4
    static final int OSR_32X  = 5
    static final int OSR_64X  = 6
    static final int OSR_128X = 7

    static final int MODE_STANDBY    = 0
    static final int MODE_NORMAL     = 1
    static final int MODE_FORCED     = 2
    static final int MODE_CONTINUOUS = 3

    static final int IIR_BYPASS    = 0
    static final int IIR_COEFF_1   = 1
    static final int IIR_COEFF_3   = 2
    static final int IIR_COEFF_7   = 3
    static final int IIR_COEFF_15  = 4
    static final int IIR_COEFF_31  = 5
    static final int IIR_COEFF_63  = 6
    static final int IIR_COEFF_127 = 7

    static final int FIFO_DISABLED = 0
    static final int FIFO_TEMP     = 1
    static final int FIFO_PRESS    = 2
    static final int FIFO_BOTH     = 3

    static final int FIFO_STREAM       = 0
    static final int FIFO_STOP_ON_FULL = 1

    static final int INT_SOURCE_DRDY       = 0x01
    static final int INT_SOURCE_FIFO_FULL  = 0x02
    static final int INT_SOURCE_FIFO_THS   = 0x04
    static final int INT_SOURCE_OOR_P      = 0x08

    protected static final int REG_REV_ID        = 0x02
    protected static final int REG_INT_SOURCE    = 0x15
    protected static final int REG_INT_CONFIG    = 0x14
    protected static final int REG_FIFO_SEL      = 0x18
    protected static final int REG_FIFO_CONFIG   = 0x16
    protected static final int REG_FIFO_COUNT    = 0x17
    protected static final int REG_DSP_CONFIG    = 0x30
    protected static final int REG_DSP_IIR       = 0x31
    protected static final int REG_OOR_THR_P_LSB = 0x32
    protected static final int REG_OOR_THR_P_MSB = 0x33
    protected static final int REG_OOR_RANGE     = 0x34
    protected static final int REG_OOR_CONFIG    = 0x35
    protected static final int REG_OSR_EFF       = 0x38
    protected static final int REG_NVM_ADDR      = 0x2B
    protected static final int REG_NVM_DATA_LSB  = 0x2C
    protected static final int REG_NVM_DATA_MSB  = 0x2D

    protected int osrP = 0
    protected int osrT = 0
    protected boolean pressEn = true

    Bmp581Full(Connection connection) throws Exception {
        super(connection, 0x46, BUS_I2C)
    }

    Bmp581Full(Connection connection, int addr) throws Exception {
        super(connection, addr, BUS_I2C)
    }

    Bmp581Full(Connection connection, int addr, int busType) throws Exception {
        super(connection, addr, busType)
    }

    void configure(int odr, int osrP, int osrT, boolean pressEn) throws Exception {
        this.odr = odr
        this.osrP = osrP
        this.osrT = osrT
        this.pressEn = pressEn
        int osr = (pressEn ? 0x40 : 0) | ((osrP & 0x7) << 3) | (osrT & 0x7)
        writeReg(REG_OSR_CONFIG, osr)
        int odrByte = ((odr & 0x1F) << 2) | (pwrMode & 0x3)
        writeReg(REG_ODR_CONFIG, odrByte)
    }

    void setMode(int mode) throws Exception {
        this.pwrMode = mode
        int odrByte = ((odr & 0x1F) << 2) | (mode & 0x3)
        writeReg(REG_ODR_CONFIG, odrByte)
    }

    double[] forced() throws Exception {
        int prev = pwrMode
        if (prev != MODE_FORCED) setMode(MODE_FORCED)
        for (int i = 0; i < 400; i++) {
            byte[] st = connection.writeRead([(byte) REG_INT_STATUS] as byte[], 1)
            if ((st[0] & INT_STATUS_DRDY) != 0) break
            Thread.sleep(5)
        }
        return both()
    }

    double altitude(double seaLevelPa) throws Exception {
        double p = pressure()
        if (p <= 0) return 0.0d
        return 44330.0d * (1.0d - Math.pow(p / seaLevelPa, 1.0d / 5.255d))
    }

    void softwareReset() throws Exception {
        try {
            writeReg(REG_CMD, SOFT_RESET)
        } catch (IOException ignored) { /* expected NACK */ }
        Thread.sleep(2)
        init(0x46)
        configure(odr, osrP, osrT, pressEn)
    }

    int chipId() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_CHIP_ID] as byte[], 1)
        return buf[0] & 0xFF
    }

    int revId() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_REV_ID] as byte[], 1)
        return buf[0] & 0xFF
    }

    int status() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_STATUS] as byte[], 1)
        return buf[0] & 0xFF
    }

    int interruptStatus() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_INT_STATUS] as byte[], 1)
        return buf[0] & 0xFF
    }

    boolean dataReady() throws Exception {
        return (interruptStatus() & INT_STATUS_DRDY) != 0
    }

    void configureInterrupt(int mode, int polarity, boolean openDrain, boolean enable) throws Exception {
        int val = enable ? 0x08 : 0
        if (openDrain) val |= 0x04
        if (polarity != 0) val |= 0x02
        if (mode != 0) val |= 0x01
        writeReg(REG_INT_CONFIG, val)
    }

    private void setIntSource(int source, boolean enable) throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_INT_SOURCE] as byte[], 1)
        int cur = buf[0] & 0xFF
        int next = enable ? (cur | source) : (cur & ~source)
        writeReg(REG_INT_SOURCE, next)
    }

    void enableDrdyInterrupt(boolean enable) throws Exception { setIntSource(INT_SOURCE_DRDY, enable) }

    void enableFifoInterrupt(boolean threshold, boolean full) throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_INT_SOURCE] as byte[], 1)
        int cur = buf[0] & 0xFF
        cur &= ~(INT_SOURCE_FIFO_FULL | INT_SOURCE_FIFO_THS)
        if (threshold) cur |= INT_SOURCE_FIFO_THS
        if (full) cur |= INT_SOURCE_FIFO_FULL
        writeReg(REG_INT_SOURCE, cur)
    }

    void enableOorInterrupt(boolean enable) throws Exception { setIntSource(INT_SOURCE_OOR_P, enable) }

    void setIirFilter(int coeffP, int coeffT) throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_DSP_CONFIG] as byte[], 1)
        int dsp = (buf[0] & 0xFF) | 0x28
        writeReg(REG_DSP_CONFIG, dsp)
        int iirVal = ((coeffP & 0x7) << 3) | (coeffT & 0x7)
        writeReg(REG_DSP_IIR, iirVal)
    }

    void configureFifo(int frameSel, int mode, int threshold) throws Exception {
        int prev = pwrMode
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY)
        writeReg(REG_FIFO_SEL, frameSel & 0x3)
        int cfg = ((mode & 0x1) << 5) | (threshold & 0x1F)
        writeReg(REG_FIFO_CONFIG, cfg)
        if (prev != MODE_STANDBY) setMode(prev)
    }

    int fifoCount() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_FIFO_COUNT] as byte[], 1)
        return buf[0] & 0x3F
    }

    int[] effectiveOsr() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_OSR_EFF] as byte[], 1)
        return new int[]{(buf[0] >> 3) & 0x7, buf[0] & 0x7}
    }

    boolean odrIsValid() throws Exception {
        byte[] buf = connection.writeRead([(byte) REG_OSR_EFF] as byte[], 1)
        return (buf[0] & 0x80) != 0
    }

    void setOorThreshold(double thresholdPa, double rangePa, int countLimit) throws Exception {
        int thr17 = (int) (thresholdPa * 64.0d) >> 7
        int oorThrP16 = (thr17 >> 16) & 0x01
        writeReg(REG_OOR_THR_P_LSB, thr17 & 0xFF)
        writeReg(REG_OOR_THR_P_MSB, (thr17 >> 8) & 0xFF)
        int range8 = ((int) (rangePa * 64.0d) >> 7) & 0xFF
        writeReg(REG_OOR_RANGE, range8)
        int cfg = ((countLimit & 0x3) << 6) | oorThrP16
        writeReg(REG_OOR_CONFIG, cfg)
    }

    int nvmRead(int row) throws Exception {
        int prev = pwrMode
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY)
        try {
            writeReg(REG_NVM_ADDR, 0x5D)
            writeReg(REG_CMD, 0xA5)
            Thread.sleep(2)
            writeReg(REG_NVM_ADDR, 0x40 | (row & 0x3F))
            writeReg(REG_CMD, 0xA5)
            Thread.sleep(2)
            byte[] buf = connection.writeRead([(byte) REG_NVM_DATA_LSB] as byte[], 2)
            return ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF)
        } finally {
            if (prev != MODE_STANDBY) setMode(prev)
        }
    }

    void nvmWrite(int row, int value) throws Exception {
        int prev = pwrMode
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY)
        try {
            writeReg(REG_NVM_ADDR, 0x40 | (row & 0x3F))
            writeReg(REG_NVM_DATA_LSB, value & 0xFF)
            writeReg(REG_NVM_DATA_MSB, (value >> 8) & 0xFF)
            writeReg(REG_NVM_ADDR, 0x5D)
            writeReg(REG_CMD, 0xA0)
            Thread.sleep(5)
        } finally {
            if (prev != MODE_STANDBY) setMode(prev)
        }
    }
}