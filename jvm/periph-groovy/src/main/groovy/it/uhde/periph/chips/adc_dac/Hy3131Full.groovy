package it.uhde.periph.chips.adc_dac

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * HY3131 — full driver: AD1/AD2/AD3 configuration, temperature, low-pass filter, RMS,
 * peak hold, frequency counter, interrupt flags and raw register access.
 * Extends {@link Hy3131Minimal}.
 */
@CompileStatic
class Hy3131Full extends Hy3131Minimal {

    private static final double[] DCOS_TABLE = [0d, 0.25d, 0.5d, 0.75d, 0d, -0.25d, -0.5d, -0.75d] as double[]

    Hy3131Full(Connection connection, float vref) {
        super(connection, vref)
    }

    private void clearFlags() {
        read1(REG_INTF)
        flags = 0
    }

    /** Software-reset the chip and re-apply the Minimal defaults. */
    void reset() {
        init()
    }

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
    void configureAd1(int osr, int chop, int inputGain, int refGain, int offset, int buffers) {
        int r25 = read1(REG_R25)
        writeRegister(REG_R25, (r25 & 0xCF) | ((inputGain & 3) << 4))
        writeRegister(REG_R22, ((offset & 7) << 5) | ((chop & 3) << 3) | (osr & 7))
        writeRegister(REG_R23, 0x80 | ((refGain & 1) << 4) | (buffers & 0x0F))
        ig = inputGain & 3
        rg = refGain & 1
        dcos = DCOS_TABLE[offset & 7]
        clearFlags()
    }

    /** Select AD1 inputs: pos 0–15 (SAD1FP), neg 0–7 (SAD1FN), filterR 0–3 (SFT1). */
    void setAd1Input(int pos, int neg, int filterR) {
        writeRegister(REG_R24, ((pos & 15) << 4) | (neg & 7))
        int r33 = read1(REG_R33)
        writeRegister(REG_R33, (r33 & 0xF3) | ((filterR & 3) << 2))
    }

    /** Select the AD1 reference: high 0–6 (SAD1RH), low 0–6 (SAD1RL). */
    void setAd1Reference(int high, int low) {
        writeRegister(REG_R28, ((high & 7) << 4) | (low & 7))
    }

    /** Read TCode = (AD1[SAD1I=11] − AD1[SAD1I=10]) / 2 from the on-chip sensor. */
    int readTemperatureCode() {
        int r33 = read1(REG_R33)
        int[] codes = new int[2]
        for (int i = 0; i < 2; i++) {
            writeRegister(REG_R33, (r33 & 0xFC) | (i + 2))
            clearFlags()
            waitFlag(FLAG_AD1F) // discard settling sample
            codes[i] = readRaw()
        }
        writeRegister(REG_R33, r33)
        flags = 0
        return (int) ((codes[1] - codes[0]) / 2)
    }

    /** Read the die temperature in °C using a one-point calibration (tcode25 measured at 25 °C). */
    float readTemperature(int tcode25) {
        double tOs = -3.15d
        double g = (double) tcode25 / (25d + 273.15d + tOs)
        return (float) ((double) readTemperatureCode() / g - 273.15d - tOs)
    }

    /**
     * Enable AD2 (and AD3 when useAd3).
     *
     * @param osr 0–7 (OSR 32 … 1024)
     * @param inputGain 0–3 → 0.5, 1.0, 1.5, 2.0
     * @param refGain 0 → 1.0, 1 → 0.333
     * @param chop enable the input chopper
     * @param clkDiv4 modulator clock SYSCLK/4 instead of /2
     * @param source SAD2IP 0–3 (OP1O, OP2O, PB4, PB7)
     * @param useAd3 show AD3 instead of AD2 in the data registers (SDO23=1)
     */
    void enableAd2(int osr, int inputGain, int refGain, boolean chop, boolean clkDiv4,
                   int source, boolean useAd3) {
        int r25 = read1(REG_R25)
        writeRegister(REG_R25, (r25 & 0x3F) | ((inputGain & 3) << 6))
        writeRegister(REG_R27, ((source & 3) << 6) | 0x15)
        int chopBit = chop ? 0x20 : 0
        writeRegister(REG_R26, 0x80 | chopBit | ((refGain & 1) << 4) | (clkDiv4 ? 0x08 : 0) | (osr & 7))
        if (useAd3) {
            writeRegister(REG_R35, ((source & 3) << 6) | (1 << 4) | ((inputGain & 3) << 2))
            writeRegister(REG_R34, 0x80 | chopBit | ((refGain & 1) << 4) | 0x04)
        } else {
            writeRegister(REG_R34, 0x00)
        }
        clearFlags()
    }

