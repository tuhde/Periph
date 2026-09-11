package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * ADXL345 full interface — extends {@link Adxl345Minimal} with configuration,
 * FIFO, tap / activity / inactivity / free-fall detection, and interrupt routing.
 *
 * <p>Adds range and data-rate selection, low-power mode, self-test, per-axis
 * offset calibration (in <i>g</i>), single/double-tap detection, activity and
 * inactivity detection, free-fall detection, 32-level FIFO, interrupt routing
 * (INT1 / INT2), and sleep / auto-sleep / link mode.
 */
public class Adxl345Full extends Adxl345Minimal {

    // Interrupt source bits — match INT_ENABLE / INT_MAP / INT_SOURCE layout.
    public static final int INT_DATA_READY  = 0x80;
    public static final int INT_SINGLE_TAP  = 0x40;
    public static final int INT_DOUBLE_TAP  = 0x20;
    public static final int INT_ACTIVITY    = 0x10;
    public static final int INT_INACTIVITY  = 0x08;
    public static final int INT_FREE_FALL   = 0x04;
    public static final int INT_WATERMARK   = 0x02;
    public static final int INT_OVERRUN     = 0x01;

    // FIFO mode values — match FIFO_CTL bits 7:6.
    public static final int FIFO_BYPASS  = 0x00;
    public static final int FIFO_FIFO    = 0x40;
    public static final int FIFO_STREAM  = 0x80;
    public static final int FIFO_TRIGGER = 0xC0;

    // Sleep-mode wakeup sample rates (POWER_CTL Wakeup bits 2:1).
    public static final int WAKEUP_8_HZ = 0x00;
    public static final int WAKEUP_4_HZ = 0x02;
    public static final int WAKEUP_2_HZ = 0x04;
    public static final int WAKEUP_1_HZ = 0x06;

    /**
     * @param connection configured I²C or SPI connection
     * @throws IOException on bus error or wrong DEVID
     */
    public Adxl345Full(Connection connection) throws IOException {
        super(connection, BUS_I2C);
    }

    /**
     * @param connection configured I²C or SPI connection
     * @param busType    {@link #BUS_I2C} or {@link #BUS_SPI}
     * @throws IOException on bus error or wrong DEVID
     */
    public Adxl345Full(Connection connection, int busType) throws IOException {
        super(connection, busType);
    }

    /** Set the measurement range to ±2/±4/±8/±16 g. FULL_RES is preserved. */
    public void setRange(int rangeG) throws IOException {
        int code;
        switch (rangeG) {
            case 2:  code = 0; break;
            case 4:  code = 1; break;
            case 8:  code = 2; break;
            case 16: code = 3; break;
            default: return;
        }
        this.rangeBits = code;
        int df = readReg(REG_DATA_FORMAT);
        df = (df & ~0x03) | (code & 0x03);
        if (fullRes) df |= 0x08;
        writeReg(REG_DATA_FORMAT, df);
    }

    /** Set the output data rate to the nearest supported value (6.25 Hz–3200 Hz). */
    public void setDataRate(double rateHz) throws IOException {
        int bestCode = RATE_CODES[0][0];
        int bestRate = RATE_CODES[0][1];
        double bestDiff = Math.abs(bestRate - rateHz);
        for (int i = 1; i < RATE_CODES.length; i++) {
            double diff = Math.abs(RATE_CODES[i][1] - rateHz);
            if (diff < bestDiff) {
                bestCode = RATE_CODES[i][0];
                bestRate = RATE_CODES[i][1];
                bestDiff = diff;
            }
        }
        int bw = readReg(REG_BW_RATE);
        bw = (bw & ~0x0F) | (bestCode & 0x0F);
        writeReg(REG_BW_RATE, bw);
    }

    /** Enable or disable low-power mode (higher noise). */
    public void setLowPower(boolean enabled) throws IOException {
        int bw = readReg(REG_BW_RATE);
        if (enabled) bw |= 0x10;
        else         bw &= ~0x10;
        writeReg(REG_BW_RATE, bw);
    }

    /** Set per-axis offset in <i>g</i>. */
    public void setOffset(double x, double y, double z) throws IOException {
        writeReg(REG_OFSX, encodeOffset(x));
        writeReg(REG_OFSY, encodeOffset(y));
        writeReg(REG_OFSZ, encodeOffset(z));
    }

    private static int encodeOffset(double offsetG) {
        int raw = (int) Math.round(offsetG / 15.6e-3);
        if (raw >  127) raw =  127;
        if (raw < -128) raw = -128;
        return raw & 0xFF;
    }

