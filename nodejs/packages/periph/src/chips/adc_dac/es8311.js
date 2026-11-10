'use strict';

/**
 * ES8311 — low-power mono audio codec, I²C control port (Everest Semiconductor).
 *
 * Mono ADC (analog differential or PDM microphone, 0–30 dB PGA, ALC, equalizer)
 * and mono DAC (digital volume, DRC, equalizer) with one I²S / left-justified /
 * DSP digital audio port. This driver covers the I²C control port only: the audio
 * stream itself runs over a separate I²S port that this library does not provide.
 * Address 0x18 (CE low) or 0x19 (CE high).
 *
 * The user guide leaves the start-up sequence and registers 0x04, 0x0B–0x0C and
 * 0x10–0x13 undocumented; the vendor reference-driver values are used — verify on
 * hardware.
 */

const _REG_RESET = 0x00;
const _REG_CLK1 = 0x01;
const _REG_CLK2 = 0x02;
const _REG_CLK3 = 0x03;
const _REG_CLK4 = 0x04;
const _REG_CLK5 = 0x05;
const _REG_CLK6 = 0x06;
const _REG_CLK7 = 0x07;
const _REG_CLK8 = 0x08;
const _REG_SDP_IN = 0x09;
const _REG_SDP_OUT = 0x0A;
const _REG_SYS_PWR = 0x0D;
const _REG_SYS_ADC = 0x0E;
const _REG_SYS_LP = 0x0F;
const _REG_SYS_MIC = 0x14;
const _REG_ADC_RAMP = 0x15;
const _REG_ADC_SCALE = 0x16;
const _REG_ADC_VOLUME = 0x17;
const _REG_ADC_ALC1 = 0x18;
const _REG_ADC_ALC2 = 0x19;
const _REG_ADC_AUTOMUTE = 0x1A;
const _REG_ADC_HPF1 = 0x1B;
const _REG_ADC_HPF2_EQ = 0x1C;
const _REG_ADCEQ = 0x1D;
const _REG_DAC_MUTE = 0x31;
const _REG_DAC_VOLUME = 0x32;
const _REG_DAC_DRC1 = 0x34;
const _REG_DAC_DRC2 = 0x35;
const _REG_DAC_RAMP_EQ = 0x37;
const _REG_DACEQ = 0x38;
const _REG_GPIO = 0x44;

// "mclk:fs" -> [0x02, 0x03, 0x04, 0x05] from the guide's clock table
const _CLOCK_PRESETS = {
    '24576000:32000': [0x40, 0x10, 0x10, 0x00],
    '24576000:48000': [0x20, 0x10, 0x10, 0x00],
    '12288000:32000': [0x48, 0x10, 0x10, 0x00],
    '12288000:48000': [0x00, 0x10, 0x10, 0x00],
    '18432000:32000': [0x20, 0x12, 0x12, 0x00],
    '18432000:48000': [0x48, 0x10, 0x10, 0x00],
    '11289600:44100': [0x00, 0x10, 0x10, 0x00],
    '12000000:32000': [0x00, 0x17, 0x17, 0x00],
    '12000000:44100': [0x00, 0x11, 0x11, 0x00],
    '12000000:48000': [0x98, 0x19, 0x19, 0x00],
    '26000000:32000': [0x40, 0x10, 0x10, 0x00],
    '3000000:32000': [0x18, 0x52, 0x17, 0x41],
    '25000000:32000': [0x48, 0x55, 0x10, 0x21],
    '25000000:48000': [0x28, 0x55, 0x10, 0x21],
};
const _PREFERRED_RATIOS = [256, 384, 512, 768, 1024, 1536];
const _BCLK_DIVIDERS = [22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72]; // DIV_BCLK codes 20..31
const _FORMATS = { i2s: 0, left_justified: 1, dsp_a: 3, dsp_b: 3 };
const _WORD_LENGTHS = { 24: 0, 20: 1, 18: 2, 16: 3, 32: 4 };
const _ADCDAT_MODES = { adc_adc: 0, adc_zero: 1, zero_adc: 2, dac_adc: 4, adc_dac: 5, dac_dac: 6 };

const ALC_LEVELS_DB = [-30.1, -24.1, -20.6, -18.1, -16.1, -14.5, -13.2, -12.0,
                       -11.0, -10.1, -9.3, -8.5, -7.8, -7.2, -6.6, -6.0];
