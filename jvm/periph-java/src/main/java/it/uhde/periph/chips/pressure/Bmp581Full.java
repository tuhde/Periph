package it.uhde.periph.chips.pressure;

import java.io.IOException;

/**
 * BMP581 — full driver. Extends {@link Bmp581Minimal} with configuration,
 * FIFO, interrupts, OOR detection, and NVM access.
 */
public class Bmp581Full extends Bmp581Minimal {

    /** Pressure oversampling: ×1. */
    public static final int OSR_1X   = 0;
    /** Pressure oversampling: ×2. */
    public static final int OSR_2X   = 1;
    /** Pressure oversampling: ×4. */
    public static final int OSR_4X   = 2;
    /** Pressure oversampling: ×8. */
    public static final int OSR_8X   = 3;
    /** Pressure oversampling: ×16. */
    public static final int OSR_16X  = 4;
    /** Pressure oversampling: ×32. */
    public static final int OSR_32X  = 5;
    /** Pressure oversampling: ×64. */
    public static final int OSR_64X  = 6;
    /** Pressure oversampling: ×128. */
    public static final int OSR_128X = 7;

    /** Power mode: standby. */
    public static final int MODE_STANDBY    = 0;
    /** Power mode: normal (ODR-driven duty-cycled). */
    public static final int MODE_NORMAL     = 1;
    /** Power mode: forced (single-shot, returns to standby). */
    public static final int MODE_FORCED     = 2;
    /** Power mode: continuous. */
    public static final int MODE_CONTINUOUS = 3;

    /** IIR filter: bypass (no filter). */
    public static final int IIR_BYPASS    = 0;
    /** IIR filter: coefficient 1. */
    public static final int IIR_COEFF_1   = 1;
    /** IIR filter: coefficient 3. */
    public static final int IIR_COEFF_3   = 2;
    /** IIR filter: coefficient 7. */
    public static final int IIR_COEFF_7   = 3;
    /** IIR filter: coefficient 15. */
    public static final int IIR_COEFF_15  = 4;
    /** IIR filter: coefficient 31. */
    public static final int IIR_COEFF_31  = 5;
    /** IIR filter: coefficient 63. */
    public static final int IIR_COEFF_63  = 6;
    /** IIR filter: coefficient 127. */
    public static final int IIR_COEFF_127 = 7;

    /** FIFO frame selection: disabled. */
    public static final int FIFO_DISABLED = 0;
    /** FIFO frame selection: temperature only. */
    public static final int FIFO_TEMP     = 1;
    /** FIFO frame selection: pressure only. */
    public static final int FIFO_PRESS    = 2;
    /** FIFO frame selection: pressure + temperature. */
    public static final int FIFO_BOTH     = 3;

    /** FIFO mode: stream (overwrites oldest when full). */
    public static final int FIFO_STREAM       = 0;
    /** FIFO mode: stop-on-full. */
    public static final int FIFO_STOP_ON_FULL = 1;

    /** INT_SOURCE bit: data-ready. */
    public static final int INT_SOURCE_DRDY       = 0x01;
    /** INT_SOURCE bit: FIFO full. */
    public static final int INT_SOURCE_FIFO_FULL  = 0x02;
    /** INT_SOURCE bit: FIFO threshold. */
    public static final int INT_SOURCE_FIFO_THS   = 0x04;
    /** INT_SOURCE bit: pressure out-of-range. */
    public static final int INT_SOURCE_OOR_P      = 0x08;

