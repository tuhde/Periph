'use strict';

const _REG_PART_ID    = 0x00;
const _REG_SEQ_ID     = 0x02;
const _REG_INT_CFG    = 0x03;
const _REG_IRQ_ENABLE = 0x04;
const _REG_HW_KEY     = 0x07;
const _REG_MEAS_RATE0 = 0x08;
const _REG_PS_RATE    = 0x0A;
const _REG_PS_LED21   = 0x0F;
const _REG_PS_LED3    = 0x10;
const _REG_UCOEF0     = 0x13;
const _REG_PARAM_WR   = 0x17;
const _REG_COMMAND    = 0x18;
const _REG_RESPONSE   = 0x20;
const _REG_IRQ_STATUS = 0x21;
const _REG_DATA       = 0x22;
const _REG_PARAM_RD   = 0x2E;

const _HW_KEY = 0x17;

const _CMD_NOP         = 0x00;
const _CMD_RESET       = 0x01;
const _CMD_BUSADDR     = 0x02;
const _CMD_PS_FORCE    = 0x05;
const _CMD_ALS_FORCE   = 0x06;
const _CMD_PSALS_FORCE = 0x07;
const _CMD_PSALS_PAUSE = 0x0B;
const _CMD_PS_AUTO     = 0x0D;
const _CMD_ALS_AUTO    = 0x0E;
const _CMD_PSALS_AUTO  = 0x0F;
const _CMD_GET_CAL     = 0x12;
const _CMD_PARAM_QUERY = 0x80;
const _CMD_PARAM_SET   = 0xA0;

const _PARAM_I2C_ADDR       = 0x00;
const _PARAM_CHLIST         = 0x01;
const _PARAM_PSLED12_SELECT = 0x02;
const _PARAM_PSLED3_SELECT  = 0x03;
const _PARAM_PS_ENCODING    = 0x05;
const _PARAM_ALS_ENCODING   = 0x06;
const _PARAM_PS1_ADCMUX     = 0x07;
const _PARAM_PS_ADC_COUNTER = 0x0A;
const _PARAM_PS_ADC_GAIN    = 0x0B;
const _PARAM_PS_ADC_MISC    = 0x0C;
const _PARAM_AUX_ADCMUX     = 0x0F;
const _PARAM_ALS_VIS_ADC_COUNTER = 0x10;
const _PARAM_ALS_VIS_ADC_GAIN    = 0x11;
const _PARAM_ALS_VIS_ADC_MISC    = 0x12;
const _PARAM_ALS_IR_ADC_COUNTER  = 0x1D;
const _PARAM_ALS_IR_ADC_GAIN     = 0x1E;
const _PARAM_ALS_IR_ADC_MISC     = 0x1F;

const _SOURCE_MASK = 0x3D;
const _CMD_POLLS = 50;        // 1 ms each
const _MEASURE_POLLS = 1000;  // 1 ms each; a gain-7 ALS measurement takes ~200 ms

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _check(name, value, lo, hi) {
    if (!Number.isInteger(value) || value < lo || value > hi) {
        throw new RangeError(`${name} must be ${lo}..${hi}`);
    }
}

