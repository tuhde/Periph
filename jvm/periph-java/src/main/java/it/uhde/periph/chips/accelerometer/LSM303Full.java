package it.uhde.periph.chips.accelerometer;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;

/** LSM303 full interface — extends {@link LSM303Minimal}. */
public class LSM303Full extends LSM303Minimal {

    /** FIFO modes. */
    public static final int FIFO_BYPASS = 0;
    public static final int FIFO_FIFO = 1;
    public static final int FIFO_STREAM = 2;
    public static final int FIFO_STREAM_TO_FIFO = 3;

    private int enabled1 = 0;
    private int enabled2 = 0;

    /**
     * Construct the driver and run the Initialization Sequence.
     *
     * @param accelConnection connection bound to 0x19
     * @param magConnection   connection bound to 0x1E
     * @throws IOException on bus error or a wrong magnetometer identity
     */
    public LSM303Full(RegisterConnection accelConnection, RegisterConnection magConnection) throws IOException {
        super(accelConnection, magConnection);
    }

    /**
     * Set the accelerometer full-scale range.
     *
     * @param rangeG one of 2, 4, 8, 16 g
     * @throws IOException on bus error
     */
    public void setRange(int rangeG) throws IOException {
        int index = -1;
        int[] ranges = {2, 4, 8, 16};
        for (int i = 0; i < ranges.length; i++) if (ranges[i] == rangeG) index = i;
        if (index < 0) throw new IllegalArgumentException("rangeG must be one of 2, 4, 8, 16");
        int ctrl4 = readReg(REG_CTRL_REG4);
        writeReg(REG_CTRL_REG4, (ctrl4 & ~0x30) | (index << 4));
        this.rangeG = rangeG;
    }

    /**
     * Set the accelerometer output data rate (0 powers down).
     *
     * @param rateHz one of 0, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376
     * @throws IOException on bus error
     */
    public void setDataRate(int rateHz) throws IOException {
        int bit = rateBit(rateHz);
        if (bit < 0) throw new IllegalArgumentException("unsupported rate");
        if (isLowPowerOnly(rateHz) && mode != MODE_LOW_POWER) throw new IllegalArgumentException("low-power only rate");
        if (isNormalHighResOnly(rateHz) && mode == MODE_LOW_POWER) throw new IllegalArgumentException("rate unavailable in low-power mode");
        int ctrl1 = readReg(REG_CTRL_REG1);
        writeReg(REG_CTRL_REG1, (ctrl1 & 0x0F) | bit);
        this.rateHz = rateHz;
        sleepMs(settleMs());
    }

    /**
     * Set the accelerometer operating mode.
     *
     * @param mode 0 low-power, 1 normal, 2 high-resolution
     * @throws IOException on bus error
     */
    public void setMode(int mode) throws IOException {
        if (mode < 0 || mode > MODE_HIGH_RESOLUTION) throw new IllegalArgumentException("mode must be 0, 1 or 2");
        if (mode == MODE_LOW_POWER && isNormalHighResOnly(rateHz)) throw new IllegalArgumentException("rate unavailable in low-power mode");
        if (mode != MODE_LOW_POWER && isLowPowerOnly(rateHz)) throw new IllegalArgumentException("low-power only rate");
        int ctrl1 = readReg(REG_CTRL_REG1);
        int ctrl4 = readReg(REG_CTRL_REG4);
        if (mode == MODE_LOW_POWER) {
            ctrl1 |= 0x08;
            ctrl4 &= ~0x08;
        } else {
            ctrl1 &= ~0x08;
            if (mode == MODE_HIGH_RESOLUTION) ctrl4 |= 0x08;
            else ctrl4 &= ~0x08;
        }
        writeReg(REG_CTRL_REG1, ctrl1);
        writeReg(REG_CTRL_REG4, ctrl4);
        this.mode = mode;
        sleepMs(settleMs());
    }

