@CompileStatic
class Bma180Full extends Bma180Minimal {

    static final int SOURCE_LOW_G    = 0x01
    static final int SOURCE_HIGH_G   = 0x02
    static final int SOURCE_SLOPE    = 0x04
    static final int SOURCE_ALERT    = 0x08
    static final int SOURCE_TAP      = 0x10
    static final int SOURCE_NEW_DATA = 0x20

    static final int STATUS_HIGH_G  = 0x80
    static final int STATUS_LOW_G   = 0x40
    static final int STATUS_SLOPE   = 0x20
    static final int STATUS_TAP     = 0x10
    static final int STATUS_X_FIRST = 0x04
    static final int STATUS_Y_FIRST = 0x02
    static final int STATUS_Z_FIRST = 0x01

    private static final int CR3_SLOPE_ALERT  = 0x80
    private static final int CR3_SLOPE_INT    = 0x40
    private static final int CR3_HIGH_INT     = 0x20
    private static final int CR3_LOW_INT      = 0x10
    private static final int CR3_TAP_INT      = 0x08
    private static final int CR3_ADV_INT      = 0x04
    private static final int CR3_NEW_DATA_INT = 0x02
    private static final int CR3_LAT_INT      = 0x01

    private static final int HLI_HIGH_AXIS_SHIFT = 5
    private static final int HLI_LOW_AXIS_SHIFT  = 1
    private static final int HLI_HIGH_FILT_BIT   = 0x10
    private static final int HLI_LOW_FILT_BIT    = 0x01

    private static final int STI_SLOPE_AXIS_SHIFT = 5
    private static final int STI_TAP_AXIS_SHIFT   = 1
    private static final int STI_SLOPE_FILT_BIT   = 0x10
    private static final int STI_TAP_FILT_BIT     = 0x01

    private static final int HY_HIGH_SHIFT = 3
    private static final int HY_LOW_MASK   = 0x07

    private static final int CR4_LOW_HY_SHIFT = 6
    private static final int CR4_MOT_CD_SHIFT = 4
    private static final int CR4_FF_CD_SHIFT  = 2

    private static final int OLSB1_RANGE_MASK = 0x0E
    private static final int OLSB1_SMP_SKIP   = 0x01

    private static final int GY_SHADOW    = 0x01
    private static final int GZ_WAKE_UP   = 0x01

    private static final int BW_HIGH_PASS_1HZ = 0x80
    private static final int BW_BAND_PASS     = 0x90

    private static final int TCO_X_SLOPE_MASK = 0x03
    private static final int TCO_Y_WAKE_MASK  = 0x03
    private static final int TCO_Z_MODE_MASK  = 0x03

    private static final int GT_TAP_MASK = 0x07

    private static final int LOW_DUR_MASK  = 0xFE
    private static final int HIGH_DUR_MASK = 0xFE

    private static final int OT_12BIT_MASK = 0x01

    private static final float DUR_LSB_MS = 2.085f

    private int enabledSources = 0
    private boolean sleeping = false

    Bma180Full(RegisterConnection connection) throws IOException {
        super(connection)
    }

    @Override
    float[] read() throws IOException {
        return super.read()
    }

    void setRange(float rangeG) throws IOException {
        int bits
        if      (rangeG == 1.0f)   bits = RANGE_1G_MASK
        else if (rangeG == 1.5f) bits = RANGE_1_5G_MASK
        else if (rangeG == 2.0f) bits = RANGE_2G_MASK
        else if (rangeG == 3.0f) bits = RANGE_3G_MASK
        else if (rangeG == 4.0f) bits = RANGE_4G_MASK
        else if (rangeG == 8.0f) bits = RANGE_8G_MASK
        else if (rangeG == 16.0f) bits = RANGE_16G_MASK
        else { throw new IOException("rangeG must be one of 1, 1.5, 2, 3, 4, 8, 16") }
        this.rangeG = rangeG
        this.rangeBits = bits
        int olsb1 = readReg(REG_OFFSET_LSB1)
        writeReg(REG_OFFSET_LSB1, (olsb1 & 0xF1) | bits)
    }