const NOISE_GATE_DB = [-96, -90, -84, -78, -72, -66, -60, -54, -51, -48, -45, -42, -39, -36, -33, -30];

function _nearest(table, value) {
    let best = 0;
    for (let i = 0; i < table.length; i++) {
        if (Math.abs(table[i] - value) < Math.abs(table[best] - value)) best = i;
    }
    return best;
}

function _clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

// Round half away from zero (matches C++ lroundf and the Go/Java ports).
function _round(v) { return v < 0 ? -Math.round(-v) : Math.round(v); }

function _volumeToReg(db) { return _clamp(_round(db / 0.5) + 191, 0, 255); }

function _sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

/**
 * Solve the clock registers 0x02–0x05 for an MCLK / sample-rate pair.
 * Tries the guide's preset table first (3.3 V only), then searches the pre-divider,
 * multiplier and ADC/DAC divider for an allowed MCLK:LRCK ratio.
 * @param {number} mclkHz - Master clock in Hz.
 * @param {number} fs - Sample rate in Hz.
 * @param {number} dvddVolts - DVDD supply (selects the 3.3 V or 1.8 V limits).
 * @returns {number[]} [0x02, 0x03, 0x04, 0x05] values.
 * @throws {RangeError} If no valid divider set exists.
 */
function _solveClock(mclkHz, fs, dvddVolts) {
    const low = dvddVolts < 2.5;
    const preset = _CLOCK_PRESETS[mclkHz + ':' + fs];
    if (!low && preset) return preset.slice();
    const preLimit = low ? 4.5e6 : 10e6;
    const clkLimit = low ? 13e6 : 35e6;
    let best = null;
    for (let dp = 1; dp <= 8; dp++) {
        if (mclkHz / dp >= preLimit) continue;
        [1, 2, 4, 8].forEach((mult, mi) => {
            const imclk = mclkHz / dp * mult;
            for (let div = 0; div < 16; div++) {
                const clk = imclk / (div + 1);
                if (clk >= clkLimit) continue;
                const ratio = clk / fs;
                if (!Number.isInteger(ratio) || ratio % 16 !== 0 || ratio < 256 || ratio > 1008) continue;
                const idx = _PREFERRED_RATIOS.indexOf(ratio);
                const key = [idx >= 0 ? idx : 99, dp, mult, div];
                if (best === null || _cmp(key, best.key) < 0) best = { key, r02: ((dp - 1) << 5) | (mi << 3), osr: ratio / 16, div };
            }
        });
    }
    if (best === null) throw new RangeError('no valid ES8311 clock divider for MCLK ' + mclkHz + ' Hz / fs ' + fs + ' Hz');
    return [best.r02, best.osr, best.osr, (best.div << 4) | best.div];
}

function _cmp(a, b) {
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return a[i] - b[i];
    return 0;
}

/**
 * ES8311 minimal interface — bring-up for voice playback and recording.
 *
 * The constructor starts the initialization sequence (software reset, clock tree for
 * the MCLK / sample rate, I²S 16-bit slave, analog path, microphone 24 dB, ADC 0 dB,
 * DAC −95.5 dB). An invalid MCLK / sample-rate pair throws a RangeError synchronously.
 * JS constructors cannot be async, so the register writes run in the background:
 * every method awaits their completion first and rethrows an I²C error from the
 * init sequence.
 */