    /**
     * Measure and write per-axis offsets to null sensor bias.
     *
     * @param targetX expected X reading during calibration (in <i>g</i>)
     * @param targetY expected Y reading during calibration (in <i>g</i>)
     * @param targetZ expected Z reading during calibration (in <i>g</i>)
     * @param samples number of samples to average
     */
    public void calibrateOffset(double targetX, double targetY, double targetZ, int samples) throws IOException {
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < samples; i++) {
            double[] xyz = read();
            sx += xyz[0]; sy += xyz[1]; sz += xyz[2];
            try { Thread.sleep(11); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        sx /= samples; sy /= samples; sz /= samples;
        setOffset(targetX - sx, targetY - sy, targetZ - sz);
    }

    /**
     * Configure single-tap detection and enable the SINGLE_TAP interrupt.
     *
     * @param thresholdG tap acceleration threshold in <i>g</i> (62.5 mg/LSB)
     * @param durationMs maximum tap duration in ms (625 µs/LSB)
     * @param axes       bitmask of participating axes (bit 2=X, 1=Y, 0=Z)
     * @param suppress   suppress double-tap if acceleration persists between taps
     */
    public void setTapDetection(double thresholdG, double durationMs, int axes, boolean suppress) throws IOException {
        writeReg(REG_THRESH_TAP, (int) Math.round(thresholdG / 62.5e-3));
        writeReg(REG_DUR, (int) Math.round(durationMs / 0.625));
        int tapAxes = (axes & 0x07) | (suppress ? 0x08 : 0x00);
        writeReg(REG_TAP_AXES, tapAxes);
        enableInterrupt(INT_SINGLE_TAP);
    }

    /** Configure double-tap latency and window; enable DOUBLE_TAP interrupt. */
    public void setDoubleTap(double latencyMs, double windowMs) throws IOException {
        writeReg(REG_LATENT, (int) Math.round(latencyMs / 1.25));
        writeReg(REG_WINDOW, (int) Math.round(windowMs / 1.25));
        enableInterrupt(INT_DOUBLE_TAP);
    }

    /** Configure activity detection. */
    public void setActivity(double thresholdG, int axes, boolean acCoupled) throws IOException {
        writeReg(REG_THRESH_ACT, (int) Math.round(thresholdG / 62.5e-3));
        int aic = readReg(REG_ACT_INACT_CTL);
        aic &= ~0xF0;
        if (acCoupled) aic |= 0x80;
        aic |= axes & 0x70;
        writeReg(REG_ACT_INACT_CTL, aic);
        enableInterrupt(INT_ACTIVITY);
    }

    /** Configure inactivity detection. */
    public void setInactivity(double thresholdG, double timeSec, int axes, boolean acCoupled) throws IOException {
        writeReg(REG_THRESH_INACT, (int) Math.round(thresholdG / 62.5e-3));
        writeReg(REG_TIME_INACT, (int) Math.round(timeSec));
        int aic = readReg(REG_ACT_INACT_CTL);
        aic &= ~0x0F;
        if (acCoupled) aic |= 0x08;
        aic |= axes & 0x07;
        writeReg(REG_ACT_INACT_CTL, aic);
        enableInterrupt(INT_INACTIVITY);
    }

    /** Configure free-fall detection and enable the FREE_FALL interrupt. */
    public void setFreeFall(double thresholdG, double timeMs) throws IOException {
        writeReg(REG_THRESH_FF, (int) Math.round(thresholdG / 62.5e-3));
        writeReg(REG_TIME_FF, (int) Math.round(timeMs / 5.0));
        enableInterrupt(INT_FREE_FALL);
    }

    /** Enable or disable an interrupt source and route it to INT1 or INT2. */
    public void setInterrupt(int source, boolean enabled, int pin) throws IOException {
        int ie = readReg(REG_INT_ENABLE);
        int im = readReg(REG_INT_MAP);
        if (enabled) {
            ie |= source;
            if (pin == 2) im |= source;
            else          im &= ~source;
        } else {
            ie &= ~source;
        }
        writeReg(REG_INT_ENABLE, ie);
        writeReg(REG_INT_MAP, im);
    }

    private void enableInterrupt(int source) throws IOException {
        setInterrupt(source, true, 1);
    }

    /** Read the INT_SOURCE register; clears latched interrupts. */
    public int readInterruptSource() throws IOException {
        return readReg(REG_INT_SOURCE);
    }

    /** Configure the FIFO. */
    public void setFifoMode(int mode, int samples) throws IOException {
        int fifoCtl = (mode & 0xC0) | (samples & 0x1F);
        writeReg(REG_FIFO_CTL, fifoCtl);
    }

    /** Number of FIFO entries currently available (0–32). */
    public int fifoCount() throws IOException {
        int status = readReg(REG_FIFO_STATUS);
        return status & 0x3F;
    }

    /**
     * Drain the FIFO, returning up to {@code maxSamples} (x, y, z) samples
     * in <i>g</i>. Each sample is the same 6-byte burst as {@link #read()}.
     */
    public double[][] readFifo(int maxSamples) throws IOException {
        int n = fifoCount();
        if (n > maxSamples) n = maxSamples;
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            out[i] = read();
        }
        return out;
    }

    /** Enter or leave sleep mode. */
    public void setSleep(boolean enabled, int wakeupHz) throws IOException {
        int pwr = readReg(REG_POWER_CTL);
        if (enabled) {
            int wakeupCode;
            switch (wakeupHz) {
                case 8: wakeupCode = WAKEUP_8_HZ; break;
                case 4: wakeupCode = WAKEUP_4_HZ; break;
                case 2: wakeupCode = WAKEUP_2_HZ; break;
                case 1: wakeupCode = WAKEUP_1_HZ; break;
                default: return;
            }
            pwr = (pwr & ~0x06) | wakeupCode | 0x08;
            pwr |= 0x04;
        } else {
            pwr &= ~0x04;
        }
        writeReg(REG_POWER_CTL, pwr);
    }

    /** Enable or disable the activity/inactivity serial-link mode. */
    public void setLinkMode(boolean enabled) throws IOException {
        int pwr = readReg(REG_POWER_CTL);
        if (enabled) pwr |= 0x40;
        else         pwr &= ~0x40;
        writeReg(REG_POWER_CTL, pwr);
    }

    /** Enable or disable auto-sleep on inactivity (requires Link=1). */
    public void setAutoSleep(boolean enabled) throws IOException {
        int pwr = readReg(REG_POWER_CTL);
        if (enabled) pwr |= 0x20;
        else         pwr &= ~0x20;
        writeReg(REG_POWER_CTL, pwr);
    }

    /** Enable or disable the electrostatic self-test force on all axes. */
    public void selfTest(boolean enabled) throws IOException {
        int df = readReg(REG_DATA_FORMAT);
        if (enabled) df |= 0x80;
        else         df &= ~0x80;
        writeReg(REG_DATA_FORMAT, df);
    }
}