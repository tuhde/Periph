package it.uhde.periph.chips.power

import it.uhde.periph.transport.Transport

/**
 * INA226 — full driver. Extends [Ina226Minimal] with configuration,
 * alert management, conversion-ready polling, reset, shutdown/wake, and
 * device identification.
 *
 * ## Alert functions (bit positions in Mask/Enable register)
 * - [SOL]  — shunt voltage over-limit (bit 15)
 * - [SUL]  — shunt voltage under-limit (bit 14)
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
 * [MODE_POWERDOWN], [MODE_SHUNT_TRIG], [MODE_BUS_TRIG], [MODE_SHUNT_BUS_TRIG],
 * [MODE_SHUNT_CONT], [MODE_BUS_CONT], [MODE_SHUNT_BUS_CONT]
 */
class Ina226Full @JvmOverloads constructor(
    transport: Transport,
    rShunt: Double = 0.1,
    maxCurrent: Double = 2.0
) : Ina226Minimal(transport, rShunt, maxCurrent) {

    companion object {
        // Alert function constants
        /** Alert: shunt voltage over-limit (bit 15). */
        const val SOL  = 32768
        /** Alert: shunt voltage under-limit (bit 14). */
        const val SUL  = 16384
        /** Alert: bus voltage over-limit (bit 13). */
        const val BOL  = 8192
        /** Alert: bus voltage under-limit (bit 12). */
        const val BUL  = 4096
        /** Alert: power over-limit (bit 11). */
        const val POL  = 2048
        /** Alert: conversion ready (bit 10). */
        const val CNVR = 1024

        // Averaging constants
        /** Averaging: 1 sample. */
        const val AVG_1    = 0
        /** Averaging: 4 samples. */
        const val AVG_4    = 1
        /** Averaging: 16 samples. */
        const val AVG_16   = 2
        /** Averaging: 64 samples. */
        const val AVG_64   = 3
        /** Averaging: 128 samples. */
        const val AVG_128  = 4
        /** Averaging: 256 samples. */
        const val AVG_256  = 5
        /** Averaging: 512 samples. */
        const val AVG_512  = 6
        /** Averaging: 1024 samples. */
        const val AVG_1024 = 7

        // Conversion time constants
        /** Conversion time: 140 µs. */
        const val CT_140US  = 0
        /** Conversion time: 204 µs. */
        const val CT_204US  = 1
        /** Conversion time: 332 µs. */
        const val CT_332US  = 2
        /** Conversion time: 588 µs. */
        const val CT_588US  = 3
        /** Conversion time: 1.1 ms. */
        const val CT_1100US = 4
        /** Conversion time: 2.116 ms. */
        const val CT_2116US = 5
        /** Conversion time: 4.156 ms. */
        const val CT_4156US = 6
        /** Conversion time: 8.244 ms. */
        const val CT_8244US = 7

        // Mode constants
        /** Mode: power-down (0). */
        const val MODE_POWERDOWN      = 0
        /** Mode: shunt voltage triggered (1). */
        const val MODE_SHUNT_TRIG     = 1
        /** Mode: bus voltage triggered (2). */
        const val MODE_BUS_TRIG       = 2
        /** Mode: shunt and bus voltage triggered (3). */
        const val MODE_SHUNT_BUS_TRIG = 3
        /** Mode: shunt voltage continuous (5). */
        const val MODE_SHUNT_CONT     = 5
        /** Mode: bus voltage continuous (6). */
        const val MODE_BUS_CONT       = 6
        /** Mode: shunt and bus voltage continuous (7). */
        const val MODE_SHUNT_BUS_CONT = 7
    }

    /**
     * Write the Configuration register.
     *
     * Bits are packed as:
     * `[0 0 0 0 | AVG2 AVG1 AVG0 | VBUSCT2 VBUSCT1 VBUSCT0 | VSHCT2 VSHCT1 VSHCT0 | MODE2 MODE1 MODE0]`
     *
     * @param avg    averaging count (0–7, use [AVG_*][AVG_1] constants)
     * @param vbusCt bus voltage conversion time (0–7, use [CT_*][CT_140US] constants)
     * @param vshCt  shunt voltage conversion time (0–7, use [CT_*][CT_140US] constants)
     * @param mode   operating mode (0–7, use [MODE_*][MODE_POWERDOWN] constants)
     */
    fun configure(avg: Int, vbusCt: Int, vshCt: Int, mode: Int) {
        val cfg = ((avg    and 0x07) shl 9) or
                  ((vbusCt and 0x07) shl 6) or
                  ((vshCt  and 0x07) shl 3) or
                   (mode   and 0x07)
        lastMode = mode and 0x07
        writeReg(REG_CONFIG, cfg)
    }

    /**
     * Check whether a conversion has completed (CVRF flag, bit 3 of Mask/Enable).
     *
     * @return true if a conversion is ready to be read
     */
    fun conversionReady(): Boolean = (readReg(REG_MASK_EN) and 0x08) != 0

    /**
     * Check whether a math overflow occurred (OVF flag, bit 2 of Mask/Enable).
     *
     * An overflow means the current or power result exceeded the register range;
     * reduce maxCurrent or increase rShunt to avoid this condition.
     *
     * @return true if an overflow has occurred
     */
    fun overflow(): Boolean = (readReg(REG_MASK_EN) and 0x04) != 0

    /**
     * Configure an alert function and its threshold.
     *
     * The raw limit is derived from the physical limit:
     * - SOL/SUL: raw = int(limit_V / 2.5e-6)
     * - BOL/BUL: raw = int(limit_V / 1.25e-3)
     * - POL:     raw = int(limit_W / (25 × Current_LSB))
     * - CNVR:    limit is ignored
     *
     * @param function alert function bitmask (e.g. [POL])
     * @param limit    physical threshold (V for shunt/bus, W for power)
     */
    fun setAlert(function: Int, limit: Double) {
        val raw = when (function) {
            SOL, SUL -> (limit / 2.5e-6).toInt()
            BOL, BUL -> (limit / 1.25e-3).toInt()
            POL      -> (limit / (25.0 * currentLsb)).toInt()
            else     -> 0
        }
        writeReg(REG_MASK_EN, function)
        writeReg(REG_ALERT_LIM, raw and 0xFFFF)
    }

    /**
     * Read the raw Mask/Enable register value.
     *
     * Reading this register also clears the alert latch. The flags of interest
     * are CVRF (bit 3) and OVF (bit 2); alert-function bits are in bits 10–15.
     *
     * @return raw 16-bit Mask/Enable register value
     */
    fun alertFlags(): Int = readReg(REG_MASK_EN)

    /**
     * Reset the chip (sets RST bit) and re-write calibration.
     *
     * After reset the chip returns to default configuration (0x4127). This method
     * re-writes the Calibration register so that current and power readings remain valid.
     */
    fun reset() {
        writeReg(REG_CONFIG, 0x8000)
        writeReg(REG_CAL, cal)
        lastMode = 7
    }

    /**
     * Put the chip into power-down mode (MODE=000).
     *
     * The current mode is preserved in [lastMode] so that [wake] can restore it.
     */
    fun shutdown() {
        val cfg = readReg(REG_CONFIG)
        lastMode = cfg and 0x07
        writeReg(REG_CONFIG, cfg and 0x07.inv())
    }

    /**
     * Restore the operating mode that was active before [shutdown].
     */
    fun wake() {
        val cfg = readReg(REG_CONFIG)
        writeReg(REG_CONFIG, (cfg and 0x07.inv()) or (lastMode and 0x07))
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
     * @return die ID (0x2260 for INA226)
     */
    fun dieId(): Int = readReg(REG_DIE_ID)
}
