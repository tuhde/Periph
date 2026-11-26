'use strict';

const _REG_PART_ID    = 0x00;
const _REG_HOSTIN0    = 0x0A;
const _REG_COMMAND    = 0x0B;
const _REG_IRQ_ENABLE = 0x0F;
const _REG_RESPONSE1  = 0x10;
const _REG_RESPONSE0  = 0x11;
const _REG_IRQ_STATUS = 0x12;
const _REG_HOSTOUT0   = 0x13;

const _PART_ID = 0x33;

const _CMD_RESET_CMD_CTR = 0x00;
const _CMD_RESET_SW      = 0x01;
const _CMD_FORCE         = 0x11;
const _CMD_PAUSE         = 0x12;
const _CMD_START         = 0x13;
const _CMD_PARAM_QUERY   = 0x40;
const _CMD_PARAM_SET     = 0x80;

const _PARAM_I2C_ADDR     = 0x00;
const _PARAM_CHAN_LIST    = 0x01;
const _PARAM_ADCCONFIG0   = 0x02;
const _PARAM_ADCSENS0     = 0x03;
const _PARAM_ADCPOST0     = 0x04;
const _PARAM_MEASCONFIG0  = 0x05;
const _PARAM_MEASRATE_H   = 0x1A;
const _PARAM_MEASRATE_L   = 0x1B;
const _PARAM_MEASCOUNT0   = 0x1C;
const _PARAM_THRESHOLD0_H = 0x25;
const _PARAM_BURST        = 0x2B;

const _CMD_POLLS     = 50;     // 1 ms each
const _MEASURE_POLLS = 15000;  // 1 ms each; covers HW_GAIN 11 with SW_GAIN 128
const _SCRATCH = 5;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _check(name, value, lo, hi) {
    if (!Number.isInteger(value) || value < lo || value > hi) {
        throw new RangeError(`${name} must be ${lo}..${hi}`);
    }
}

