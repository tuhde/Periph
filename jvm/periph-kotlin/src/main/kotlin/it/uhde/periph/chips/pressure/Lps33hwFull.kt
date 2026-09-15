package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection

/**
 * LPS33HW — full driver. Extends [Lps33hwMinimal] with configuration,
 * one-shot, FIFO, interrupt routing, AUTOZERO/AUTORIFP, soft reset, reboot,
 * pressure offset, and status inspection.
 *
 * ## Output data rate constants
 * [ODR_POWER_DOWN], [ODR_1_HZ], [ODR_10_HZ], [ODR_25_HZ], [ODR_50_HZ],
 * [ODR_75_HZ]
 *
 * ## FIFO mode constants
 * [FIFO_MODE_BYPASS], [FIFO_MODE_FIFO], [FIFO_MODE_STREAM],
 * [FIFO_MODE_STREAM_TO_FIFO], [FIFO_MODE_BYPASS_TO_STREAM],
 * [FIFO_MODE_DYNAMIC_STREAM], [FIFO_MODE_BYPASS_TO_FIFO]
 *
 * ## INT_DRDY signal constants
 * [INT_S_DATA_SIGNALS], [INT_S_PRESSURE_HIGH], [INT_S_PRESSURE_LOW],
 * [INT_S_PRESSURE_BOTH]
 */
class Lps33hwFull(connection: Connection) : Lps33hwMinimal(connection) {

    companion object {
        /** Output data rate: power-down / one-shot. */
        const val ODR_POWER_DOWN = 0
        /** Output data rate: 1 Hz. */
        const val ODR_1_HZ       = 1
        /** Output data rate: 10 Hz. */
        const val ODR_10_HZ      = 2
        /** Output data rate: 25 Hz. */
        const val ODR_25_HZ      = 3
        /** Output data rate: 50 Hz. */
        const val ODR_50_HZ      = 4
        /** Output data rate: 75 Hz. */
        const val ODR_75_HZ      = 5

        /** LPF bandwidth: ODR / 9. */
        const val LPFP_BW_ODR_9  = 0
        /** LPF bandwidth: ODR / 20. */
        const val LPFP_BW_ODR_20 = 1

        /** FIFO mode: Bypass. */
        const val FIFO_MODE_BYPASS           = 0
        /** FIFO mode: FIFO (fill to 32, then stop). */
        const val FIFO_MODE_FIFO             = 1
        /** FIFO mode: Stream (circular). */
        const val FIFO_MODE_STREAM           = 2
        /** FIFO mode: Stream-to-FIFO. */
        const val FIFO_MODE_STREAM_TO_FIFO   = 3
        /** FIFO mode: Bypass-to-Stream. */
        const val FIFO_MODE_BYPASS_TO_STREAM = 4
        /** FIFO mode: Dynamic-Stream. */
        const val FIFO_MODE_DYNAMIC_STREAM   = 6
        /** FIFO mode: Bypass-to-FIFO. */
        const val FIFO_MODE_BYPASS_TO_FIFO   = 7

        /** INT_DRDY signal: data signals (DRDY/F_FTH/F_OVR/F_FSS5). */
        const val INT_S_DATA_SIGNALS  = 0
        /** INT_DRDY signal: pressure high. */
        const val INT_S_PRESSURE_HIGH = 1
        /** INT_DRDY signal: pressure low. */
        const val INT_S_PRESSURE_LOW  = 2
        /** INT_DRDY signal: pressure low or high. */
        const val INT_S_PRESSURE_BOTH = 3

        /** Status bit: pressure data available. */
        const val STATUS_P_DA_FLAG = 0x01
        /** Status bit: temperature data available. */
        const val STATUS_T_DA_FLAG = 0x02
        /** Status bit: pressure data overrun. */
        const val STATUS_P_OR_FLAG = 0x10
        /** Status bit: temperature data overrun. */
        const val STATUS_T_OR_FLAG = 0x20
    }

    /**
     * Write CTRL_REG1 (ODR/BDU/EN_LPFP/LPFP_CFG/SIM) and the LC_EN bit
     * inside RES_CONF.
     *
     * @param odr     output data rate (0–5, use `ODR_*`)
     * @param bdu     block data update (true = hold until PRESS_OUT_H read)
     * @param enLpfp  enable additional low-pass filter on pressure
     * @param lpfpCfg LPF bandwidth when enabled (0=ODR/9, 1=ODR/20)
     * @param lcEn    low-current mode (only writable in power-down)
     * @param sim     SPI 3-wire mode (false=4-wire, true=3-wire)
     */
    fun configure(odr: Int, bdu: Boolean, enLpfp: Boolean, lpfpCfg: Int,
                 lcEn: Boolean, sim: Boolean) {
        val ctrl1 = ((odr and 7) shl 4) or
                    (if (enLpfp) (1 shl 3) else 0) or
                    ((lpfpCfg and 1) shl 2) or
                    (if (bdu) (1 shl 1) else 0) or
                    (if (sim) 1 else 0)
        writeReg(REG_CTRL_REG1, ctrl1)

        val current = readReg(REG_RES_CONF)
        val newRes = (current and 0xFE) or (if (lcEn) 1 else 0)
        writeReg(REG_RES_CONF, newRes)
    }

