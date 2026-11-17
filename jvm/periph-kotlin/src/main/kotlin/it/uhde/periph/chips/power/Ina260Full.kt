package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection

/**
 * INA260 — full driver. Extends [Ina260Minimal] with configuration, triggered
 * conversions, alert management, conversion-ready polling, reset,
 * shutdown/wake, and device identification.
 *
 * ## Alert functions (bit positions in Mask/Enable register)
 * - [OCL]  — over-current limit (bit 15)
 * - [UCL]  — under-current limit (bit 14)
 * - [BOL]  — bus voltage over-limit (bit 13)
 * - [BUL]  — bus voltage under-limit (bit 12)
 * - [POL]  — power over-limit (bit 11)
 * - [CNVR] — conversion ready (bit 10)
 *
 * ## Averaging constants
 * [AVG_1], [AVG_4], [AVG_16], [AVG_64], [AVG_128], [AVG_256], [AVG_512], [AVG_1024]
 *
 * ## Conversion time constants
 * [CT_140US], [CT_204US], [CT_332US], [CT_588US],
 * [CT_1100US], [CT_2116US], [CT_4156US], [CT_8244US]
 *
 * ## Mode constants
 * [MODE_POWER_DOWN], [MODE_SHUNT_TRIGGERED], [MODE_BUS_TRIGGERED], [MODE_SHUNT_BUS_TRIGGERED],
 * [MODE_SHUNT_CONT], [MODE_BUS_CONT], [MODE_SHUNT_BUS_CONT]
 */
class Ina260Full(connection: RegisterConnection) : Ina260Minimal(connection) {

    companion object {
        /** Alert: over-current limit (bit 15). */
        const val OCL  = 32768
        /** Alert: under-current limit (bit 14). */
        const val UCL  = 16384
        /** Alert: bus voltage over-limit (bit 13). */
        const val BOL  = 8192
        /** Alert: bus voltage under-limit (bit 12). */
        const val BUL  = 4096
        /** Alert: power over-limit (bit 11). */
        const val POL  = 2048
        /** Alert: conversion ready (bit 10). */
        const val CNVR = 1024
        /** Alert Function Flag (bit 4 of Mask/Enable, read-only). */
        const val AFF  = 0x0010

        const val AVG_1    = 0
        const val AVG_4    = 1
        const val AVG_16   = 2
        const val AVG_64   = 3
        const val AVG_128  = 4
        const val AVG_256  = 5
        const val AVG_512  = 6
        const val AVG_1024 = 7

        const val CT_140US  = 0
        const val CT_204US  = 1
        const val CT_332US  = 2
        const val CT_588US  = 3
        const val CT_1100US = 4
        const val CT_2116US = 5
        const val CT_4156US = 6
        const val CT_8244US = 7

        const val MODE_POWER_DOWN          = 0
        const val MODE_SHUNT_TRIGGERED     = 1
        const val MODE_BUS_TRIGGERED       = 2
        const val MODE_SHUNT_BUS_TRIGGERED = 3
        const val MODE_SHUNT_CONT          = 5
        const val MODE_BUS_CONT            = 6
        const val MODE_SHUNT_BUS_CONT      = 7

        /** Configuration register base: bits 14:12 must be written as 110. */
        private const val CONFIG_BASE = 0x6000
    }

    private var avg = 0
    private var vbusCt = 4
    private var ishCt = 4
    private var mode = 7

    private fun writeConfig(m: Int) {
        writeReg(REG_CONFIG, CONFIG_BASE or (avg shl 9) or (vbusCt shl 6) or (ishCt shl 3) or (m and 0x07))
    }