/**
 * Si1133 UV index and ambient light sensor (Silicon Labs) — minimal interface.
 *
 * One call runs a forced measurement and returns the UV and white-light counts. `init()` waits
 * 25 ms, verifies PART_ID = 0x33, sends RESET_SW and configures channel 0 = UV (ADCMUX 0x18,
 * HW_GAIN 9, 24-bit output), channel 1 = white (ADCMUX 0x0B, HW_GAIN 7, 24-bit), CHAN_LIST = 0x03
 * and IRQ_ENABLE = 0x3F (so IRQ_STATUS flags finished channels).
 *
 * All configuration goes through the indirect parameter table (PARAM_SET / PARAM_QUERY); every
 * command is confirmed by the RESPONSE0 counter. Results are raw ADC counts — the datasheet gives
 * no lux or UV-index formula. INT is open-drain and must be high at power-up. I²C address 0x55
 * (AD pulled up) or 0x52 (AD pulled down). The constructor does not touch the bus.
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class Si1133Minimal {
    constructor(connection) {
        this._conn = connection;
        this._chanList = 0;
        this._bits24 = [false, false, false, false, false, false];
        this._irqEnable = 0;
    }

    /**
     * Verify the chip, reset it and configure the UV and white channels.
     * @returns {Promise<void>}
     * @throws {Error} If PART_ID is not 0x33 or a command fails.
     */
    async init() {
        await _sleep(25);
        const part = await this._readReg(_REG_PART_ID);
        if (part !== _PART_ID) {
            throw new Error(`Unexpected Si1133 PART_ID 0x${part.toString(16)}, expected 0x33`);
        }
        await this._softwareReset();
        await this._applyDefaults();
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, [value & 0xFF]);
    }

    async _softwareReset() {
        await this._writeReg(_REG_COMMAND, _CMD_RESET_SW);
        await _sleep(10);
        this._chanList = 0;
        this._bits24 = [false, false, false, false, false, false];
        this._irqEnable = 0;
        for (let i = 0; i < _CMD_POLLS; i++) {
            if ((await this._readReg(_REG_RESPONSE0)) & Si1133Minimal.RESP_SLEEP) return;
            await _sleep(1);
        }
        throw new Error('Si1133 reset timeout');
    }

    async _applyDefaults() {
        await this._setChannel(0, Si1133Minimal.MUX_UV, 9, 0, 0, false, true, 0);
        await this._setChannel(1, Si1133Minimal.MUX_WHITE, 7, 0, 0, false, true, 0);
        await this._paramSet(_PARAM_CHAN_LIST, 0x03);
        this._chanList = 0x03;
        this._irqEnable = 0x3F;
        await this._writeReg(_REG_IRQ_ENABLE, this._irqEnable);
    }

    async _counter() {
        let r = await this._readReg(_REG_RESPONSE0);
        if (r & Si1133Minimal.RESP_CMD_ERR) {
            await this._writeReg(_REG_COMMAND, _CMD_RESET_CMD_CTR);
            r = await this._readReg(_REG_RESPONSE0);
        }
        return r & 0x0F;
    }

    async _waitCounter(before) {
        for (let i = 0; i < _CMD_POLLS; i++) {
            const r = await this._readReg(_REG_RESPONSE0);
            if (r & Si1133Minimal.RESP_CMD_ERR) {
                await this._writeReg(_REG_COMMAND, _CMD_RESET_CMD_CTR);
                throw new Error(`Si1133 command error 0x${(r & 0x1F).toString(16).padStart(2, '0')}`);
            }
            if ((r & 0x0F) !== before) return;
            await _sleep(1);
        }
        throw new Error('Si1133 command timeout');
    }

    async _command(cmd) {
        const before = await this._counter();
        await this._writeReg(_REG_COMMAND, cmd);
        await this._waitCounter(before);
    }

    async _paramSet(address, value) {
        const before = await this._counter();
        await this._conn.writeReg(_REG_HOSTIN0, [value & 0xFF, _CMD_PARAM_SET | (address & 0x3F)]);
        await this._waitCounter(before);
    }

    async _paramQuery(address) {
        await this._command(_CMD_PARAM_QUERY | (address & 0x3F));
        return this._readReg(_REG_RESPONSE1);
    }

    async _setChannel(channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift) {
        const base = 4 * channel;
        await this._paramSet(_PARAM_ADCCONFIG0 + base, (decimRate << 5) | adcmux);
        await this._paramSet(_PARAM_ADCSENS0 + base, (highSignal ? 0x80 : 0) | (swGain << 4) | hwGain);
        await this._paramSet(_PARAM_ADCPOST0 + base, (bits24 ? 0x40 : 0) | (postshift << 3));
        this._bits24[channel] = !!bits24;
    }

    async _readOutputs() {
        let n = 0;
        for (let ch = 0; ch < 6; ch++) {
            if (this._chanList & (1 << ch)) n += this._bits24[ch] ? 3 : 2;
        }
        if (n === 0) return [];
        const d = await this._conn.readReg(_REG_HOSTOUT0, n);
        const values = [];
        let pos = 0;
        for (let ch = 0; ch < 6; ch++) {
            if (!(this._chanList & (1 << ch))) continue;
            if (this._bits24[ch]) {
                let v = (d[pos] << 16) | (d[pos + 1] << 8) | d[pos + 2];
                if (v & 0x800000) v -= 0x1000000;
                values.push(v);
                pos += 3;
            } else {
                values.push((d[pos] << 8) | d[pos + 1]);
                pos += 2;
            }
        }
        return values;
    }

    async _measure() {
        await this._readReg(_REG_IRQ_STATUS);   // clear stale flags
        await this._command(_CMD_FORCE);
        const wait = this._chanList & this._irqEnable;
        if (wait === 0) {
            await _sleep(100);
        } else {
            let seen = 0;
            let i = 0;
            for (;;) {
                seen |= await this._readReg(_REG_IRQ_STATUS);
                if ((seen & wait) === wait) break;
                if (++i >= _MEASURE_POLLS) throw new Error('Si1133 measurement timeout');
                await _sleep(1);
            }
        }
        return this._readOutputs();
    }

    /**
     * Run one forced measurement and return the UV and white counts.
     * @returns {Promise<{uv: number, white: number}>} Signed 24-bit raw counts of channels 0 and 1
     *   (near zero or slightly negative in the dark).
     * @throws {Error} If channel 0 or 1 is disabled, a command fails or the measurement does not finish.
     */
    async read() {
        if ((this._chanList & 0x03) !== 0x03) throw new Error('Si1133 channels 0 and 1 must be enabled for read()');
        const v = await this._measure();
        return { uv: v[0], white: v[1] };
    }
}