class ES8311Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (0x18 / 0x19).
     * @param {number} [mclkHz=12288000] - Master clock in Hz.
     * @param {number} [sampleRateHz=48000] - Audio sample rate in Hz.
     * @param {number} [dvddVolts=3.3] - DVDD supply, selects the 3.3 V or 1.8 V clock limits.
     * @throws {RangeError} If the MCLK : sample-rate combination has no valid divider set.
     */
    constructor(connection, mclkHz = 12288000, sampleRateHz = 48000, dvddVolts = 3.3) {
        this._conn = connection;
        this._mclkHz = mclkHz;
        this._fs = sampleRateHz;
        this._dvdd = dvddVolts;
        const clk = _solveClock(mclkHz, sampleRateHz, dvddVolts);
        this._ready = this._init(clk);
        this._ready.catch(() => {});
    }

    async _init(clk) {
        await this._softReset();
        await this._w(_REG_CLK1, 0x3F);
        await this._writeClock(clk);
        await this._w(_REG_CLK6, 0x03);
        await this._w(_REG_CLK7, 0x00);
        await this._w(_REG_CLK8, 0xFF);
        await this._w(_REG_SDP_IN, 0x0C);
        await this._w(_REG_SDP_OUT, 0x0C);
        await this._powerUpAnalog();
        await this._w(_REG_ADC_SCALE, 0x04);
        await this._w(_REG_SYS_MIC, 0x18);
        await this._w(_REG_ADC_VOLUME, 0xBF);
        await this._w(_REG_DAC_VOLUME, 0x00);
    }

    async _w(reg, value) { await this._conn.writeReg(reg, value); }

    async _r(reg) { return (await this._conn.readReg(reg, 1))[0]; }

    async _update(reg, mask, value) {
        await this._w(reg, ((await this._r(reg)) & ~mask & 0xFF) | (value & mask));
    }

    async _softReset() {
        await this._w(_REG_RESET, 0x1F);
        await _sleep(20);
        await this._w(_REG_RESET, 0x00);
        await this._w(_REG_RESET, 0x80);
    }

    async _writeClock(clk) {
        await this._w(_REG_CLK2, clk[0]);
        await this._w(_REG_CLK3, clk[1]);
        await this._w(_REG_CLK4, clk[2]);
        await this._w(_REG_CLK5, clk[3]);
    }

    async _powerUpAnalog() {
        await this._w(0x0B, 0x00);
        await this._w(0x0C, 0x00);
        await this._w(0x10, 0x1F);
        await this._w(0x11, 0x7F);
        await this._w(_REG_SYS_PWR, 0x01);
        await this._w(_REG_SYS_ADC, 0x02);
        await this._w(0x12, 0x00);
        await this._w(0x13, 0x10);
        await this._w(_REG_ADC_HPF2_EQ, 0x6A);
        await this._w(_REG_DAC_RAMP_EQ, 0x08);
    }

    /**
     * Set the DAC digital volume.
     * @param {number} db - −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
     * @returns {Promise<void>}
     */
    async setVolume(db) {
        await this._ready;
        await this._w(_REG_DAC_VOLUME, _volumeToReg(db));
    }

    /**
     * Mute or unmute the DAC (DSM and DEM mute together, 0x31 bits 6 and 5).
     * @param {boolean} muted - True to mute.
     * @returns {Promise<void>}
     */
    async setMute(muted) {
        await this._ready;
        await this._update(_REG_DAC_MUTE, 0x60, muted ? 0x60 : 0x00);
    }

    /**
     * Set the analog microphone PGA gain.
     * @param {number} db - 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
     * @returns {Promise<void>}
     */
    async setMicGain(db) {
        await this._ready;
        await this._update(_REG_SYS_MIC, 0x0F, _clamp(_round(db / 3), 0, 10));
    }
}

/**
 * ES8311 full interface — adds clock tree, serial port, microphone / ADC / DAC
 * dynamics, equalizers, routing, power control and raw register access.
 */
class ES8311Full extends ES8311Minimal {
    /**
     * Read back the DAC volume.
     * @returns {Promise<number>} dB (−95.5 … +32).
     */
    async getVolume() {
        await this._ready;
        return ((await this._r(_REG_DAC_VOLUME)) - 191) * 0.5;
    }

    /**
     * Read back the analog microphone PGA gain.
     * @returns {Promise<number>} dB (0 … 30).
     */
    async getMicGain() {
        await this._ready;
        return ((await this._r(_REG_SYS_MIC)) & 0x0F) * 3;
    }

    /**
     * Software reset; registers return to their reset values (configure again afterwards).
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        await this._softReset();
    }

    /**
     * Re-enable the analog, reference and clock blocks (vendor values) and set CSM_ON.
     * @returns {Promise<void>}
     */
    async powerUp() {
        await this._ready;
        await this._update(_REG_RESET, 0x9F, 0x00);
        await this._update(_REG_RESET, 0x80, 0x80);
        await this._w(_REG_CLK1, 0x3F);
        await this._powerUpAnalog();
    }

    /**
     * Standby: mute the DAC, power down the analog blocks, reset all and clear CSM_ON.
     * @returns {Promise<void>}
     */
    async powerDown() {
        await this.setMute(true);
        await this._update(_REG_SYS_PWR, 0xFC, 0xFC);
        await this._update(_REG_SYS_ADC, 0x70, 0x70);
        await this._update(_REG_SYS_PWR, 0x03, 0x00);
        await this._w(_REG_RESET, 0x1F);
    }

