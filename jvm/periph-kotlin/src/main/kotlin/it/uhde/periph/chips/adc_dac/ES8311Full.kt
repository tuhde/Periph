package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection

/**
 * ES8311 — low-power mono audio codec, full driver.
 *
 * Extends [ES8311Minimal] with the clock tree, serial port, microphone / ADC / DAC dynamics,
 * equalizers, routing, power control and raw register access. Invalid parameters raise
 * [IllegalArgumentException].
 *
 * @param connection I²C register connection bound to 0x18 / 0x19.
 * @param mclkHz master clock in Hz.
 * @param sampleRateHz audio sample rate in Hz.
 * @param dvddVolts DVDD supply, selects the 3.3 V or 1.8 V clock limits.
 */
class ES8311Full @JvmOverloads constructor(
    connection: RegisterConnection,
    mclkHz: Long = 12288000,
    sampleRateHz: Long = 48000,
    dvddVolts: Double = 3.3
) : ES8311Minimal(connection, mclkHz, sampleRateHz, dvddVolts) {

    private val bclkDividers = listOf(22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72)

    /** Read back the DAC volume in dB (−95.5 … +32). */
    fun getVolume(): Double = (r(REG_DAC_VOLUME) - 191) * 0.5

    /** Read back the analog microphone PGA gain in dB (0 … 30). */
    fun getMicGain(): Double = (r(REG_SYS_MIC) and 0x0F) * 3.0

    /** Software reset; registers return to their reset values (configure again afterwards). */
    fun reset() {
        softReset()
    }

    /** Re-enable the analog, reference and clock blocks (vendor values) and set CSM_ON. */
    fun powerUp() {
        update(REG_RESET, 0x9F, 0x00)
        update(REG_RESET, 0x80, 0x80)
        w(REG_CLK1, 0x3F)
        powerUpAnalog()
    }

    /** Standby: mute the DAC, power down the analog blocks, reset all and clear CSM_ON. */
    fun powerDown() {
        setMute(true)
        update(REG_SYS_PWR, 0xFC, 0xFC)
        update(REG_SYS_ADC, 0x70, 0x70)
        update(REG_SYS_PWR, 0x03, 0x00)
        w(REG_RESET, 0x1F)
    }

    /**
     * Set the low-power bits of register 0x0F (slightly lower THD+N / SNR).
     *
     * @param dac LPDAC.
     * @param pga LPPGA.
     * @param pgaOut LPPGAOUT.
     * @param adc LPVCMMOD (ADC).
     * @param adcRef LPADCVRP.
     * @param dacRef LPDACVRP.
     * @param flash LPFLASH.
     * @param integrator LPINT1.
     */
    fun setLowPower(dac: Boolean = false, pga: Boolean = false, pgaOut: Boolean = false, adc: Boolean = false,
                    adcRef: Boolean = false, dacRef: Boolean = false, flash: Boolean = false,
                    integrator: Boolean = false) {
        val flags = booleanArrayOf(dac, pga, pgaOut, adc, adcRef, dacRef, flash, integrator)
        var v = 0
        for (i in flags.indices) if (flags[i]) v = v or (0x80 shr i)
        w(REG_SYS_LP, v)
    }

    /**
     * Program the clock tree (registers 0x01–0x05) from a preset or computed dividers.
     *
     * @param mclkHz master clock in Hz.
     * @param sampleRateHz audio sample rate in Hz.
     * @param mclkFromBclk take the master clock from the SCLK (BCLK) pin.
     * @param mclkInvert invert the master clock.
     * @throws IllegalArgumentException if no valid divider set exists (nothing is written).
     */
    fun configureClock(mclkHz: Long, sampleRateHz: Long, mclkFromBclk: Boolean = false, mclkInvert: Boolean = false) {
        val clk = es8311SolveClock(mclkHz, sampleRateHz, dvdd)
        this.mclkHz = mclkHz
        this.fs = sampleRateHz
        w(REG_CLK1, 0x3F or (if (mclkFromBclk) 0x80 else 0) or (if (mclkInvert) 0x40 else 0))
        writeClock(clk)
    }

    private fun internalClock(): Long {
        val r02 = r(REG_CLK2)
        val mults = longArrayOf(1, 2, 4, 8)
        return mclkHz * mults[(r02 shr 3) and 3] / (((r02 shr 5) and 7) + 1)
    }

    /**
     * Configure the digital audio port (0x09, 0x0A, RESET.MSC, 0x06–0x08).
     *
     * @param format "i2s", "left_justified", "dsp_a" or "dsp_b".
     * @param wordLength 16, 18, 20, 24 or 32 bit.
     * @param master true for master mode (the chip drives BCLK / LRCK).
     * @param bclkInvert invert BCLK.
     * @param lrckInvert invert the LRCK polarity.
     * @param dacChannel "left" or "right" data to the DAC.
     * @param bclkDiv master BCLK divider (null = computed).
     * @param lrckDiv master LRCK divider (null = computed).
     * @param continuousBclk keep BCLK running when idle (master mode).
     * @throws IllegalArgumentException for an unknown format, word length or BCLK divider.
     */
    fun configureSerial(format: String = "i2s", wordLength: Int = 16, master: Boolean = false,
                        bclkInvert: Boolean = false, lrckInvert: Boolean = false, dacChannel: String = "left",
                        bclkDiv: Int? = null, lrckDiv: Int? = null, continuousBclk: Boolean = true) {
        val fmt = when (format) {
            "i2s" -> 0
            "left_justified" -> 1
            "dsp_a", "dsp_b" -> 3
            else -> throw IllegalArgumentException("unknown format")
        }
        val wl = when (wordLength) {
            24 -> 0
            20 -> 1
            18 -> 2
            16 -> 3
            32 -> 4
            else -> throw IllegalArgumentException("wordLength must be 16, 18, 20, 24 or 32")
        }
        val lrp = (if (lrckInvert) 1 else 0) xor (if (format == "dsp_b") 1 else 0)
        val base = (wl shl 2) or fmt or (lrp shl 5)
        var code = 0
        var lrck = 0
        if (master) {
            val imclk = internalClock()
            val bclk = bclkDiv ?: (imclk / (fs * 2 * wordLength)).toInt()
            lrck = lrckDiv ?: (imclk / fs).toInt()
            require(bclk > 0 && lrck in 1..4096) { "invalid master clock divider" }
            if (bclk <= 20) {
                code = bclk - 1
            } else {
                val i = bclkDividers.indexOf(bclk)
                require(i >= 0) { "unsupported BCLK divider" }
                code = 20 + i
            }
        }
        w(REG_SDP_IN, base or (if (dacChannel == "right") 0x80 else 0x00) or (r(REG_SDP_IN) and 0x40))
        w(REG_SDP_OUT, base or (r(REG_SDP_OUT) and 0x40))
        if (master) {
            w(REG_CLK6, (if (continuousBclk) 0x00 else 0x40) or (if (bclkInvert) 0x20 else 0) or code)
            update(REG_CLK7, 0x0F, ((lrck - 1) shr 8) and 0x0F)
            w(REG_CLK8, (lrck - 1) and 0xFF)
            update(REG_RESET, 0x44, 0x40)
        } else {
            update(REG_CLK6, 0x20, if (bclkInvert) 0x20 else 0x00)
            update(REG_RESET, 0x44, 0x04)
        }
    }

    /**
     * Tri-state the BCLK / LRCK pins and / or ADCDAT (0x07 bits 5:4).
     *
     * @param bclkLrck tri-state BCLK and LRCK.
     * @param adcdat tri-state ADCDAT.
     */
    fun setTristate(bclkLrck: Boolean = false, adcdat: Boolean = false) {
        update(REG_CLK7, 0x30, (if (bclkLrck) 0x20 else 0) or (if (adcdat) 0x10 else 0))
    }

    /**
     * Select the microphone input.
     *
     * @param source "analog" (MIC1P/MIC1N), "digital" (PDM on MIC1P) or "none".
     * @throws IllegalArgumentException for an unknown source.
     */
    fun setInput(source: String = "analog") {
        val (sel, pga) = when (source) {
            "analog" -> Pair(0x10, 0x00)
            "digital" -> Pair(0x40, 0x40)
            "none" -> Pair(0x00, 0x40)
            else -> throw IllegalArgumentException("source must be analog, digital or none")
        }
        update(REG_SYS_MIC, 0x70, sel)
        update(REG_SYS_ADC, 0x40, pga)
    }

    /**
     * Select the PDM data latch edge (0x15 bit 0).
     *
     * @param latchFallingEdge true to latch on the falling clock edge.
     */
    fun configureDmic(latchFallingEdge: Boolean = false) {
        update(REG_ADC_RAMP, 0x01, if (latchFallingEdge) 0x01 else 0x00)
    }

    /**
     * Set the ADC digital volume (ALC maximum gain when the ALC is on).
     *
     * @param db −95.5 … +32 dB in 0.5 dB steps.
     */
    fun setAdcVolume(db: Double) {
        w(REG_ADC_VOLUME, volumeToReg(db))
    }

    /**
     * Configure the ADC digital path.
     *
     * @param scaleDb digital gain scale-up 0 … 42 dB in 6 dB steps.
     * @param osr oversampling ratio in × fs (single speed 60–252, double speed 32–126).
     * @param doubleSpeed select the double-speed ADC.
     * @param hpf true for the dynamic high-pass filter, false to freeze the DC offset.
     * @param hpfCoeff1 stage-1 high-pass coefficient 0–31.
     * @param hpfCoeff2 stage-2 high-pass coefficient 0–31.
     * @throws IllegalArgumentException if osr is out of range.
     */
    fun configureAdc(scaleDb: Int = 24, osr: Int = 64, doubleSpeed: Boolean = false, hpf: Boolean = true,
                     hpfCoeff1: Int = 0x0C, hpfCoeff2: Int = 0x0C) {
        val code = if (doubleSpeed) osr / 2 else osr / 4
        require(code in 15..63 && !(doubleSpeed && code < 16)) { "osr out of range" }
        update(REG_ADC_SCALE, 0x07, round(scaleDb / 6.0).coerceIn(0, 7))
        w(REG_CLK3, (if (doubleSpeed) 0x40 else 0x00) or code)
        update(REG_ADC_HPF2_EQ, 0x3F, (if (hpf) 0x20 else 0x00) or (hpfCoeff2 and 0x1F))
        update(REG_ADC_HPF1, 0x1F, hpfCoeff1 and 0x1F)
    }

    /**
     * Set the ADC volume soft-ramp rate (0x15 bits 7:4).
     *
     * @param rate 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
     */
    fun setAdcRamp(rate: Int) {
        update(REG_ADC_RAMP, 0xF0, rate.coerceIn(0, 15) shl 4)
    }

    /**
     * Configure the automatic level control, noise gate and automute (0x18–0x1A).
     *
     * @param enabled enable the ALC.
     * @param maxLevelDb upper target level (nearest of 16 levels).
     * @param minLevelDb lower target level.
     * @param window ALC window size 0–15.
     * @param automute enable automute.
     * @param automuteWindow automute detection window 0–15 (2048 × (n+1) samples).
     * @param noiseGateDb noise gate threshold (nearest of 16).
     */
    fun configureAlc(enabled: Boolean, maxLevelDb: Double = -6.0, minLevelDb: Double = -30.1, window: Int = 0,
                     automute: Boolean = false, automuteWindow: Int = 0, noiseGateDb: Double = -96.0) {
        w(REG_ADC_ALC1, (if (enabled) 0x80 else 0) or (if (automute) 0x40 else 0) or (window and 0x0F))
        w(REG_ADC_ALC2, (nearest(ALC_LEVELS_DB, maxLevelDb) shl 4) or nearest(ALC_LEVELS_DB, minLevelDb))
        w(REG_ADC_AUTOMUTE, ((automuteWindow and 0x0F) shl 4) or nearest(NOISE_GATE_DB, noiseGateDb))
    }

    private fun eqBytes(coefficients: LongArray, count: Int): ByteArray {
        require(coefficients.size == count) { "expected $count coefficients" }
        val data = ByteArray(4 * count)
        for (i in 0 until count) {
            val c = coefficients[i]
            require(c >= 0 && c < (1L shl 30)) { "coefficient must be a 30-bit value" }
            data[4 * i] = ((c shr 24) and 0x3F).toByte()
            data[4 * i + 1] = ((c shr 16) and 0xFF).toByte()
            data[4 * i + 2] = ((c shr 8) and 0xFF).toByte()
            data[4 * i + 3] = (c and 0xFF).toByte()
        }
        return data
    }

    /** Bypass the ADC equalizer (0x1C bit 6). */
    fun bypassAdcEq() {
        update(REG_ADC_HPF2_EQ, 0x40, 0x40)
    }

    /**
     * Load the ADC equalizer coefficients and enable it.
     *
     * @param coefficients b0, a1, a2, b1, b2 as 30-bit values.
     * @throws IllegalArgumentException if the count or range is wrong.
     */
    fun setAdcEq(coefficients: LongArray) {
        connection.write(REG_ADCEQ, eqBytes(coefficients, 5))
        update(REG_ADC_HPF2_EQ, 0x40, 0x00)
    }

    /**
     * Set the DAC volume / DRC soft-ramp rate (0x37 bits 7:4).
     *
     * @param rate 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
     */
    fun setDacRamp(rate: Int) {
        update(REG_DAC_RAMP_EQ, 0xF0, rate.coerceIn(0, 15) shl 4)
    }

    /**
     * Configure the DAC dynamic range control (0x34, 0x35).
     *
     * @param enabled enable the DRC (the DAC volume becomes its maximum gain).
     * @param maxLevelDb upper target level.
     * @param minLevelDb lower target level.
     * @param window window size 0–15.
     */
    fun configureDrc(enabled: Boolean, maxLevelDb: Double = -30.1, minLevelDb: Double = -30.1, window: Int = 0) {
        w(REG_DAC_DRC1, (if (enabled) 0x80 else 0) or (window and 0x0F))
        w(REG_DAC_DRC2, (nearest(ALC_LEVELS_DB, maxLevelDb) shl 4) or nearest(ALC_LEVELS_DB, minLevelDb))
    }

    /** Bypass the DAC equalizer (0x37 bit 3). */
    fun bypassDacEq() {
        update(REG_DAC_RAMP_EQ, 0x08, 0x08)
    }

    /**
     * Load the DAC equalizer coefficients and enable it.
     *
     * @param coefficients b0, b1, a1 as 30-bit values.
     * @throws IllegalArgumentException if the count or range is wrong.
     */
    fun setDacEq(coefficients: LongArray) {
        connection.write(REG_DACEQ, eqBytes(coefficients, 3))
        update(REG_DAC_RAMP_EQ, 0x08, 0x00)
    }

    /**
     * Set the three DAC mute bits individually (0x31 bits 7:5).
     *
     * @param dsm DSM mute (bit 6).
     * @param dem DEM mute (bit 5).
     * @param dsmTarget DSM mute target (bit 7).
     */
    fun setDacMuteOptions(dsm: Boolean = true, dem: Boolean = true, dsmTarget: Boolean = false) {
        update(REG_DAC_MUTE, 0xE0, (if (dsmTarget) 0x80 else 0) or (if (dsm) 0x40 else 0) or (if (dem) 0x20 else 0))
    }

    /**
     * Feed the ADC output to the DAC (0x44 bit 7).
     *
     * @param enabled true to enable the loopback.
     */
    fun setLoopback(enabled: Boolean) {
        update(REG_GPIO, 0x80, if (enabled) 0x80 else 0x00)
    }

    /**
     * Select the ADCDAT content (0x44 bits 6:4).
     *
     * @param mode "adc_adc", "adc_zero", "zero_adc", "dac_adc", "adc_dac" or "dac_dac".
     * @throws IllegalArgumentException for an unknown mode.
     */
    fun setAdcdatMode(mode: String) {
        val code = when (mode) {
            "adc_adc" -> 0
            "adc_zero" -> 1
            "zero_adc" -> 2
            "dac_adc" -> 4
            "adc_dac" -> 5
            "dac_dac" -> 6
            else -> throw IllegalArgumentException("unknown ADCDAT mode")
        }
        update(REG_GPIO, 0x70, code shl 4)
    }

    /**
     * Mute the digital audio input and / or output (0x09 / 0x0A bit 6).
     *
     * @param input mute the serial input (DAC data).
     * @param output mute the serial output (ADC data).
     */
    fun setSdpMute(input: Boolean = false, output: Boolean = false) {
        update(REG_SDP_IN, 0x40, if (input) 0x40 else 0x00)
        update(REG_SDP_OUT, 0x40, if (output) 0x40 else 0x00)
    }

    /**
     * Raw register read.
     *
     * @param register register address 0x00–0xFF.
     * @return register value.
     */
    fun readRegister(register: Int): Int = r(register)

    /**
     * Raw register write.
     *
     * @param register register address 0x00–0xFF.
     * @param value byte value.
     */
    fun writeRegister(register: Int, value: Int) {
        w(register, value and 0xFF)
    }
}
