package it.uhde.periph.chips.gyroscope;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * L3G4200D three-axis MEMS gyroscope — full driver.
 *
 * <p>Extends {@link L3g4200dMinimal} with configuration, FIFO,
 * high-pass filter, interrupts, axis-enable, and power-mode control.
 */
public class L3g4200dFull extends L3g4200dMinimal {

    /** ODR codes (DR[1:0] in CTRL_REG1). */
    public static final int ODR_100_HZ = 0;
    public static final int ODR_200_HZ = 1;
    public static final int ODR_400_HZ = 2;
    public static final int ODR_800_HZ = 3;

    /** Full-scale ranges. */
    public static final int FS_250_DPS  = 250;
    public static final int FS_500_DPS  = 500;
    public static final int FS_2000_DPS = 2000;

    /** FIFO modes (FM[2:0] in FIFO_CTRL_REG). */
    public static final int FIFO_BYPASS           = 0;
    public static final int FIFO_FIFO             = 1;
    public static final int FIFO_STREAM           = 2;
    public static final int FIFO_STREAM_TO_FIFO   = 3;
    public static final int FIFO_BYPASS_TO_STREAM = 4;

    private int odr = ODR_100_HZ;
    private int bw = 0;

    public L3g4200dFull(Connection connection, boolean spi) throws IOException {
        super(connection, spi);
    }

    /** Read a single byte via I²C or SPI (with appropriate register address framing). */
    private byte[] readReg(int reg, int n) throws IOException {
        if (spi) {
            connection.write(new byte[] { (byte) ((reg | 0xC0) & 0xFF) });
            return connection.read(n);
        } else if (n > 1) {
            return connection.writeRead(new byte[] { (byte) (reg | 0x80) }, n);
        } else {
            return connection.writeRead(new byte[] { (byte) reg }, n);
        }
    }

    /**
     * Configure ODR, LPF2 bandwidth, and full scale in one call.
     *
     * @param odr        ODR code 0-3 (100/200/400/800 Hz).
     * @param bandwidth  LPF2 bandwidth code 0-3.
     * @param fullScale  Full-scale in dps (250, 500, or 2000).
     */
    public void configure(int odr, int bandwidth, int fullScale) throws IOException {
        if (fullScale != FS_250_DPS && fullScale != FS_500_DPS && fullScale != FS_2000_DPS) return;
        this.odr = odr & 0x3;
        this.bw = bandwidth & 0x3;
        this.fullScale = fullScale;
        int ctrl1 = CTRL_REG1_DEFAULT | ((this.odr & 0x3) << 6) | ((this.bw & 0x3) << 4);
        writeReg(REG_CTRL_REG1, ctrl1);
        int fsBits = (fullScale == FS_250_DPS) ? 0 : (fullScale == FS_500_DPS ? 1 : 2);
        writeReg(REG_CTRL_REG4, CTRL_REG4_DEFAULT | ((fsBits & 0x3) << 4));
    }

    /** Update the full-scale range. */
    public void setFullScale(int fullScale) throws IOException {
        if (fullScale != FS_250_DPS && fullScale != FS_500_DPS && fullScale != FS_2000_DPS) return;
        this.fullScale = fullScale;
        int fsBits = (fullScale == FS_250_DPS) ? 0 : (fullScale == FS_500_DPS ? 1 : 2);
        int ctrl4 = readReg(REG_CTRL_REG4, 1)[0] & 0xFF;
        ctrl4 = (ctrl4 & 0xCF) | ((fsBits & 0x3) << 4);
        writeReg(REG_CTRL_REG4, ctrl4);
    }

    /** @return WHO_AM_I (0xD3 for genuine L3G4200D). */
    public int whoAmI() throws IOException {
        return readReg(REG_WHO_AM_I, 1)[0] & 0xFF;
    }

    /** @return raw STATUS_REG byte. */
    public int status() throws IOException {
        return readReg(REG_STATUS, 1)[0] & 0xFF;
    }

    /** @return true if STATUS_REG.ZYXDA (bit 3) is set. */
    public boolean dataReady() throws IOException {
        return (status() & 0x08) != 0;
    }

    /**
     * Read OUT_TEMP (signed 8-bit value; -1 °C/digit scale; relative
     * calibration only).
     */
    public int temperature() throws IOException {
        return (byte) (readReg(REG_OUT_TEMP, 1)[0] & 0xFF);
    }

