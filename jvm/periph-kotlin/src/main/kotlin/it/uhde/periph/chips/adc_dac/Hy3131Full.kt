package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection

/**
 * HY3131 — full driver: AD1/AD2/AD3 configuration, temperature, low-pass filter, RMS,
 * peak hold, frequency counter, interrupt flags and raw register access.
 * Extends [Hy3131Minimal].
 */
class Hy3131Full(connection: Connection, vref: Float) : Hy3131Minimal(connection, vref) {

    private val dcosTable = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 0.0, -0.25, -0.5, -0.75)

    private fun clearFlags() {
        read1(REG_INTF)
        flags = 0
    }

    /** Software-reset the chip and re-apply the Minimal defaults. */
    fun reset() = init()

    /**
     * Configure AD1.
     *
     * @param osr 0–7 → OSR1 256 … 32768
     * @param chop 0–3 (1x enables the chopper and halves the rate)
     * @param inputGain 0–3 → 0.9, 1.8, 2.7, 3.6
     * @param refGain 0 → 1.0, 1 → 0.333
     * @param offset 0–7 DCOS code
     * @param buffers 4-bit mask: bit3 RH, bit2 RL, bit1 IP, bit0 IN
     */
    fun configureAd1(osr: Int, chop: Int, inputGain: Int, refGain: Int, offset: Int, buffers: Int) {
        val r25 = read1(REG_R25)
        writeRegister(REG_R25, (r25 and 0xCF) or ((inputGain and 3) shl 4))
        writeRegister(REG_R22, ((offset and 7) shl 5) or ((chop and 3) shl 3) or (osr and 7))
        writeRegister(REG_R23, 0x80 or ((refGain and 1) shl 4) or (buffers and 0x0F))
        ig = inputGain and 3
        rg = refGain and 1
        dcos = dcosTable[offset and 7]
        clearFlags()
    }

    /** Select AD1 inputs: [pos] 0–15 (SAD1FP), [neg] 0–7 (SAD1FN), [filterR] 0–3 (SFT1). */
    fun setAd1Input(pos: Int, neg: Int, filterR: Int) {
        writeRegister(REG_R24, ((pos and 15) shl 4) or (neg and 7))
        val r33 = read1(REG_R33)
        writeRegister(REG_R33, (r33 and 0xF3) or ((filterR and 3) shl 2))
    }

    /** Select the AD1 reference: [high] 0–6 (SAD1RH), [low] 0–6 (SAD1RL). */
    fun setAd1Reference(high: Int, low: Int) = writeRegister(REG_R28, ((high and 7) shl 4) or (low and 7))

    /** Read TCode = (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2 from the on-chip sensor. */
    fun readTemperatureCode(): Int {
        val r33 = read1(REG_R33)
        val codes = IntArray(2)
        for (i in 0..1) {
            writeRegister(REG_R33, (r33 and 0xFC) or (i + 2))
            clearFlags()
            waitFlag(FLAG_AD1F) // discard settling sample
            codes[i] = readRaw()
        }
        writeRegister(REG_R33, r33)
        flags = 0
        return (codes[1] - codes[0]) / 2
    }

    /** Read the die temperature in °C using a one-point calibration ([tcode25] measured at 25 °C). */
    fun readTemperature(tcode25: Int): Float {
        val tOs = -3.15
        val g = tcode25 / (25 + 273.15 + tOs)
        return (readTemperatureCode() / g - 273.15 - tOs).toFloat()
    }

    /**
     * Enable AD2 (and AD3 when [useAd3]).
     *
     * @param osr 0–7 (OSR 32 … 1024)
     * @param inputGain 0–3 → 0.5, 1.0, 1.5, 2.0
     * @param refGain 0 → 1.0, 1 → 0.333
     * @param chop enable the input chopper
     * @param clkDiv4 modulator clock SYSCLK/4 instead of /2
     * @param source SAD2IP 0–3 (OP1O, OP2O, PB4, PB7)
     * @param useAd3 show AD3 instead of AD2 in the data registers (SDO23=1)
     */
    fun enableAd2(
        osr: Int, inputGain: Int, refGain: Int, chop: Boolean, clkDiv4: Boolean,
        source: Int, useAd3: Boolean,
    ) {
        val r25 = read1(REG_R25)
        writeRegister(REG_R25, (r25 and 0x3F) or ((inputGain and 3) shl 6))
        writeRegister(REG_R27, ((source and 3) shl 6) or 0x15)
        val chopBit = if (chop) 0x20 else 0
        writeRegister(REG_R26, 0x80 or chopBit or ((refGain and 1) shl 4) or (if (clkDiv4) 0x08 else 0) or (osr and 7))
        if (useAd3) {
            writeRegister(REG_R35, ((source and 3) shl 6) or (1 shl 4) or ((inputGain and 3) shl 2))
            writeRegister(REG_R34, 0x80 or chopBit or ((refGain and 1) shl 4) or 0x04)
        } else {
            writeRegister(REG_R34, 0x00)
        }
        clearFlags()
    }

    /** Wait for AD2 (or AD3) and return the signed 19-bit code. */
    fun readAd2Raw(): Int {
        waitFlag(FLAG_AD2F)
        return sext(read24(REG_AD2), 24).toInt()
    }

    /** Enable the low-pass filter; [bw] 0–7 → OSR4 128 … 16384. */
    fun enableLpf(bw: Int) {
        val r29 = read1(REG_R29)
        writeRegister(REG_R29, (r29 and 0x87) or 0x40 or ((bw and 7) shl 3))
    }

    /** Wait for the LPF and return the signed 19-bit code. */
    fun readLpfRaw(): Int {
        waitFlag(FLAG_LPFF)
        return sext(read24(REG_LPF), 24).toInt()
    }

    /** Enable the RMS converter (requires AD2 and the LPF). */
    fun enableRms() = writeRegister(REG_R29, read1(REG_R29) or 0x80)

    /** Wait for RMS and return `sqrt(RMS accumulator)` in AD2 LSB units. */
    fun readRms(): Float {
        waitFlag(FLAG_RMSF)
        val b = readRegister(REG_RMS, 5)
        var raw = 0L
        for (i in 0..4) raw = raw or ((b[i].toLong() and 0xFF) shl (8 * i))
        return Math.sqrt(maxOf(sext(raw, 40), 0L).toDouble()).toFloat()
    }

    /** Enable peak hold; [source] 0–3 → AD2, AD1<23:5>, LPF, LPF. Re-enabling resets min/max. */
    fun enablePeakHold(source: Int) {
        val r29 = read1(REG_R29)
        writeRegister(REG_R29, r29 and 0x07.inv())
        writeRegister(REG_R29, (r29 and 0x07.inv()) or 0x04 or (source and 3))
    }

    /** Read the peak-hold registers: `[min, max]` signed 19-bit codes. */
    fun readPeak(): IntArray = intArrayOf(sext(read24(REG_PKHMIN), 24).toInt(), sext(read24(REG_PKHMAX), 24).toInt())

    /** Start the frequency counter; [gatePreload] (0–0xFFFF) is written to CTA<23:8>. */
    fun startCounter(gatePreload: Int) {
        val r20 = read1(REG_R20)
        writeRegister(REG_R20, r20 and 0x02.inv())
        writeRegister(REG_CTA + 1, byteArrayOf(gatePreload.toByte(), (gatePreload shr 8).toByte()))
        clearFlags()
        writeRegister(REG_R20, r20 or 0x0A)
    }

    /** Wait for the counter and read it: `[cta, ctb, ctc, overflow(0/1)]`. */
    fun readCounter(): LongArray {
        waitFlag(FLAG_CTF)
        val overflow = (read1(REG_CTSTA) and 0x01).toLong()
        return longArrayOf(read24(REG_CTA), read24(REG_CTB), read24(REG_CTC), overflow)
    }

    /** Read INTF (clears it on the chip); latched flags are included and cleared. */
    fun readFlags(): Int {
        val f = flags or read1(REG_INTF)
        flags = 0
        return f
    }

    /** Write INTE (RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0). */
    fun enableInterrupts(mask: Int) = writeRegister(REG_INTE, mask and 0x1F)
}