/**
 * Si1145 proximity / UV index / ambient light sensor (Silicon Labs) — minimal interface.
 *
 * One call runs a forced PS + ALS measurement and returns the proximity count, the UV index and
 * the visible/infrared counts. `init()` waits 25 ms, verifies PART_ID ∈ {0x45, 0x46, 0x47},
 * writes HW_KEY = 0x17, UCOEF0..3 = 7B 6B 01 00, CHLIST = 0xB1 (UV, ALS IR, ALS VIS, PS1),
 * PSLED12_SELECT = 0x01, PS_LED21 = 0x03 (LED1 at level 3 of 15) and IRQ_ENABLE = ALS_IE | PS1_IE
 * (INT stays undriven until `Si1145Full.enableInterrupt()`).
 *
 * Configuration is split between registers and the parameter RAM (PARAM_SET / PARAM_QUERY); every
 * command increments the RESPONSE counter. I²C address 0x60 (older silicon: 0x5A). The
 * constructor does not touch the bus.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class Si1145Minimal {
    constructor(connection) {
        this._conn = connection;
        this._partId = 0x45;
        this._seqId = 0;
        this._chlist = 0;
        this._irqEnable = 0;
    }

    /**
     * Verify the chip, unlock it and configure UV, ALS and PS1.
     * @returns {Promise<void>}
     * @throws {Error} If PART_ID is not 0x45/0x46/0x47 or a command fails.
     */
    async init() {
        await _sleep(25);
        const part = await this._readReg(_REG_PART_ID);
        if (part < 0x45 || part > 0x47) {
            throw new Error(`Unexpected Si1145 PART_ID 0x${part.toString(16)}, expected 0x45/0x46/0x47`);
        }
        this._partId = part;
        this._seqId = await this._readReg(_REG_SEQ_ID);
        await this._applyDefaults();
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, [value & 0xFF]);
    }

    async _applyDefaults() {
        await this._writeReg(_REG_HW_KEY, _HW_KEY);
        await this._conn.writeReg(_REG_UCOEF0, Si1145Minimal.UCOEF_DEFAULT);
        const chlist = Si1145Minimal.CH_UV | Si1145Minimal.CH_ALS_IR | Si1145Minimal.CH_ALS_VIS | Si1145Minimal.CH_PS1;
        await this._paramSet(_PARAM_CHLIST, chlist);
        this._chlist = chlist;
        await this._paramSet(_PARAM_PSLED12_SELECT, 0x01);
        await this._writeReg(_REG_PS_LED21, 0x03);
        this._irqEnable = Si1145Minimal.SOURCE_ALS | Si1145Minimal.SOURCE_PS1;
        await this._writeReg(_REG_IRQ_ENABLE, this._irqEnable);
    }

    async _counter() {
        let r = await this._readReg(_REG_RESPONSE);
        if (r & 0x80) {
            await this._writeReg(_REG_COMMAND, _CMD_NOP);
            r = await this._readReg(_REG_RESPONSE);
        }
        return r & 0x0F;
    }

    async _command(cmd) {
        const before = await this._counter();
        await this._writeReg(_REG_COMMAND, cmd);
        for (let i = 0; i < _CMD_POLLS; i++) {
            const r = await this._readReg(_REG_RESPONSE);
            if (r & 0x80) {
                await this._writeReg(_REG_COMMAND, _CMD_NOP);
                throw new Error(`Si1145 command 0x${cmd.toString(16)} error 0x${r.toString(16)}`);
            }
            if ((r & 0x0F) !== before) return;
            await _sleep(1);
        }
        throw new Error(`Si1145 command 0x${cmd.toString(16)} timeout`);
    }

    async _paramSet(offset, value) {
        await this._writeReg(_REG_PARAM_WR, value);
        await this._command(_CMD_PARAM_SET | (offset & 0x1F));
    }

    async _paramQuery(offset) {
        await this._command(_CMD_PARAM_QUERY | (offset & 0x1F));
        return this._readReg(_REG_PARAM_RD);
    }

    async _clearIrq() {
        const status = await this._readReg(_REG_IRQ_STATUS);
        if (status) await this._writeReg(_REG_IRQ_STATUS, status);
        return status;
    }

    async _measure(proximity, als) {
        const cmd = proximity && als ? _CMD_PSALS_FORCE : proximity ? _CMD_PS_FORCE : _CMD_ALS_FORCE;
        await this._clearIrq();
        await this._counter();
        await this._writeReg(_REG_COMMAND, cmd);
        let wait = 0;
        if (als && (this._chlist & 0xF0)) wait |= Si1145Minimal.SOURCE_ALS;
        if (proximity) wait |= (this._chlist & 0x07) << 2;
        wait &= this._irqEnable;
        if (wait === 0) {
            await _sleep(100);
            return;
        }
        let seen = 0;
        for (let i = 0; i < _MEASURE_POLLS; i++) {
            seen |= await this._clearIrq();
            if ((seen & wait) === wait) return;
            await _sleep(1);
        }
        throw new Error('Si1145 measurement timeout');
    }

    async _readData() {
        const b = await this._conn.readReg(_REG_DATA, 12);
        const v = [];
        for (let i = 0; i < 12; i += 2) v.push(b[i] | (b[i + 1] << 8));
        return v;
    }

    /**
     * Run one forced PS + ALS measurement (PSALS_FORCE).
     * @returns {Promise<{proximity: number, uv_index: number, visible: number, infrared: number}>}
     *   Raw PS1 count, UV index (AUX_DATA / 100) and raw visible/infrared counts.
     * @throws {Error} If a command fails or the measurement does not finish.
     */
    async read() {
        await this._measure(true, true);
        const v = await this._readData();
        return { proximity: v[2], uv_index: v[5] / 100, visible: v[0], infrared: v[1] };
    }
}

