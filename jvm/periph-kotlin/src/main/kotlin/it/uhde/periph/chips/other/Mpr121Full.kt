package it.uhde.periph.chips.other

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * MPR121 — full driver. Extends Mpr121Minimal with explicit Stop/Run
 * control, per-electrode and per-proximity threshold configuration,
 * filtered-data and baseline access, baseline filter and AFE (sampling)
 * configuration, debounce, autoconfig recomputation, OOR status, and
 * over-current flag clear.
 */
class Mpr121Full(connection: Connection) : Mpr121Minimal(connection) {

    /** Software-reset the chip and re-apply Minimal defaults. */
    @Throws(IOException::class)
    fun reset() {
        softReset()
        writeReg(REG_MHDR, 0x01)
        writeReg(REG_NHDR, 0x01)
        writeReg(REG_MHDF, 0x01)
        writeReg(REG_NHDF, 0x01)
        writeReg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT)
        writeReg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT)
        writeReg(REG_USL, USL_3V3)
        writeReg(REG_TL, TL_3V3)
        writeReg(REG_LSL, LSL_3V3)
        writeReg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT)
        for (n in 0 until 12) {
            writeReg(REG_E0TTH + 2 * n, TOUCH_DEFAULT)
            writeReg(REG_E0RTH + 2 * n, RELEASE_DEFAULT)
        }
        writeReg(REG_ECR, ECR_DEFAULT)
    }

    /** Enter Stop Mode (ECR=0x00). */
    @Throws(IOException::class)
    fun stop() {
        writeReg(REG_ECR, 0x00)
    }

    /**
     * Enter Run Mode with the given electrode configuration.
     *
     * @param nElectrodes number of electrodes to enable 1-12
     * @param cl calibration lock / baseline init 0-3
     * @param eleproxEn proximity enable 0-3
     */
    @Throws(IOException::class)
    fun start(nElectrodes: Int, cl: Int, eleproxEn: Int) {
        require(nElectrodes in 1..12) { "n_electrodes must be in 1..12" }
        require(cl in 0..3) { "cl must be in 0..3" }
        require(eleproxEn in 0..3) { "eleprox_en must be in 0..3" }
        val ecr = ((cl and 0x03) shl 6) or ((eleproxEn and 0x03) shl 4) or (nElectrodes and 0x0F)
        writeReg(REG_ECR, ecr)
    }

    /** Set touch and release thresholds for a single electrode. */
    @Throws(IOException::class)
    fun configureThresholds(electrode: Int, touch: Int, release: Int) {
        require(electrode in 0..11) { "electrode must be in 0..11" }
        writeReg(REG_E0TTH + 2 * electrode, touch and 0xFF)
        writeReg(REG_E0RTH + 2 * electrode, release and 0xFF)
    }

    /** Apply the same touch and release thresholds to all 12 electrodes. */
    @Throws(IOException::class)
    fun configureAllThresholds(touch: Int, release: Int) {
        for (n in 0 until 12) {
            configureThresholds(n, touch, release)
        }
    }

    /** Set ELEPROX touch and release thresholds. */
    @Throws(IOException::class)
    fun configureProximityThresholds(touch: Int, release: Int) {
        writeReg(REG_EPROXTTH, touch and 0xFF)
        writeReg(REG_EPROXRTH, release and 0xFF)
    }

    /**
     * Read the 10-bit filtered capacitance data for an electrode.
     *
     * @param electrode 0-11 for ELE0-ELE11, 12 for ELEPROX
     * @return 10-bit value
     */
    @Throws(IOException::class)
    fun filtered(electrode: Int): Int {
        require(electrode in 0..12) { "electrode must be in 0..12" }
        val addr = if (electrode == 12) 0x1C else (0x04 + 2 * electrode)
        return readReg16(addr)
    }

    /**
     * Read the 10-bit baseline for an electrode.
     *
     * The chip stores only the 8 MSBs of the baseline; the returned value is
     * shifted left by 2 to align with [filtered].
     */
    @Throws(IOException::class)
    fun baseline(electrode: Int): Int {
        require(electrode in 0..12) { "electrode must be in 0..12" }
        val addr = if (electrode == 12) 0x2A else (0x1E + electrode)
        return (readReg(addr) and 0xFF) shl 2
    }

    /** Write a baseline value (Stop Mode only). */
    @Throws(IOException::class)
    fun setBaseline(electrode: Int, value: Int) {
        require(electrode in 0..12) { "electrode must be in 0..12" }
        val addr = if (electrode == 12) 0x2A else (0x1E + electrode)
        writeReg(addr, (value shr 2) and 0xFF)
    }

    /** Read the 13-bit out-of-range bitmask. */
    @Throws(IOException::class)
    fun oorStatus(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_ELE0_7_OOR.toByte()), 2)
        return (buf[0].toInt() and 0xFF) or (((buf[1].toInt() and 0xFF) and 0x1F) shl 8)
    }

    /** Set the global baseline filter parameters (Stop Mode). */
    @Throws(IOException::class)
    fun configureBaselineFilter(
        mhdr: Int, nhdr: Int, nclr: Int, fdlr: Int,
        mhdf: Int, nhdf: Int, nclf: Int, fdlf: Int,
        nhdt: Int, nclt: Int, fdlt: Int,
    ) {
        writeReg(REG_MHDR, mhdr and 0x3F)
        writeReg(REG_NHDR, nhdr and 0x3F)
        writeReg(0x2D, nclr and 0xFF)
        writeReg(0x2E, fdlr and 0xFF)
        writeReg(REG_MHDF, mhdf and 0x3F)
        writeReg(REG_NHDF, nhdf and 0x3F)
        writeReg(0x31, nclf and 0xFF)
        writeReg(0x32, fdlf and 0xFF)
        writeReg(0x33, nhdt and 0x3F)
        writeReg(0x34, nclt and 0xFF)
        writeReg(0x35, fdlt and 0xFF)
    }

    /** Set global AFE (sampling) configuration (Stop Mode). */
    @Throws(IOException::class)
    fun configureSampling(cdc: Int, cdt: Int, ffi: Int, sfi: Int, esi: Int) {
        val cdcCfg = ((ffi and 0x03) shl 6) or (cdc and 0x3F)
        val cdtCfg = ((cdt and 0x07) shl 5) or ((sfi and 0x03) shl 2) or (esi and 0x07)
        writeReg(REG_CDC_CONFIG, cdcCfg)
        writeReg(REG_CDT_CONFIG, cdtCfg)
    }

    /** Set debounce counts (Stop Mode). */
    @Throws(IOException::class)
    fun configureDebounce(touch: Int, release: Int) {
        val deb = ((release and 0x07) shl 4) or (touch and 0x07)
        writeReg(REG_DEBOUNCE, deb)
    }

    /** Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode). */
    @Throws(IOException::class)
    fun configureAutoconfig(
        vddMv: Int, retry: Int,
        scts: Boolean, are: Boolean, ace: Boolean,
    ) {
        val usl = (((vddMv - 700) * 256) / vddMv).toInt()
        val tl = (usl * 0.9f).toInt()
        val lsl = (usl * 0.65f).toInt()
        writeReg(REG_USL, usl and 0xFF)
        writeReg(REG_TL, tl and 0xFF)
        writeReg(REG_LSL, lsl and 0xFF)
        val cdcCfg = readReg(REG_CDC_CONFIG)
        val ffi = (cdcCfg shr 6) and 0x03
        var autoconfig0 = ((ffi and 0x03) shl 6) or ((retry and 0x03) shl 4)
        if (are) autoconfig0 = autoconfig0 or 0x08
        if (ace) autoconfig0 = autoconfig0 or 0x01
        writeReg(REG_AUTOCONFIG0, autoconfig0)
        val autoconfig1 = if (scts) 0x80 else 0x00
        writeReg(REG_AUTOCONFIG1, autoconfig1)
    }

    /** Return true if the ELEPROX virtual electrode is touched. */
    @Throws(IOException::class)
    fun proximityTouched(): Boolean {
        return (readReg(REG_ELE8_PROX_TCH) and 0x10) != 0
    }

    /** Clear the OVCF bit in register 0x01. */
    @Throws(IOException::class)
    fun clearOvercurrent() {
        val raw = readReg(REG_ELE8_PROX_TCH)
        writeReg(REG_ELE8_PROX_TCH, raw and 0x7F)
    }

    /** Enable one of the AUTOCONFIG1-based interrupt sources. */
    @Throws(IOException::class)
    fun enableInterrupt(source: Int) {
        val cur = readReg(REG_AUTOCONFIG1) and 0x00
        writeReg(REG_AUTOCONFIG1, cur or (source and 0x07))
    }

    /** Disable one of the AUTOCONFIG1-based interrupt sources. */
    @Throws(IOException::class)
    fun disableInterrupt(source: Int) {
        val cur = readReg(REG_AUTOCONFIG1)
        writeReg(REG_AUTOCONFIG1, cur and (source and 0x07).inv())
    }

    companion object {
        /** AUTOCONFIG1 OORIE interrupt source (bit 2 of 0x7C). */
        const val SOURCE_OOR = 0x04
        /** AUTOCONFIG1 ARFIE interrupt source (bit 1 of 0x7C). */
        const val SOURCE_ARF = 0x02
        /** AUTOCONFIG1 ACFIE interrupt source (bit 0 of 0x7C). */
        const val SOURCE_ACF = 0x01
    }
}
