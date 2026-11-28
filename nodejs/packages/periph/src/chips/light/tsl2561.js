'use strict';

const CMD = 0x80;
const CMD_CLEAR = 0xC0;
const CMD_WORD = 0xA0;
const CMD_DATA_BLOCK = 0x9C;

const REG_CONTROL = 0x0;
const REG_TIMING = 0x1;
const REG_THRESH_LOW = 0x2;
const REG_THRESH_HIGH = 0x4;
const REG_INTERRUPT = 0x6;
const REG_ID = 0xA;

const POWER_ON = 0x03;
const INTEG_MANUAL = 0x03;

// Per INTEG code 0..2: nominal ms, scale, full-scale count, settle wait (one integration + ±6 % + margin).
const INTEG = [
    { ms: 14, scale: 0.034, fullScale: 5047, settleMs: 20 },
    { ms: 101, scale: 0.252, fullScale: 37177, settleMs: 120 },
    { ms: 402, scale: 1.0, fullScale: 65535, settleMs: 450 },
];
const INTEG_BY_MS = { 14: 0, 101: 1, 402: 2 };

// (gain, INTEG) from most to least sensitive, used by autoRange().
const RANGES = [[16, 2], [1, 2], [1, 1], [1, 0]];
const AUTO_MIN_COUNTS = 100;

// Segments [ratio limit, a, b]: first a*ch0n - b*ch0n*r^1.4, others a*ch0n - b*ch1n; r > 1.30 gives 0.
const COEFF_TFNCL = [[0.50, 0.0304, 0.062], [0.61, 0.0224, 0.031], [0.80, 0.0128, 0.0153], [1.30, 0.00146, 0.00112]];
const COEFF_CS = [[0.52, 0.0315, 0.0593], [0.65, 0.0229, 0.0291], [0.80, 0.0157, 0.0180], [1.30, 0.00338, 0.00260]];
const PACKAGES = { T: COEFF_TFNCL, FN: COEFF_TFNCL, CL: COEFF_TFNCL, CS: COEFF_CS };

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Convert raw channel counts into lux with the datasheet's empirical formula.
 * @param {number} ch0 - Channel 0 count (visible + infrared).
 * @param {number} ch1 - Channel 1 count (infrared).
 * @param {number} [gain=1] - 1 or 16.
 * @param {number} [integrationMs=402] - 14, 101 or 402 (any other value is treated as 402 ms).
 * @param {string} [pkg='T'] - "T", "FN", "CL" or "CS".
 * @returns {number} Illuminance in lux; 0 for ch0 = 0 or a ratio above 1.30.
 */
function calculateLux(ch0, ch1, gain = 1, integrationMs = 402, pkg = 'T') {
    const code = INTEG_BY_MS[integrationMs] ?? 2;
    const factor = (16 / gain) / INTEG[code].scale;
    const ch0n = ch0 * factor;
    const ch1n = ch1 * factor;
    if (ch0n <= 0) return 0;
    const r = ch1n / ch0n;
    const c = PACKAGES[pkg];
    let lux;
    if (r <= c[0][0]) lux = c[0][1] * ch0n - c[0][2] * ch0n * (r ** 1.4);
    else if (r <= c[1][0]) lux = c[1][1] * ch0n - c[1][2] * ch1n;
    else if (r <= c[2][0]) lux = c[2][1] * ch0n - c[2][2] * ch1n;
    else if (r <= c[3][0]) lux = c[3][1] * ch0n - c[3][2] * ch1n;
    else lux = 0;
    return Math.max(0, lux);
}

async function _wait(ms) {
    const scaled = ms * TSL2561Minimal.waitFactor;
    if (scaled > 0) await sleep(scaled);
}

/**
 * TSL2561 two-channel ambient light sensor (TAOS / ams) — minimal interface.
 *
 * Channel 0 responds to visible and infrared light, channel 1 mainly to
 * infrared; the two 16-bit integrating ADC counts are combined with the
 * datasheet's empirical formula into lux. The I²C address is 0x29 (ADDR SEL =
 * GND), 0x39 (floating) or 0x49 (VDD). Every register access starts with a
 * command byte (`0x80 | addr` byte, `0xA0 | addr` little-endian word); both
 * channels are read in one 4-byte block read (`0x9C`) so the pair belongs to
 * one integration.
 */
