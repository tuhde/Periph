package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * LPS28DFW — dual full-scale digital barometer (full driver).
 *
 * <p>Extends {@link Lps28dfwMinimal} with configuration, one-shot trigger,
 * one-point calibration offset, FIFO configuration / drain / level, and
 * pressure-threshold interrupt setup.
 */
public class Lps28dfwFull extends Lps28dfwMinimal {

    /** ODR code: power-down / one-shot. */
    public static final int ODR_POWER_DOWN = 0x00;
    /** ODR code: 1 Hz. */
    public static final int ODR_1_HZ   = 0x01;
    /** ODR code: 4 Hz. */
    public static final int ODR_4_HZ   = 0x02;
    /** ODR code: 10 Hz. */
    public static final int ODR_10_HZ  = 0x03;
    /** ODR code: 25 Hz (default). */
    public static final int ODR_25_HZ  = 0x04;
    /** ODR code: 50 Hz. */
    public static final int ODR_50_HZ  = 0x05;
    /** ODR code: 75 Hz. */
    public static final int ODR_75_HZ  = 0x06;
    /** ODR code: 100 Hz. */
    public static final int ODR_100_HZ = 0x07;
    /** ODR code: 200 Hz. */
    public static final int ODR_200_HZ = 0x08;

    /** Averaging: 4 samples. */
    public static final int AVG_4   = 0x00;
    /** Averaging: 8 samples. */
    public static final int AVG_8   = 0x01;
    /** Averaging: 16 samples. */
    public static final int AVG_16  = 0x02;
    /** Averaging: 32 samples. */
    public static final int AVG_32  = 0x03;
    /** Averaging: 64 samples. */
    public static final int AVG_64  = 0x04;
    /** Averaging: 128 samples. */
    public static final int AVG_128 = 0x05;
    /** Averaging: 512 samples. */
    public static final int AVG_512 = 0x07;

    /** IIR low-pass filter bandwidth: ODR/4. */
    public static final int LFPF_ODR_OVER_4 = 0;
    /** IIR low-pass filter bandwidth: ODR/9. */
    public static final int LFPF_ODR_OVER_9 = 1;

    /** FIFO mode: bypass. */
    public static final int FIFO_BYPASS               = 0;
    /** FIFO mode: FIFO. */
    public static final int FIFO_FIFO                 = 1;
    /** FIFO mode: continuous. */
    public static final int FIFO_CONTINUOUS           = 2;
    /** FIFO mode: bypass-to-FIFO. */
    public static final int FIFO_BYPASS_TO_FIFO       = 4;
    /** FIFO mode: bypass-to-continuous. */
    public static final int FIFO_BYPASS_TO_CONTINUOUS = 5;
    /** FIFO mode: continuous-to-FIFO. */
    public static final int FIFO_CONTINUOUS_TO_FIFO   = 6;

    /** STATUS flag: new pressure data available. */
    public static final int STATUS_P_DA = 0x01;
    /** STATUS flag: new temperature data available. */
    public static final int STATUS_T_DA = 0x02;
    /** STATUS flag: pressure overrun. */
    public static final int STATUS_P_OR = 0x10;
    /** STATUS flag: temperature overrun. */
    public static final int STATUS_T_OR = 0x20;

    private static final int REG_THS_P_L       = 0x0C;
    private static final int REG_THS_P_H       = 0x0D;
    private static final int REG_FIFO_CTRL     = 0x14;
    private static final int REG_FIFO_WTM      = 0x15;
    private static final int REG_RPDS_L        = 0x1A;
    private static final int REG_RPDS_H        = 0x1B;
    private static final int REG_FIFO_STATUS1  = 0x25;
    private static final int REG_FIFO_DATA_XL  = 0x78;

    /**
     * Construct the driver and apply the default configuration.
     *
     * @param connection I²C connection bound to address 0x5C
     * @throws IOException on I²C error or WHO_AM_I mismatch
     */
    public Lps28dfwFull(Connection connection) throws IOException {
        super(connection);
    }