/** CHLIST.EN_PS1. */
Si1145Minimal.CH_PS1 = 0x01;
/** CHLIST.EN_PS2 (Si1146/47). */
Si1145Minimal.CH_PS2 = 0x02;
/** CHLIST.EN_PS3 (Si1147). */
Si1145Minimal.CH_PS3 = 0x04;
/** CHLIST.EN_ALS_VIS. */
Si1145Minimal.CH_ALS_VIS = 0x10;
/** CHLIST.EN_ALS_IR. */
Si1145Minimal.CH_ALS_IR = 0x20;
/** CHLIST.EN_AUX. */
Si1145Minimal.CH_AUX = 0x40;
/** CHLIST.EN_UV. */
Si1145Minimal.CH_UV = 0x80;
/** Interrupt source: ALS/UV measurement complete. */
Si1145Minimal.SOURCE_ALS = 0x01;
/** Interrupt source: PS1 complete. */
Si1145Minimal.SOURCE_PS1 = 0x04;
/** Interrupt source: PS2 complete (Si1146/47). */
Si1145Minimal.SOURCE_PS2 = 0x08;
/** Interrupt source: PS3 complete (Si1147). */
Si1145Minimal.SOURCE_PS3 = 0x10;
/** Interrupt source: command complete. */
Si1145Minimal.SOURCE_CMD = 0x20;
/** Default UV coefficients: AUX_DATA = UV index × 100. */
Si1145Minimal.UCOEF_DEFAULT = [0x7B, 0x6B, 0x01, 0x00];

