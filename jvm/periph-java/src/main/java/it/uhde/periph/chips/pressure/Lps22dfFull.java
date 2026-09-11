package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * LPS22DF full driver — extends {@link Lps22dfMinimal} with configuration,
 * threshold/offset calibration, FIFO, interrupts, and AUTOZERO/AUTOREFP.
 */
public class Lps22dfFull extends Lps22dfMinimal {

    /** Output data rate: power-down. */
    public static final int ODR_POWER_DOWN = 0;
    /** Output data rate: 1 Hz. */
    public static final int ODR_1_HZ       = 1;
    /** Output data rate: 4 Hz. */
    public static final int ODR_4_HZ       = 2;
    /** Output data rate: 10 Hz. */
    public static final int ODR_10_HZ      = 3;
    /** Output data rate: 25 Hz. */
    public static final int ODR_25_HZ      = 4;
    /** Output data rate: 50 Hz. */
    public static final int ODR_50_HZ      = 5;
    /** Output data rate: 75 Hz. */
    public static final int ODR_75_HZ      = 6;
    /** Output data rate: 100 Hz. */
    public static final int ODR_100_HZ     = 7;
    /** Output data rate: 200 Hz. */
    public static final int ODR_200_HZ     = 8;

    /** Averaging filter: 4 samples. */
    public static final int AVG_4   = 0;
    /** Averaging filter: 8 samples. */
    public static final int AVG_8   = 1;
    /** Averaging filter: 16 samples. */
    public static final int AVG_16  = 2;
    /** Averaging filter: 32 samples. */
    public static final int AVG_32  = 3;
    /** Averaging filter: 64 samples. */
    public static final int AVG_64  = 4;
    /** Averaging filter: 128 samples. */
    public static final int AVG_128 = 5;
    /** Averaging filter: 512 samples. */
    public static final int AVG_512 = 7;

    /** FIFO mode: bypass. */
    public static final int FIFO_BYPASS         = 0;
    /** FIFO mode: FIFO. */
    public static final int FIFO_FIFO           = 1;
    /** FIFO mode: continuous. */
    public static final int FIFO_CONTINUOUS     = 2;
    /** FIFO mode: bypass-to-FIFO. */
    public static final int FIFO_BYPASS_TO_FIFO = 3;
    /** FIFO mode: bypass-to-continuous. */
    public static final int FIFO_BYPASS_TO_CONT = 4;
    /** FIFO mode: continuous-to-FIFO. */
    public static final int FIFO_CONT_TO_FIFO   = 5;

    /** Construct at default address 0x5C. */
    public Lps22dfFull(Connection connection) throws IOException {
        super(connection);
    }

    /** Construct at given address. */
    public Lps22dfFull(Connection connection, int addr) throws IOException {
        super(connection, addr, BUS_I2C);
    }

    /** Construct at given address and bus type. */
    public Lps22dfFull(Connection connection, int addr, int busType) throws IOException {
        super(connection, addr, busType);
    }

    /**
     * Write CTRL_REG1 and CTRL_REG2.
     *
     * @param odr      output data rate (0=power-down, 1..7=1..100 Hz, 8=200 Hz)
     * @param avg      averaging filter (0=4, 1=8, 2=16, 3=32, 4=64, 5=128, 7=512)
     * @param enLpfp   enable low-pass filter on pressure output
     * @param lfpfCfg  0=ODR/4 cutoff, 1=ODR/9 cutoff
     * @param bdu      block data update
     * @throws IOException on I²C error
     */
    public void configure(int odr, int avg, boolean enLpfp, int lfpfCfg, boolean bdu) throws IOException {
        int ctrl1 = ((odr & 0x0F) << 3) | (avg & 0x07);
        int ctrl2 = 0;
        if (enLpfp)      ctrl2 |= 0x10;
        if (lfpfCfg != 0) ctrl2 |= 0x20;
        if (bdu)         ctrl2 |= 0x08;
        writeReg(REG_CTRL_REG1, ctrl1);
        writeReg(REG_CTRL_REG2, ctrl2);
    }

    /** Trigger a single measurement in power-down mode; blocks until P_DA. */
    public void oneshot() throws IOException {
        writeReg(REG_CTRL_REG1, 0x00);
        writeReg(REG_CTRL_REG2, 0x08 | 0x01);
        waitPDa();
    }