    /**
     * Trigger a single pressure+temperature measurement.
     *
     * Requires ODR=000 (power-down). Writes ONE_SHOT in CTRL_REG2 and polls
     * STATUS until both P_DA and T_DA are set, then bursts 5 bytes.
     *
     * @return Pair(pressure_Pa, temperature_C)
     */
    fun oneShot(): Pair<Double, Double> {
        val current = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, current or 0x01)
        for (i in 0 until 50) {
            val status = readReg(REG_STATUS)
            if ((status and 0x03) == 0x03) {
                return readPressTemp()
            }
            Thread.sleep(5)
        }
        return Pair(0.0, 0.0)
    }

    /**
     * Read the STATUS register.
     *
     * @return raw status byte (bit 0=P_DA, bit 1=T_DA, bit 4=P_OR, bit 5=T_OR)
     */
    fun status(): Int = readReg(REG_STATUS)

    /**
     * Read the INT_SOURCE register.
     *
     * @return raw byte (bit 0=PH, bit 1=PL, bit 2=IA, bit 7=BOOT_STATUS)
     */
    fun interruptStatus(): Int = readReg(REG_INT_SOURCE)

    /**
     * Software-reset via SWRESET, wait for self-clear, restore defaults.
     */
    fun reset() {
        writeReg(REG_CTRL_REG2, CTRL_REG2_RESET)
        for (i in 0 until 50) {
            val current = readReg(REG_CTRL_REG2)
            if ((current and 0x04) == 0) break
            Thread.sleep(1)
        }
        writeReg(REG_CTRL_REG2, CTRL_REG2_DEFAULT)
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    /**
     * Reload factory trimming from internal Flash via BOOT bit.
     */
    fun reboot() {
        writeReg(REG_CTRL_REG2, 0x80)
        for (i in 0 until 100) {
            val status = readReg(REG_INT_SOURCE)
            if ((status and 0x80) == 0) break
            Thread.sleep(5)
        }
    }

    /**
     * Write RPDS to apply a one-point calibration offset.
     *
     * @param offsetHPa pressure offset in hPa. 1 RPDS LSB = 1/16 hPa.
     */
    fun setPressureOffset(offsetHPa: Double) {
        var raw = Math.round(offsetHPa * 16).toInt()
        if (raw < 0) raw += 0x10000
        writeReg(REG_RPDS_L, raw and 0xFF)
        writeReg(REG_RPDS_H, (raw shr 8) and 0xFF)
    }

    /**
     * Set AUTOZERO=1 — current pressure is stored in REF_P.
     */
    fun setAutozero() {
        val current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current or 0x20)
    }

    /**
     * Clear AUTOZERO mode and reset REF_P to 0.
     */
    fun clearAutozero() {
        val current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current or 0x10)
    }

    /**
     * Set AUTORIFP=1 — next measurement value is stored in RPDS.
     */
    fun setAutorifp() {
        val current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current or 0x80)
    }

    /**
     * Clear AUTORIFP mode and reset RPDS to 0.
     */
    fun clearAutorifp() {
        val current = readReg(REG_INTERRUPT_CFG)
        writeReg(REG_INTERRUPT_CFG, current or 0x40)
    }

    /**
     * Route CTRL_REG3 events to the INT_DRDY pin.
     */
    fun configureInterrupt(drdy: Boolean, fFth: Boolean, fOvr: Boolean, fFss5: Boolean,
                           intS: Int, activeLow: Boolean, openDrain: Boolean) {
        val ctrl3 = (if (activeLow) (1 shl 7) else 0) or
                    (if (openDrain) (1 shl 6) else 0) or
                    (if (fFss5) (1 shl 5) else 0) or
                    (if (fFth) (1 shl 4) else 0) or
                    (if (fOvr) (1 shl 3) else 0) or
                    (if (drdy) (1 shl 2) else 0) or
                    (intS and 0x03)
        writeReg(REG_CTRL_REG3, ctrl3)
    }

    /**
     * Configure the differential pressure threshold interrupt.
     */
    fun configurePressureInterrupt(highEn: Boolean, lowEn: Boolean,
                                   thresholdHPa: Double, latch: Boolean) {
        val rawThs = (Math.round(thresholdHPa * 16).toInt()) and 0xFFFF
        writeReg(REG_THS_P_L, rawThs and 0xFF)
        writeReg(REG_THS_P_H, (rawThs shr 8) and 0xFF)

        val current = readReg(REG_INTERRUPT_CFG)
        val newCfg = (current and 0xF0) or
                     (if (latch) 0x04 else 0) or
                     (if (highEn) 0x02 else 0) or
                     (if (lowEn) 0x01 else 0)
        writeReg(REG_INTERRUPT_CFG, newCfg)
    }

    /**
     * Enable the FIFO with the given mode and watermark.
     *
     * @param mode      FIFO mode (0–7, excluding reserved value 5)
     * @param watermark FIFO watermark level (0–31)
     */
    fun enableFifo(mode: Int, watermark: Int) {
        if (mode == 5) {
            // FIFO mode 5 is reserved; refuse to set it.
            return
        }
        val ctrl = ((mode and 7) shl 5) or (watermark and 0x1F)
        writeReg(REG_FIFO_CTRL, ctrl)
        val current = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, current or 0x40)
    }

    /**
     * Disable the FIFO and reset to Bypass mode.
     */
    fun disableFifo() {
        val current = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, current and 0xBF)
        writeReg(REG_FIFO_CTRL, 0)
    }

    /**
     * Read the FIFO_STATUS register.
     *
     * @return raw byte (bit 7=FTH_FIFO, bit 6=OVR, bits [5:0]=FSS count)
     */
    fun fifoStatus(): Int = readReg(REG_FIFO_STATUS)

    /**
     * Read LPFP_RES to flush any transitory LPF state.
     */
    fun resetLpf() {
        readReg(REG_LPFP_RES)
    }
}