    /**
     * Set the low-power bits of register 0x0F (slightly lower THD+N / SNR).
     * @param {{dac?: boolean, pga?: boolean, pgaOut?: boolean, adc?: boolean, adcRef?: boolean,
     *          dacRef?: boolean, flash?: boolean, integrator?: boolean}} [opts] - Bits to set; omitted = false.
     * @returns {Promise<void>}
     */
    async setLowPower(opts = {}) {
        await this._ready;
        const flags = [opts.dac, opts.pga, opts.pgaOut, opts.adc, opts.adcRef, opts.dacRef, opts.flash, opts.integrator];
        let value = 0;
        flags.forEach((f, i) => { if (f) value |= 0x80 >> i; });
        await this._w(_REG_SYS_LP, value);
    }

    /**
     * Program the clock tree (registers 0x01–0x05) from a preset or computed dividers.
     * @param {number} mclkHz - Master clock in Hz.
     * @param {number} sampleRateHz - Audio sample rate in Hz.
     * @param {boolean} [mclkFromBclk=false] - Take the master clock from the SCLK (BCLK) pin.
     * @param {boolean} [mclkInvert=false] - Invert the master clock.
     * @returns {Promise<void>}
     * @throws {RangeError} If no valid divider set exists for the combination.
     */
    async configureClock(mclkHz, sampleRateHz, mclkFromBclk = false, mclkInvert = false) {
        await this._ready;
        const clk = _solveClock(mclkHz, sampleRateHz, this._dvdd);
        this._mclkHz = mclkHz;
        this._fs = sampleRateHz;
        await this._w(_REG_CLK1, 0x3F | (mclkFromBclk ? 0x80 : 0) | (mclkInvert ? 0x40 : 0));
        await this._writeClock(clk);
    }

    async _internalClock() {
        const r02 = await this._r(_REG_CLK2);
        return Math.floor(this._mclkHz / (((r02 >> 5) & 7) + 1) * [1, 2, 4, 8][(r02 >> 3) & 3]);
    }

    /**
     * Configure the digital audio port (0x09, 0x0A, RESET.MSC, 0x06–0x08).
     * @param {object} [o] - Options.
     * @param {string} [o.format='i2s'] - 'i2s', 'left_justified', 'dsp_a' or 'dsp_b'.
     * @param {number} [o.wordLength=16] - 16, 18, 20, 24 or 32 bit.
     * @param {boolean} [o.master=false] - Master mode (chip drives BCLK / LRCK).
     * @param {boolean} [o.bclkInvert=false] - Invert BCLK.
     * @param {boolean} [o.lrckInvert=false] - Invert the LRCK polarity.
     * @param {string} [o.dacChannel='left'] - 'left' or 'right' data to the DAC.
     * @param {?number} [o.bclkDiv=null] - Master BCLK divider (null = computed).
     * @param {?number} [o.lrckDiv=null] - Master LRCK divider (null = computed).
     * @param {boolean} [o.continuousBclk=true] - Keep BCLK running when idle (master mode).
     * @returns {Promise<void>}
     * @throws {RangeError} For an unknown format, word length or BCLK divider.
     */
    async configureSerial(o = {}) {
        const format = o.format === undefined ? 'i2s' : o.format;
        const wordLength = o.wordLength === undefined ? 16 : o.wordLength;
        if (!(format in _FORMATS)) throw new RangeError('unknown format');
        if (!(wordLength in _WORD_LENGTHS)) throw new RangeError('wordLength must be 16, 18, 20, 24 or 32');
        await this._ready;
        const lrp = (o.lrckInvert ? 1 : 0) ^ (format === 'dsp_b' ? 1 : 0);
        const base = (_WORD_LENGTHS[wordLength] << 2) | _FORMATS[format] | (lrp << 5);
        let code = 0;
        let lrckDiv = 0;
        if (o.master) {
            const imclk = await this._internalClock();
            let bclkDiv = o.bclkDiv == null ? Math.floor(imclk / (this._fs * 2 * wordLength)) : o.bclkDiv;
            lrckDiv = o.lrckDiv == null ? Math.floor(imclk / this._fs) : o.lrckDiv;
            if (bclkDiv <= 20) code = bclkDiv - 1;
            else {
                const i = _BCLK_DIVIDERS.indexOf(bclkDiv);
                if (i < 0) throw new RangeError('unsupported BCLK divider');
                code = 20 + i;
            }
        }
        await this._w(_REG_SDP_IN, base | (o.dacChannel === 'right' ? 0x80 : 0x00) | ((await this._r(_REG_SDP_IN)) & 0x40));
        await this._w(_REG_SDP_OUT, base | ((await this._r(_REG_SDP_OUT)) & 0x40));
        if (o.master) {
            await this._w(_REG_CLK6, (o.continuousBclk === false ? 0x40 : 0x00) | (o.bclkInvert ? 0x20 : 0) | code);
            await this._update(_REG_CLK7, 0x0F, ((lrckDiv - 1) >> 8) & 0x0F);
            await this._w(_REG_CLK8, (lrckDiv - 1) & 0xFF);
            await this._update(_REG_RESET, 0x44, 0x40);
        } else {
            await this._update(_REG_CLK6, 0x20, o.bclkInvert ? 0x20 : 0x00);
            await this._update(_REG_RESET, 0x44, 0x04);
        }
    }