/** RESPONSE0: sequencer running. */
Si1133Minimal.RESP_RUNNING = 0x80;
/** RESPONSE0: suspended. */
Si1133Minimal.RESP_SUSPEND = 0x40;
/** RESPONSE0: sleeping (idle). */
Si1133Minimal.RESP_SLEEP = 0x20;
/** RESPONSE0: command error. */
Si1133Minimal.RESP_CMD_ERR = 0x10;
/** ADCMUX: small IR photodiode. */
Si1133Minimal.MUX_SMALL_IR = 0x00;
/** ADCMUX: medium IR photodiode. */
Si1133Minimal.MUX_MEDIUM_IR = 0x01;
/** ADCMUX: large IR photodiode. */
Si1133Minimal.MUX_LARGE_IR = 0x02;
/** ADCMUX: white photodiode. */
Si1133Minimal.MUX_WHITE = 0x0B;
/** ADCMUX: large white photodiode. */
Si1133Minimal.MUX_LARGE_WHITE = 0x0D;
/** ADCMUX: UV photodiode. */
Si1133Minimal.MUX_UV = 0x18;
/** ADCMUX: deep UV photodiode. */
Si1133Minimal.MUX_UV_DEEP = 0x19;

/**
 * Si1133 full interface — extends Si1133Minimal with per-channel configuration,
 * single-photodiode reads, autonomous and burst modes, thresholds, direct parameter access, the
 * I²C address and the INT interrupt (active low, open-drain, released when IRQ_STATUS is read).
 *
 * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection.
 */
