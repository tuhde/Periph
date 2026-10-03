package it.uhde.periph.chips.gyroscope

import it.uhde.periph.connection.RegisterConnection
import groovy.transform.CompileStatic

/**
 * L3GD20H three-axis MEMS gyroscope — full driver.
 *
 * Extends {@link L3gd20hMinimal} with configuration, FIFO,
 * high-pass filter, interrupts, axis-enable, and power-mode control.
 */
@CompileStatic
class L3gd20hFull extends L3gd20hMinimal {

    static final int ODR_95_HZ  = 0
    static final int ODR_190_HZ = 1
    static final int ODR_380_HZ = 2
    static final int ODR_760_HZ = 3

    static final int FS_250_DPS  = 0
    static final int FS_500_DPS  = 1
    static final int FS_2000_DPS = 2

    static final int FIFO_BYPASS             = 0
    static final int FIFO_FIFO               = 1
    static final int FIFO_STREAM             = 2
    static final int FIFO_BYPASS_TO_STREAM   = 3
    static final int FIFO_STREAM_TO_FIFO     = 7

    static final int HPM_NORMAL      = 0
    static final int HPM_REFERENCE   = 1
    static final int HPM_NORMAL_ALT  = 2
    static final int HPM_AUTORESET   = 3

    static final String POWER_NORMAL     = "normal"
    static final String POWER_SLEEP      = "sleep"
    static final String POWER_POWERDOWN  = "power_down"

    private int odr = ODR_95_HZ
    private int bw = 0

    L3gd20hFull(RegisterConnection connection) throws IOException {
        super(connection)
    }

    /** Read a register via I²C or SPI. */
    /**
     * Configure ODR, bandwidth, and full scale in one call.
     *
     * @param odr         ODR code 0-3 (95/190/380/760 Hz).
     * @param bw          Bandwidth code 0-3 (ODR-dependent).
     * @param fullScale   Full-scale code 0=±250, 1=±500, 2=±2000 dps.
     */
    void configure(int odr, int bw, int fullScale) throws IOException {
        if (odr > 3 || bw > 3 || fullScale > 2) return
        this.odr = odr
        this.bw = bw
        int[] fsMap = [250, 500, 2000]
        this.fullScale = fsMap[fullScale]
        int ctrl1 = CTRL_REG1_DEFAULT | ((this.odr & 0x3) << 6) | ((this.bw & 0x3) << 4)
        connection.write(REG_CTRL_REG1, [(byte) (ctrl1)] as byte[])
        connection.write(REG_CTRL_REG4, [(byte) (CTRL_REG4_DEFAULT | ((fullScale & 0x3) << 4))] as byte[])
    }

    /**
     * Read raw 16-bit signed angular rate values.
     *
     * @return short[3] {x_raw, y_raw, z_raw}.
     */
    short[] gyroRaw() throws IOException {
        byte[] raw = readReg(REG_OUT_X_L, 6)
        return [
            int16Le(raw, 0),
            int16Le(raw, 2),
            int16Le(raw, 4)
        ] as short[]
    }

    /**
     * Read the relative temperature count.
     *
     * OUT_TEMP is an 8-bit signed value with 1 LSB/°C sensitivity. There is
     * no absolute calibration — it represents change from the device's
     * power-on temperature baseline. Do not convert to absolute Celsius.
     *
     * @return signed 8-bit temperature count.
     */
    int temperature() throws IOException {
        return (readReg(REG_OUT_TEMP, 1)[0] & 0xFF) as byte
    }

    /**
     * Check whether a new X/Y/Z sample is ready.
     *
     * @return true if STATUS_REG.ZYXDA (bit 3) is set.
     */
    boolean dataReady() throws IOException {
        return (readReg(REG_STATUS, 1)[0] & 0x08) != 0
    }

    /**
     * Configure the high-pass filter (CTRL_REG2).
     *
     * @param mode   HPF mode 0-3 (HPM field).
     * @param cutoff HPF cutoff code 0-15 (HPCF[3:0]).
     */
    void configureHpFilter(int mode, int cutoff) throws IOException {
        if (mode > 3 || cutoff > 15) return
        connection.write(REG_CTRL_REG2, [(byte) (((mode & 0x3) << 4) | (cutoff & 0x0F))] as byte[])
    }