class TSL2561Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (1-byte register address).
     */
    constructor(connection) {
        this._conn = connection;
        this._gain = 1;
        this._integ = 2;
        this._package = 'T';
    }

    /** 7-bit I²C address with ADDR SEL floating (default of most breakout boards). */
    static get I2C_ADDRESS() { return 0x39; }

    /** 7-bit I²C address with ADDR SEL = GND. */
    static get I2C_ADDRESS_GND() { return 0x29; }

    /** 7-bit I²C address with ADDR SEL = VDD. */
    static get I2C_ADDRESS_VDD() { return 0x49; }

    async _readByte(reg) {
        return (await this._conn.readReg(CMD | reg, 1))[0];
    }

    async _writeByte(reg, value) {
        await this._conn.writeReg(CMD | reg, Buffer.from([value & 0xFF]));
    }

    async _readWord(reg) {
        const b = await this._conn.readReg(CMD_WORD | reg, 2);
        return b[0] | (b[1] << 8);
    }

    async _writeWord(reg, value) {
        await this._conn.writeReg(CMD_WORD | reg, Buffer.from([value & 0xFF, (value >> 8) & 0xFF]));
    }

    async _writeTiming(manual = false) {
        const value = (this._gain === 16 ? 0x10 : 0) | this._integ | (manual ? 0x08 : 0);
        await this._writeByte(REG_TIMING, value);
    }

    async _readChannels() {
        const b = await this._conn.readReg(CMD_DATA_BLOCK, 4);
        return { ch0: b[0] | (b[1] << 8), ch1: b[2] | (b[3] << 8) };
    }

    _fullScale() { return INTEG[this._integ] ? INTEG[this._integ].fullScale : 65535; }

    _integrationMs() { return INTEG[this._integ] ? INTEG[this._integ].ms : 0; }

    _settleMs() { return INTEG[this._integ] ? INTEG[this._integ].settleMs : 0; }

    _luxFrom(ch0, ch1) {
        const limit = this._fullScale();
        if (ch0 >= limit || ch1 >= limit) {
            throw new Error(`TSL2561: channel saturated (ch0=${ch0}, ch1=${ch1})`);
        }
        return calculateLux(ch0, ch1, this._gain, this._integrationMs(), this._package);
    }

    /**
     * Run the initialization sequence: CONTROL ← 0x03, read it back, TIMING ←
     * 0x02 (1× gain, 402 ms) and wait one integration (450 ms, scaled by
     * `TSL2561Minimal.waitFactor`).
     * @returns {Promise<void>}
     * @throws {Error} If CONTROL does not read back as powered up.
     */
    async init() {
        this._gain = 1;
        this._integ = 2;
        await this._writeByte(REG_CONTROL, POWER_ON);
        if (((await this._readByte(REG_CONTROL)) & 0x03) !== POWER_ON) {
            throw new Error('TSL2561: CONTROL did not read back as powered up');
        }
        await this._writeTiming();
        await _wait(this._settleMs());
    }

    /**
     * Read the illuminance.
     * @returns {Promise<number>} Lux (T/FN/CL coefficients unless changed); 0 for a channel ratio above 1.30.
     * @throws {Error} If a channel is saturated (count at or above the full-scale limit).
     */
    async readLux() {
        const { ch0, ch1 } = await this._readChannels();
        return this._luxFrom(ch0, ch1);
    }
}

/** Multiplier for every integration wait (tests set it to 0 to skip the sleeps). */
TSL2561Minimal.waitFactor = 1;

/**
 * TSL2561 full interface — extends Minimal with raw counts, gain and
 * integration time, package coefficients, auto-ranging, power control, manual
 * integration, the interrupt window and interrupt delivery.
 */