class Si1133Full extends Si1133Minimal {
    constructor(connection) {
        super(connection);
        this._counterIndex = [0, 0, 0, 0, 0, 0];
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    /**
     * Read PART_ID, HW_ID and REV_ID.
     * @returns {Promise<{partId: number, hwId: number, revId: number}>} 0x33, implementation /
     *   silicon revision code and product revision.
     */
    async readId() {
        const b = await this._conn.readReg(_REG_PART_ID, 3);
        return { partId: b[0], hwId: b[1], revId: b[2] };
    }

    /**
     * Configure one measurement channel (ADCCONFIG, ADCSENS, ADCPOST); clears its threshold select.
     * @param {number} channel - Channel 0–5.
     * @param {number} adcmux - Photodiode (MUX_* constant).
     * @param {object} [options]
     * @param {number} [options.hwGain=0] - Integration time code 0–11 (48.8 µs × 2^n at decimation 0).
     * @param {number} [options.swGain=0] - Accumulate 2^n samples, n = 0–7.
     * @param {number} [options.decimRate=0] - Decimation rate 0–3.
     * @param {boolean} [options.highSignal=false] - Divide the sensitivity by 14.5 (direct sunlight).
     * @param {boolean} [options.bits24=true] - 24-bit signed output, otherwise 16-bit unsigned.
     * @param {number} [options.postshift=0] - Right shift 0–7 of the accumulated result.
     * @returns {Promise<void>}
     * @throws {RangeError} If a value is out of range.
     */
    async configureChannel(channel, adcmux, { hwGain = 0, swGain = 0, decimRate = 0, highSignal = false,
        bits24 = true, postshift = 0 } = {}) {
        _check('channel', channel, 0, 5);
        _check('adcmux', adcmux, 0, 0x1F);
        _check('hwGain', hwGain, 0, 11);
        _check('swGain', swGain, 0, 7);
        _check('decimRate', decimRate, 0, 3);
        _check('postshift', postshift, 0, 7);
        await this._setChannel(channel, adcmux, hwGain, swGain, decimRate, highSignal, bits24, postshift);
    }

    /**
     * Select the channels that run (CHAN_LIST).
     * @param {number} mask - Bit c enables channel c (0x00–0x3F).
     * @returns {Promise<void>}
     */
    async enableChannels(mask) {
        _check('mask', mask, 0, 0x3F);
        await this._paramSet(_PARAM_CHAN_LIST, mask);
        this._chanList = mask;
    }

    /**
     * Read the results of all enabled channels in channel order.
     * @param {boolean} [force=true] - Run a forced measurement first, otherwise only decode HOSTOUT.
     * @returns {Promise<number[]>} One value per enabled channel (24-bit signed or 16-bit unsigned).
     */
    async readChannels(force = true) {
        return force ? this._measure() : this._readOutputs();
    }

    async _scratchRead(adcmux, hwGain) {
        const saved = this._chanList;
        await this._setChannel(_SCRATCH, adcmux, hwGain, 0, 0, false, true, 0);
        if (this._counterIndex[_SCRATCH]) {
            await this._paramSet(_PARAM_MEASCONFIG0 + 4 * _SCRATCH, 0);
            this._counterIndex[_SCRATCH] = 0;
        }
        await this._paramSet(_PARAM_CHAN_LIST, 1 << _SCRATCH);
        this._chanList = 1 << _SCRATCH;
        try {
            return (await this._measure())[0];
        } finally {
            await this._paramSet(_PARAM_CHAN_LIST, saved);
            this._chanList = saved;
        }
    }

    /**
     * Measure the UV photodiode once (channel 5 used as scratch, HW_GAIN 9).
     * @returns {Promise<number>} Signed 24-bit raw count.
     */
    async readUv() {
        return this._scratchRead(Si1133Minimal.MUX_UV, 9);
    }

    /**
     * Measure the white photodiode once (channel 5 used as scratch, HW_GAIN 7).
     * @returns {Promise<number>} Signed 24-bit raw count.
     */
    async readWhite() {
        return this._scratchRead(Si1133Minimal.MUX_WHITE, 7);
    }

    /**
     * Measure an IR photodiode once (channel 5 used as scratch, HW_GAIN 7).
     * @param {number} [photodiode=MUX_SMALL_IR] - MUX_SMALL_IR, MUX_MEDIUM_IR or MUX_LARGE_IR.
     * @returns {Promise<number>} Signed 24-bit raw count.
     */
    async readIr(photodiode = Si1133Minimal.MUX_SMALL_IR) {
        _check('photodiode', photodiode, 0, 2);
        return this._scratchRead(photodiode, 7);
    }

    /** Send FORCE without waiting for the results. @returns {Promise<void>} */
    async force() {
        await this._command(_CMD_FORCE);
    }

    /**
     * Assign a rate counter to a channel (MEASCONFIG.COUNTER_INDEX).
     * @param {number} channel - Channel 0–5.
     * @param {number} index - 0 = none (forced only), 1–3 = MEASCOUNT0..2.
     * @returns {Promise<void>}
     */
    async setChannelCounter(channel, index) {
        _check('channel', channel, 0, 5);
        _check('index', index, 0, 3);
        await this._paramSet(_PARAM_MEASCONFIG0 + 4 * channel, index << 6);
        this._counterIndex[channel] = index;
    }

    /**
     * Start autonomous measurements: MEASRATE (800 µs units), MEASCOUNT0..2, counter 1 for every
     * enabled channel without one, then START. Channel period = rate × MEASCOUNTk.
     * @param {number} rate - Main period in seconds (800 µs … 52.4 s).
     * @param {number[]} [counters=[1, 1, 1]] - MEASCOUNT0..2, 1–255 each.
     * @returns {Promise<void>}
     */
    async startAutonomous(rate, counters = [1, 1, 1]) {
        const ticks = Math.round(rate / 800e-6);
        _check('rate ticks', ticks, 1, 0xFFFF);
        if (!Array.isArray(counters) || counters.length !== 3) throw new RangeError('counters must have three entries');
        counters.forEach((c) => _check('counter', c, 1, 0xFF));
        await this._paramSet(_PARAM_MEASRATE_H, ticks >> 8);
        await this._paramSet(_PARAM_MEASRATE_L, ticks & 0xFF);
        for (let i = 0; i < 3; i++) await this._paramSet(_PARAM_MEASCOUNT0 + i, counters[i]);
        for (let ch = 0; ch < 6; ch++) {
            if ((this._chanList & (1 << ch)) && !this._counterIndex[ch]) await this.setChannelCounter(ch, 1);
        }
        await this._command(_CMD_START);
    }

    /** Pause autonomous measurements (PAUSE). @returns {Promise<void>} */
    async pause() {
        await this._command(_CMD_PAUSE);
    }

    /**
     * Configure burst mode (BURST).
     * @param {number} count - Readings 1–127; 0 disables burst mode.
     * @returns {Promise<void>}
     */
    async setBurst(count) {
        _check('count', count, 0, 127);
        await this._paramSet(_PARAM_BURST, count ? (0x80 | count) : 0);
    }

    /**
     * Set one of the three 16-bit thresholds.
     * @param {number} index - Threshold 0–2.
     * @param {number} value - Threshold count 0–65535.
     * @returns {Promise<void>}
     */
    async setThreshold(index, value) {
        _check('index', index, 0, 2);
        _check('value', value, 0, 0xFFFF);
        await this._paramSet(_PARAM_THRESHOLD0_H + 2 * index, value >> 8);
        await this._paramSet(_PARAM_THRESHOLD0_H + 2 * index + 1, value & 0xFF);
    }

    /**
     * Select the threshold a channel's interrupt compares against (ADCPOST.THRESH_SEL).
     * @param {number} channel - Channel 0–5.
     * @param {number} index - 0 = off, 1–3 = THRESHOLD0..2.
     * @returns {Promise<void>}
     */
    async setChannelThreshold(channel, index) {
        _check('channel', channel, 0, 5);
        _check('index', index, 0, 3);
        const address = _PARAM_ADCPOST0 + 4 * channel;
        const v = await this._paramQuery(address);
        await this._paramSet(address, (v & 0xFC) | index);
    }

    /**
     * Read a parameter-table byte (PARAM_QUERY).
     * @param {number} address - Parameter address 0x00–0x3F.
     * @returns {Promise<number>} Parameter value.
     */
    async readParameter(address) {
        _check('address', address, 0, 0x3F);
        return this._paramQuery(address);
    }

    /**
     * Write a parameter-table byte (PARAM_SET). Writing channel parameters directly bypasses the
     * driver's record of the channel layout; prefer configureChannel().
     * @param {number} address - Parameter address 0x00–0x3F.
     * @param {number} value - Byte value.
     * @returns {Promise<void>}
     */
    async writeParameter(address, value) {
        _check('address', address, 0, 0x3F);
        _check('value', value, 0, 0xFF);
        await this._paramSet(address, value);
    }

    /**
     * Decode RESPONSE0.
     * @returns {Promise<{state: number, error: boolean, counter: number}>} State bits (RESP_RUNNING,
     *   RESP_SUSPEND, RESP_SLEEP), the CMD_ERR flag and the 4-bit counter (error code when error).
     */
    async readResponse() {
        const r = await this._readReg(_REG_RESPONSE0);
        return { state: r & 0xE0, error: !!(r & Si1133Minimal.RESP_CMD_ERR), counter: r & 0x0F };
    }

    /**
     * Read IRQ_STATUS (clears it).
     * @returns {Promise<number>} Bit c set when channel c has a new result.
     */
    async dataReady() {
        return (await this._readReg(_REG_IRQ_STATUS)) & 0x3F;
    }

    /**
     * Change the I²C address (volatile). The chip answers on the new address at once, so no
     * completion check is made; open a new connection afterwards.
     * @param {number} address - New 7-bit address 0x08–0x77.
     * @returns {Promise<void>}
     */
    async setI2cAddress(address) {
        _check('address', address, 0x08, 0x77);
        await this._readReg(_REG_RESPONSE0);
        await this._conn.writeReg(_REG_HOSTIN0, [address, _CMD_PARAM_SET | _PARAM_I2C_ADDR]);
    }

    /** Software reset (RESET_SW), wait for SLEEP, re-apply the Minimal defaults. @returns {Promise<void>} */
    async reset() {
        await this._softwareReset();
        this._counterIndex = [0, 0, 0, 0, 0, 0];
        await this._applyDefaults();
    }

    /**
     * Enable INT for channels (IRQ_ENABLE).
     * @param {number} source - SOURCE_CHANNEL* mask.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        this._irqEnable = (this._irqEnable | source) & 0x3F;
        await this._writeReg(_REG_IRQ_ENABLE, this._irqEnable);
    }

    /**
     * Disable INT for channels. Forced reads only wait for channels whose interrupt is enabled
     * (none: a fixed 100 ms wait).
     * @param {number} source - SOURCE_CHANNEL* mask.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        this._irqEnable &= ~source & 0x3F;
        await this._writeReg(_REG_IRQ_ENABLE, this._irqEnable);
    }

    /**
     * Read IRQ_STATUS (clears it and releases INT).
     * @returns {Promise<number>} Channel mask, 0 = nothing pending.
     */
    async pollInterrupt() {
        return this.dataReady();
    }

    /**
     * Subscribe to channel-ready events. Uses `intPin` (or the connection's `intPin`) on its
     * falling edge, otherwise a 10 ms polling timer on IRQ_STATUS.
     * @param {function(number): void} callback - Called with the IRQ_STATUS channel mask.
     * @param {object|null} [intPin=null] - Optional InputPin wired to INT.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin = null) {
        this._callback = callback;
        const ipin = intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = ipin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
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

/** Interrupt source: channel 0 result ready. */
Si1133Full.SOURCE_CHANNEL0 = 0x01;
/** Interrupt source: channel 1 result ready. */
Si1133Full.SOURCE_CHANNEL1 = 0x02;
/** Interrupt source: channel 2 result ready. */
Si1133Full.SOURCE_CHANNEL2 = 0x04;
/** Interrupt source: channel 3 result ready. */
Si1133Full.SOURCE_CHANNEL3 = 0x08;
/** Interrupt source: channel 4 result ready. */
Si1133Full.SOURCE_CHANNEL4 = 0x10;
/** Interrupt source: channel 5 result ready. */
Si1133Full.SOURCE_CHANNEL5 = 0x20;

module.exports = { Si1133Minimal, Si1133Full };