    /**
     * Tri-state the BCLK / LRCK pins and / or ADCDAT (0x07 bits 5:4).
     * @param {boolean} [bclkLrck=false] - Tri-state BCLK and LRCK.
     * @param {boolean} [adcdat=false] - Tri-state ADCDAT.
     * @returns {Promise<void>}
     */
    async setTristate(bclkLrck = false, adcdat = false) {
        await this._ready;
        await this._update(_REG_CLK7, 0x30, (bclkLrck ? 0x20 : 0) | (adcdat ? 0x10 : 0));
    }

    /**
     * Select the microphone input.
     * @param {string} [source='analog'] - 'analog' (MIC1P/MIC1N), 'digital' (PDM on MIC1P) or 'none'.
     * @returns {Promise<void>}
     * @throws {RangeError} For an unknown source.
     */
    async setInput(source = 'analog') {
        if (!['analog', 'digital', 'none'].includes(source)) throw new RangeError('source must be analog, digital or none');
        await this._ready;
        await this._update(_REG_SYS_MIC, 0x70, source === 'analog' ? 0x10 : (source === 'digital' ? 0x40 : 0x00));
        await this._update(_REG_SYS_ADC, 0x40, source === 'analog' ? 0x00 : 0x40);
    }

    /**
     * Select the PDM data latch edge (0x15 bit 0).
     * @param {boolean} [latchFallingEdge=false] - Latch on the falling clock edge.
     * @returns {Promise<void>}
     */
    async configureDmic(latchFallingEdge = false) {
        await this._ready;
        await this._update(_REG_ADC_RAMP, 0x01, latchFallingEdge ? 0x01 : 0x00);
    }

    /**
     * Set the ADC digital volume (ALC maximum gain when the ALC is on).
     * @param {number} db - −95.5 … +32 dB in 0.5 dB steps.
     * @returns {Promise<void>}
     */
    async setAdcVolume(db) {
        await this._ready;
        await this._w(_REG_ADC_VOLUME, _volumeToReg(db));
    }

    /**
     * Configure the ADC digital path.
     * @param {object} [o] - Options.
     * @param {number} [o.scaleDb=24] - Digital gain scale-up 0 … 42 dB in 6 dB steps.
     * @param {number} [o.osr=64] - Oversampling ratio in × fs (single speed 60–252, double speed 32–126).
     * @param {boolean} [o.doubleSpeed=false] - Select the double-speed ADC.
     * @param {boolean} [o.hpf=true] - Dynamic high-pass filter (false freezes the DC offset).
     * @param {number[]} [o.hpfCoeffs=[0x0C, 0x0C]] - [stage-1, stage-2] coefficients 0–31.
     * @returns {Promise<void>}
     * @throws {RangeError} If osr is out of range.
     */
    async configureAdc(o = {}) {
        const osr = o.osr === undefined ? 64 : o.osr;
        const doubleSpeed = !!o.doubleSpeed;
        const code = Math.floor(doubleSpeed ? osr / 2 : osr / 4);
        if (code < 15 || code > 63 || (doubleSpeed && code < 16)) throw new RangeError('osr out of range');
        const coeffs = o.hpfCoeffs || [0x0C, 0x0C];
        await this._ready;
        const scale = o.scaleDb === undefined ? 24 : o.scaleDb;
        await this._update(_REG_ADC_SCALE, 0x07, _clamp(_round(scale / 6), 0, 7));
        await this._w(_REG_CLK3, (doubleSpeed ? 0x40 : 0x00) | code);
        await this._update(_REG_ADC_HPF2_EQ, 0x3F, (o.hpf === false ? 0x00 : 0x20) | (coeffs[1] & 0x1F));
        await this._update(_REG_ADC_HPF1, 0x1F, coeffs[0] & 0x1F);
    }

