package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * ES8311 — low-power mono audio codec, full driver.
 *
 * <p>Extends {@link ES8311Minimal} with the clock tree, serial port, microphone / ADC / DAC
 * dynamics, equalizers, routing, power control and raw register access. Invalid parameters raise
 * {@link IllegalArgumentException}.
 */
public class ES8311Full extends ES8311Minimal {

    private static final List<Integer> BCLK_DIVIDERS = Arrays.asList(22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72);

    /** Create the driver (MCLK 12.288 MHz, 48 kHz). @param connection I²C register connection. @throws IOException on I²C error. */
    public ES8311Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /** Create the driver. @param connection I²C register connection. @param mclkHz master clock in Hz. @param sampleRateHz sample rate in Hz. @throws IOException on I²C error. */
    public ES8311Full(RegisterConnection connection, long mclkHz, long sampleRateHz) throws IOException {
        super(connection, mclkHz, sampleRateHz);
    }

    /** Create the driver. @param connection I²C register connection. @param mclkHz master clock in Hz. @param sampleRateHz sample rate in Hz. @param dvddVolts DVDD supply. @throws IOException on I²C error. */
    public ES8311Full(RegisterConnection connection, long mclkHz, long sampleRateHz, double dvddVolts) throws IOException {
        super(connection, mclkHz, sampleRateHz, dvddVolts);
    }

    /**
     * Read back the DAC volume.
     *
     * @return dB (−95.5 … +32).
     * @throws IOException on I²C error.
     */
    public double getVolume() throws IOException {
        return (r(REG_DAC_VOLUME) - 191) * 0.5;
    }

    /**
     * Read back the analog microphone PGA gain.
     *
     * @return dB (0 … 30).
     * @throws IOException on I²C error.
     */
    public double getMicGain() throws IOException {
        return (r(REG_SYS_MIC) & 0x0F) * 3.0;
    }

    /**
     * Software reset; registers return to their reset values (configure again afterwards).
     *
     * @throws IOException on I²C error.
     */
    public void reset() throws IOException {
        softReset();
    }

    /**
     * Re-enable the analog, reference and clock blocks (vendor values) and set CSM_ON.
     *
     * @throws IOException on I²C error.
     */
    public void powerUp() throws IOException {
        update(REG_RESET, 0x9F, 0x00);
        update(REG_RESET, 0x80, 0x80);
        w(REG_CLK1, 0x3F);
        powerUpAnalog();
    }

