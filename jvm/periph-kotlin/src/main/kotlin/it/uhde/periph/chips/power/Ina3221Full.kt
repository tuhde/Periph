package it.uhde.periph.chips.power

import it.uhde.periph.transport.Transport
import kotlin.math.roundToInt

/**
 * INA3221 — full driver. Extends [Ina3221Minimal] with configuration,
 * per-channel enable/disable, alert limits, summation, power-valid thresholds,
 * shutdown/wake, and identification registers.
 *
 * ## Alert flags
 * Reading the Mask/Enable register (via [alertFlags]) also clears the latched
 * alert flags. Read it once after a monitoring interval to capture and clear
 * all pending alerts.
 *
 * ## Mode constants
 * - [MODE_POWERDOWN] — power-down (0)
 * - [MODE_SHUNT_TRIG] — shunt triggered (1)
 * - [MODE_BUS_TRIG] — bus triggered (2)
 * - [MODE_SHUNT_BUS_TRIG] — shunt + bus triggered (3)
 * - [MODE_SHUNT_CONT] — shunt continuous (5)
 * - [MODE_BUS_CONT] — bus continuous (6)
 * - [MODE_SHUNT_BUS_CONT] — shunt + bus continuous (7, default)
 */
class Ina3221Full : Ina3221Minimal {

    companion object {
        // Register addresses
        private const val REG_CONFIG       = 0x00
        private const val REG_MASK_ENABLE  = 0x0F
        private const val REG_CH1_CRIT     = 0x07
        private const val REG_CH1_WARN     = 0x08
        private const val REG_SV_SUM       = 0x0D
        private const val REG_SV_SUM_LIMIT = 0x0E
        private const val REG_PV_UPPER     = 0x10
        private const val REG_PV_LOWER     = 0x11
        private const val REG_MANUFACTURER = 0xFE
        private const val REG_DIE_ID       = 0xFF

        // Mode constants
        /** Power-down mode (MODE bits = 000). */
        const val MODE_POWERDOWN      = 0
        /** Single-shot shunt voltage measurement (MODE bits = 001). */
        const val MODE_SHUNT_TRIG     = 1
        /** Single-shot bus voltage measurement (MODE bits = 010). */
        const val MODE_BUS_TRIG       = 2
        /** Single-shot shunt and bus voltage measurement (MODE bits = 011). */
        const val MODE_SHUNT_BUS_TRIG = 3
        /** Continuous shunt voltage measurement (MODE bits = 101). */
        const val MODE_SHUNT_CONT     = 5
        /** Continuous bus voltage measurement (MODE bits = 110). */
        const val MODE_BUS_CONT       = 6
        /** Continuous shunt and bus voltage measurement (MODE bits = 111, default). */
        const val MODE_SHUNT_BUS_CONT = 7

        // Mask/Enable bit positions
        /** Critical flag channel 1 (bit 9). */
        const val CF1  = 512
        /** Critical flag channel 2 (bit 8). */
        const val CF2  = 256
        /** Critical flag channel 3 (bit 7). */
        const val CF3  = 128
        /** Summation flag (bit 6). */
        const val SF   = 64
        /** Warning flag channel 1 (bit 5). */
        const val WF1  = 32
        /** Warning flag channel 2 (bit 4). */
        const val WF2  = 16
        /** Warning flag channel 3 (bit 3). */
        const val WF3  = 8
        /** Power-valid flag (bit 2). */
        const val PVF  = 4
        /** Timing control flag (bit 1). */
        const val TCF  = 2
        /** Conversion-ready flag (bit 0). */
        const val CVRF = 1
    }

    /** Last user-configured MODE bits; saved so [wake] can restore them. */
    private var savedMode = MODE_SHUNT_BUS_CONT

    /**
     * Construct with a uniform shunt resistance for all channels.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunt    shunt resistance in Ω applied to all three channels
     */
    constructor(transport: Transport, rShunt: Double) : super(transport, rShunt)

