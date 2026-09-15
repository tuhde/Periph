package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic

/**
 * LPS28DFW — dual full-scale digital barometer (full driver).
 *
 * Extends [Lps28dfwMinimal] with configuration, one-shot trigger, one-point
 * calibration offset, FIFO configuration / drain / level, and pressure-threshold
 * interrupt setup.
 */
@CompileStatic
class Lps28dfwFull extends Lps28dfwMinimal {

    public static final int ODR_POWER_DOWN = 0x00
    public static final int ODR_1_HZ   = 0x01
    public static final int ODR_4_HZ   = 0x02
    public static final int ODR_10_HZ  = 0x03
    public static final int ODR_25_HZ  = 0x04
    public static final int ODR_50_HZ  = 0x05
    public static final int ODR_75_HZ  = 0x06
    public static final int ODR_100_HZ = 0x07
    public static final int ODR_200_HZ = 0x08

    public static final int AVG_4   = 0x00
    public static final int AVG_8   = 0x01
    public static final int AVG_16  = 0x02
    public static final int AVG_32  = 0x03
    public static final int AVG_64  = 0x04
    public static final int AVG_128 = 0x05
    public static final int AVG_512 = 0x07

    public static final int LFPF_ODR_OVER_4 = 0
    public static final int LFPF_ODR_OVER_9 = 1

    public static final int FIFO_BYPASS               = 0
    public static final int FIFO_FIFO                 = 1
    public static final int FIFO_CONTINUOUS           = 2
    public static final int FIFO_BYPASS_TO_FIFO       = 4
    public static final int FIFO_BYPASS_TO_CONTINUOUS = 5
    public static final int FIFO_CONTINUOUS_TO_FIFO   = 6

    public static final int STATUS_P_DA = 0x01
    public static final int STATUS_T_DA = 0x02
    public static final int STATUS_P_OR = 0x10
    public static final int STATUS_T_OR = 0x20

    private static final int REG_THS_P_L      = 0x0C
    private static final int REG_THS_P_H      = 0x0D
    private static final int REG_FIFO_CTRL    = 0x14
    private static final int REG_FIFO_WTM     = 0x15
    private static final int REG_RPDS_L       = 0x1A
    private static final int REG_RPDS_H       = 0x1B
    private static final int REG_FIFO_STATUS1 = 0x25
    private static final int REG_FIFO_DATA_XL = 0x78

    Lps28dfwFull(it.uhde.periph.connection.Connection connection) {
        super(connection)
    }

    /** Set output data rate, averaging, full-scale mode, and IIR filter. */
    void configure(int odr, int avg, int fsMode, boolean lpfEn, int lpfCfg) {
        this.odr = odr
        this.avg = avg
        this.fsMode = fsMode
        this.lpfEn = lpfEn ? 1 : 0
        this.lpfCfg = lpfCfg
        int ctrl2 = (fsMode << 6) | (lpfCfg << 5) | (this.lpfEn << 4) | (bdu << 3)
        writeReg(REG_CTRL_REG2, ctrl2)
        int ctrl1 = (odr << 3) | (avg & 0x07)
        writeReg(REG_CTRL_REG1, ctrl1)
    }