class TSL2561Full extends TSL2561Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._serviceEdge(); };
    }

    /**
     * Read both ADC channels in one block read.
     * @returns {Promise<{ch0: number, ch1: number}>} Channel 0 (visible + IR) and channel 1 (IR) counts.
     */
    async readRaw() {
        return this._readChannels();
    }

    /**
     * Set the ADC gain (TIMING.GAIN); wait one integration before trusting the next reading.
     * @param {number} gain - 1 or 16.
     * @returns {Promise<void>}
     * @throws {RangeError} On any other value.
     */
    async setGain(gain) {
        if (gain !== 1 && gain !== 16) throw new RangeError('gain must be 1 or 16');
        this._gain = gain;
        await this._writeTiming();
    }

    /**
     * Current ADC gain.
     * @returns {number} 1 or 16.
     */
    getGain() { return this._gain; }

    /**
     * Set the integration time (TIMING.INTEG); wait one integration before trusting the next reading.
     * @param {number} ms - 14 (13.7 ms), 101 or 402.
     * @returns {Promise<void>}
     * @throws {RangeError} On any other value.
     */
    async setIntegrationTime(ms) {
        if (!(ms in INTEG_BY_MS)) throw new RangeError('integration time must be 14, 101 or 402 ms');
        this._integ = INTEG_BY_MS[ms];
        await this._writeTiming();
    }

    /**
     * Current integration time.
     * @returns {number} 14, 101 or 402 ms; 0 in manual timing mode.
     */
    getIntegrationTime() { return this._integrationMs(); }

    /**
     * Select the lux coefficient set.
     * @param {string} pkg - "T", "FN", "CL" (shared coefficients) or "CS".
     * @throws {RangeError} On any other value.
     */
    setPackage(pkg) {
        if (!(pkg in PACKAGES)) throw new RangeError('package must be T, FN, CL or CS');
        this._package = pkg;
    }

    /**
     * Read lux and both channel counts from one sample.
     * @returns {Promise<{lux: number, ch0: number, ch1: number}>}
     * @throws {Error} If a channel is saturated.
     */
    async readLuxRaw() {
        const { ch0, ch1 } = await this._readChannels();
        return { lux: this._luxFrom(ch0, ch1), ch0, ch1 };
    }

    async _applyRange(index) {
        const oldWait = this._settleMs();
        [this._gain, this._integ] = RANGES[index];
        await this._writeTiming();
        await _wait(oldWait + this._settleMs());
    }

    /**
     * Pick gain and integration time for the current light level and read lux.
     * Steps through 16×/402 ms, 1×/402 ms, 1×/101 ms and 1×/13.7 ms until
     * neither channel saturates and channel 0 is above 100 counts, waiting for a
     * fresh integration after every change; the chosen setting stays active.
     * @returns {Promise<number>} Illuminance in lux.
     * @throws {Error} If a channel still saturates at 1×/13.7 ms.
     */
    async autoRange() {
        let index = RANGES.findIndex(([g, i]) => g === this._gain && i === this._integ);
        if (index < 0) {
            index = 1;
            await this._applyRange(index);
        }
        for (let step = 0; step < 2 * RANGES.length; step++) {
            const { ch0, ch1 } = await this._readChannels();
            const limit = this._fullScale();
            const saturated = ch0 >= limit || ch1 >= limit;
            if (saturated && index < RANGES.length - 1) index++;
            else if (!saturated && ch0 < AUTO_MIN_COUNTS && index > 0) index--;
            else return this._luxFrom(ch0, ch1);
            await this._applyRange(index);
        }
        const { ch0, ch1 } = await this._readChannels();
        return this._luxFrom(ch0, ch1);
    }

    /**
     * Power up (CONTROL ← 0x03); integration restarts.
     * @returns {Promise<void>}
     */
    async powerUp() { await this._writeByte(REG_CONTROL, POWER_ON); }

    /**
     * Power down (CONTROL ← 0x00); the registers keep their values.
     * @returns {Promise<void>}
     */
    async powerDown() { await this._writeByte(REG_CONTROL, 0x00); }

    /**
     * Report whether the chip is powered up.
     * @returns {Promise<boolean>} True if CONTROL reads 0x03.
     */
    async isPowered() { return ((await this._readByte(REG_CONTROL)) & 0x03) === POWER_ON; }

    /**
     * Start a host-timed integration (INTEG = 11, MANUAL = 1). readLux() then
     * uses the 402 ms scale; use readRaw() and scale by the actual time.
     * @returns {Promise<void>}
     */
    async startManual() {
        this._integ = INTEG_MANUAL;
        await this._writeTiming(true);
    }

    /**
     * Stop the host-timed integration (INTEG = 11, MANUAL = 0); read the counts next.
     * @returns {Promise<void>}
     */
    async stopManual() {
        this._integ = INTEG_MANUAL;
        await this._writeTiming(false);
    }

    /**
     * Set the channel-0 interrupt window (word writes of 0x2–0x3 and 0x4–0x5).
     * @param {number} low - Low threshold, channel-0 counts 0–65535.
     * @param {number} high - High threshold, channel-0 counts 0–65535.
     * @returns {Promise<void>}
     * @throws {RangeError} If a value is outside 0–65535.
     */
    async setThresholds(low, high) {
        for (const v of [low, high]) {
            if (!Number.isInteger(v) || v < 0 || v > 0xFFFF) throw new RangeError('threshold must be 0-65535');
        }
        await this._writeWord(REG_THRESH_LOW, low);
        await this._writeWord(REG_THRESH_HIGH, high);
    }

    /**
     * Read the channel-0 interrupt window.
     * @returns {Promise<{low: number, high: number}>} Thresholds in channel-0 counts.
     */
    async getThresholds() {
        return { low: await this._readWord(REG_THRESH_LOW), high: await this._readWord(REG_THRESH_HIGH) };
    }

    /**
     * Configure the INTERRUPT register.
     * @param {number} mode - 0 disabled, 1 level interrupt, 2 SMBAlert style.
     * @param {number} [persist=0] - 0 every cycle, 1 any value outside, N (2–15) N consecutive cycles outside.
     * @returns {Promise<void>}
     * @throws {RangeError} If mode is not 0–2 or persist not 0–15.
     */
    async configureInterrupt(mode, persist = 0) {
        if (![0, 1, 2].includes(mode)) throw new RangeError('mode must be 0, 1 or 2');
        if (!Number.isInteger(persist) || persist < 0 || persist > 15) throw new RangeError('persist must be 0-15');
        await this._writeByte(REG_INTERRUPT, (mode << 4) | persist);
    }

    /**
     * Read the ID register.
     * @returns {Promise<number>} ID byte; `(id >> 4) === 1` for the TSL2561, bits 3:0 the revision.
     */
    async readId() { return this._readByte(REG_ID); }

    /**
     * Check channel 0 against the programmed window (the persistence filter is
     * not modelled and the chip's interrupt is not cleared).
     * @returns {Promise<boolean>} True if channel 0 is ≤ the low or > the high threshold.
     */
    async pollInterrupt() {
        const { ch0 } = await this._readChannels();
        const { low, high } = await this.getThresholds();
        return ch0 <= low || ch0 > high;
    }

    /**
     * Send the command byte 0xC0 (CMD + CLEAR) — releases a level interrupt.
     * @returns {Promise<void>}
     */
    async clearInterrupt() {
        await this._conn.write(Buffer.from([CMD_CLEAR]));
    }

    /**
     * Subscribe to INT (falling edge). `callback(ch0)` receives the current
     * channel-0 count. With a pin (options.intPin or connection.intPin) the
     * driver clears the level interrupt after the callback; without one a timer
     * calls pollInterrupt() once per integration time.
     * @param {function} callback - Called with (ch0).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        const intPin = options.intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            const period = Math.max(this._integrationMs(), 14);
            this._pollTimer = setInterval(() => { this._servicePoll(); }, period);
        }
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _serviceEdge() {
        if (this._polling) return;
        this._polling = true;
        try {
            const { ch0 } = await this._readChannels();
            if (this._callback) this._callback(ch0);
            await this.clearInterrupt();
        } finally {
            this._polling = false;
        }
    }

    async _servicePoll() {
        if (this._polling) return;
        this._polling = true;
        try {
            if (await this.pollInterrupt()) {
                const { ch0 } = await this._readChannels();
                if (this._callback) this._callback(ch0);
            }
        } finally {
            this._polling = false;
        }
    }
}

module.exports = { TSL2561Minimal, TSL2561Full, calculateLux };