    /**
     * Construct with per-channel shunt resistances.
     *
     * @param transport I²C transport bound to the INA3221 device address
     * @param rShunts   shunt resistances in Ω for channels 1, 2, and 3
     */
    constructor(transport: Transport, rShunts: DoubleArray) : super(transport, rShunts)

    /**
     * Construct with the default shunt resistance (0.1 Ω) for all channels.
     *
     * @param transport I²C transport bound to the INA3221 device address
     */
    constructor(transport: Transport) : super(transport)

    /**
     * Write the configuration register, preserving the channel-enable bits.
     *
     * The channel-enable bits (CH1en, CH2en, CH3en) in the current configuration
     * are read first and retained so that calling configure() does not inadvertently
     * disable channels.
     *
     * @param avg    averaging mode (0–7): 0=1, 1=4, 2=16, 3=64, 4=128, 5=256, 6=512, 7=1024 samples
     * @param vbusCt bus voltage conversion time (0–7)
     * @param vshCt  shunt voltage conversion time (0–7)
     * @param mode   operating mode (0–7); use MODE_* constants
     */
    fun configure(avg: Int, vbusCt: Int, vshCt: Int, mode: Int) {
        savedMode = mode and 0x07
        val current = readReg(REG_CONFIG)
        val channelBits = current and 0x7000
        val value = channelBits or
                ((avg    and 0x07) shl 9) or
                ((vbusCt and 0x07) shl 6) or
                ((vshCt  and 0x07) shl 3) or
                (mode    and 0x07)
        writeReg(REG_CONFIG, value)
    }

    /**
     * Enable or disable a channel.
     *
     * @param channel channel number (1, 2, or 3)
     * @param enabled true to enable, false to disable
     */
    fun enableChannel(channel: Int, enabled: Boolean) {
        checkChannel(channel)
        val bit = 1 shl (15 - channel)
        var cfg = readReg(REG_CONFIG)
        cfg = if (enabled) cfg or bit else cfg and bit.inv()
        writeReg(REG_CONFIG, cfg and 0xFFFF)
    }

    /**
     * Read whether a channel is currently enabled.
     *
     * @param channel channel number (1, 2, or 3)
     * @return true if the channel enable bit is set
     */
    fun channelEnabled(channel: Int): Boolean {
        checkChannel(channel)
        val bit = 1 shl (15 - channel)
        return (readReg(REG_CONFIG) and bit) != 0
    }

    /**
     * Read the Conversion Ready Flag (CVRF) from the Mask/Enable register.
     *
     * CVRF is set after a complete conversion cycle.
     *
     * @return true if a conversion cycle has completed since the last read
     */
    fun conversionReady(): Boolean = (readReg(REG_MASK_ENABLE) and CVRF) != 0

    /**
     * Set the critical alert limit for a channel.
     *
     * The alert fires when the shunt voltage magnitude exceeds this limit.
     *
     * @param channel channel number (1, 2, or 3)
     * @param limitV  shunt voltage threshold in V (positive)
     */
    fun setCriticalAlert(channel: Int, limitV: Double) {
        checkChannel(channel)
        val reg = REG_CH1_CRIT + (channel - 1) * 2
        writeReg(reg, encodeAlertLimit(limitV))
    }

    /**
     * Set the warning alert limit for a channel.
     *
     * @param channel channel number (1, 2, or 3)
     * @param limitV  shunt voltage threshold in V (positive)
     */
    fun setWarningAlert(channel: Int, limitV: Double) {
        checkChannel(channel)
        val reg = REG_CH1_WARN + (channel - 1) * 2
        writeReg(reg, encodeAlertLimit(limitV))
    }

    /**
     * Read and clear the Mask/Enable register (alert flags).
     *
     * Reading this register clears all latched alert flag bits. The returned value
     * can be inspected against the CF1, CF2, CF3, SF, WF1, WF2, WF3, PVF, TCF,
     * and CVRF constants.
     *
     * @return the raw 16-bit Mask/Enable register value
     */
    fun alertFlags(): Int = readReg(REG_MASK_ENABLE)