    void setBandwidth(int bandwidthHz) throws IOException {
        int bwCode = nearestBandwidth(bandwidthHz)
        int cur = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (cur & 0x0F) | bwCode)
    }

    void setFilterMode(int mode) throws IOException {
        int code
        if      (mode == 1) code = BW_HIGH_PASS_1HZ
        else if (mode == 2) code = BW_BAND_PASS
        else return
        int cur = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (cur & 0x0F) | code)
    }

    void setMode(int mode) throws IOException {
        if (mode < 0 || mode > 3) throw new IOException("mode must be 0..3")
        int tcoz = readReg(REG_TCO_Z)
        writeReg(REG_TCO_Z, (tcoz & 0xFC) | (mode & 0x03))
    }

    void setResolution(int bits) throws IOException {
        if (bits != 12 && bits != 14) throw new IOException("bits must be 12 or 14")
        int ot = readReg(REG_OFFSET_T)
        int out = bits == 12 ? ot | OT_12BIT_MASK : ot & ~OT_12BIT_MASK & 0xFF
        writeReg(REG_OFFSET_T, out)
    }

    int[] readRaw() throws IOException {
        byte[] raw = connection.read(REG_ACC_X_LSB, 6)
        int rx = Register.toSigned(((raw[1] & 0xFF) << 6) | ((raw[0] & 0xFF) >> 2), 14)
        int ry = Register.toSigned(((raw[3] & 0xFF) << 6) | ((raw[2] & 0xFF) >> 2), 14)
        int rz = Register.toSigned(((raw[5] & 0xFF) << 6) | ((raw[4] & 0xFF) >> 2), 14)
        return new int[] { rx, ry, rz }
    }

    float readTemperature() throws IOException {
        int raw = readReg(REG_TEMP)
        int signed = raw < 128 ? raw : raw - 256
        return 25.0f + (signed - 2) * 0.5f
    }

    boolean newDataAvailable() throws IOException {
        int x = readReg(REG_ACC_X_LSB)
        int y = readReg(REG_ACC_Y_LSB)
        int z = readReg(REG_ACC_Z_LSB)
        return (x & 0x01) != 0 && (y & 0x01) != 0 && (z & 0x01) != 0
    }

    void setShadow(boolean enabled) throws IOException {
        int gy = readReg(REG_GAIN_Y)
        int out = enabled ? gy & ~GY_SHADOW & 0xFF : gy | GY_SHADOW
        writeReg(REG_GAIN_Y, out)
    }

    void setSampleSkip(boolean enabled) throws IOException {
        int olsb1 = readReg(REG_OFFSET_LSB1)
        int out = enabled ? olsb1 | OLSB1_SMP_SKIP : olsb1 & ~OLSB1_SMP_SKIP & 0xFF
        writeReg(REG_OFFSET_LSB1, out)
    }

    void setLowG(float thresholdG, int durationMs, float hysteresisG,
                 int axes, int counter, boolean filtered) throws IOException {
        writeThreshold(REG_LOW_TH, thresholdG)
        writeLowDur(durationMs)
        writeLowHy(hysteresisG)
        writeLowAxes(axes)
        writeFiltBit(REG_HIGH_LOW_INFO, HLI_LOW_FILT_BIT, filtered)
        writeDebounce('l' as char, counter)
        enableSource(SOURCE_LOW_G)
    }

    void setHighG(float thresholdG, int durationMs, float hysteresisG,
                  int axes, int counter, boolean filtered) throws IOException {
        writeThreshold(REG_HIGH_TH, thresholdG)
        writeHighDur(durationMs)
        writeHighHy(hysteresisG)
        writeHighAxes(axes)
        writeFiltBit(REG_HIGH_LOW_INFO, HLI_HIGH_FILT_BIT, filtered)
        writeDebounce('h' as char, counter)
        enableSource(SOURCE_HIGH_G)
    }

    void setSlope(float thresholdG, int samples, int axes, boolean filtered) throws IOException {
        writeSlopeThreshold(REG_SLOPE_TH, thresholdG)
        writeSlopeDur(samples)
        writeSlopeAxes(axes)
        writeFiltBit(REG_SLOPE_TAPSENS, STI_SLOPE_FILT_BIT, filtered)
        writeCR3Bit(CR3_SLOPE_INT, true)
        writeCR3Bit(CR3_SLOPE_ALERT, false)
        writeCR3Bit(CR3_ADV_INT, true)
        enableSource(SOURCE_SLOPE)
    }

    void setAlert(boolean enabled) throws IOException {
        if (enabled) {
            enabledSources &= ~SOURCE_SLOPE
            writeCR3Bit(CR3_SLOPE_INT, false)
            writeCR3Bit(CR3_SLOPE_ALERT, true)
            writeCR3Bit(CR3_ADV_INT, true)
            enableSource(SOURCE_ALERT)
        } else {
            disableSource(SOURCE_ALERT)
            writeCR3Bit(CR3_SLOPE_ALERT, false)
        }
    }

    void setTap(float thresholdG, int windowMs, int axes, boolean filtered) throws IOException {
        writeSlopeThreshold(REG_TAPSENS_TH, thresholdG)
        writeTapDur(windowMs)
        writeTapAxes(axes)
        writeFiltBit(REG_SLOPE_TAPSENS, STI_TAP_FILT_BIT, filtered)
        enableSource(SOURCE_TAP)
    }

    void setLatch(boolean enabled) throws IOException {
        writeCR3Bit(CR3_LAT_INT, enabled)
    }

    void clearInterrupt() throws IOException {
        if (sleeping) return
        int ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_RESET_INT)
    }

    void enableInterrupt(int source) throws IOException {
        if (source == SOURCE_NEW_DATA) {
            writeCR3Bit(CR3_NEW_DATA_INT, true)
        } else {
            writeCR3Bit(CR3_NEW_DATA_INT, false)
            if      (source == SOURCE_SLOPE) {
                writeCR3Bit(CR3_SLOPE_ALERT, false)
                writeCR3Bit(CR3_SLOPE_INT, true)
                writeCR3Bit(CR3_ADV_INT, true)
                disableSource(SOURCE_ALERT)
            } else if (source == SOURCE_ALERT) {
                writeCR3Bit(CR3_SLOPE_INT, false)
                writeCR3Bit(CR3_SLOPE_ALERT, true)
                writeCR3Bit(CR3_ADV_INT, true)
                disableSource(SOURCE_SLOPE)
            } else if (source == SOURCE_HIGH_G) {
                writeCR3Bit(CR3_HIGH_INT, true)
            } else if (source == SOURCE_LOW_G) {
                writeCR3Bit(CR3_LOW_INT, true)
            } else if (source == SOURCE_TAP) {
                writeCR3Bit(CR3_TAP_INT, true)
            }
        }
        enableSource(source)
    }

    void disableInterrupt(int source) throws IOException {
        if      (source == SOURCE_NEW_DATA) writeCR3Bit(CR3_NEW_DATA_INT, false)
        else if (source == SOURCE_SLOPE)    writeCR3Bit(CR3_SLOPE_INT, false)
        else if (source == SOURCE_ALERT) {
            writeCR3Bit(CR3_SLOPE_ALERT, false)
            writeCR3Bit(CR3_ADV_INT, false)
        }
        else if (source == SOURCE_HIGH_G) writeCR3Bit(CR3_HIGH_INT, false)
        else if (source == SOURCE_LOW_G)  writeCR3Bit(CR3_LOW_INT, false)
        else if (source == SOURCE_TAP)    writeCR3Bit(CR3_TAP_INT, false)
        disableSource(source)
    }

    int pollInterrupt() throws IOException {
        return readReg(REG_STATUS_REG3)
    }

    int[] readStatus() throws IOException {
        return new int[] { readReg(REG_STATUS_REG1), readReg(REG_STATUS_REG2),
                           readReg(REG_STATUS_REG3), readReg(REG_STATUS_REG4) }
    }

    void setWakeUp(boolean enabled, int pauseMs) throws IOException {
        int code
        switch (pauseMs) {
            case 80:    code = 0x01; break
            case 320:   code = 0x02; break
            case 2560:  code = 0x03; break
            default:    code = 0x00; break
        }
        int tcoy = readReg(REG_TCO_Y)
        writeReg(REG_TCO_Y, (tcoy & 0xFC) | code)
        int gz = readReg(REG_GAIN_Z)
        int out = enabled ? gz | GZ_WAKE_UP : gz & ~GZ_WAKE_UP & 0xFF
        writeReg(REG_GAIN_Z, out)
    }

    void sleep() throws IOException {
        if (sleeping) return
        int ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_SLEEP)
        sleeping = true
    }

    void wake() throws IOException, InterruptedException {
        if (!sleeping) return
        int ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 & ~CTRL_REG0_SLEEP & 0xFF)
        Thread.sleep(2)
        sleeping = false
    }

    void softReset() throws IOException, InterruptedException {
        writeReg(REG_RESET, SOFT_RESET_CMD)
        Thread.sleep(30)
        this.rangeG = 2.0f
        this.rangeBits = RANGE_2G_MASK
        int id = readReg(REG_CHIP_ID)
        if ((id & CHIP_ID_MASK) != CHIP_ID_VALUE) {
            throw new IOException(String.format(
                "BMA180 CHIP_ID after reset: expected 0x%02X, got 0x%02X",
                CHIP_ID_VALUE, id & CHIP_ID_MASK))
        }
        int ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_EE_W)
        int olsb1 = readReg(REG_OFFSET_LSB1)
        writeReg(REG_OFFSET_LSB1, (olsb1 & 0xF1) | RANGE_2G_MASK)
        int bw = readReg(REG_BW_TCS)
        writeReg(REG_BW_TCS, (bw & 0x0F) | 0x40)
        sleeping = false
    }

    boolean selfTest() throws IOException, InterruptedException {
        int ctrl0 = readReg(REG_CTRL_REG0)
        writeReg(REG_CTRL_REG0, ctrl0 | CTRL_REG0_ST0)
        Thread.sleep(10)
        int[] r = readRaw()
        writeReg(REG_CTRL_REG0, ctrl0)
        boolean ok = Math.abs(r[0]) > 200 && Math.abs(r[1]) > 200 && Math.abs(r[2]) > 200
        softReset()
        return ok
    }

    void calibrateOffset(int axes, int mode) throws IOException, InterruptedException {
        if (mode < 0 || mode > 3) throw new IOException("mode must be 0..3")
        int cr4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (cr4 & 0xFC) | (mode & 0x03))
        int[][] ax = [[0x01, 0x80], [0x02, 0x40], [0x04, 0x20]] as int[][]
        for (int[] a in ax) {
            if ((axes & a[0]) == 0) continue
            int ctrl1 = readReg(REG_CTRL_REG1)
            writeReg(REG_CTRL_REG1, ctrl1 | a[1])
            for (int t = 0; t < 100; t++) {
                int s1 = readReg(REG_STATUS_REG1)
                if ((s1 & 0x02) != 0) break
                Thread.sleep(100)
            }
            ctrl1 = readReg(REG_CTRL_REG1)
            writeReg(REG_CTRL_REG1, ctrl1 & ~a[1] & 0xFF)
        }
        cr4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, cr4 & 0xFC)
    }

    int[] readVersion() throws IOException {
        int raw = readReg(REG_VERSION)
        return new int[] { (raw >> 4) & 0x0F, raw & 0x0F }
    }

    int readCustomer(int index) throws IOException {
        return readReg(index == 0 ? REG_CD1 : REG_CD2)
    }

    void writeCustomer(int index, int value) throws IOException {
        writeReg(index == 0 ? REG_CD1 : REG_CD2, value & 0xFF)
    }

    private void writeThreshold(int reg, float thresholdG) throws IOException {
        int code = Math.round(thresholdG / rangeG * 255.0f)
        if (code < 0)   code = 0
        if (code > 255) code = 255
        writeReg(reg, code)
    }

    private void writeSlopeThreshold(int reg, float thresholdG) throws IOException {
        int code = Math.round(thresholdG / (0.0156f * rangeG / 2.0f))
        if (code < 0)   code = 0
        if (code > 255) code = 255
        writeReg(reg, code)
    }

    private void writeLowDur(int durationMs) throws IOException {
        int code = Math.round(durationMs / DUR_LSB_MS)
        if (code < 0)   code = 0
        if (code > 127) code = 127
        int ld = readReg(REG_LOW_DUR)
        writeReg(REG_LOW_DUR, (ld & 0x01) | ((code & 0x7F) << 1))
    }

    private void writeHighDur(int durationMs) throws IOException {
        int code = Math.round(durationMs / DUR_LSB_MS)
        if (code < 0)   code = 0
        if (code > 127) code = 127
        int hd = readReg(REG_HIGH_DUR)
        writeReg(REG_HIGH_DUR, (hd & 0x01) | ((code & 0x7F) << 1))
    }

    private void writeLowHy(float hysteresisG) throws IOException {
        int code = Math.round(hysteresisG / rangeG * 255.0f / 32.0f)
        if (code < 0)  code = 0
        if (code > 31) code = 31
        int hy = readReg(REG_HY)
        writeReg(REG_HY, (hy & 0xF8) | (code & HY_LOW_MASK))
        int cr4 = readReg(REG_CTRL_REG4)
        writeReg(REG_CTRL_REG4, (cr4 & ~(0x03 << CR4_LOW_HY_SHIFT)) | (((code >> 3) & 0x03) << CR4_LOW_HY_SHIFT))
    }

    private void writeHighHy(float hysteresisG) throws IOException {
        int code = Math.round(hysteresisG / rangeG * 255.0f / 32.0f)
        if (code < 0)  code = 0
        if (code > 31) code = 31
        int hy = readReg(REG_HY)
        writeReg(REG_HY, (hy & 0x07) | ((code & 0x1F) << HY_HIGH_SHIFT))
    }

    private void writeLowAxes(int axes) throws IOException {
        int hli = readReg(REG_HIGH_LOW_INFO)
        writeReg(REG_HIGH_LOW_INFO, (hli & 0xF1) | ((axes & 0x07) << HLI_LOW_AXIS_SHIFT))
    }

    private void writeHighAxes(int axes) throws IOException {
        int hli = readReg(REG_HIGH_LOW_INFO)
        writeReg(REG_HIGH_LOW_INFO, (hli & 0x0F) | ((axes & 0x07) << HLI_HIGH_AXIS_SHIFT))
    }

    private void writeSlopeAxes(int axes) throws IOException {
        int st = readReg(REG_SLOPE_TAPSENS)
        writeReg(REG_SLOPE_TAPSENS, (st & 0x0F) | ((axes & 0x07) << STI_SLOPE_AXIS_SHIFT))
    }

    private void writeTapAxes(int axes) throws IOException {
        int st = readReg(REG_SLOPE_TAPSENS)
        writeReg(REG_SLOPE_TAPSENS, (st & 0xF1) | ((axes & 0x07) << STI_TAP_AXIS_SHIFT))
    }

    private void writeFiltBit(int reg, int bit, boolean enabled) throws IOException {
        int v = readReg(reg)
        int out = enabled ? v | bit : v & ~bit & 0xFF
        writeReg(reg, out)
    }

    private void writeDebounce(char kind, int counter) throws IOException {
        if (counter > 3) throw new IOException("counter must be 0..3")
        int code = (counter & 0x03) << 2
        int cr4 = readReg(REG_CTRL_REG4)
        if (kind == 'l') {
            writeReg(REG_CTRL_REG4, (cr4 & ~(0x03 << CR4_FF_CD_SHIFT)) | (code & (0x03 << CR4_FF_CD_SHIFT)))
        } else {
            writeReg(REG_CTRL_REG4, (cr4 & ~(0x03 << CR4_MOT_CD_SHIFT)) | ((code << 2) & (0x03 << CR4_MOT_CD_SHIFT)))
        }
    }

    private void writeSlopeDur(int samples) throws IOException {
        int code
        switch (samples) {
            case 3:  code = 0x01; break
            case 5:  code = 0x02; break
            case 7:  code = 0x03; break
            default: code = 0x00; break
        }
        int tcox = readReg(REG_TCO_X)
        writeReg(REG_TCO_X, (tcox & 0xFC) | code)
    }

    private void writeTapDur(int windowMs) throws IOException {
        int code = nearestTapDur(windowMs)
        int gt = readReg(REG_GAIN_T)
        writeReg(REG_GAIN_T, (gt & 0xF8) | code)
    }

    private void writeCR3Bit(int bit, boolean enabled) throws IOException {
        if (sleeping) return
        int cr3 = readReg(REG_CTRL_REG3)
        int out = enabled ? cr3 | bit : cr3 & ~bit & 0xFF
        writeReg(REG_CTRL_REG3, out)
    }

    private void enableSource(int source) {
        if (sleeping) return
        enabledSources |= source
    }

    private void disableSource(int source) {
        enabledSources &= ~source & 0xFF
    }

    private static int nearestBandwidth(int bandwidthHz) {
        int[] hz = [10, 20, 40, 75, 150, 300, 600, 1200] as int[]
        int[] codes = [0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70] as int[]
        int best = codes[3]
        int bestDiff = Integer.MAX_VALUE
        for (int i = 0; i < hz.length; i++) {
            int dd = Math.abs(hz[i] - bandwidthHz)
            if (dd < bestDiff) {
                bestDiff = dd
                best = codes[i]
            }
        }
        return best
    }

    private static int nearestTapDur(int windowMs) {
        int[] ms = [50, 75, 100, 150, 250, 500, 750, 1000] as int[]
        int[] codes = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07] as int[]
        int best = codes[4]
        int bestDiff = Integer.MAX_VALUE
        for (int i = 0; i < ms.length; i++) {
            if (ms[i] >= windowMs) {
                int dd = ms[i] - windowMs
                if (dd < bestDiff) {
                    bestDiff = dd
                    best = codes[i]
                }
            }
        }
        if (bestDiff == Integer.MAX_VALUE) {
            bestDiff = Integer.MAX_VALUE
            for (int i = ms.length - 1; i >= 0; i--) {
                int dd = windowMs - ms[i]
                if (dd < bestDiff) {
                    bestDiff = dd
                    best = codes[i]
                }
            }
        }
        return best
    }
}