    /**
     * Set the ADC volume soft-ramp rate (0x15 bits 7:4).
     * @param {number} rate - 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
     * @returns {Promise<void>}
     */
    async setAdcRamp(rate) {
        await this._ready;
        await this._update(_REG_ADC_RAMP, 0xF0, _clamp(rate, 0, 15) << 4);
    }

    /**
     * Configure the automatic level control, noise gate and automute (0x18–0x1A).
     * @param {boolean} enabled - Enable the ALC.
     * @param {object} [o] - Options.
     * @param {number} [o.maxLevelDb=-6.0] - Upper target level (nearest of 16 levels).
     * @param {number} [o.minLevelDb=-30.1] - Lower target level.
     * @param {number} [o.window=0] - ALC window size 0–15.
     * @param {boolean} [o.automute=false] - Enable automute.
     * @param {number} [o.automuteWindow=0] - Automute window 0–15 (2048 × (n+1) samples).
     * @param {number} [o.noiseGateDb=-96] - Noise gate threshold (nearest of 16).
     * @returns {Promise<void>}
     */
    async configureAlc(enabled, o = {}) {
        await this._ready;
        const maxL = o.maxLevelDb === undefined ? -6.0 : o.maxLevelDb;
        const minL = o.minLevelDb === undefined ? -30.1 : o.minLevelDb;
        const gate = o.noiseGateDb === undefined ? -96 : o.noiseGateDb;
        await this._w(_REG_ADC_ALC1, (enabled ? 0x80 : 0) | (o.automute ? 0x40 : 0) | ((o.window || 0) & 0x0F));
        await this._w(_REG_ADC_ALC2, (_nearest(ALC_LEVELS_DB, maxL) << 4) | _nearest(ALC_LEVELS_DB, minL));
        await this._w(_REG_ADC_AUTOMUTE, (((o.automuteWindow || 0) & 0x0F) << 4) | _nearest(NOISE_GATE_DB, gate));
    }

    _eqBuffer(coefficients, count) {
        if (!coefficients || coefficients.length !== count) throw new RangeError('expected ' + count + ' coefficients');
        const data = Buffer.alloc(4 * count);
        coefficients.forEach((c, i) => {
            if (!Number.isInteger(c) || c < 0 || c >= 2 ** 30) throw new RangeError('coefficient must be a 30-bit value');
            data[4 * i] = Math.floor(c / 2 ** 24) & 0x3F;
            data[4 * i + 1] = (c >>> 16) & 0xFF;
            data[4 * i + 2] = (c >>> 8) & 0xFF;
            data[4 * i + 3] = c & 0xFF;
        });
        return data;
    }

    /**
     * Bypass the ADC equalizer or load its coefficients.
     * @param {?number[]} [coefficients=null] - null to bypass, else [b0, a1, a2, b1, b2] as 30-bit values.
     * @returns {Promise<void>}
     * @throws {RangeError} If the coefficient count or range is wrong.
     */
    async setAdcEq(coefficients = null) {
        const data = coefficients === null ? null : this._eqBuffer(coefficients, 5);
        await this._ready;
        if (data) await this._conn.writeReg(_REG_ADCEQ, data);
        await this._update(_REG_ADC_HPF2_EQ, 0x40, coefficients === null ? 0x40 : 0x00);
    }

    /**
     * Set the DAC volume / DRC soft-ramp rate (0x37 bits 7:4).
     * @param {number} rate - 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK periods.
     * @returns {Promise<void>}
     */
    async setDacRamp(rate) {
        await this._ready;
        await this._update(_REG_DAC_RAMP_EQ, 0xF0, _clamp(rate, 0, 15) << 4);
    }