    // Register addresses (Full-only)
    protected static final int REG_REV_ID       = 0x02;
    protected static final int REG_INT_SOURCE   = 0x15;
    protected static final int REG_INT_CONFIG   = 0x14;
    protected static final int REG_FIFO_SEL     = 0x18;
    protected static final int REG_FIFO_CONFIG  = 0x16;
    protected static final int REG_FIFO_COUNT   = 0x17;
    protected static final int REG_FIFO_DATA    = 0x29;
    protected static final int REG_DSP_CONFIG   = 0x30;
    protected static final int REG_DSP_IIR      = 0x31;
    protected static final int REG_OOR_THR_P_LSB = 0x32;
    protected static final int REG_OOR_THR_P_MSB = 0x33;
    protected static final int REG_OOR_RANGE    = 0x34;
    protected static final int REG_OOR_CONFIG   = 0x35;
    protected static final int REG_OSR_EFF      = 0x38;
    protected static final int REG_NVM_ADDR     = 0x2B;
    protected static final int REG_NVM_DATA_LSB = 0x2C;
    protected static final int REG_NVM_DATA_MSB = 0x2D;

    protected int osrP = 0;
    protected int osrT = 0;
    protected boolean pressEn = true;

    /** Construct at default address 0x46. */
    public Bmp581Full(it.uhde.periph.connection.Connection connection) throws IOException {
        super(connection, 0x46);
    }

    /** Construct at a given I²C address. */
    public Bmp581Full(it.uhde.periph.connection.Connection connection, int addr) throws IOException {
        super(connection, addr, BUS_I2C);
    }

    /** Construct with explicit bus type. */
    public Bmp581Full(it.uhde.periph.connection.Connection connection, int addr, int busType) throws IOException {
        super(connection, addr, busType);
    }

    /**
     * Write OSR_CONFIG and ODR_CONFIG atomically.
     *
     * @param odr     ODR field 0x00-0x1F (default 0x1C = 1 Hz).
     * @param osrP    Pressure oversampling 0-7.
     * @param osrT    Temperature oversampling 0-7.
     * @param pressEn Whether to enable pressure measurements.
     * @throws IOException on bus error
     */
    public void configure(int odr, int osrP, int osrT, boolean pressEn) throws IOException {
        this.odr = odr;
        this.osrP = osrP;
        this.osrT = osrT;
        this.pressEn = pressEn;
        int osr = (pressEn ? 0x40 : 0) | ((osrP & 0x7) << 3) | (osrT & 0x7);
        writeReg(REG_OSR_CONFIG, osr);
        int odrByte = ((odr & 0x1F) << 2) | (pwrMode & 0x3);
        writeReg(REG_ODR_CONFIG, odrByte);
    }

    /**
     * Set the power mode (preserves the current ODR setting).
     *
     * @param mode {@link #MODE_STANDBY}, {@link #MODE_NORMAL},
     *             {@link #MODE_FORCED}, or {@link #MODE_CONTINUOUS}.
     * @throws IOException on bus error
     */
    public void setMode(int mode) throws IOException {
        this.pwrMode = mode;
        int odrByte = ((odr & 0x1F) << 2) | (mode & 0x3);
        writeReg(REG_ODR_CONFIG, odrByte);
    }