    /**
     * Set output data rate, averaging, full-scale mode, and IIR filter.
     *
     * @param odr    output data rate code (0–8)
     * @param avg    averaging code (0–7)
     * @param fsMode 0=Mode 1, 1=Mode 2
     * @param lpfEn  true to enable the IIR low-pass filter
     * @param lpfCfg 0=ODR/4, 1=ODR/9
     * @throws IOException on I²C error
     */
    public void configure(int odr, int avg, int fsMode, boolean lpfEn, int lpfCfg) throws IOException {
        this.odr = odr;
        this.avg = avg;
        this.fsMode = fsMode;
        this.lpfEn = lpfEn ? 1 : 0;
        this.lpfCfg = lpfCfg;
        int ctrl2 = (fsMode << 6) | (lpfCfg << 5) | (lpfEn ? 1 : 0) << 4 | (bdu << 3);
        writeReg(REG_CTRL_REG2, ctrl2);
        int ctrl1 = (odr << 3) | (avg & 0x07);
        writeReg(REG_CTRL_REG1, ctrl1);
    }

    /**
     * Burst-read pressure and temperature.
     *
     * @return array of length 2: [pressure in hPa, temperature in °C]
     * @throws IOException on I²C error
     */
    public double[] read() throws IOException {
        byte[] b = connection.writeRead(new byte[]{REG_PRESS_OUT_XL}, 5);
        int p = ((b[2] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[0] & 0xFF);
        if ((p & 0x800000) != 0) p |= 0xFF000000;
        int t = (short) (((b[4] & 0xFF) << 8) | (b[3] & 0xFF));
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2;
        return new double[]{p / sens, t / 100.0};
    }

    /**
     * Trigger a one-shot measurement (with ODR=0000) and read the result.
     *
     * @return array of length 2: [pressure in hPa, temperature in °C]
     * @throws IOException on I²C error or timeout
     */
    public double[] readOneshot() throws IOException {
        byte[] saved = connection.writeRead(new byte[]{REG_CTRL_REG1}, 1);
        int savedOdr = (saved[0] & 0xFF) >> 3;
        writeReg(REG_CTRL_REG1, (avg & 0x07));
        byte[] c2 = connection.writeRead(new byte[]{REG_CTRL_REG2}, 1);
        writeReg(REG_CTRL_REG2, (c2[0] & 0xFF) | 0x01);
        for (int i = 0; i < 200; i++) {
            byte[] status = connection.writeRead(new byte[]{REG_STATUS}, 1);
            if ((status[0] & STATUS_P_DA) != 0) break;
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        double[] result = read();
        writeReg(REG_CTRL_REG1, (savedOdr << 3) | (avg & 0x07));
        return result;
    }

    /**
     * Return true if STATUS.P_DA is set (new pressure sample available).
     *
     * @return true if STATUS bit 0 is asserted
     * @throws IOException on I²C error
     */
    public boolean isDataReady() throws IOException {
        byte[] status = connection.writeRead(new byte[]{REG_STATUS}, 1);
        return (status[0] & STATUS_P_DA) != 0;
    }

    /**
     * Program the one-point calibration offset (RPDS).
     *
     * @param offsetHpa offset in hPa to subtract from subsequent pressure readings
     * @throws IOException on I²C error
     */
    public void setOffset(double offsetHpa) throws IOException {
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2;
        int raw = (int) (offsetHpa * sens);
        if (raw < 0) raw += 0x10000;
        writeReg(REG_RPDS_L, raw & 0xFF);
        writeReg(REG_RPDS_H, (raw >> 8) & 0xFF);
    }

    /**
     * Issue a software reset and wait for the chip to reboot (~2 ms).
     *
     * @throws IOException on I²C error
     */
    public void softreset() throws IOException {
        byte[] c2 = connection.writeRead(new byte[]{REG_CTRL_REG2}, 1);
        writeReg(REG_CTRL_REG2, (c2[0] & 0xFF) | 0x02);
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Configure FIFO mode, watermark level, and stop-on-watermark.
     *
     * @param mode       FIFO mode (0=bypass, 1=FIFO, 2=continuous; +4/+5/+6 for
     *                   triggered variants)
     * @param wtm        watermark level, 0–127
     * @param stopOnWtm  true to limit FIFO depth to the watermark
     * @throws IOException on I²C error
     */
    public void fifoConfigure(int mode, int wtm, boolean stopOnWtm) throws IOException {
        if (mode == FIFO_BYPASS) {
            writeReg(REG_FIFO_CTRL, 0x00);
        }
        int trig = mode >= 4 ? 1 : 0;
        int fMode = mode & 0x03;
        int ctrl = (trig << 2) | ((stopOnWtm ? 1 : 0) << 3) | fMode;
        writeReg(REG_FIFO_CTRL, ctrl);
        writeReg(REG_FIFO_WTM, wtm & 0x7F);
    }

    /**
     * Drain up to {@code count} pressure samples from the FIFO.
     *
     * @param count number of samples to read (capped at 128)
     * @return pressure values in hPa, most-recent last
     * @throws IOException on I²C error
     */
    public double[] fifoRead(int count) throws IOException {
        if (count <= 0) return new double[0];
        if (count > 128) count = 128;
        byte[] raw = connection.writeRead(new byte[]{REG_FIFO_DATA_XL}, count * 3);
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2;
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            int base = i * 3;
            int v = ((raw[base + 2] & 0xFF) << 16) | ((raw[base + 1] & 0xFF) << 8) | (raw[base] & 0xFF);
            if ((v & 0x800000) != 0) v |= 0xFF000000;
            out[i] = v / sens;
        }
        return out;
    }

    /**
     * Return the number of unread samples in the FIFO.
     *
     * @return 0 (empty) through 128 (full)
     * @throws IOException on I²C error
     */
    public int fifoLevel() throws IOException {
        byte[] b = connection.writeRead(new byte[]{REG_FIFO_STATUS1}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Program the pressure threshold and enable interrupt sources.
     *
     * @param thresholdHpa pressure threshold in hPa
     * @param high         true to assert PH when pressure exceeds threshold
     * @param low          true to assert PL when pressure falls below threshold
     * @throws IOException on I²C error
     */
    public void setThreshold(double thresholdHpa, boolean high, boolean low) throws IOException {
        double sens = (fsMode == 0) ? 16.0 : 8.0;
        int raw = (int) (thresholdHpa * sens);
        if (raw < 0) raw = 0;
        if (raw > 0x7FFF) raw = 0x7FFF;
        writeReg(REG_THS_P_L, raw & 0xFF);
        writeReg(REG_THS_P_H, (raw >> 8) & 0x7F);
        byte[] cfg = connection.writeRead(new byte[]{REG_INTERRUPT_CFG}, 1);
        int v = (cfg[0] & 0xFF) & ~0x03;
        if (high) v |= 0x01;
        if (low)  v |= 0x02;
        writeReg(REG_INTERRUPT_CFG, v);
    }

    /**
     * Read the WHO_AM_I register.
     *
     * @return chip ID; expect 0xB4 for LPS28DFW
     * @throws IOException on I²C error
     */
    public int chipId() throws IOException {
        byte[] b = connection.writeRead(new byte[]{REG_WHO_AM_I}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Compute altitude above sea level from the current pressure.
     *
     * @param seaLevelHpa reference sea-level pressure in hPa (default 1013.25)
     * @return altitude in metres
     * @throws IOException on I²C error
     */
    public double altitude(double seaLevelHpa) throws IOException {
        double p = readPressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelHpa, 1.0 / 5.255));
    }
}