    /** Burst-read pressure and temperature. */
    double[] read() {
        byte[] b = connection.writeRead([REG_PRESS_OUT_XL] as byte[], 5)
        int p = ((b[2] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[0] & 0xFF)
        if ((p & 0x800000) != 0) p = (int) (p | 0xFF000000L)
        int t = (short) (((b[4] & 0xFF) << 8) | (b[3] & 0xFF))
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2
        return [p / sens, t / 100.0d] as double[]
    }

    /** True if STATUS.P_DA is set. */
    boolean isDataReady() {
        byte[] status = connection.writeRead([REG_STATUS] as byte[], 1)
        return (status[0] & STATUS_P_DA) != 0
    }

    /** Trigger a one-shot measurement (with ODR=0000) and read the result. */
    double[] readOneshot() {
        byte[] saved = connection.writeRead([REG_CTRL_REG1] as byte[], 1)
        int savedOdr = (saved[0] & 0xFF) >> 3
        writeReg(REG_CTRL_REG1, avg & 0x07)
        byte[] c2 = connection.writeRead([REG_CTRL_REG2] as byte[], 1)
        writeReg(REG_CTRL_REG2, (c2[0] & 0xFF) | 0x01)
        for (int i = 0; i < 200; i++) {
            byte[] status = connection.writeRead([REG_STATUS] as byte[], 1)
            if ((status[0] & STATUS_P_DA) != 0) break
            try { Thread.sleep(5) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
        }
        double[] result = read()
        writeReg(REG_CTRL_REG1, (savedOdr << 3) | (avg & 0x07))
        return result
    }

    /** Program the one-point calibration offset (RPDS). */
    void setOffset(double offsetHpa) {
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2
        int raw = (int) (offsetHpa * sens)
        if (raw < 0) raw += 0x10000
        writeReg(REG_RPDS_L, raw & 0xFF)
        writeReg(REG_RPDS_H, (raw >> 8) & 0xFF)
    }

    /** Issue a software reset and wait for the chip to reboot (~2 ms). */
    void softreset() {
        byte[] c2 = connection.writeRead([REG_CTRL_REG2] as byte[], 1)
        writeReg(REG_CTRL_REG2, (c2[0] & 0xFF) | 0x02)
        try { Thread.sleep(2) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
    }

    /** Configure FIFO mode, watermark level, and stop-on-watermark. */
    void fifoConfigure(int mode, int wtm, boolean stopOnWtm) {
        if (mode == FIFO_BYPASS) {
            writeReg(REG_FIFO_CTRL, 0x00)
        }
        int trig = mode >= 4 ? 1 : 0
        int fMode = mode & 0x03
        int ctrl = (trig << 2) | ((stopOnWtm ? 1 : 0) << 3) | fMode
        writeReg(REG_FIFO_CTRL, ctrl)
        writeReg(REG_FIFO_WTM, wtm & 0x7F)
    }

    /** Drain up to count pressure samples from the FIFO. */
    double[] fifoRead(int count) {
        if (count <= 0) return new double[0]
        int n = count > 128 ? 128 : count
        byte[] raw = connection.writeRead([REG_FIFO_DATA_XL] as byte[], n * 3)
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2
        double[] out = new double[n]
        for (int i = 0; i < n; i++) {
            int base = i * 3
            int v = ((raw[base + 2] & 0xFF) << 16) | ((raw[base + 1] & 0xFF) << 8) | (raw[base] & 0xFF)
            if ((v & 0x800000) != 0) v = (int) (v | 0xFF000000L)
            out[i] = v / sens
        }
        return out
    }

    /** Return the number of unread samples in the FIFO. */
    int fifoLevel() {
        byte[] b = connection.writeRead([REG_FIFO_STATUS1] as byte[], 1)
        return b[0] & 0xFF
    }

    /** Program the pressure threshold and enable interrupt sources. */
    void setThreshold(double thresholdHpa, boolean high, boolean low) {
        double sens = (fsMode == 0) ? 16.0d : 8.0d
        int raw = (int) (thresholdHpa * sens)
        if (raw < 0) raw = 0
        if (raw > 0x7FFF) raw = 0x7FFF
        writeReg(REG_THS_P_L, raw & 0xFF)
        writeReg(REG_THS_P_H, (raw >> 8) & 0x7F)
        byte[] cfg = connection.writeRead([REG_INTERRUPT_CFG] as byte[], 1)
        int v = (cfg[0] & 0xFF) & ~0x03
        if (high) v |= 0x01
        if (low)  v |= 0x02
        writeReg(REG_INTERRUPT_CFG, v)
    }

    /** Read the WHO_AM_I register. */
    int chipId() {
        byte[] b = connection.writeRead([REG_WHO_AM_I] as byte[], 1)
        return b[0] & 0xFF
    }

    /** Compute altitude above sea level from the current pressure. */
    double altitude(double seaLevelHpa = 1013.25d) {
        double p = readPressure()
        return 44330.0d * (1.0d - Math.pow(p / seaLevelHpa, 1.0d / 5.255d))
    }
}