/**
 * Si1145 full interface — extends Si1145Minimal with per-channel reads, channel list, LED and
 * photodiode selection, gains, ranges, alignment, UV coefficients, calibration, autonomous mode,
 * the I²C address, reset and the INT interrupt.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class Si1145Full extends Si1145Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    _ledCount() {
        return this._partId - 0x44;
    }

    /**
     * Read PART_ID, REV_ID and SEQ_ID.
     * @returns {Promise<{partId: number, revId: number, seqId: number}>} 0x45/0x46/0x47, hardware
     *   revision and sequencer firmware revision (0x01 swaps the MEAS_RATE bytes).
     */
    async readId() {
        const b = await this._conn.readReg(_REG_PART_ID, 3);
        return { partId: b[0], revId: b[1], seqId: b[2] };
    }

    /**
     * Forced PS measurement (PS_FORCE) and one proximity channel.
     * @param {number} [channel=1] - PS channel 1–3 (Si1145: 1); must be enabled in CHLIST.
     * @returns {Promise<number>} Raw proximity count (rises as an object approaches).
     * @throws {RangeError} If the channel is not supported or not enabled.
     */
    async readProximity(channel = 1) {
        _check('channel', channel, 1, this._ledCount());
        if (!(this._chlist & (1 << (channel - 1)))) throw new RangeError(`PS${channel} is not enabled (setChannels)`);
        await this._measure(true, false);
        return (await this._readData())[1 + channel];
    }

    /**
     * Forced ALS measurement and the UV index.
     * @returns {Promise<number>} AUX_DATA / 100.
     * @throws {RangeError} If UV is not enabled.
     */
    async readUvIndex() {
        if (!(this._chlist & Si1145Minimal.CH_UV)) throw new RangeError('UV is not enabled (setChannels)');
        await this._measure(false, true);
        return (await this._readData())[5] / 100;
    }

    /**
     * Forced ALS measurement and the raw visible count.
     * @returns {Promise<number>} ALS_VIS_DATA.
     * @throws {RangeError} If the visible channel is not enabled.
     */
    async readVisible() {
        if (!(this._chlist & Si1145Minimal.CH_ALS_VIS)) throw new RangeError('visible is not enabled (setChannels)');
        await this._measure(false, true);
        return (await this._readData())[0];
    }

    /**
     * Forced ALS measurement and the raw infrared count.
     * @returns {Promise<number>} ALS_IR_DATA.
     * @throws {RangeError} If the infrared channel is not enabled.
     */
    async readInfrared() {
        if (!(this._chlist & Si1145Minimal.CH_ALS_IR)) throw new RangeError('infrared is not enabled (setChannels)');
        await this._measure(false, true);
        return (await this._readData())[1];
    }

    /**
     * Read the latest data registers without measuring.
     * @returns {Promise<{visible: number, infrared: number, ps1: number, ps2: number, ps3: number, aux: number}>}
     *   Raw 16-bit values (`aux` is the UV index × 100 while UV is enabled).
     */
    async readRaw() {
        const [visible, infrared, ps1, ps2, ps3, aux] = await this._readData();
        return { visible, infrared, ps1, ps2, ps3, aux };
    }

    async _readAux(mux) {
        const saved = this._chlist;
        await this._paramSet(_PARAM_AUX_ADCMUX, mux);
        await this._paramSet(_PARAM_CHLIST, Si1145Minimal.CH_AUX);
        this._chlist = Si1145Minimal.CH_AUX;
        try {
            await this._measure(false, true);
            return (await this._readData())[5];
        } finally {
            await this._paramSet(_PARAM_CHLIST, saved);
            this._chlist = saved;
        }
    }

    /**
     * Die temperature on the auxiliary channel (AUX_ADCMUX 0x65); CHLIST is switched to EN_AUX
     * temporarily and restored.
     * @returns {Promise<number>} Raw relative temperature count.
     */
    async readTemperatureRaw() {
        return this._readAux(Si1145Full.AUX_TEMPERATURE);
    }

    /**
     * Supply voltage on the auxiliary channel (AUX_ADCMUX 0x75); CHLIST restored afterwards.
     * @returns {Promise<number>} Raw VDD count.
     */
    async readVddRaw() {
        return this._readAux(Si1145Full.AUX_VDD);
    }

    /**
     * Select the measured channels (CHLIST).
     * @param {boolean} uv - UV index (in AUX_DATA); excludes `aux`.
     * @param {boolean} visible - Visible ambient light.
     * @param {boolean} infrared - Infrared ambient light.
     * @param {boolean} ps1 - Proximity channel 1.
     * @param {boolean} [ps2=false] - Proximity channel 2 (Si1146/47).
     * @param {boolean} [ps3=false] - Proximity channel 3 (Si1147).
     * @param {boolean} [aux=false] - Auxiliary channel (AUX_ADCMUX).
     * @returns {Promise<void>}
     * @throws {RangeError} For uv together with aux or an unsupported PS channel.
     */
    async setChannels(uv, visible, infrared, ps1, ps2 = false, ps3 = false, aux = false) {
        if (uv && aux) throw new RangeError('uv and aux share AUX_DATA; enable only one');
        if ((ps2 && this._ledCount() < 2) || (ps3 && this._ledCount() < 3)) {
            throw new RangeError('PS channel not available on this part');
        }
        const mask = (uv ? 0x80 : 0) | (aux ? 0x40 : 0) | (infrared ? 0x20 : 0) | (visible ? 0x10 : 0) |
            (ps3 ? 0x04 : 0) | (ps2 ? 0x02 : 0) | (ps1 ? 0x01 : 0);
        await this._paramSet(_PARAM_CHLIST, mask);
        this._chlist = mask;
    }

    /**
     * Set an LED drive current (PS_LED21 / PS_LED3).
     * @param {number} led - LED 1–3 (Si1145: 1 only).
     * @param {number} level - Current level 0 (off) … 15 (maximum, ~359 mA).
     * @returns {Promise<void>}
     */
    async setLedCurrent(led, level) {
        _check('led', led, 1, this._ledCount());
        _check('level', level, 0, 15);
        if (led === 3) {
            await this._writeReg(_REG_PS_LED3, level);
            return;
        }
        const cur = await this._readReg(_REG_PS_LED21);
        await this._writeReg(_REG_PS_LED21, led === 1 ? (cur & 0xF0) | level : (cur & 0x0F) | (level << 4));
    }

    /**
     * Select the LEDs a PS channel drives (PSLED12_SELECT / PSLED3_SELECT).
     * @param {number} channel - PS channel 1–3.
     * @param {number} leds - LED mask (bit 0 LED1, bit 1 LED2, bit 2 LED3), only the part's LEDs.
     * @returns {Promise<void>}
     */
    async setPsLed(channel, leds) {
        _check('channel', channel, 1, 3);
        _check('leds', leds, 0, (1 << this._ledCount()) - 1);
        if (channel === 3) {
            await this._paramSet(_PARAM_PSLED3_SELECT, leds);
            return;
        }
        const cur = await this._paramQuery(_PARAM_PSLED12_SELECT);
        await this._paramSet(_PARAM_PSLED12_SELECT, channel === 1 ? (cur & 0x70) | leds : (cur & 0x07) | (leds << 4));
    }

    /**
     * Select the photodiode of a PS channel (PSn_ADCMUX).
     * @param {number} channel - PS channel 1–3.
     * @param {boolean} large - Large IR photodiode (0x03) or small (0x00).
     * @returns {Promise<void>}
     */
    async setPsPhotodiode(channel, large) {
        _check('channel', channel, 1, 3);
        await this._paramSet(_PARAM_PS1_ADCMUX + channel - 1, large ? 0x03 : 0x00);
    }

    /**
     * Set the ADC gains (integration time × 2^n) and the recommended recovery counts (one's
     * complement of the gain). Do not combine a high PS gain with a high LED current.
     * @param {number} [visible=0] - ALS_VIS_ADC_GAIN 0–7.
     * @param {number} [infrared=0] - ALS_IR_ADC_GAIN 0–7.
     * @param {number} [proximity=0] - PS_ADC_GAIN 0–7.
     * @returns {Promise<void>}
     */
    async setGain(visible = 0, infrared = 0, proximity = 0) {
        _check('visible', visible, 0, 7);
        _check('infrared', infrared, 0, 7);
        _check('proximity', proximity, 0, 7);
        const sets = [[visible, _PARAM_ALS_VIS_ADC_GAIN, _PARAM_ALS_VIS_ADC_COUNTER],
            [infrared, _PARAM_ALS_IR_ADC_GAIN, _PARAM_ALS_IR_ADC_COUNTER],
            [proximity, _PARAM_PS_ADC_GAIN, _PARAM_PS_ADC_COUNTER]];
        for (const [gain, gOff, cOff] of sets) {
            await this._paramSet(gOff, gain);
            await this._paramSet(cOff, ((~gain) & 0x07) << 4);
        }
    }

    /**
     * Select the high signal range (sensitivity ÷ 14.5) per measurement.
     * @param {boolean} visible - VIS_RANGE.
     * @param {boolean} infrared - IR_RANGE.
     * @param {boolean} proximity - PS_RANGE (PS_ADC_MODE stays 1).
     * @returns {Promise<void>}
     */
    async setHighRange(visible, infrared, proximity) {
        await this._paramSet(_PARAM_ALS_VIS_ADC_MISC, visible ? 0x20 : 0);
        await this._paramSet(_PARAM_ALS_IR_ADC_MISC, infrared ? 0x20 : 0);
        await this._paramSet(_PARAM_PS_ADC_MISC, proximity ? 0x24 : 0x04);
    }

    /**
     * Report the 16 LSBs instead of the 16 MSBs of the ADC result.
     * @param {{visible?: boolean, infrared?: boolean, ps1?: boolean, ps2?: boolean, ps3?: boolean}} [align={}]
     * @returns {Promise<void>}
     */
    async setAlignment({ visible = false, infrared = false, ps1 = false, ps2 = false, ps3 = false } = {}) {
        await this._paramSet(_PARAM_ALS_ENCODING, (infrared ? 0x20 : 0) | (visible ? 0x10 : 0));
        await this._paramSet(_PARAM_PS_ENCODING, (ps3 ? 0x40 : 0) | (ps2 ? 0x20 : 0) | (ps1 ? 0x10 : 0));
    }

    /**
     * Write UCOEF0..3.
     * @param {number[]} ucoef - Four bytes; `UCOEF_DEFAULT` gives the UV index × 100.
     * @returns {Promise<void>}
     */
    async setUvCoefficients(ucoef) {
        if (!Array.isArray(ucoef) || ucoef.length !== 4) throw new RangeError('ucoef must have four entries');
        ucoef.forEach((c) => _check('ucoef', c, 0, 0xFF));
        await this._conn.writeReg(_REG_UCOEF0, ucoef);
    }

    /**
     * Factory calibration (GET_CAL); overwrites the data registers.
     * @returns {Promise<number[]>} 12 calibration bytes.
     */
    async readCalibration() {
        await this._command(_CMD_GET_CAL);
        return Array.from(await this._conn.readReg(_REG_DATA, 12));
    }

    /**
     * Start autonomous measurements: MEAS_RATE = round(interval / 31.25 µs) (bytes swapped when
     * SEQ_ID = 0x01), then PS_AUTO / ALS_AUTO / PSALS_AUTO.
     * @param {number} interval - Interval in seconds (31.25 µs … 2.048 s).
     * @param {boolean} [proximity=true] - Run the PS channels.
     * @param {boolean} [als=true] - Run the ALS/UV channels.
     * @returns {Promise<void>}
     */
    async startAutonomous(interval, proximity = true, als = true) {
        const rate = Math.round(interval / 31.25e-6);
        _check('rate ticks', rate, 1, 0xFFFF);
        if (!(proximity || als)) throw new RangeError('select proximity and/or als');
        if (this._seqId === 0x01) {
            await this._writeReg(_REG_PS_RATE, rate & 0xFF);
            await this._writeReg(_REG_MEAS_RATE0, rate >> 8);
        } else {
            await this._conn.writeReg(_REG_MEAS_RATE0, [rate & 0xFF, rate >> 8]);
        }
        await this._command(proximity && als ? _CMD_PSALS_AUTO : proximity ? _CMD_PS_AUTO : _CMD_ALS_AUTO);
    }

    /** Pause the autonomous loop (PSALS_PAUSE). @returns {Promise<void>} */
    async pause() {
        await this._command(_CMD_PSALS_PAUSE);
    }

    /**
     * Start a forced measurement without waiting (PS/ALS/PSALS_FORCE).
     * @param {boolean} [proximity=true] - Measure the PS channels.
     * @param {boolean} [als=true] - Measure the ALS/UV channels.
     * @returns {Promise<void>}
     */
    async force(proximity = true, als = true) {
        if (!(proximity || als)) throw new RangeError('select proximity and/or als');
        await this._command(proximity && als ? _CMD_PSALS_FORCE : proximity ? _CMD_PS_FORCE : _CMD_ALS_FORCE);
    }

    /**
     * Decode RESPONSE.
     * @returns {Promise<{counter: number, error: number}>} 4-bit command counter and the error
     *   code (0 = none, 0x80 invalid setting, 0x88–0x8E ADC overflow).
     */
    async readResponse() {
        const r = await this._readReg(_REG_RESPONSE);
        return r & 0x80 ? { counter: 0, error: r } : { counter: r & 0x0F, error: 0 };
    }

    /**
     * Change the I²C address (I2C_ADDR, then BUSADDR; volatile). The chip answers on the new
     * address at once; create a new connection afterwards.
     * @param {number} address - New 7-bit address 0x08–0x77.
     * @returns {Promise<void>}
     */
    async setI2cAddress(address) {
        _check('address', address, 0x08, 0x77);
        await this._paramSet(_PARAM_I2C_ADDR, address);
        await this._writeReg(_REG_COMMAND, _CMD_BUSADDR);
    }

    /** Software reset, 10 ms wait, HW_KEY and the Minimal defaults again. @returns {Promise<void>} */
    async reset() {
        await this._writeReg(_REG_COMMAND, _CMD_RESET);
        await _sleep(10);
        await this._applyDefaults();
    }

    /**
     * Drive INT for sources (INT_CFG.INT_OE and IRQ_ENABLE).
     * @param {number} source - SOURCE_* mask.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        this._irqEnable = (this._irqEnable | source) & _SOURCE_MASK;
        await this._writeReg(_REG_INT_CFG, 0x01);
        await this._writeReg(_REG_IRQ_ENABLE, this._irqEnable);
    }

    /**
     * Clear sources from IRQ_ENABLE. Forced reads only wait for enabled sources (none: a fixed
     * 100 ms wait).
     * @param {number} source - SOURCE_* mask.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        this._irqEnable &= ~source & _SOURCE_MASK;
        await this._writeReg(_REG_IRQ_ENABLE, this._irqEnable);
    }

    /**
     * Read IRQ_STATUS and write it back to clear it (releases INT).
     * @returns {Promise<number>} Pending SOURCE_* mask among the enabled sources, 0 = none.
     */
    async pollInterrupt() {
        return (await this._clearIrq()) & this._irqEnable;
    }

    /**
     * Subscribe to measurement events: INT falling edge via `intPin` (or the connection's pin),
     * otherwise a 10 ms polling timer on IRQ_STATUS.
     * @param {function(number): void} callback - Called with the SOURCE_* mask.
     * @param {object|null} [intPin=null] - Optional InputPin wired to INT.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin = null) {
        await this.offInterrupt();
        this._callback = callback;
        const ipin = intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = ipin;
        if (ipin) {
            await ipin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 10);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        } catch (e) {
            /* a failed poll is retried on the next edge / tick */
        } finally {
            this._polling = false;
        }
    }
}

/** AUX_ADCMUX: die temperature. */
Si1145Full.AUX_TEMPERATURE = 0x65;
/** AUX_ADCMUX: supply voltage. */
Si1145Full.AUX_VDD = 0x75;

module.exports = { Si1145Minimal, Si1145Full };