    /**
     * Enable or disable the high-pass filter on the output path.
     *
     * @param enable True to enable (sets HPen in CTRL_REG5), False to disable.
     */
    void enableHpFilter(boolean enable) throws IOException {
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        connection.write(REG_CTRL_REG5, [(byte) (enable ? (ctrl5 | 0x10) : (ctrl5 & ~0x10))] as byte[])
    }

    /**
     * Configure the FIFO (FIFO_CTRL_REG).
     *
     * @param mode      FIFO mode 0=Bypass, 1=FIFO, 2=Stream, 3=Bypass-to-Stream, 7=Stream-to-FIFO.
     * @param watermark Watermark threshold 0-31 (WTM[4:0]).
     */
    void configureFifo(int mode, int watermark) throws IOException {
        boolean valid = mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7
        if (!valid || watermark < 0 || watermark > 31) return
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        connection.write(REG_CTRL_REG5, [(byte) (ctrl5 | 0x40)] as byte[])
        connection.write(REG_FIFO_CTRL, [(byte) (((mode & 0x7) << 5) | (watermark & 0x1F))] as byte[])
    }

    /**
     * Enable or disable the FIFO (FIFO_EN bit in CTRL_REG5).
     *
     * @param enable True to enable FIFO, False to disable and clear to bypass.
     */
    void enableFifo(boolean enable) throws IOException {
        int ctrl5 = readReg(REG_CTRL_REG5, 1)[0] & 0xFF
        if (enable) {
            connection.write(REG_CTRL_REG5, [(byte) (ctrl5 | 0x40)] as byte[])
        } else {
            connection.write(REG_CTRL_REG5, [(byte) (ctrl5 & ~0x40)] as byte[])
            connection.write(REG_FIFO_CTRL, [(byte) (0x00)] as byte[])
        }
    }

    /**
     * Read number of unread samples in FIFO (FIFO_SRC_REG FSS[4:0]).
     *
     * @return Number of stored samples (0-31).
     */
    int fifoLevel() throws IOException {
        return readReg(REG_FIFO_SRC, 1)[0] & 0x1F
    }

    /**
     * Read all available FIFO samples and return as rad/s tuples.
     *
     * Each burst read of OUT_X_L through OUT_Z_H pops the oldest entry.
     * Reads FIFO_SRC_REG to determine sample count, then burst-reads all.
     *
     * @return List of float[3] {x, y, z} in rad/s.
     */
    List<float[]> readFifo() throws IOException {
        int n = fifoLevel()
        if (n == 0) return Collections.emptyList()
        float sens = sensitivity(fullScale)
        float k = (float) (Math.PI / 180.0)
        byte[] buf = readReg(REG_OUT_X_L, n * 6)
        List<float[]> out = new ArrayList<>(n)
        for (int i = 0; i < n; i++) {
            int o = i * 6
            float x = int16Le(buf, o) * sens * k
            float y = int16Le(buf, o + 2) * sens * k
            float z = int16Le(buf, o + 4) * sens * k
            out.add([x, y, z] as float[])
        }
        return out
    }

    /**
     * Set the power mode (CTRL_REG1 PD and axis enable bits).
     *
     * @param mode "normal" (PD=1, all axes on), "sleep" (PD=1, all axes off),
     *              or "power_down" (PD=0).
     */
    void setPowerMode(String mode) throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1, 1)[0] & 0xFF
        switch (mode) {
            case POWER_NORMAL:
                ctrl1 = (ctrl1 & 0xF0) | 0x0F
                break
            case POWER_SLEEP:
                ctrl1 = (ctrl1 & 0xF8) | 0x08
                break
            case POWER_POWERDOWN:
                ctrl1 &= 0xF7
                break
        }
        connection.write(REG_CTRL_REG1, [(byte) (ctrl1)] as byte[])
    }
}