package it.uhde.periph.chips.gyroscope

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * L3G4200D three-axis MEMS gyroscope — full driver.
 *
 * Extends L3g4200dMinimal with configuration, FIFO, high-pass filter,
 * interrupts, axis-enable, and power-mode control.
 */
@CompileStatic
class L3g4200dFull extends L3g4200dMinimal {

    static final int ODR_100_HZ = 0
    static final int ODR_200_HZ = 1
    static final int ODR_400_HZ = 2
    static final int ODR_800_HZ = 3

    static final int FS_250_DPS  = 250
    static final int FS_500_DPS  = 500
    static final int FS_2000_DPS = 2000

    static final int FIFO_BYPASS           = 0
    static final int FIFO_FIFO             = 1
    static final int FIFO_STREAM           = 2
    static final int FIFO_STREAM_TO_FIFO   = 3
    static final int FIFO_BYPASS_TO_STREAM = 4

    private int odr = ODR_100_HZ
    private int bw = 0

    L3g4200dFull(Connection connection, boolean spi) throws Exception {
        super(connection, spi)
    }

    private byte[] readReg(int reg, int n) throws Exception {
        if (spi) {
            connection.write([(byte) ((reg | 0xC0) & 0xFF)] as byte[])
            return connection.read(n)
        }
        int addr = n > 1 ? (reg | 0x80) : reg
        return connection.writeRead([(byte) addr] as byte[], n)
    }