    /**
     * Compute altitude above sea level from the current pressure.
     *
     * @param seaLevelPa reference sea-level pressure in pascals (default 101325)
     * @return altitude in metres
     * @throws IOException on I²C error
     */
    public double altitude(double seaLevelPa) throws IOException {
        double p = pressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255));
    }

    /** Software-reset the chip and wait for self-clear. */
    public void softwareReset() throws IOException {
        writeReg(REG_CTRL_REG2, 0x04);
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Write a one-point calibration offset.
     *
     * @param offsetPa offset in pascals (signed; persisted in NVM)
     * @throws IOException on I²C error
     */
    public void setPressureOffset(double offsetPa) throws IOException {
        double offsetHpa = offsetPa / 100.0;
        int raw = (int) Math.round(offsetHpa * 4096.0);
        if (raw < 0) raw += 0x10000;
        writeReg(REG_RPDS_L, raw & 0xFF);
        writeReg(REG_RPDS_H, (raw >> 8) & 0xFF);
    }

    /**
     * Write a 15-bit unsigned pressure threshold.
     *
     * @param thresholdPa threshold in pascals
     * @throws IOException on I²C error
     */
    public void setPressureThreshold(double thresholdPa) throws IOException {
        double thresholdHpa = thresholdPa / 100.0;
        int raw = ((int) Math.round(thresholdHpa * 16.0)) & 0x7FFF;
        writeReg(REG_THS_P_L, raw & 0xFF);
        writeReg(REG_THS_P_H, (raw >> 8) & 0xFF);
    }

    /** Configure the INT pin and routing. */
    public void configureInterrupt(boolean intHL, boolean ppOd, boolean drdy, boolean drdyPls,
                                  boolean intEn, boolean intFWtm, boolean intFFull, boolean intFOvr) throws IOException {
        int ctrl3 = 0x01;  // IF_ADD_INC=1
        if (intHL) ctrl3 |= 0x08;
        if (ppOd)  ctrl3 |= 0x02;
        int ctrl4 = 0;
        if (drdyPls)   ctrl4 |= 0x40;
        if (drdy)      ctrl4 |= 0x20;
        if (intEn)     ctrl4 |= 0x10;
        if (intFFull)  ctrl4 |= 0x04;
        if (intFWtm)   ctrl4 |= 0x02;
        if (intFOvr)   ctrl4 |= 0x01;
        writeReg(REG_CTRL_REG3, ctrl3);
        writeReg(REG_CTRL_REG4, ctrl4);
    }

    /** Configure pressure-event interrupts. */
    public void configurePressureEvent(boolean phe, boolean ple, boolean lir) throws IOException {
        int cfg = 0;
        if (phe) cfg |= 0x01;
        if (ple) cfg |= 0x02;
        if (lir) cfg |= 0x04;
        writeReg(REG_INTERRUPT_CFG, cfg);
    }

    /** Capture the current pressure as the AUTOZERO reference. */
    public void autozero() throws IOException {
        writeReg(REG_INTERRUPT_CFG, 0x20);
    }

    /** Capture the current pressure in REF_P for use as a comparator. */
    public void autorefp() throws IOException {
        writeReg(REG_INTERRUPT_CFG, 0x80);
    }

    /** Reset both AUTOZERO and AUTOREFP, returning PRESS_OUT to absolute. */
    public void resetReference() throws IOException {
        writeReg(REG_INTERRUPT_CFG, 0x50);
    }

    /** Read the stored AUTOZERO/AUTOREFP reference pressure in pascals. */
    public double referencePressure() throws IOException {
        byte[] raw = readReg(REG_REF_P_L, 2);
        short s = (short)(((raw[0] & 0xFF) << 0) | ((raw[1] & 0xFF) << 8));
        return (s / 4096.0) * 100.0;
    }

    /**
     * Set the FIFO mode.
     *
     * @param mode 0=bypass, 1=FIFO, 2=continuous, 3=bypass-to-FIFO,
     *             4=bypass-to-continuous, 5=continuous-to-FIFO
     * @throws IOException on I²C error
     */
    public void setFifoMode(int mode) throws IOException {
        int trig, fm;
        switch (mode) {
            case 0: trig = 0; fm = 0; break;
            case 1: trig = 0; fm = 1; break;
            case 2: trig = 0; fm = 2; break;
            case 3: trig = 1; fm = 1; break;
            case 4: trig = 1; fm = 2; break;
            default: trig = 1; fm = 3; break;
        }
        writeReg(REG_FIFO_CTRL, (trig << 2) | (fm & 0x03));
    }

    /** Set the FIFO watermark level (0..127). */
    public void setFifoWatermark(int level) throws IOException {
        writeReg(REG_FIFO_WTM, level & 0x7F);
    }

    /** Read the FIFO sample count. */
    public int fifoSampleCount() throws IOException {
        byte[] v = readReg(REG_FIFO_STATUS1, 1);
        return v[0] & 0xFF;
    }

    /**
     * Read every available FIFO sample.
     *
     * @param out destination array; one double per sample, in pascals
     * @return number of samples written into out
     * @throws IOException on I²C error
     */
    public int readFifo(double[] out) throws IOException {
        int count = fifoSampleCount();
        if (count == 0) return 0;
        int n = Math.min(count, out.length);
        byte[] raw = readReg(REG_FIFO_PRESS_XL, n * 3);
        for (int i = 0; i < n; i++) {
            int base = i * 3;
            int value = (raw[base] & 0xFF) | ((raw[base + 1] & 0xFF) << 8) | ((raw[base + 2] & 0xFF) << 16);
            if ((value & 0x800000) != 0) value -= 0x1000000;
            out[i] = (value / 4096.0) * 100.0;
        }
        return n;
    }

    /** Read and clear the INT_SOURCE register. */
    public int interruptSource() throws IOException {
        byte[] v = readReg(REG_INT_SOURCE, 1);
        return v[0] & 0xFF;
    }
}