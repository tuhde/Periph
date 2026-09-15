package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection

import groovy.transform.CompileStatic

/**
 * LPS22DF full driver — extends {@link Lps22dfMinimal} with configuration,
 * threshold/offset calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.
 */
@CompileStatic
class Lps22dfFull extends Lps22dfMinimal {

    public static final int ODR_POWER_DOWN = 0
    public static final int ODR_1_HZ       = 1
    public static final int ODR_4_HZ       = 2
    public static final int ODR_10_HZ      = 3
    public static final int ODR_25_HZ      = 4
    public static final int ODR_50_HZ      = 5
    public static final int ODR_75_HZ      = 6
    public static final int ODR_100_HZ     = 7
    public static final int ODR_200_HZ     = 8

    public static final int AVG_4   = 0
    public static final int AVG_8   = 1
    public static final int AVG_16  = 2
    public static final int AVG_32  = 3
    public static final int AVG_64  = 4
    public static final int AVG_128 = 5
    public static final int AVG_512 = 7

    public static final int FIFO_BYPASS         = 0
    public static final int FIFO_FIFO           = 1
    public static final int FIFO_CONTINUOUS     = 2
    public static final int FIFO_BYPASS_TO_FIFO = 3
    public static final int FIFO_BYPASS_TO_CONT = 4
    public static final int FIFO_CONT_TO_FIFO   = 5

    Lps22dfFull(Connection connection) {
        super(connection)
    }

    Lps22dfFull(Connection connection, int addr) {
        super(connection, addr, BUS_I2C)
    }

    Lps22dfFull(Connection connection, int addr, int busType) {
        super(connection, addr, busType)
    }

    void configure(int odr, int avg, boolean enLpfp, int lfpfCfg, boolean bdu) {
        int ctrl1 = ((odr & 0x0F) << 3) | (avg & 0x07)
        int ctrl2 = 0
        if (enLpfp)      ctrl2 |= 0x10
        if (lfpfCfg != 0) ctrl2 |= 0x20
        if (bdu)         ctrl2 |= 0x08
        writeReg(REG_CTRL_REG1, ctrl1)
        writeReg(REG_CTRL_REG2, ctrl2)
    }

    void oneshot() {
        writeReg(REG_CTRL_REG1, 0x00)
        writeReg(REG_CTRL_REG2, 0x08 | 0x01)
        waitPDa()
    }

    double altitude(double seaLevelPa = 101325.0d) {
        double p = pressure()
        return 44330.0d * (1.0d - Math.pow(p / seaLevelPa, 1.0d / 5.255d))
    }

    void softwareReset() {
        writeReg(REG_CTRL_REG2, 0x04)
        Thread.sleep(1)
    }

    void setPressureOffset(double offsetPa) {
        double offsetHpa = offsetPa / 100.0d
        int raw = (int) Math.round(offsetHpa * 4096.0d)
        if (raw < 0) raw += 0x10000
        writeReg(REG_RPDS_L, raw & 0xFF)
        writeReg(REG_RPDS_H, (raw >> 8) & 0xFF)
    }

    void setPressureThreshold(double thresholdPa) {
        double thresholdHpa = thresholdPa / 100.0d
        int raw = ((int) Math.round(thresholdHpa * 16.0d)) & 0x7FFF
        writeReg(REG_THS_P_L, raw & 0xFF)
        writeReg(REG_THS_P_H, (raw >> 8) & 0xFF)
    }

    void configureInterrupt(boolean intHL, boolean ppOd, boolean drdy, boolean drdyPls,
                            boolean intEn, boolean intFWtm, boolean intFFull, boolean intFOvr) {
        int ctrl3 = 0x01
        if (intHL) ctrl3 |= 0x08
        if (ppOd)  ctrl3 |= 0x02
        int ctrl4 = 0
        if (drdyPls)  ctrl4 |= 0x40
        if (drdy)     ctrl4 |= 0x20
        if (intEn)    ctrl4 |= 0x10
        if (intFFull) ctrl4 |= 0x04
        if (intFWtm)  ctrl4 |= 0x02
        if (intFOvr)  ctrl4 |= 0x01
        writeReg(REG_CTRL_REG3, ctrl3)
        writeReg(REG_CTRL_REG4, ctrl4)
    }

    void configurePressureEvent(boolean phe, boolean ple, boolean lir) {
        int cfg = 0
        if (phe) cfg |= 0x01
        if (ple) cfg |= 0x02
        if (lir) cfg |= 0x04
        writeReg(REG_INTERRUPT_CFG, cfg)
    }

    void autozero()     { writeReg(REG_INTERRUPT_CFG, 0x20) }
    void autorefp()     { writeReg(REG_INTERRUPT_CFG, 0x80) }
    void resetReference() { writeReg(REG_INTERRUPT_CFG, 0x50) }

    double referencePressure() {
        byte[] raw = readReg(REG_REF_P_L, 2)
        short s = (short)(((raw[0] & 0xFF) << 0) | ((raw[1] & 0xFF) << 8))
        return (s / 4096.0d) * 100.0d
    }

    void setFifoMode(int mode) {
        int trig, fm
        switch (mode) {
            case 0: trig = 0; fm = 0; break
            case 1: trig = 0; fm = 1; break
            case 2: trig = 0; fm = 2; break
            case 3: trig = 1; fm = 1; break
            case 4: trig = 1; fm = 2; break
            default: trig = 1; fm = 3; break
        }
        writeReg(REG_FIFO_CTRL, (trig << 2) | (fm & 0x03))
    }

    void setFifoWatermark(int level) {
        writeReg(REG_FIFO_WTM, level & 0x7F)
    }

    int fifoSampleCount() {
        byte[] v = readReg(REG_FIFO_STATUS1, 1)
        return v[0] & 0xFF
    }

    int readFifo(double[] out) {
        int count = fifoSampleCount()
        if (count == 0) return 0
        int n = Math.min(count, out.length)
        byte[] raw = readReg(REG_FIFO_PRESS_XL, n * 3)
        for (int i = 0; i < n; i++) {
            int base = i * 3
            int value = (raw[base] & 0xFF) | ((raw[base + 1] & 0xFF) << 8) | ((raw[base + 2] & 0xFF) << 16)
            if ((value & 0x800000) != 0) value -= 0x1000000
            out[i] = (value / 4096.0d) * 100.0d
        }
        return n
    }

    int interruptSource() {
        byte[] v = readReg(REG_INT_SOURCE, 1)
        return v[0] & 0xFF
    }
}