    /**
     * Configure the DAC dynamic range control (0x34, 0x35).
     * @param {boolean} enabled - Enable the DRC (the DAC volume becomes its maximum gain).
     * @param {object} [o] - Options.
     * @param {number} [o.maxLevelDb=-30.1] - Upper target level.
     * @param {number} [o.minLevelDb=-30.1] - Lower target level.
     * @param {number} [o.window=0] - Window size 0–15.
     * @returns {Promise<void>}
     */
    async configureDrc(enabled, o = {}) {
        await this._ready;
        const maxL = o.maxLevelDb === undefined ? -30.1 : o.maxLevelDb;
        const minL = o.minLevelDb === undefined ? -30.1 : o.minLevelDb;
        await this._w(_REG_DAC_DRC1, (enabled ? 0x80 : 0) | ((o.window || 0) & 0x0F));
        await this._w(_REG_DAC_DRC2, (_nearest(ALC_LEVELS_DB, maxL) << 4) | _nearest(ALC_LEVELS_DB, minL));
    }

    /**
     * Bypass the DAC equalizer or load its coefficients.
     * @param {?number[]} [coefficients=null] - null to bypass, else [b0, b1, a1] as 30-bit values.
     * @returns {Promise<void>}
     * @throws {RangeError} If the coefficient count or range is wrong.
     */
    async setDacEq(coefficients = null) {
        const data = coefficients === null ? null : this._eqBuffer(coefficients, 3);
        await this._ready;
        if (data) await this._conn.writeReg(_REG_DACEQ, data);
        await this._update(_REG_DAC_RAMP_EQ, 0x08, coefficients === null ? 0x08 : 0x00);
    }

    /**
     * Set the three DAC mute bits individually (0x31 bits 7:5).
     * @param {boolean} [dsm=true] - DSM mute (bit 6).
     * @param {boolean} [dem=true] - DEM mute (bit 5).
     * @param {boolean} [dsmTarget=false] - DSM mute target (bit 7).
     * @returns {Promise<void>}
     */
    async setDacMuteOptions(dsm = true, dem = true, dsmTarget = false) {
        await this._ready;
        await this._update(_REG_DAC_MUTE, 0xE0, (dsmTarget ? 0x80 : 0) | (dsm ? 0x40 : 0) | (dem ? 0x20 : 0));
    }

    /**
     * Feed the ADC output to the DAC (0x44 bit 7).
     * @param {boolean} enabled - True to enable the loopback.
     * @returns {Promise<void>}
     */
    async setLoopback(enabled) {
        await this._ready;
        await this._update(_REG_GPIO, 0x80, enabled ? 0x80 : 0x00);
    }

    /**
     * Select the ADCDAT content (0x44 bits 6:4).
     * @param {string} mode - 'adc_adc', 'adc_zero', 'zero_adc', 'dac_adc', 'adc_dac' or 'dac_dac'.
     * @returns {Promise<void>}
     * @throws {RangeError} For an unknown mode.
     */
    async setAdcdatMode(mode) {
        if (!(mode in _ADCDAT_MODES)) throw new RangeError('unknown ADCDAT mode');
        await this._ready;
        await this._update(_REG_GPIO, 0x70, _ADCDAT_MODES[mode] << 4);
    }

    /**
     * Mute the digital audio input and / or output (0x09 / 0x0A bit 6).
     * @param {boolean} [input=false] - Mute the serial input (DAC data).
     * @param {boolean} [output=false] - Mute the serial output (ADC data).
     * @returns {Promise<void>}
     */
    async setSdpMute(input = false, output = false) {
        await this._ready;
        await this._update(_REG_SDP_IN, 0x40, input ? 0x40 : 0x00);
        await this._update(_REG_SDP_OUT, 0x40, output ? 0x40 : 0x00);
    }

    /**
     * Raw register read.
     * @param {number} register - Register address 0x00–0xFF.
     * @returns {Promise<number>} Register value.
     */
    async readRegister(register) {
        await this._ready;
        return this._r(register);
    }

    /**
     * Raw register write.
     * @param {number} register - Register address 0x00–0xFF.
     * @param {number} value - Byte value.
     * @returns {Promise<void>}
     */
    async writeRegister(register, value) {
        await this._ready;
        await this._w(register, value & 0xFF);
    }
}

module.exports = { ES8311Minimal, ES8311Full, ALC_LEVELS_DB, NOISE_GATE_DB };