    /** Enter power-down mode (PD=0 in CTRL_REG1). */
    public void powerDown() throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF;
        writeReg(REG_CTRL_REG1, ctrl1 & 0xF7);
    }

    /** Wake from power-down (PD=1); previously enabled axes restored. */
    public void wakeUp() throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF;
        writeReg(REG_CTRL_REG1, ctrl1 | 0x08);
    }

    /** Enter sleep mode (PD=1, all axes disabled). */
    public void sleep() throws IOException {
        writeReg(REG_CTRL_REG1, 0x08);
    }

    /** Enable or disable individual axes (Xen/Yen/Zen in CTRL_REG1). */
    public void enableAxes(boolean x, boolean y, boolean z) throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF;
        ctrl1 &= 0xF8;
        if (z) ctrl1 |= 0x04;
        if (y) ctrl1 |= 0x02;
        if (x) ctrl1 |= 0x01;
        writeReg(REG_CTRL_REG1, ctrl1);
    }

    /**
     * Configure and enable the FIFO.
     *
     * @param mode      FIFO mode 0-4.
     * @param watermark Watermark threshold 0-31.
     */
    public void enableFifo(int mode, int watermark) throws IOException {
        if (mode < 0 || mode > 4) return;
        if (watermark < 0) watermark = 0;
        if (watermark > 31) watermark = 31;
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF;
        writeReg(REG_CTRL_REG5, ctrl5 | 0x40);
        writeReg(REG_FIFO_CTRL, ((mode & 0x7) << 5) | (watermark & 0x1F));
    }

    /** Disable the FIFO. */
    public void disableFifo() throws IOException {
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF;
        writeReg(REG_CTRL_REG5, ctrl5 & ~0x40);
        writeReg(REG_FIFO_CTRL, 0x00);
    }

    /** @return FSS[4:0] from FIFO_SRC_REG. */
    public int fifoSamples() throws IOException {
        return readReg(REG_FIFO_SRC, 1)[0] & 0x1F;
    }

    /**
     * Read all stored FIFO samples.
     *
     * @return List of float[3] {x, y, z} tuples in rad/s.
     */
    public java.util.List<float[]> readFifo() throws IOException {
        int n = fifoSamples();
        if (n == 0) return java.util.Collections.emptyList();
        float sens = sensitivity(fullScale);
        float k = (float) (Math.PI / 180.0);
        byte[] buf = readReg(REG_OUT_X_L, n * 6);
        java.util.List<float[]> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int o = i * 6;
            float x = int16Le(buf, o) * sens * k;
            float y = int16Le(buf, o + 2) * sens * k;
            float z = int16Le(buf, o + 4) * sens * k;
            out.add(new float[] { x, y, z });
        }
        return out;
    }

    /**
     * Enable the high-pass filter on the output path.
     *
     * @param mode   HPF mode 0-3.
     * @param cutoff HPF cutoff code 0-9.
     */
    public void enableHighpass(int mode, int cutoff) throws IOException {
        if (mode < 0 || mode > 3) return;
        if (cutoff < 0 || cutoff > 9) return;
        writeReg(REG_CTRL_REG2, ((mode & 0x3) << 4) | (cutoff & 0x0F));
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF;
        writeReg(REG_CTRL_REG5, ctrl5 | 0x10);
    }

    /** Clear HPen in CTRL_REG5. */
    public void disableHighpass() throws IOException {
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF;
        writeReg(REG_CTRL_REG5, ctrl5 & ~0x10);
    }

    /** Configure INT1_CFG axis/direction events. */
    public void setInterrupt(boolean xHigh, boolean xLow,
                              boolean yHigh, boolean yLow,
                              boolean zHigh, boolean zLow,
                              boolean andMode, boolean latch) throws IOException {
        int cfg = 0;
        if (andMode) cfg |= 0x80;
        if (latch)    cfg |= 0x40;
        if (zHigh)   cfg |= 0x20;
        if (zLow)    cfg |= 0x10;
        if (yHigh)   cfg |= 0x08;
        if (yLow)    cfg |= 0x04;
        if (xHigh)   cfg |= 0x02;
        if (xLow)    cfg |= 0x01;
        writeReg(REG_INT1_CFG, cfg);
        if ((cfg & 0x3F) != 0) {
            int ctrl3 = readReg(REG_CTRL_REG3, 1)[0] & 0xFF;
            writeReg(REG_CTRL_REG3, ctrl3 | 0x80);
        }
    }

    /**
     * Set the interrupt threshold for one axis.
     *
     * @param axis          'x', 'y', or 'z'.
     * @param thresholdDps  Threshold in dps.
     */
    public void setThreshold(char axis, float thresholdDps) throws IOException {
        int raw = ((int) (thresholdDps / sensitivity(fullScale))) & 0x7FFF;
        int hiReg, loReg;
        switch (axis) {
            case 'x': hiReg = REG_INT1_THS_XH; loReg = REG_INT1_THS_XL; break;
            case 'y': hiReg = REG_INT1_THS_YH; loReg = REG_INT1_THS_YL; break;
            case 'z': hiReg = REG_INT1_THS_ZH; loReg = REG_INT1_THS_ZL; break;
            default: return;
        }
        writeReg(hiReg, (raw >> 8) & 0x7F);
        writeReg(loReg, raw & 0xFF);
    }

    /** Set INT1_DURATION. */
    public void setDuration(int samples, boolean wait) throws IOException {
        if (samples < 0 || samples > 127) return;
        int val = ((wait ? 1 : 0) << 7) | (samples & 0x7F);
        writeReg(REG_INT1_DURATION, val);
    }

    /** Read INT1_SRC; reading clears the interrupt-active bit. */
    public int readIntSource() throws IOException {
        return readReg(REG_INT1_SRC, 1)[0] & 0xFF;
    }

    /** Route the data-ready signal to the DRDY/INT2 pin. */
    public void setDataReadyPin(boolean enable) throws IOException {
        int ctrl3 = readReg(REG_CTRL_REG3, 1)[0] & 0xFF;
        if (enable) {
            ctrl3 |= 0x08;
        } else {
            ctrl3 &= ~0x08;
        }
        writeReg(REG_CTRL_REG3, ctrl3);
    }
}