    void configure(int odr, int bandwidth, int fullScale) throws Exception {
        if (fullScale != FS_250_DPS && fullScale != FS_500_DPS && fullScale != FS_2000_DPS) return
        this.odr = odr & 0x3
        this.bw = bandwidth & 0x3
        this.fullScaleDps = fullScale
        int ctrl1 = CTRL_REG1_DEFAULT | ((this.odr & 0x3) << 6) | ((this.bw & 0x3) << 4)
        writeReg(REG_CTRL_REG1, ctrl1)
        int fsBits = (fullScale == FS_250_DPS) ? 0 : (fullScale == FS_500_DPS ? 1 : 2)
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT | ((fsBits & 0x3) << 4))
    }

    void setFullScale(int fullScale) throws Exception {
        if (fullScale != FS_250_DPS && fullScale != FS_500_DPS && fullScale != FS_2000_DPS) return
        this.fullScaleDps = fullScale
        int fsBits = (fullScale == FS_250_DPS) ? 0 : (fullScale == FS_500_DPS ? 1 : 2)
        int ctrl4 = readReg(REG_CTRL_REG4, 1)[0] & 0xFF
        ctrl4 = (ctrl4 & 0xCF) | ((fsBits & 0x3) << 4)
        writeReg(REG_CTRL_REG4, ctrl4)
    }

    int whoAmI() throws Exception {
        return readReg(REG_WHO_AM_I, 1)[0] & 0xFF
    }

    int status() throws Exception {
        return readReg(REG_STATUS, 1)[0] & 0xFF
    }

    boolean dataReady() throws Exception {
        return (status() & 0x08) != 0
    }

    int temperature() throws Exception {
        return (byte) (readReg(REG_OUT_TEMP, 1)[0] & 0xFF)
    }

    void powerDown() throws Exception {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF
        writeReg(REG_CTRL_REG1, ctrl1 & 0xF7)
    }

    void wakeUp() throws Exception {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF
        writeReg(REG_CTRL_REG1, ctrl1 | 0x08)
    }

    void sleep() throws Exception {
        writeReg(REG_CTRL_REG1, 0x08)
    }

    void enableAxes(boolean x, boolean y, boolean z) throws Exception {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF
        ctrl1 = ctrl1 & 0xF8
        if (z) ctrl1 |= 0x04
        if (y) ctrl1 |= 0x02
        if (x) ctrl1 |= 0x01
        writeReg(REG_CTRL_REG1, ctrl1)
    }

    void enableFifo(int mode, int watermark) throws Exception {
        if (mode < 0 || mode > 4) return
        int wm = watermark
        if (wm < 0) wm = 0
        if (wm > 31) wm = 31
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 | 0x40)
        writeReg(REG_FIFO_CTRL, ((mode & 0x7) << 5) | (wm & 0x1F))
    }

    void disableFifo() throws Exception {
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 & ~0x40)
        writeReg(REG_FIFO_CTRL, 0x00)
    }

    int fifoSamples() throws Exception {
        return readReg(REG_FIFO_SRC, 1)[0] & 0x1F
    }

    List<float[]> readFifo() throws Exception {
        int n = fifoSamples()
        if (n == 0) return new ArrayList<float[]>()
        float sens = sensitivity(fullScaleDps)
        float k = (float) (Math.PI / 180.0)
        byte[] buf = readReg(REG_OUT_X_L, n * 6)
        List<float[]> out = new ArrayList<float[]>(n)
        for (int i = 0; i < n; i++) {
            int o = i * 6
            float x = int16Le(buf, o) * sens * k
            float y = int16Le(buf, o + 2) * sens * k
            float z = int16Le(buf, o + 4) * sens * k
            out.add([x, y, z] as float[])
        }
        return out
    }

    void enableHighpass(int mode, int cutoff) throws Exception {
        if (mode < 0 || mode > 3) return
        if (cutoff < 0 || cutoff > 9) return
        writeReg(REG_CTRL_REG2, ((mode & 0x3) << 4) | (cutoff & 0x0F))
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 | 0x10)
    }

    void disableHighpass() throws Exception {
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        writeReg(REG_CTRL_REG5, ctrl5 & ~0x10)
    }

    void setInterrupt(boolean xHigh, boolean xLow,
                       boolean yHigh, boolean yLow,
                       boolean zHigh, boolean zLow,
                       boolean andMode, boolean latch) throws Exception {
        int cfg = 0
        if (andMode) cfg |= 0x80
        if (latch)   cfg |= 0x40
        if (zHigh)   cfg |= 0x20
        if (zLow)    cfg |= 0x10
        if (yHigh)   cfg |= 0x08
        if (yLow)    cfg |= 0x04
        if (xHigh)   cfg |= 0x02
        if (xLow)    cfg |= 0x01
        writeReg(REG_INT1_CFG, cfg)
        if ((cfg & 0x3F) != 0) {
            int ctrl3 = readReg(REG_CTRL_REG3, 1)[0] & 0xFF
            writeReg(REG_CTRL_REG3, ctrl3 | 0x80)
        }
    }

    void setThreshold(char axis, float thresholdDps) throws Exception {
        // Groovy's `/` widens to double; round-trip through float so the
        // truncation below matches the other languages' native float division.
        int raw = ((int) (float) (thresholdDps / sensitivity(fullScaleDps))) & 0x7FFF
        int hiReg, loReg
        switch (axis) {
            case 'x': hiReg = REG_INT1_THS_XH; loReg = REG_INT1_THS_XL; break
            case 'y': hiReg = REG_INT1_THS_YH; loReg = REG_INT1_THS_YL; break
            case 'z': hiReg = REG_INT1_THS_ZH; loReg = REG_INT1_THS_ZL; break
            default: return
        }
        writeReg(hiReg, (raw >> 8) & 0x7F)
        writeReg(loReg, raw & 0xFF)
    }

    void setDuration(int samples, boolean wait) throws Exception {
        if (samples < 0 || samples > 127) return
        int val = ((wait ? 1 : 0) << 7) | (samples & 0x7F)
        writeReg(REG_INT1_DURATION, val)
    }

    int readIntSource() throws Exception {
        return readReg(REG_INT1_SRC, 1)[0] & 0xFF
    }

    void setDataReadyPin(boolean enable) throws Exception {
        int ctrl3 = readReg(REG_CTRL_REG3, 1)[0] & 0xFF
        int v = enable ? (ctrl3 | 0x08) : (ctrl3 & ~0x08)
        writeReg(REG_CTRL_REG3, v)
    }
}
