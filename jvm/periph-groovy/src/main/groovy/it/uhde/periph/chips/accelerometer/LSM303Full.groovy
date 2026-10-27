package it.uhde.periph.chips.accelerometer

import groovy.transform.CompileStatic
import it.uhde.periph.connection.RegisterConnection

/** LSM303 full interface — extends LSM303Minimal. */
@CompileStatic
class LSM303Full extends LSM303Minimal {
    static final int FIFO_BYPASS = 0
    static final int FIFO_FIFO = 1
    static final int FIFO_STREAM = 2
    static final int FIFO_STREAM_TO_FIFO = 3

    private int enabled1 = 0
    private int enabled2 = 0

    LSM303Full(RegisterConnection accelConnection, RegisterConnection magConnection) {
        super(accelConnection, magConnection)
    }

    void setRange(int rangeG) {
        int index = -1
        for (int i = 0; i < 4; i++) if ([2, 4, 8, 16][i] == rangeG) index = i
        if (index < 0) throw new IllegalArgumentException('rangeG must be one of 2, 4, 8, 16')
        int ctrl4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (ctrl4 & ~0x30) | (index << 4))
        this.rangeG = rangeG
    }

    void setDataRate(int rateHz) {
        int bit = rateBit(rateHz)
        if (bit < 0) throw new IllegalArgumentException('unsupported rate')
        if (isLowPowerOnly(rateHz) && mode != MODE_LOW_POWER) throw new IllegalArgumentException('low-power only rate')
        if (isNormalHighResOnly(rateHz) && mode == MODE_LOW_POWER) throw new IllegalArgumentException('rate unavailable in low-power mode')
        int ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, (ctrl1 & 0x0F) | bit)
        this.rateHz = rateHz
        sleepMs(settleMs())
    }

    void setMode(int mode) {
        if (mode < 0 || mode > MODE_HIGH_RESOLUTION) throw new IllegalArgumentException('mode must be 0, 1 or 2')
        if (mode == MODE_LOW_POWER && isNormalHighResOnly(rateHz)) throw new IllegalArgumentException('rate unavailable in low-power mode')
        if (mode != MODE_LOW_POWER && isLowPowerOnly(rateHz)) throw new IllegalArgumentException('low-power only rate')
        int ctrl1 = readReg(REG_CTRL_REG1)
        int ctrl4 = readReg(REG_CTRL_REG4)
        if (mode == MODE_LOW_POWER) {
            ctrl1 |= 0x08
            ctrl4 &= ~0x08
        } else {
            ctrl1 &= ~0x08
            if (mode == MODE_HIGH_RESOLUTION) ctrl4 |= 0x08
            else ctrl4 &= ~0x08
        }
        writeReg(REG_CTRL_REG1, ctrl1)
        writeReg(REG_CTRL_REG4, ctrl4)
        this.mode = mode
        sleepMs(settleMs())
    }

    void setAxes(boolean x, boolean y, boolean z) {
        int ctrl1 = readReg(REG_CTRL_REG1)
        int axes = (x ? 1 : 0) | ((y ? 1 : 0) << 1) | ((z ? 1 : 0) << 2)
        writeReg(REG_CTRL_REG1, (ctrl1 & 0xF8) | axes)
    }

    void powerDown() {
        int ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, ctrl1 & 0x0F)
        this.rateHz = 0
    }

    int[] readAccelerationRaw() {
        byte[] raw = readBurst(REG_OUT_X_L, 6)
        return [decodeAxis(raw[0], raw[1]), decodeAxis(raw[2], raw[3]), decodeAxis(raw[4], raw[5])] as int[]
    }

    boolean newDataAvailable() { return (readReg(REG_STATUS) & 0x08) != 0 }

    int readStatus() { return readReg(REG_STATUS) }

    void setHighPass(int mode, int cutoff, boolean filterOutput, boolean onIa1, boolean onIa2, boolean onClick) {
        if (mode < 0 || mode > 3 || cutoff < 0 || cutoff > 3) throw new IllegalArgumentException('mode and cutoff must be 0-3')
        int value = (mode << 6) | (cutoff << 4)
        if (filterOutput) value |= 0x08
        if (onClick) value |= 0x04
        if (onIa2) value |= 0x02
        if (onIa1) value |= 0x01
        writeReg(REG_CTRL_REG2, value)
    }

    int resetHighPass() { return readReg(REG_REFERENCE) }

    void setReference(int value) { writeReg(REG_REFERENCE, value & 0xFF) }

    void setFifo(int mode, int watermark, boolean triggerInt2) {
        if (mode < 0 || mode > 3 || watermark < 0 || watermark > 31) throw new IllegalArgumentException('invalid FIFO mode or watermark')
        writeReg(REG_FIFO_CTRL, (mode << 6) | (triggerInt2 ? 0x20 : 0) | watermark)
        int ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, mode == 0 ? (ctrl5 & ~0x40) : (ctrl5 | 0x40))
    }

    int fifoLevel() { return readReg(REG_FIFO_SRC) & 0x1F }

    double[][] readFifo(int count) {
        int level = fifoLevel()
        int n = count < 0 ? level : Math.min(count, level)
        double scale = sensitivityMg() / 1000d
        double[][] out = new double[n][3]
        for (int i = 0; i < n; i++) {
            byte[] raw = readBurst(REG_OUT_X_L, 6)
            out[i][0] = decodeAxis(raw[0], raw[1]) * scale
            out[i][1] = decodeAxis(raw[2], raw[3]) * scale
            out[i][2] = decodeAxis(raw[4], raw[5]) * scale
        }
        return out
    }

    Object[] readFifoStatus() {
        int raw = readReg(REG_FIFO_SRC)
        return [(raw & 0x80) != 0, (raw & 0x40) != 0, (raw & 0x20) != 0, raw & 0x1F] as Object[]
    }

    private int[] generatorRegs(int generator) {
        if (generator == 1) return [REG_INT1_CFG, REG_INT1_THS, REG_INT1_DUR] as int[]
        if (generator == 2) return [REG_INT2_CFG, REG_INT2_THS, REG_INT2_DUR] as int[]
        throw new IllegalArgumentException('generator must be 1 or 2')
    }

    private int thresholdCode(double thresholdG) {
        int code = roundHalfAway(thresholdG / ((double) rangeG / 128d))
        return Math.max(0, Math.min(127, code))
    }

    private int durationCode(double durationMs, int maximum) {
        if (durationMs <= 0 || rateHz <= 0) return 0
        return Math.max(0, Math.min(maximum, roundHalfAway(durationMs * rateHz / 1000d)))
    }

    void setMotionInterrupt(int generator, int events, double thresholdG, double durationMs, boolean andMode) {
        int[] regs = generatorRegs(generator)
        if (events < 0 || events > 0x3F) throw new IllegalArgumentException('events must be a 6-bit mask')
        writeReg(regs[0], (andMode ? 0x80 : 0) | (events & 0x3F))
        writeReg(regs[1], thresholdCode(thresholdG))
        writeReg(regs[2], durationCode(durationMs, 127))
    }

    void setFreeFall(int generator, double thresholdG, double durationMs) {
        int[] regs = generatorRegs(generator)
        writeReg(regs[0], 0x95)
        writeReg(regs[1], thresholdCode(thresholdG))
        writeReg(regs[2], durationCode(durationMs, 127))
    }

    void setOrientation(int generator, boolean position, boolean fourD) {
        int[] regs = generatorRegs(generator)
        writeReg(regs[0], (position ? 0xC0 : 0x40) | (fourD ? 0x0F : 0x3F))
        int ctrl5 = readReg(REG_CTRL_REG5)
        int bit = generator == 1 ? 0x04 : 0x01
        writeReg(REG_CTRL_REG5, fourD ? (ctrl5 | bit) : (ctrl5 & ~bit))
    }

    void setLatch(int generator, boolean enabled) {
        int bit
        if (generator == 1) bit = 0x08
        else if (generator == 2) bit = 0x02
        else throw new IllegalArgumentException('generator must be 1 or 2')
        int ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, enabled ? (ctrl5 | bit) : (ctrl5 & ~bit))
    }

    int readInterruptSource(int generator) {
        if (generator == 1) return readReg(REG_INT1_SRC)
        if (generator == 2) return readReg(REG_INT2_SRC)
        throw new IllegalArgumentException('generator must be 1 or 2')
    }

    void setClick(int single, int doubleClick, double thresholdG, double timeLimitMs, double latencyMs, double windowMs) {
        if (single < 0 || single > 7 || doubleClick < 0 || doubleClick > 7) throw new IllegalArgumentException('axis masks must be 0-7')
        int cfg = (single & 0x01) | ((single & 0x02) << 1) | ((single & 0x04) << 2) |
                ((doubleClick & 0x01) << 1) | ((doubleClick & 0x02) << 2) | ((doubleClick & 0x04) << 3)
        writeReg(REG_CLICK_CFG, cfg)
        writeReg(REG_CLICK_THS, thresholdCode(thresholdG))
        writeReg(REG_TIME_LIMIT, durationCode(timeLimitMs, 127))
        writeReg(REG_TIME_LATENCY, durationCode(latencyMs, 255))
        writeReg(REG_TIME_WINDOW, durationCode(windowMs, 255))
    }

    int readClickSource() { return readReg(REG_CLICK_SRC) }

    void setInterruptPolarity(boolean activeLow) {
        int ctrl6 = readReg(REG_CTRL_REG6)
        writeReg(REG_CTRL_REG6, activeLow ? (ctrl6 | 0x02) : (ctrl6 & ~0x02))
    }

    void enableInterrupt(int source, int pin) {
        if (source == LSM303Source.SOURCE_MAG_DATA_READY) throw new IllegalArgumentException('magnetometer DRDY cannot be routed')
        int[] route = route(source, pin)
        writeReg(route[0], readReg(route[0]) | route[1])
        if (route[2] == 1) enabled1 |= 1 << sourceBit(source)
        else enabled2 |= 1 << sourceBit(source)
    }

    void disableInterrupt(int source, int pin) {
        if (source == LSM303Source.SOURCE_MAG_DATA_READY) throw new IllegalArgumentException('magnetometer DRDY cannot be routed')
        int[] route = route(source, pin)
        writeReg(route[0], readReg(route[0]) & ~route[1])
        if (route[2] == 1) enabled1 &= ~(1 << sourceBit(source))
        else enabled2 &= ~(1 << sourceBit(source))
    }

    private int[] route(int source, int pin) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException('pin must be 1 or 2')
        int both = pin == 2 ? REG_CTRL_REG6 : REG_CTRL_REG3
        if (source == LSM303Source.SOURCE_DATA_READY) return [REG_CTRL_REG3, 0x10, 1] as int[]
        if (source == LSM303Source.SOURCE_WATERMARK) return [REG_CTRL_REG3, 0x04, 1] as int[]
        if (source == LSM303Source.SOURCE_OVERRUN) return [REG_CTRL_REG3, 0x02, 1] as int[]
        if (source == LSM303Source.SOURCE_IA1) return [both, 0x40, pin] as int[]
        if (source == LSM303Source.SOURCE_IA2) return [both, 0x20, pin] as int[]
        if (source == LSM303Source.SOURCE_CLICK) return [both, 0x80, pin] as int[]
        if (source == LSM303Source.SOURCE_BOOT) return [REG_CTRL_REG6, 0x10, 2] as int[]
        throw new IllegalArgumentException('unknown interrupt source')
    }

    private static int sourceBit(int source) {
        int s = source
        int bit = 0
        while (s > 1) {
            s >>= 1
            bit++
        }
        return bit
    }

    int pollInterrupt() {
        int enabled = enabled1 | enabled2
        int status = 0
        if ((enabled & 0x01) != 0 && (readReg(REG_STATUS) & 0x08) != 0) status |= LSM303Source.SOURCE_DATA_READY
        if ((enabled & 0x06) != 0) {
            int fifo = readReg(REG_FIFO_SRC)
            if ((enabled & 0x02) != 0 && (fifo & 0x80) != 0) status |= LSM303Source.SOURCE_WATERMARK
            if ((enabled & 0x04) != 0 && (fifo & 0x40) != 0) status |= LSM303Source.SOURCE_OVERRUN
        }
        if ((enabled & 0x08) != 0 && (readReg(REG_INT1_SRC) & 0x40) != 0) status |= LSM303Source.SOURCE_IA1
        if ((enabled & 0x10) != 0 && (readReg(REG_INT2_SRC) & 0x40) != 0) status |= LSM303Source.SOURCE_IA2
        if ((enabled & 0x20) != 0 && (readReg(REG_CLICK_SRC) & 0x40) != 0) status |= LSM303Source.SOURCE_CLICK
        if ((magReadReg(MAG_REG_SR) & 0x01) != 0) status |= LSM303Source.SOURCE_MAG_DATA_READY
        return status
    }

    void setMagRange(int rangeUt) {
        int index = -1
        for (int i = 0; i < MAG_RANGES.length; i++) if (MAG_RANGES[i] == rangeUt) index = i
        if (index < 0) throw new IllegalArgumentException('rangeUt must be 130, 190, 250, 400, 470, 560 or 810')
        magGainCode = MAG_CODES[index]
        magWriteReg(MAG_REG_CRB, magGainCode)
    }

    void setMagDataRate(double rateHz) {
        int index = -1
        for (int i = 0; i < MAG_RATES.length; i++) if (Math.abs(MAG_RATES[i] - rateHz) < 1e-9) index = i
        if (index < 0) throw new IllegalArgumentException('rateHz must be 0.75, 1.5, 3, 7.5, 15, 30, 75 or 220')
        int cra = magReadReg(MAG_REG_CRA)
        magWriteReg(MAG_REG_CRA, (cra & 0xE3) | MAG_RATE_BITS[index])
        this.magRateHz = rateHz
    }

    void setMagMode(int mode) {
        int code = 0
        if (mode == 0) code = 0x00
        else if (mode == 1) code = 0x01
        else if (mode == 2) code = 0x03
        else throw new IllegalArgumentException('mode must be 0, 1 or 2')
        magWriteReg(MAG_REG_MR, code)
        if (mode == 1) sleepMs((int) Math.ceil(1000d / magRateHz))
    }

    int[] readMagneticRaw() {
        byte[] raw = magReadBurst(MAG_REG_OUT_X_H, 6)
        int x = (short) ((((raw[0] as int) & 0xFF) << 8) | ((raw[1] as int) & 0xFF))
        int z = (short) ((((raw[2] as int) & 0xFF) << 8) | ((raw[3] as int) & 0xFF))
        int y = (short) ((((raw[4] as int) & 0xFF) << 8) | ((raw[5] as int) & 0xFF))
        return [x, y, z] as int[]
    }

    boolean magDataReady() { return (magReadReg(MAG_REG_SR) & 0x01) != 0 }

    boolean[] readMagStatus() {
        int raw = magReadReg(MAG_REG_SR)
        return [(raw & 0x01) != 0, (raw & 0x02) != 0] as boolean[]
    }

    byte[] readMagIdentity() { return magReadBurst(MAG_REG_IRA, 3) }

    void enableTemperature(boolean enabled) {
        int cra = magReadReg(MAG_REG_CRA)
        magWriteReg(MAG_REG_CRA, enabled ? (cra | 0x80) : (cra & ~0x80))
    }

    double readTemperatureDelta() {
        if ((magReadReg(MAG_REG_CRA) & 0x80) == 0) {
            enableTemperature(true)
            sleepMs((int) Math.ceil(1000d / magRateHz))
        }
        int raw = (short) ((magReadReg(MAG_REG_TEMP_H) << 8) | magReadReg(MAG_REG_TEMP_L))
        return (raw >> 4) / 8d
    }

    double heading(boolean tiltCompensate) {
        double[] m = readMagnetic()
        double angle
        if (tiltCompensate) {
            double[] a = readAcceleration()
            double roll = Math.atan2(a[1], a[2])
            double pitch = Math.atan2(-a[0], a[1] * Math.sin(roll) + a[2] * Math.cos(roll))
            double xh = m[0] * Math.cos(pitch) + m[2] * Math.sin(pitch)
            double yh = m[0] * Math.sin(roll) * Math.sin(pitch) + m[1] * Math.cos(roll) -
                    m[2] * Math.sin(roll) * Math.cos(pitch)
            angle = Math.atan2(yh, xh)
        } else {
            angle = Math.atan2(m[1], m[0])
        }
        double degrees = angle * 180d / Math.PI
        return degrees < 0 ? degrees + 360d : degrees
    }

    void reboot() {
        int ctrl5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG5, ctrl5 | 0x80)
        sleepMs(BOOT_DELAY_MS)
        rangeG = RANGE_2G
        mode = MODE_HIGH_RESOLUTION
        rateHz = 100
        magGainCode = 0x20
        magRateHz = 15d
        enabled1 = 0
        enabled2 = 0
        runInitSequence()
    }
}