    /**
     * Set the summation channel selection (SCC) bits and the summation limit.
     *
     * The SCC bits in the Mask/Enable register determine which channels contribute
     * to the shunt-voltage sum.
     *
     * @param channels array of channel numbers to include in the sum (values 1–3)
     * @param limitV   summation shunt voltage limit in V
     */
    fun setSummationChannels(channels: IntArray, limitV: Double) {
        var me = readReg(REG_MASK_ENABLE) and 0x0FFF
        for (ch in channels) {
            checkChannel(ch)
            me = me or (1 shl (15 - ch))
        }
        writeReg(REG_MASK_ENABLE, me and 0xFFFF)
        val raw = (limitV / 40e-6).roundToInt() and 0x7FFE
        writeReg(REG_SV_SUM_LIMIT, (raw shl 1) and 0xFFFE)
    }

    /**
     * Read the shunt-voltage sum register.
     *
     * The register holds a 14-bit signed value, left-aligned by 1 bit (LSB = 20 µV).
     *
     * @return shunt-voltage sum in V
     */
    fun summationValue(): Double {
        val raw = readReg(REG_SV_SUM)
        return (raw.toShort().toInt() shr 1) * 40e-6
    }

    /**
     * Set the power-valid upper and lower voltage thresholds.
     *
     * The power-valid flag (PVF) is set when all enabled bus voltages are between
     * lowerV and upperV. Thresholds are 12-bit unsigned, left-aligned by 3 bits
     * (8 mV LSB).
     *
     * @param upperV upper threshold in V (register 0x10, reset = 10.000 V)
     * @param lowerV lower threshold in V (register 0x11, reset = 9.000 V)
     */
    fun setPowerValidLimits(upperV: Double, lowerV: Double) {
        writeReg(REG_PV_UPPER, ((upperV / 8e-3).roundToInt() and 0x1FFF) shl 3)
        writeReg(REG_PV_LOWER, ((lowerV / 8e-3).roundToInt() and 0x1FFF) shl 3)
    }

    /**
     * Read the Power Valid Flag (PVF) from the Mask/Enable register.
     *
     * @return true if all enabled channels are within the power-valid window
     */
    fun powerValid(): Boolean = (readReg(REG_MASK_ENABLE) and PVF) != 0

    /**
     * Put the device into power-down mode (MODE = 000).
     *
     * The current MODE setting is saved so that [wake] can restore it.
     */
    fun shutdown() {
        val cfg = readReg(REG_CONFIG)
        savedMode = cfg and 0x07
        writeReg(REG_CONFIG, (cfg and 0xFFF8) or MODE_POWERDOWN)
    }

    /**
     * Restore the operating mode saved by the last call to [shutdown] or [configure].
     *
     * If neither method has been called, the default continuous shunt+bus mode (7) is restored.
     */
    fun wake() {
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, (cfg and 0xFFF8) or (savedMode and 0x07))
    }

    /**
     * Trigger a software reset by setting the RST bit, then restore the saved mode.
     *
     * After reset the chip returns to its hardware defaults. The previously saved mode
     * is written back so the chip resumes normal operation.
     */
    fun reset() {
        writeReg(REG_CONFIG, 0x8000)
        val defaults = 0x7127
        writeReg(REG_CONFIG, (defaults and 0xFFF8) or (savedMode and 0x07))
    }

    /**
     * Read the Manufacturer ID register.
     *
     * Expected value: 0x5449 ("TI").
     *
     * @return manufacturer ID
     */
    fun manufacturerId(): Int = readReg(REG_MANUFACTURER)

    /**
     * Read the Die ID register.
     *
     * Expected value: 0x3220.
     *
     * @return die ID
     */
    fun dieId(): Int = readReg(REG_DIE_ID)

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun encodeAlertLimit(limitV: Double): Int =
        ((limitV / 40e-6).roundToInt() shl 3) and 0xFFF8
}