    /** Enable or disable the accelerometer axes. */
    public void setAxes(boolean x, boolean y, boolean z) throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1);
        int axes = (x ? 1 : 0) | ((y ? 1 : 0) << 1) | ((z ? 1 : 0) << 2);
        writeReg(REG_CTRL_REG1, (ctrl1 & 0xF8) | axes);
    }

    /** Stop accelerometer conversions. */
    public void powerDown() throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1);
        writeReg(REG_CTRL_REG1, ctrl1 & 0x0F);
        this.rateHz = 0;
    }

    /** Read raw accelerometer counts. */
    public int[] readAccelerationRaw() throws IOException {
        byte[] raw = readBurst(REG_OUT_X_L, 6);
        return new int[]{decodeAxis(raw[0], raw[1]), decodeAxis(raw[2], raw[3]), decodeAxis(raw[4], raw[5])};
    }

    /** @return true when STATUS_REG_A.ZYXDA is set */
    public boolean newDataAvailable() throws IOException {
        return (readReg(REG_STATUS) & 0x08) != 0;
    }

    /** @return the STATUS_REG_A byte */
    public int readStatus() throws IOException {
        return readReg(REG_STATUS);
    }

    /** Configure the accelerometer high-pass filter. */
    public void setHighPass(int mode, int cutoff, boolean filterOutput, boolean onIa1, boolean onIa2, boolean onClick) throws IOException {
        if (mode < 0 || mode > 3 || cutoff < 0 || cutoff > 3) throw new IllegalArgumentException("mode and cutoff must be 0-3");
        int value = (mode << 6) | (cutoff << 4);
        if (filterOutput) value |= 0x08;
        if (onClick) value |= 0x04;
        if (onIa2) value |= 0x02;
        if (onIa1) value |= 0x01;
        writeReg(REG_CTRL_REG2, value);
    }

    /** Reset the high-pass filter by reading REFERENCE_A. */
    public int resetHighPass() throws IOException {
        return readReg(REG_REFERENCE);
    }

    /** Write the high-pass reference value. */
    public void setReference(int value) throws IOException {
        writeReg(REG_REFERENCE, value & 0xFF);
    }

    /** Configure the FIFO. */
    public void setFifo(int mode, int watermark, boolean triggerInt2) throws IOException {
        if (mode < 0 || mode > 3 || watermark < 0 || watermark > 31) throw new IllegalArgumentException("invalid FIFO mode or watermark");
        int value = (mode << 6) | (triggerInt2 ? 0x20 : 0) | watermark;
        writeReg(REG_FIFO_CTRL, value);
        int ctrl5 = readReg(REG_CTRL_REG5);
        writeReg(REG_CTRL_REG5, mode == 0 ? (ctrl5 & ~0x40) : (ctrl5 | 0x40));
    }

    /** @return the FIFO level (0-32) */
    public int fifoLevel() throws IOException {
        return readReg(REG_FIFO_SRC) & 0x1F;
    }

    /** Drain up to {@code count} sample sets; {@code count < 0} reads all. */
    public double[][] readFifo(int count) throws IOException {
        int level = fifoLevel();
        int n = count < 0 ? level : Math.min(count, level);
        double scale = sensitivityMg() / 1000.0;
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) {
            byte[] raw = readBurst(REG_OUT_X_L, 6);
            out[i][0] = decodeAxis(raw[0], raw[1]) * scale;
            out[i][1] = decodeAxis(raw[2], raw[3]) * scale;
            out[i][2] = decodeAxis(raw[4], raw[5]) * scale;
        }
        return out;
    }

    /** @return {watermark, overrun, empty, level encoded as int} */
    public Object[] readFifoStatus() throws IOException {
        int raw = readReg(REG_FIFO_SRC);
        return new Object[]{(raw & 0x80) != 0, (raw & 0x40) != 0, (raw & 0x20) != 0, raw & 0x1F};
    }

    private int[] generatorRegs(int generator) {
        switch (generator) {
            case 1: return new int[]{REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR};
            case 2: return new int[]{REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR};
            default: throw new IllegalArgumentException("generator must be 1 or 2");
        }
    }

    private int thresholdCode(double thresholdG) {
        int code = roundHalfAway(thresholdG / ((double) rangeG / 128.0));
        return Math.max(0, Math.min(127, code));
    }

    private int durationCode(double durationMs, int maximum) {
        if (durationMs <= 0 || rateHz <= 0) return 0;
        int code = roundHalfAway(durationMs * rateHz / 1000.0);
        return Math.max(0, Math.min(maximum, code));
    }

    /** Configure an inertial interrupt generator. */
    public void setMotionInterrupt(int generator, int events, double thresholdG, double durationMs, boolean andMode) throws IOException {
        int[] regs = generatorRegs(generator);
        if (events < 0 || events > 0x3F) throw new IllegalArgumentException("events must be a 6-bit mask");
        writeReg(regs[0], (andMode ? 0x80 : 0) | (events & 0x3F));
        writeReg(regs[1], thresholdCode(thresholdG));
        writeReg(regs[2], durationCode(durationMs, 127));
    }

    /** Configure the free-fall detector. */
    public void setFreeFall(int generator, double thresholdG, double durationMs) throws IOException {
        int[] regs = generatorRegs(generator);
        writeReg(regs[0], 0x95);
        writeReg(regs[1], thresholdCode(thresholdG));
        writeReg(regs[2], durationCode(durationMs, 127));
    }

    /** Configure 6D / 4D orientation detection. */
    public void setOrientation(int generator, boolean position, boolean fourD) throws IOException {
        int[] regs = generatorRegs(generator);
        writeReg(regs[0], (position ? 0xC0 : 0x40) | (fourD ? 0x0F : 0x3F));
        int ctrl5 = readReg(REG_CTRL_REG5);
        int bit = generator == 1 ? 0x04 : 0x01;
        writeReg(REG_CTRL_REG5, fourD ? (ctrl5 | bit) : (ctrl5 & ~bit));
    }

    /** Latch a generator's interrupt. */
    public void setLatch(int generator, boolean enabled) throws IOException {
        int bit;
        switch (generator) {
            case 1: bit = 0x08; break;
            case 2: bit = 0x02; break;
            default: throw new IllegalArgumentException("generator must be 1 or 2");
        }
        int ctrl5 = readReg(REG_CTRL_REG5);
        writeReg(REG_CTRL_REG5, enabled ? (ctrl5 | bit) : (ctrl5 & ~bit));
    }

    /** Read a generator's source register — clears the interrupt. */
    public int readInterruptSource(int generator) throws IOException {
        switch (generator) {
            case 1: return readReg(REG_INT1_SRC);
            case 2: return readReg(REG_INT2_SRC);
            default: throw new IllegalArgumentException("generator must be 1 or 2");
        }
    }

    /** Configure single / double click detection. */
    public void setClick(int single, int doubleClick, double thresholdG, double timeLimitMs, double latencyMs, double windowMs) throws IOException {
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) throw new IllegalArgumentException("axis masks must be 0-7");
        int cfg = (single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2)
                | ((doubleClick & 0x01) << 1) | ((doubleClick & 0x02) << 2) | ((doubleClick & 0x04) << 3);
        writeReg(REG_CLICK_CFG, cfg);
        writeReg(REG_CLICK_THS, thresholdCode(thresholdG));
        writeReg(REG_TIME_LIMIT, durationCode(timeLimitMs, 127));
        writeReg(REG_TIME_LATENCY, durationCode(latencyMs, 255));
        writeReg(REG_TIME_WINDOW, durationCode(windowMs, 255));
    }

    /** Read CLICK_SRC_A — clears the interrupt. */
    public int readClickSource() throws IOException {
        return readReg(REG_CLICK_SRC);
    }

    /** Set the shared INT1 / INT2 polarity. */
    public void setInterruptPolarity(boolean activeLow) throws IOException {
        int ctrl6 = readReg(REG_CTRL_REG6);
        writeReg(REG_CTRL_REG6, activeLow ? (ctrl6 | 0x02) : (ctrl6 & ~0x02));
    }

    /** Route one interrupt source to an INT pin. */
    public void enableInterrupt(int source, int pin) throws IOException {
        if (source == LSM303Source.SOURCE_MAG_DATA_READY) throw new IllegalArgumentException("magnetometer DRDY cannot be routed");
        int[] route = route(source, pin);
        writeReg(route[0], readReg(route[0]) | route[1]);
        if (route[2] == 1) enabled1 |= 1 << sourceBit(source);
        else enabled2 |= 1 << sourceBit(source);
    }

    /** Remove one interrupt source from an INT pin. */
    public void disableInterrupt(int source, int pin) throws IOException {
        if (source == LSM303Source.SOURCE_MAG_DATA_READY) throw new IllegalArgumentException("magnetometer DRDY cannot be routed");
        int[] route = route(source, pin);
        writeReg(route[0], readReg(route[0]) & ~route[1]);
        if (route[2] == 1) enabled1 &= ~(1 << sourceBit(source));
        else enabled2 &= ~(1 << sourceBit(source));
    }

    private int[] route(int source, int pin) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException("pin must be 1 or 2");
        int both = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3;
        switch (source) {
            case LSM303Source.SOURCE_DATA_READY: return new int[]{REG_CTRL_REG3, 0x10, 1};
            case LSM303Source.SOURCE_WATERMARK: return new int[]{REG_CTRL_REG3, 0x04, 1};
            case LSM303Source.SOURCE_OVERRUN: return new int[]{REG_CTRL_REG3, 0x02, 1};
            case LSM303Source.SOURCE_IA1: return new int[]{both, 0x40, pin};
            case LSM303Source.SOURCE_IA2: return new int[]{both, 0x20, pin};
            case LSM303Source.SOURCE_CLICK: return new int[]{both, 0x80, pin};
            case LSM303Source.SOURCE_BOOT: return new int[]{REG_CTRL_REG6, 0x10, 2};
            default: throw new IllegalArgumentException("unknown interrupt source");
        }
    }

    private static int sourceBit(int source) {
        int bit = 0;
        while (source > 1) {
            source >>= 1;
            bit++;
        }
        return bit;
    }

    /** Read every enabled source and return the combined status. */
    public int pollInterrupt() throws IOException {
        int enabled = enabled1 | enabled2;
        int status = 0;
        if ((enabled & 0x01) != 0 && (readReg(REG_STATUS) & 0x08) != 0) status |= LSM303Source.SOURCE_DATA_READY;
        if ((enabled & 0x06) != 0) {
            int fifo = readReg(REG_FIFO_SRC);
            if ((enabled & 0x02) != 0 && (fifo & 0x80) != 0) status |= LSM303Source.SOURCE_WATERMARK;
            if ((enabled & 0x04) != 0 && (fifo & 0x40) != 0) status |= LSM303Source.SOURCE_OVERRUN;
        }
        if ((enabled & 0x08) != 0 && (readReg(REG_INT1_SRC) & 0x40) != 0) status |= LSM303Source.SOURCE_IA1;
        if ((enabled & 0x10) != 0 && (readReg(REG_INT2_SRC) & 0x40) != 0) status |= LSM303Source.SOURCE_IA2;
        if ((enabled & 0x20) != 0 && (readReg(REG_CLICK_SRC) & 0x40) != 0) status |= LSM303Source.SOURCE_CLICK;
        if ((magReadReg(MAG_REG_SR) & 0x01) != 0) status |= LSM303Source.SOURCE_MAG_DATA_READY;
        return status;
    }

    /** Set the magnetometer gain / full-scale range in µT. */
    public void setMagRange(int rangeUt) throws IOException {
        int index = -1;
        for (int i = 0; i < MAG_RANGES.length; i++) if (MAG_RANGES[i] == rangeUt) index = i;
        if (index < 0) throw new IllegalArgumentException("rangeUt must be 130, 190, 250, 400, 470, 560 or 810");
        magGainCode = MAG_CODES[index];
        magWriteReg(MAG_REG_CRB, magGainCode);
    }

    /** Set the magnetometer output data rate in Hz. */
    public void setMagDataRate(double rateHz) throws IOException {
        int index = -1;
        for (int i = 0; i < MAG_RATES.length; i++) if (Math.abs(MAG_RATES[i] - rateHz) < 1e-9) index = i;
        if (index < 0) throw new IllegalArgumentException("rateHz must be 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220");
        int cra = magReadReg(MAG_REG_CRA);
        magWriteReg(MAG_REG_CRA, (cra & 0xE3) | MAG_RATE_BITS[index]);
        this.magRateHz = rateHz;
    }

    /** Set the magnetometer operating mode (0 continuous, 1 single, 2 sleep). */
    public void setMagMode(int mode) throws IOException {
        int code;
        switch (mode) {
            case 0: code = 0x00; break;
            case 1: code = 0x01; break;
            case 2: code = 0x03; break;
            default: throw new IllegalArgumentException("mode must be 0, 1 or 2");
        }
        magWriteReg(MAG_REG_MR, code);
        if (mode == 1) sleepMs((int) Math.ceil(1000.0 / magRateHz));
    }

    /** Read raw magnetometer counts, reordered to X, Y, Z. */
    public int[] readMagneticRaw() throws IOException {
        byte[] raw = magReadBurst(MAG_REG_OUT_X_H, 6);
        int x = (short) (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF));
        int z = (short) (((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF));
        int y = (short) (((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF));
        return new int[]{x, y, z};
    }

    /** @return true when SR_REG_M.DRDY is set */
    public boolean magDataReady() throws IOException {
        return (magReadReg(MAG_REG_SR) & 0x01) != 0;
    }

    /** @return {drdy, lock} */
    public boolean[] readMagStatus() throws IOException {
        int raw = magReadReg(MAG_REG_SR);
        return new boolean[]{(raw & 0x01) != 0, (raw & 0x02) != 0};
    }

    /** Read the magnetometer identity bytes. */
    public byte[] readMagIdentity() throws IOException {
        return magReadBurst(MAG_REG_IRA, 3);
    }

    /** Enable or disable the magnetometer temperature sensor. */
    public void enableTemperature(boolean enabled) throws IOException {
        int cra = magReadReg(MAG_REG_CRA);
        magWriteReg(MAG_REG_CRA, enabled ? (cra | 0x80) : (cra & ~0x80));
    }

    /** Read the relative magnetometer temperature in °C. */
    public double readTemperatureDelta() throws IOException {
        if ((magReadReg(MAG_REG_CRA) & 0x80) == 0) {
            enableTemperature(true);
            sleepMs((int) Math.ceil(1000.0 / magRateHz));
        }
        int raw = (short) ((magReadReg(MAG_REG_TEMP_H) << 8) | magReadReg(MAG_REG_TEMP_L));
        return (raw >> 4) / 8.0;
    }

    /** Compute the compass heading in degrees (0-360). */
    public double heading(boolean tiltCompensate) throws IOException {
        double[] m = readMagnetic();
        double angle;
        if (tiltCompensate) {
            double[] a = readAcceleration();
            double roll = Math.atan2(a[1], a[2]);
            double pitch = Math.atan2(-a[0], a[1] * Math.sin(roll) + a[2] * Math.cos(roll));
            double xh = m[0] * Math.cos(pitch) + m[2] * Math.sin(pitch);
            double yh = m[0] * Math.sin(roll) * Math.sin(pitch) + m[1] * Math.cos(roll)
                    - m[2] * Math.sin(roll) * Math.cos(pitch);
            angle = Math.atan2(yh, xh);
        } else {
            angle = Math.atan2(m[1], m[0]);
        }
        double degrees = angle * 180 / Math.PI;
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /** Reboot the accelerometer memory and re-run the Initialization Sequence. */
    public void reboot() throws IOException {
        int ctrl5 = readReg(REG_CTRL_REG5);
        writeReg(REG_CTRL_REG5, ctrl5 | 0x80);
        sleepMs(BOOT_DELAY_MS);
        rangeG = RANGE_2G;
        mode = MODE_HIGH_RESOLUTION;
        rateHz = 100;
        magGainCode = 0x20;
        magRateHz = 15;
        enabled1 = 0;
        enabled2 = 0;
        runInitSequence();
    }
}