    /**
     * Write the Configuration register (bits 14:12 kept at 110).
     *
     * @param avg    averaging count (0–7, use `AVG_*` constants)
     * @param vbusCt bus voltage conversion time (0–7, use `CT_*` constants)
     * @param ishCt  shunt current conversion time (0–7, use `CT_*` constants)
     * @param mode   operating mode (0–7, use `MODE_*` constants)
     */
    fun configure(avg: Int, vbusCt: Int, ishCt: Int, mode: Int) {
        this.avg = avg and 0x07
        this.vbusCt = vbusCt and 0x07
        this.ishCt = ishCt and 0x07
        this.mode = mode and 0x07
        writeConfig(this.mode)
    }

    /**
     * Start one triggered conversion with the current settings (rewrites the
     * Configuration register). Poll [conversionReady] for completion.
     *
     * @param triggerMode [MODE_SHUNT_TRIGGERED], [MODE_BUS_TRIGGERED] or [MODE_SHUNT_BUS_TRIGGERED] (default)
     */
    @JvmOverloads
    fun trigger(triggerMode: Int = MODE_SHUNT_BUS_TRIGGERED) {
        writeConfig(triggerMode)
    }

    /**
     * Check whether a conversion has completed (CVRF flag, bit 3 of Mask/Enable).
     * Reading Mask/Enable clears CVRF.
     *
     * @return true if a conversion is ready to be read
     */
    fun conversionReady(): Boolean = (readReg(REG_MASK_EN) and 0x08) != 0

    /**
     * Check whether a math overflow occurred (OVF flag, bit 2 of Mask/Enable).
     *
     * @return true if an overflow has occurred
     */
    fun overflow(): Boolean = (readReg(REG_MASK_EN) and 0x04) != 0

    /**
     * Configure an alert function and its threshold.
     *
     * The Alert Limit register is written first, then Mask/Enable. The raw limit is
     * round(limit_A / 1.25e-3) for OCL/UCL, round(limit_V / 1.25e-3) for BOL/BUL,
     * round(limit_W / 10e-3) for POL; for CNVR the limit register is not written.
     *
     * @param function alert function bitmask (e.g. [POL])
     * @param limit    physical threshold (A, V or W depending on the function)
     * @param polarity false = active-low (default), true = active-high
     * @param latch    false = transparent (default), true = latch until Mask/Enable is read
     */
    @JvmOverloads
    fun setAlert(function: Int, limit: Double, polarity: Boolean = false, latch: Boolean = false) {
        when (function) {
            OCL, UCL, BOL, BUL -> writeReg(REG_ALERT_LIM, Math.round(limit / 1.25e-3).toInt() and 0xFFFF)
            POL -> writeReg(REG_ALERT_LIM, Math.round(limit / 10e-3).toInt() and 0xFFFF)
            else -> {}
        }
        writeReg(REG_MASK_EN, function or (if (polarity) 0x0002 else 0) or (if (latch) 0x0001 else 0))
    }

    /** Disable all alert functions (Mask/Enable = 0). */
    fun clearAlert() {
        writeReg(REG_MASK_EN, 0)
    }

    /**
     * Read the raw Mask/Enable register value (clears CVRF and, in latch mode, AFF).
     *
     * @return raw 16-bit Mask/Enable register value
     */
    fun alertFlags(): Int = readReg(REG_MASK_EN)

    /** Reset the chip (sets the RST bit); registers return to power-on defaults. */
    fun reset() {
        writeReg(REG_CONFIG, 0x8000)
        avg = 0; vbusCt = 4; ishCt = 4; mode = 7
    }

    /** Put the chip into power-down mode (MODE=000); the previous mode is kept for [wake]. */
    fun shutdown() {
        writeConfig(MODE_POWER_DOWN)
    }

    /** Restore the operating mode that was active before [shutdown]. */
    fun wake() {
        writeConfig(mode)
    }

    /**
     * Read the Manufacturer ID register.
     *
     * @return manufacturer ID (0x5449 = "TI")
     */
    fun manufacturerId(): Int = readReg(REG_MFR_ID)

    /**
     * Read the Die ID register.
     *
     * @return die ID; `value shr 4` is 0x227, the low nibble is the revision
     */
    fun dieId(): Int = readReg(REG_DIE_ID)
}
