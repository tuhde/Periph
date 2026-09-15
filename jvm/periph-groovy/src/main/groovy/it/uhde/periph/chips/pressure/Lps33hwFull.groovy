package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * LPS33HW — full driver. Extends {@link Lps33hwMinimal} with configuration,
 * one-shot, FIFO, interrupt routing, AUTOZERO/AUTORIFP, soft reset, reboot,
 * pressure offset, and status inspection.
 *
 * <h2>Output data rate constants</h2>
 * {@link #ODR_POWER_DOWN}, {@link #ODR_1_HZ}, {@link #ODR_10_HZ},
 * {@link #ODR_25_HZ}, {@link #ODR_50_HZ}, {@link #ODR_75_HZ}
 *
 * <h2>FIFO mode constants</h2>
 * {@link #FIFO_MODE_BYPASS}, {@link #FIFO_MODE_FIFO}, {@link #FIFO_MODE_STREAM},
 * {@link #FIFO_MODE_STREAM_TO_FIFO}, {@link #FIFO_MODE_BYPASS_TO_STREAM},
 * {@link #FIFO_MODE_DYNAMIC_STREAM}, {@link #FIFO_MODE_BYPASS_TO_FIFO}
 *
 * <h2>INT_DRDY signal constants</h2>
 * {@link #INT_S_DATA_SIGNALS}, {@link #INT_S_PRESSURE_HIGH},
 * {@link #INT_S_PRESSURE_LOW}, {@link #INT_S_PRESSURE_BOTH}
 */
@CompileStatic
class Lps33hwFull extends Lps33hwMinimal {

    /** Output data rate: power-down / one-shot. */
    static final int ODR_POWER_DOWN = 0
    /** Output data rate: 1 Hz. */
    static final int ODR_1_HZ       = 1
    /** Output data rate: 10 Hz. */
    static final int ODR_10_HZ      = 2
    /** Output data rate: 25 Hz. */
    static final int ODR_25_HZ      = 3
    /** Output data rate: 50 Hz. */
    static final int ODR_50_HZ      = 4
    /** Output data rate: 75 Hz. */
    static final int ODR_75_HZ      = 5

    /** LPF bandwidth: ODR / 9. */
    static final int LPFP_BW_ODR_9  = 0
    /** LPF bandwidth: ODR / 20. */
    static final int LPFP_BW_ODR_20 = 1

    /** FIFO mode: Bypass. */
    static final int FIFO_MODE_BYPASS           = 0
    /** FIFO mode: FIFO (fill to 32, then stop). */
    static final int FIFO_MODE_FIFO             = 1
    /** FIFO mode: Stream (circular). */
    static final int FIFO_MODE_STREAM           = 2
    /** FIFO mode: Stream-to-FIFO. */
    static final int FIFO_MODE_STREAM_TO_FIFO   = 3
    /** FIFO mode: Bypass-to-Stream. */
    static final int FIFO_MODE_BYPASS_TO_STREAM = 4
    /** FIFO mode: Dynamic-Stream. */
    static final int FIFO_MODE_DYNAMIC_STREAM   = 6
    /** FIFO mode: Bypass-to-FIFO. */
    static final int FIFO_MODE_BYPASS_TO_FIFO   = 7

    /** INT_DRDY signal: data signals (DRDY/F_FTH/F_OVR/F_FSS5). */
    static final int INT_S_DATA_SIGNALS  = 0
    /** INT_DRDY signal: pressure high. */
    static final int INT_S_PRESSURE_HIGH = 1
    /** INT_DRDY signal: pressure low. */
    static final int INT_S_PRESSURE_LOW  = 2
    /** INT_DRDY signal: pressure low or high. */
    static final int INT_S_PRESSURE_BOTH = 3

    /** Status bit: pressure data available. */
    static final int STATUS_P_DA_FLAG = 0x01
    /** Status bit: temperature data available. */
    static final int STATUS_T_DA_FLAG = 0x02
    /** Status bit: pressure data overrun. */
    static final int STATUS_P_OR_FLAG = 0x10
    /** Status bit: temperature data overrun. */
    static final int STATUS_T_OR_FLAG = 0x20

    /**
     * Construct the full driver, verify the chip ID, software-reset, and
     * apply the default configuration.
     *
     * @param connection I²C connection bound to address 0x5C
     * @throws IOException on I²C error or wrong chip ID
     */
    Lps33hwFull(Connection connection) {
        super(connection)
    }

    /**
     * Write CTRL_REG1 (ODR/BDU/EN_LPFP/LPFP_CFG/SIM) and the LC_EN bit
     * inside RES_CONF.
     */
    void configure(int odr, boolean bdu, boolean enLpfp, int lpfpCfg,
                   boolean lcEn, boolean sim) {
        int ctrl1 = ((odr & 7) << 4)
                  | (enLpfp ? (1 << 3) : 0)
                  | ((lpfpCfg & 1) << 2)
                  | (bdu ? (1 << 1) : 0)
                  | (sim ? 1 : 0)
        writeReg(REG_CTRL_REG1, ctrl1)

        int current = readReg(REG_RES_CONF)
        int newRes = (current & 0xFE) | (lcEn ? 1 : 0)
        writeReg(REG_RES_CONF, newRes)
    }

    /**
     * Trigger a single pressure+temperature measurement.
     */
    double[] oneShot() {
        int current = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, current | 0x01)
        for (int i = 0; i < 50; i++) {
            int status = readReg(REG_STATUS)
            if ((status & 0x03) == 0x03) {
                return readPressTemp()
            }
            Thread.sleep(5)
        }
        return new double[]{0.0d, 0.0d}
    }

    /**
     * Read the STATUS register.
     */
    int status() {
        return readReg(REG_STATUS)
    }

    /**
     * Read the INT_SOURCE register.
     */
    int interruptStatus() {
        return readReg(REG_INT_SOURCE)
    }

    /**
     * Software-reset via SWRESET, wait for self-clear, restore defaults.
     */
    void reset() {
        writeReg(REG_CTRL_REG2, CTRL_REG2_RESET)
        for (int i = 0; i < 50; i++) {
            int current = readReg(REG_CTRL_REG2)
            if ((current & 0x04) == 0) break
            Thread.sleep(1)
        }
        writeReg(REG_CTRL_REG2, CTRL_REG2_DEFAULT)
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    /**
     * Reload factory trimming from internal Flash via BOOT bit.
     */
    void reboot() {
        writeReg(REG_CTRL_REG2, 0x80)
        for (int i = 0; i < 100; i++) {
            int status = readReg(REG_INT_SOURCE)
            if ((status & 0x80) == 0) break
            Thread.sleep(5)
        }
    }

    /**
     * Write RPDS to apply a one-point calibration offset.
     */
    void setPressureOffset(double offsetHPa) {
        int raw = (int) Math.round(offsetHPa * 16)
        if (raw < 0) raw += 0x10000
        writeReg(REG_RPDS_L, raw & 0xFF)
        writeReg(REG_RPDS_H, (raw >> 8) & 0xFF)
    }

    /** Set AUTOZERO=1 — current pressure is stored in REF_P. */
    void setAutozero() {
        int current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current | 0x20)
    }

    /** Clear AUTOZERO mode and reset REF_P to 0. */
    void clearAutozero() {
        int current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current | 0x10)
    }

    /** Set AUTORIFP=1 — next measurement value is stored in RPDS. */
    void setAutorifp() {
        int current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current | 0x80)
    }

    /** Clear AUTORIFP mode and reset RPDS to 0. */
    void clearAutorifp() {
        int current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current | 0x40)
    }

    /**
     * Route CTRL_REG3 events to the INT_DRDY pin.
     */
    void configureInterrupt(boolean drdy, boolean fFth, boolean fOvr, boolean fFss5,
                            int intS, boolean activeLow, boolean openDrain) {
        int ctrl3 = (activeLow ? (1 << 7) : 0)
                  | (openDrain ? (1 << 6) : 0)
                  | (fFss5     ? (1 << 5) : 0)
                  | (fFth      ? (1 << 4) : 0)
                  | (fOvr      ? (1 << 3) : 0)
                  | (drdy      ? (1 << 2) : 0)
                  | (intS & 0x03)
        writeReg(REG_CTRL_REG3, ctrl3)
    }

    /**
     * Configure the differential pressure threshold interrupt.
     */
    void configurePressureInterrupt(boolean highEn, boolean lowEn,
                                    double thresholdHPa, boolean latch) {
        int rawThs = ((int) Math.round(thresholdHPa * 16)) & 0xFFFF
        writeReg(REG_THS_P_L, rawThs & 0xFF)
        writeReg(REG_THS_P_H, (rawThs >> 8) & 0xFF)

        int current = readReg(REG_INTERRUPT_CFG)
        int newCfg = (current & 0xF0)
                   | (latch  ? 0x04 : 0)
                   | (highEn ? 0x02 : 0)
                   | (lowEn  ? 0x01 : 0)
        writeReg(REG_INTERRUPT_CFG, newCfg)
    }

    /**
     * Enable the FIFO with the given mode and watermark.
     */
    void enableFifo(int mode, int watermark) {
        if (mode == 5) {
            return  // reserved
        }
        int ctrl = ((mode & 7) << 5) | (watermark & 0x1F)
        writeReg(REG_FIFO_CTRL, ctrl)
        int current = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, current | 0x40)
    }

    /** Disable the FIFO and reset to Bypass mode. */
    void disableFifo() {
        int current = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, current & 0xBF)
        writeReg(REG_FIFO_CTRL, 0)
    }

    /**
     * Read the FIFO_STATUS register.
     */
    int fifoStatus() {
        return readReg(REG_FIFO_STATUS)
    }

    /**
     * Read LPFP_RES to flush any transitory LPF state.
     */
    void resetLpf() {
        readReg(REG_LPFP_RES)
    }
}