    /**
     * Standby: mute the DAC, power down the analog blocks, reset all and clear CSM_ON.
     *
     * @throws IOException on I²C error.
     */
    public void powerDown() throws IOException {
        setMute(true);
        update(REG_SYS_PWR, 0xFC, 0xFC);
        update(REG_SYS_ADC, 0x70, 0x70);
        update(REG_SYS_PWR, 0x03, 0x00);
        w(REG_RESET, 0x1F);
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
     * @throws IOException on I²C error.
     */
    public void setLowPower(boolean dac, boolean pga, boolean pgaOut, boolean adc, boolean adcRef,
                            boolean dacRef, boolean flash, boolean integrator) throws IOException {
        boolean[] flags = {dac, pga, pgaOut, adc, adcRef, dacRef, flash, integrator};
        int v = 0;
        for (int i = 0; i < flags.length; i++) if (flags[i]) v |= 0x80 >> i;
        w(REG_SYS_LP, v);
    }

    /** Clear all low-power bits. @throws IOException on I²C error. */
    public void setLowPower() throws IOException {
        setLowPower(false, false, false, false, false, false, false, false);
    }

    /**
     * Program the clock tree (registers 0x01–0x05) from a preset or computed dividers.
     *
     * @param mclkHz master clock in Hz.
     * @param sampleRateHz audio sample rate in Hz.
     * @param mclkFromBclk take the master clock from the SCLK (BCLK) pin.
     * @param mclkInvert invert the master clock.
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException if no valid divider set exists (nothing is written).
     */
    public void configureClock(long mclkHz, long sampleRateHz, boolean mclkFromBclk, boolean mclkInvert) throws IOException {
        int[] clk = solveClock(mclkHz, sampleRateHz, dvdd);
        this.mclkHz = mclkHz;
        this.fs = sampleRateHz;
        w(REG_CLK1, 0x3F | (mclkFromBclk ? 0x80 : 0) | (mclkInvert ? 0x40 : 0));
        writeClock(clk);
    }

    /** Program the clock tree with the MCLK taken from the pin, not inverted. @param mclkHz master clock in Hz. @param sampleRateHz sample rate in Hz. @throws IOException on I²C error. */
    public void configureClock(long mclkHz, long sampleRateHz) throws IOException {
        configureClock(mclkHz, sampleRateHz, false, false);
    }

    private long internalClock() throws IOException {
        int r02 = r(REG_CLK2);
        long[] mults = {1, 2, 4, 8};
        return mclkHz * mults[(r02 >> 3) & 3] / (((r02 >> 5) & 7) + 1);
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
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException for an unknown format, word length or BCLK divider.
     */
    public void configureSerial(String format, int wordLength, boolean master, boolean bclkInvert,
                                boolean lrckInvert, String dacChannel, Integer bclkDiv, Integer lrckDiv,
                                boolean continuousBclk) throws IOException {
        int fmt;
        switch (format) {
            case "i2s": fmt = 0; break;
            case "left_justified": fmt = 1; break;
            case "dsp_a":
            case "dsp_b": fmt = 3; break;
            default: throw new IllegalArgumentException("unknown format");
        }
        int wl;
        switch (wordLength) {
            case 24: wl = 0; break;
            case 20: wl = 1; break;
            case 18: wl = 2; break;
            case 16: wl = 3; break;
            case 32: wl = 4; break;
            default: throw new IllegalArgumentException("wordLength must be 16, 18, 20, 24 or 32");
        }
        int lrp = (lrckInvert ? 1 : 0) ^ ("dsp_b".equals(format) ? 1 : 0);
        int base = (wl << 2) | fmt | (lrp << 5);
        int code = 0;
        int lrck = 0;
        if (master) {
            long imclk = internalClock();
            int bclk = bclkDiv != null ? bclkDiv : (int) (imclk / (fs * 2 * wordLength));
            lrck = lrckDiv != null ? lrckDiv : (int) (imclk / fs);
            if (bclk <= 0 || lrck <= 0 || lrck > 4096) throw new IllegalArgumentException("invalid master clock divider");
            if (bclk <= 20) {
                code = bclk - 1;
            } else {
                int i = BCLK_DIVIDERS.indexOf(bclk);
                if (i < 0) throw new IllegalArgumentException("unsupported BCLK divider");
                code = 20 + i;
            }
        }
        w(REG_SDP_IN, base | ("right".equals(dacChannel) ? 0x80 : 0x00) | (r(REG_SDP_IN) & 0x40));
        w(REG_SDP_OUT, base | (r(REG_SDP_OUT) & 0x40));
        if (master) {
            w(REG_CLK6, (continuousBclk ? 0x00 : 0x40) | (bclkInvert ? 0x20 : 0) | code);
            update(REG_CLK7, 0x0F, ((lrck - 1) >> 8) & 0x0F);
            w(REG_CLK8, (lrck - 1) & 0xFF);
            update(REG_RESET, 0x44, 0x40);
        } else {
            update(REG_CLK6, 0x20, bclkInvert ? 0x20 : 0x00);
            update(REG_RESET, 0x44, 0x04);
        }
    }

    /**
     * Configure the digital audio port as a slave with normal polarity and the left channel to the DAC.
     *
     * @param format "i2s", "left_justified", "dsp_a" or "dsp_b".
     * @param wordLength 16, 18, 20, 24 or 32 bit.
     * @throws IOException on I²C error.
     */
    public void configureSerial(String format, int wordLength) throws IOException {
        configureSerial(format, wordLength, false, false, false, "left", null, null, true);
    }

    /**
     * Tri-state the BCLK / LRCK pins and / or ADCDAT (0x07 bits 5:4).
     *
     * @param bclkLrck tri-state BCLK and LRCK.
     * @param adcdat tri-state ADCDAT.
     * @throws IOException on I²C error.
     */
    public void setTristate(boolean bclkLrck, boolean adcdat) throws IOException {
        update(REG_CLK7, 0x30, (bclkLrck ? 0x20 : 0) | (adcdat ? 0x10 : 0));
    }

    /**
     * Select the microphone input.
     *
     * @param source "analog" (MIC1P/MIC1N), "digital" (PDM on MIC1P) or "none".
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException for an unknown source.
     */
    public void setInput(String source) throws IOException {
        int sel, pga;
        switch (source) {
            case "analog": sel = 0x10; pga = 0x00; break;
            case "digital": sel = 0x40; pga = 0x40; break;
            case "none": sel = 0x00; pga = 0x40; break;
            default: throw new IllegalArgumentException("source must be analog, digital or none");
        }
        update(REG_SYS_MIC, 0x70, sel);
        update(REG_SYS_ADC, 0x40, pga);
    }

    /**
     * Select the PDM data latch edge (0x15 bit 0).
     *
     * @param latchFallingEdge true to latch on the falling clock edge.
     * @throws IOException on I²C error.
     */
    public void configureDmic(boolean latchFallingEdge) throws IOException {
        update(REG_ADC_RAMP, 0x01, latchFallingEdge ? 0x01 : 0x00);
    }

    /**
     * Set the ADC digital volume (ALC maximum gain when the ALC is on).
     *
     * @param db −95.5 … +32 dB in 0.5 dB steps.
     * @throws IOException on I²C error.
     */
    public void setAdcVolume(double db) throws IOException {
        w(REG_ADC_VOLUME, volumeToReg(db));
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
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException if osr is out of range.
     */
    public void configureAdc(int scaleDb, int osr, boolean doubleSpeed, boolean hpf, int hpfCoeff1, int hpfCoeff2)
            throws IOException {
        int code = doubleSpeed ? osr / 2 : osr / 4;
        if (code < 15 || code > 63 || (doubleSpeed && code < 16)) throw new IllegalArgumentException("osr out of range");
        update(REG_ADC_SCALE, 0x07, clamp(round(scaleDb / 6.0), 0, 7));
        w(REG_CLK3, (doubleSpeed ? 0x40 : 0x00) | code);
        update(REG_ADC_HPF2_EQ, 0x3F, (hpf ? 0x20 : 0x00) | (hpfCoeff2 & 0x1F));
        update(REG_ADC_HPF1, 0x1F, hpfCoeff1 & 0x1F);
    }

    /** Configure the ADC with 24 dB scale, OSR 64, single speed, dynamic HPF with coefficients 0x0C. @throws IOException on I²C error. */
    public void configureAdc() throws IOException {
        configureAdc(24, 64, false, true, 0x0C, 0x0C);
    }

    /**
     * Set the ADC volume soft-ramp rate (0x15 bits 7:4).
     *
     * @param rate 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
     * @throws IOException on I²C error.
     */
    public void setAdcRamp(int rate) throws IOException {
        update(REG_ADC_RAMP, 0xF0, clamp(rate, 0, 15) << 4);
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
     * @throws IOException on I²C error.
     */
    public void configureAlc(boolean enabled, double maxLevelDb, double minLevelDb, int window,
                             boolean automute, int automuteWindow, double noiseGateDb) throws IOException {
        w(REG_ADC_ALC1, (enabled ? 0x80 : 0) | (automute ? 0x40 : 0) | (window & 0x0F));
        w(REG_ADC_ALC2, (nearest(ALC_LEVELS_DB, maxLevelDb) << 4) | nearest(ALC_LEVELS_DB, minLevelDb));
        w(REG_ADC_AUTOMUTE, ((automuteWindow & 0x0F) << 4) | nearest(NOISE_GATE_DB, noiseGateDb));
    }

    /** Enable or disable the ALC with the datasheet defaults (−6 dB / −30.1 dB, no automute). @param enabled enable the ALC. @throws IOException on I²C error. */
    public void configureAlc(boolean enabled) throws IOException {
        configureAlc(enabled, -6.0, -30.1, 0, false, 0, -96);
    }

    private byte[] eqBytes(long[] coefficients, int count) {
        if (coefficients == null || coefficients.length != count) {
            throw new IllegalArgumentException("expected " + count + " coefficients");
        }
        byte[] data = new byte[4 * count];
        for (int i = 0; i < count; i++) {
            long c = coefficients[i];
            if (c < 0 || c >= (1L << 30)) throw new IllegalArgumentException("coefficient must be a 30-bit value");
            data[4 * i] = (byte) ((c >> 24) & 0x3F);
            data[4 * i + 1] = (byte) ((c >> 16) & 0xFF);
            data[4 * i + 2] = (byte) ((c >> 8) & 0xFF);
            data[4 * i + 3] = (byte) (c & 0xFF);
        }
        return data;
    }

    /** Bypass the ADC equalizer (0x1C bit 6). @throws IOException on I²C error. */
    public void bypassAdcEq() throws IOException {
        update(REG_ADC_HPF2_EQ, 0x40, 0x40);
    }

    /**
     * Load the ADC equalizer coefficients and enable it.
     *
     * @param coefficients {b0, a1, a2, b1, b2} as 30-bit values.
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException if the count or range is wrong.
     */
    public void setAdcEq(long[] coefficients) throws IOException {
        byte[] data = eqBytes(coefficients, 5);
        connection.write(REG_ADCEQ, data);
        update(REG_ADC_HPF2_EQ, 0x40, 0x00);
    }

    /**
     * Set the DAC volume / DRC soft-ramp rate (0x37 bits 7:4).
     *
     * @param rate 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
     * @throws IOException on I²C error.
     */
    public void setDacRamp(int rate) throws IOException {
        update(REG_DAC_RAMP_EQ, 0xF0, clamp(rate, 0, 15) << 4);
    }

    /**
     * Configure the DAC dynamic range control (0x34, 0x35).
     *
     * @param enabled enable the DRC (the DAC volume becomes its maximum gain).
     * @param maxLevelDb upper target level.
     * @param minLevelDb lower target level.
     * @param window window size 0–15.
     * @throws IOException on I²C error.
     */
    public void configureDrc(boolean enabled, double maxLevelDb, double minLevelDb, int window) throws IOException {
        w(REG_DAC_DRC1, (enabled ? 0x80 : 0) | (window & 0x0F));
        w(REG_DAC_DRC2, (nearest(ALC_LEVELS_DB, maxLevelDb) << 4) | nearest(ALC_LEVELS_DB, minLevelDb));
    }

    /** Enable or disable the DRC with the datasheet default levels (−30.1 dB). @param enabled enable the DRC. @throws IOException on I²C error. */
    public void configureDrc(boolean enabled) throws IOException {
        configureDrc(enabled, -30.1, -30.1, 0);
    }

    /** Bypass the DAC equalizer (0x37 bit 3). @throws IOException on I²C error. */
    public void bypassDacEq() throws IOException {
        update(REG_DAC_RAMP_EQ, 0x08, 0x08);
    }

    /**
     * Load the DAC equalizer coefficients and enable it.
     *
     * @param coefficients {b0, b1, a1} as 30-bit values.
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException if the count or range is wrong.
     */
    public void setDacEq(long[] coefficients) throws IOException {
        byte[] data = eqBytes(coefficients, 3);
        connection.write(REG_DACEQ, data);
        update(REG_DAC_RAMP_EQ, 0x08, 0x00);
    }

    /**
     * Set the three DAC mute bits individually (0x31 bits 7:5).
     *
     * @param dsm DSM mute (bit 6).
     * @param dem DEM mute (bit 5).
     * @param dsmTarget DSM mute target (bit 7).
     * @throws IOException on I²C error.
     */
    public void setDacMuteOptions(boolean dsm, boolean dem, boolean dsmTarget) throws IOException {
        update(REG_DAC_MUTE, 0xE0, (dsmTarget ? 0x80 : 0) | (dsm ? 0x40 : 0) | (dem ? 0x20 : 0));
    }

    /**
     * Feed the ADC output to the DAC (0x44 bit 7).
     *
     * @param enabled true to enable the loopback.
     * @throws IOException on I²C error.
     */
    public void setLoopback(boolean enabled) throws IOException {
        update(REG_GPIO, 0x80, enabled ? 0x80 : 0x00);
    }

    /**
     * Select the ADCDAT content (0x44 bits 6:4).
     *
     * @param mode "adc_adc", "adc_zero", "zero_adc", "dac_adc", "adc_dac" or "dac_dac".
     * @throws IOException on I²C error.
     * @throws IllegalArgumentException for an unknown mode.
     */
    public void setAdcdatMode(String mode) throws IOException {
        int code;
        switch (mode) {
            case "adc_adc": code = 0; break;
            case "adc_zero": code = 1; break;
            case "zero_adc": code = 2; break;
            case "dac_adc": code = 4; break;
            case "adc_dac": code = 5; break;
            case "dac_dac": code = 6; break;
            default: throw new IllegalArgumentException("unknown ADCDAT mode");
        }
        update(REG_GPIO, 0x70, code << 4);
    }

    /**
     * Mute the digital audio input and / or output (0x09 / 0x0A bit 6).
     *
     * @param input mute the serial input (DAC data).
     * @param output mute the serial output (ADC data).
     * @throws IOException on I²C error.
     */
    public void setSdpMute(boolean input, boolean output) throws IOException {
        update(REG_SDP_IN, 0x40, input ? 0x40 : 0x00);
        update(REG_SDP_OUT, 0x40, output ? 0x40 : 0x00);
    }

    /**
     * Raw register read.
     *
     * @param register register address 0x00–0xFF.
     * @return register value.
     * @throws IOException on I²C error.
     */
    public int readRegister(int register) throws IOException {
        return r(register);
    }

    /**
     * Raw register write.
     *
     * @param register register address 0x00–0xFF.
     * @param value byte value.
     * @throws IOException on I²C error.
     */
    public void writeRegister(int register, int value) throws IOException {
        w(register, value & 0xFF);
    }
}