    /** Wait for AD2 (or AD3) and return the signed 19-bit code. */
    int readAd2Raw() {
        waitFlag(FLAG_AD2F)
        return (int) sext(read24(REG_AD2), 24)
    }

    /** Enable the low-pass filter; bw 0–7 → OSR4 128 … 16384. */
    void enableLpf(int bw) {
        int r29 = read1(REG_R29)
        writeRegister(REG_R29, (r29 & 0x87) | 0x40 | ((bw & 7) << 3))
    }

    /** Wait for the LPF and return the signed 19-bit code. */
    int readLpfRaw() {
        waitFlag(FLAG_LPFF)
        return (int) sext(read24(REG_LPF), 24)
    }

    /** Enable the RMS converter (requires AD2 and the LPF). */
    void enableRms() {
        writeRegister(REG_R29, read1(REG_R29) | 0x80)
    }

    /** Wait for RMS and return {@code sqrt(RMS accumulator)} in AD2 LSB units. */
    float readRms() {
        waitFlag(FLAG_RMSF)
        byte[] b = readRegister(REG_RMS, 5)
        long raw = 0L
        for (int i = 0; i < 5; i++) {
            raw |= (b[i] & 0xFFL) << (8 * i)
        }
        return (float) Math.sqrt((double) Math.max(sext(raw, 40), 0L))
    }

    /** Enable peak hold; source 0–3 → AD2, AD1&lt;23:5&gt;, LPF, LPF. Re-enabling resets min/max. */
    void enablePeakHold(int source) {
        int r29 = read1(REG_R29)
        writeRegister(REG_R29, r29 & ~0x07)
        writeRegister(REG_R29, (r29 & ~0x07) | 0x04 | (source & 3))
    }

    /** Read the peak-hold registers: {@code [min, max]} signed 19-bit codes. */
    int[] readPeak() {
        int[] out = new int[2]
        out[0] = (int) sext(read24(REG_PKHMIN), 24)
        out[1] = (int) sext(read24(REG_PKHMAX), 24)
        return out
    }

    /** Start the frequency counter; gatePreload (0–0xFFFF) is written to CTA&lt;23:8&gt;. */
    void startCounter(int gatePreload) {
        int r20 = read1(REG_R20)
        writeRegister(REG_R20, r20 & ~0x02)
        byte[] preload = new byte[2]
        preload[0] = (byte) gatePreload
        preload[1] = (byte) (gatePreload >> 8)
        writeRegister(REG_CTA + 1, preload)
        clearFlags()
        writeRegister(REG_R20, r20 | 0x0A)
    }

    /** Wait for the counter and read it: {@code [cta, ctb, ctc, overflow(0/1)]}. */
    long[] readCounter() {
        waitFlag(FLAG_CTF)
        long overflow = read1(REG_CTSTA) & 0x01
        long[] out = new long[4]
        out[0] = read24(REG_CTA)
        out[1] = read24(REG_CTB)
        out[2] = read24(REG_CTC)
        out[3] = overflow
        return out
    }

    /** Read INTF (clears it on the chip); latched flags are included and cleared. */
    int readFlags() {
        int f = flags | read1(REG_INTF)
        flags = 0
        return f
    }

    /** Write INTE (RMSIE 4, LPFIE 3, AD1IE 2, AD2IE 1, CTIE 0). */
    void enableInterrupts(int mask) {
        writeRegister(REG_INTE, mask & 0x1F)
    }
}