    /**
     * Trigger a single FORCED measurement, wait for completion, and return
     * the readings.
     *
     * @return double array of length 2: {pressure_Pa, temperature_C}
     * @throws IOException on bus error
     */
    public double[] forced() throws IOException {
        int prev = pwrMode;
        if (prev != MODE_FORCED) setMode(MODE_FORCED);
        for (int i = 0; i < 400; i++) {
            byte[] st = connection.writeRead(new byte[]{(byte) REG_INT_STATUS}, 1);
            if ((st[0] & INT_STATUS_DRDY) != 0) break;
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return both();
    }

    /**
     * Compute altitude above sea level from the current pressure.
     *
     * @param seaLevelPa Reference pressure in Pa (default 101325).
     * @return altitude in metres
     * @throws IOException on bus error
     */
    public double altitude(double seaLevelPa) throws IOException {
        double p = pressure();
        if (p <= 0) return 0.0;
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255));
    }

    /** Issue a soft reset and re-initialise the chip. */
    public void softwareReset() throws IOException {
        try {
            writeReg(REG_CMD, SOFT_RESET);
        } catch (IOException e) {
            // expected NACK
        }
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        init(0x46);
        configure(odr, osrP, osrT, pressEn);
    }

    /** Read CHIP_ID. @return 0x50 for a genuine BMP581. */
    public int chipId() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_CHIP_ID}, 1);
        return buf[0] & 0xFF;
    }

    /** Read REV_ID. @return ASIC revision identifier. */
    public int revId() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_REV_ID}, 1);
        return buf[0] & 0xFF;
    }

    /** Read STATUS. @return raw status byte. */
    public int status() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_STATUS}, 1);
        return buf[0] & 0xFF;
    }

    /** Read INT_STATUS (clear-on-read). @return raw interrupt status byte. */
    public int interruptStatus() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_INT_STATUS}, 1);
        return buf[0] & 0xFF;
    }

    /**
     * Check whether a new data sample is available.
     *
     * @return true if drdy_data_reg is set
     * @throws IOException on bus error
     */
    public boolean dataReady() throws IOException {
        return (interruptStatus() & INT_STATUS_DRDY) != 0;
    }

    /**
     * Configure INT pin: latching, polarity, drive mode, pin enable.
     *
     * @param mode      0 = pulsed, 1 = latched.
     * @param polarity  0 = active-low, 1 = active-high.
     * @param openDrain true for open-drain output.
     * @param enable    true to enable the INT pin driver.
     * @throws IOException on bus error
     */
    public void configureInterrupt(int mode, int polarity, boolean openDrain, boolean enable) throws IOException {
        int val = enable ? 0x08 : 0;
        if (openDrain) val |= 0x04;
        if (polarity != 0) val |= 0x02;
        if (mode != 0) val |= 0x01;
        writeReg(REG_INT_CONFIG, val);
    }

    private void setIntSource(int source, boolean enable) throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_INT_SOURCE}, 1);
        int cur = buf[0] & 0xFF;
        int next = enable ? (cur | source) : (cur & ~source);
        writeReg(REG_INT_SOURCE, next);
    }

    /** Enable or disable the data-ready interrupt source. */
    public void enableDrdyInterrupt(boolean enable) throws IOException {
        setIntSource(INT_SOURCE_DRDY, enable);
    }

    /**
     * Enable or disable FIFO threshold and FIFO-full interrupt sources.
     *
     * @param threshold true to enable threshold interrupt.
     * @param full      true to enable full interrupt.
     * @throws IOException on bus error
     */
    public void enableFifoInterrupt(boolean threshold, boolean full) throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_INT_SOURCE}, 1);
        int cur = buf[0] & 0xFF;
        cur &= ~(INT_SOURCE_FIFO_FULL | INT_SOURCE_FIFO_THS);
        if (threshold) cur |= INT_SOURCE_FIFO_THS;
        if (full) cur |= INT_SOURCE_FIFO_FULL;
        writeReg(REG_INT_SOURCE, cur);
    }

    /** Enable or disable the pressure out-of-range interrupt source. */
    public void enableOorInterrupt(boolean enable) throws IOException {
        setIntSource(INT_SOURCE_OOR_P, enable);
    }

    /**
     * Set IIR filter coefficients for pressure and temperature.
     *
     * <p>Also sets shdw_sel_iir_p/t in DSP_CONFIG so the data registers hold
     * post-IIR values.
     *
     * @param coeffP pressure filter coefficient 0-7
     * @param coeffT temperature filter coefficient 0-7
     * @throws IOException on bus error
     */
    public void setIirFilter(int coeffP, int coeffT) throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_DSP_CONFIG}, 1);
        int dsp = (buf[0] & 0xFF) | 0x28;
        writeReg(REG_DSP_CONFIG, dsp);
        int iirVal = ((coeffP & 0x7) << 3) | (coeffT & 0x7);
        writeReg(REG_DSP_IIR, iirVal);
    }

    /**
     * Configure FIFO source, mode, and threshold. Must be called in STANDBY mode.
     *
     * @param frameSel  {@link #FIFO_DISABLED}, {@link #FIFO_TEMP},
     *                  {@link #FIFO_PRESS}, or {@link #FIFO_BOTH}.
     * @param mode      {@link #FIFO_STREAM} or {@link #FIFO_STOP_ON_FULL}.
     * @param threshold 0-31 frames (0 = disabled).
     * @throws IOException on bus error
     */
    public void configureFifo(int frameSel, int mode, int threshold) throws IOException {
        int prev = pwrMode;
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY);
        writeReg(REG_FIFO_SEL, frameSel & 0x3);
        int cfg = ((mode & 0x1) << 5) | (threshold & 0x1F);
        writeReg(REG_FIFO_CONFIG, cfg);
        if (prev != MODE_STANDBY) setMode(prev);
    }

    /** Read the number of frames currently in the FIFO. @return frame count 0-32. */
    public int fifoCount() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_FIFO_COUNT}, 1);
        return buf[0] & 0x3F;
    }

    /**
     * Read OSR_EFF.
     *
     * @return int array of length 2: {osrP_eff, osrT_eff}
     * @throws IOException on bus error
     */
    public int[] effectiveOsr() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_OSR_EFF}, 1);
        return new int[]{(buf[0] >> 3) & 0x7, buf[0] & 0x7};
    }

    /**
     * Check whether the current ODR/OSR combination is valid.
     *
     * @return true if odr_is_valid bit is set
     * @throws IOException on bus error
     */
    public boolean odrIsValid() throws IOException {
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_OSR_EFF}, 1);
        return (buf[0] & 0x80) != 0;
    }

    /**
     * Configure the out-of-range pressure detector.
     *
     * @param thresholdPa pressure threshold in Pa
     * @param rangePa     symmetric +/- window around the threshold in Pa
     * @param countLimit  0-3 successive over-threshold events required
     * @throws IOException on bus error
     */
    public void setOorThreshold(double thresholdPa, double rangePa, int countLimit) throws IOException {
        int thr17 = (int) (thresholdPa * 64.0) >> 7;
        int oorThrP16 = (thr17 >> 16) & 0x01;
        writeReg(REG_OOR_THR_P_LSB, thr17 & 0xFF);
        writeReg(REG_OOR_THR_P_MSB, (thr17 >> 8) & 0xFF);
        int range8 = ((int) (rangePa * 64.0) >> 7) & 0xFF;
        writeReg(REG_OOR_RANGE, range8);
        int cfg = ((countLimit & 0x3) << 6) | oorThrP16;
        writeReg(REG_OOR_CONFIG, cfg);
    }

    /**
     * Read one user NVM row.
     *
     * @param row NVM row address 0x20-0x22.
     * @return 16-bit value stored in the row.
     * @throws IOException on bus error
     */
    public int nvmRead(int row) throws IOException {
        int prev = pwrMode;
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY);
        try {
            writeReg(REG_NVM_ADDR, 0x5D);
            writeReg(REG_CMD, 0xA5);
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            writeReg(REG_NVM_ADDR, 0x40 | (row & 0x3F));
            writeReg(REG_CMD, 0xA5);
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] buf = connection.writeRead(new byte[]{(byte) REG_NVM_DATA_LSB}, 2);
            return ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
        } finally {
            if (prev != MODE_STANDBY) setMode(prev);
        }
    }

    /**
     * Write one user NVM row. Limited to 10,000 total write cycles.
     *
     * @param row   NVM row address 0x20-0x22.
     * @param value 16-bit value to store.
     * @throws IOException on bus error
     */
    public void nvmWrite(int row, int value) throws IOException {
        int prev = pwrMode;
        if (prev != MODE_STANDBY) setMode(MODE_STANDBY);
        try {
            writeReg(REG_NVM_ADDR, 0x40 | (row & 0x3F));
            writeReg(REG_NVM_DATA_LSB, value & 0xFF);
            writeReg(REG_NVM_DATA_MSB, (value >> 8) & 0xFF);
            writeReg(REG_NVM_ADDR, 0x5D);
            writeReg(REG_CMD, 0xA0);
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } finally {
            if (prev != MODE_STANDBY) setMode(prev);
        }
    }
}