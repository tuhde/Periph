'use strict';

const CFG_RDY = 0x80;
const CFG_CONT = 0x10;
const VREF = 2.048;
const BITS_TO_S = { 12: 0, 14: 1, 16: 2, 18: 3 };
const GAIN_TO_G = { 1: 0, 2: 1, 4: 2, 8: 3 };
const CONV_S = { 12: 1 / 240, 14: 1 / 60, 16: 1 / 15, 18: 1 / 3.75 };   // nominal conversion times

const GC_RESET = Buffer.from([0x00, 0x06]);
const GC_CONVERT = Buffer.from([0x00, 0x08]);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * MCP3422 2-channel 18-bit delta-sigma ADC with PGA (Microchip) — minimal
 * interface.
 *
 * Two differential inputs, an on-board 2.048 V reference, a PGA (x1, x2, x4,
 * x8) and an on-board oscillator. The resolution is selected together with the
 * data rate: 12 bit / 240 SPS, 14 bit / 60 SPS, 16 bit / 15 SPS, 18 bit /
 * 3.75 SPS. The full-scale differential input range is ±2.048 V / gain. The
 * chip has no register map: a write is one configuration byte
 * (`RDY C1 C0 O/C S1 S0 G1 G0`) and a read returns the 2 (12/14/16 bit) or 3
 * (18 bit) result bytes followed by the configuration byte, whose RDY bit
 * (0 = fresh result) says whether the conversion has finished. The driver
 * therefore uses the plain Connection `write()` / `read()` calls.
 *
 * Defaults: one-shot mode (lowest power), 16 bit / 15 SPS (LSB 62.5 µV), gain
 * x1. `init()` writes that configuration with RDY = 0, so nothing is started.
 */
class MCP3422Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - I²C connection (0x68–0x6F).
     */
    constructor(connection) {
        this._conn = connection;
        this._bits = 16;
        this._gain = 1;
        this._channel = 0;
        this._continuous = false;
        this._discard = false;
    }

    /** 7-bit I²C address of the standard part (order code A0). */
    static get I2C_ADDRESS() { return 0x68; }

    _config(rdy) {
        let c = (this._channel << 5) | (BITS_TO_S[this._bits] << 2) | GAIN_TO_G[this._gain];
        if (rdy) c |= CFG_RDY;
        if (this._continuous) c |= CFG_CONT;
        return c;
    }

    _nbytes() { return this._bits === 18 ? 3 : 2; }

    _decode(data) {
        let raw = 0;
        for (let i = 0; i < this._nbytes(); i++) raw = (raw * 256) + data[i];
        const span = 2 ** this._bits;
        raw = raw % span;
        if (raw >= span / 2) raw -= span;
        return raw;
    }

    async _waitReady(convS) {
        const polls = Math.floor((2 * convS + 0.010) / 0.005) + 1;
        for (let i = 0; i < polls; i++) {
            const data = await this._conn.read(this._nbytes() + 1);
            if (!(data[this._nbytes()] & CFG_RDY)) return this._decode(data);
            await sleep(5);
        }
        throw new Error('MCP3422 conversion did not finish');
    }

    async _convert(channel) {
        this._channel = channel < 1 ? 0 : 1;
        this._continuous = false;
        this._discard = false;
        const conv = CONV_S[this._bits];
        await this._conn.write(Buffer.from([this._config(true)]));
        await sleep(conv * 1100);
        return this._waitReady(conv);
    }

    /**
     * Write the default configuration (one-shot, 16 bit / 15 SPS, gain x1, CH1,
     * RDY = 0); nothing is started and the chip goes to standby.
     * @returns {Promise<void>}
     */
    async init() {
        await this._conn.write(Buffer.from([this._config(false)]));
    }

    /**
     * Run a one-shot conversion on a channel and return the signed code.
     * @param {number} channel - 0 (CH1) or 1 (CH2); values outside 0–1 are clamped.
     * @returns {Promise<number>} Signed code, −2^(N−1) … 2^(N−1) − 1 (N = resolution bits).
     * @throws {Error} if the conversion does not finish in time.
     */
    async readRaw(channel) {
        return this._convert(channel);
    }

    /**
     * Run a one-shot conversion on a channel and return the differential voltage.
     * @param {number} channel - 0 (CH1) or 1 (CH2); values outside 0–1 are clamped.
     * @returns {Promise<number>} `CHn+ − CHn−` in volts, `raw * lsb / gain`.
     */
    async readVoltage(channel) {
        return (await this._convert(channel)) * this.lsb() / this._gain;
    }

    /**
     * Convert both channels, one conversion each.
     * @returns {Promise<number[]>} `[CH1, CH2]` voltages in volts.
     */
    async readAll() {
        return [await this.readVoltage(0), await this.readVoltage(1)];
    }

    /**
     * Size of one code before the gain.
     * @returns {number} `2 * 2.048 / 2^bits` in volts.
     */
    lsb() {
        return 2 * VREF / (2 ** this._bits);
    }
}

/**
 * MCP3422 full interface — extends Minimal with resolution, gain and channel
 * selection, continuous conversion, status reads, scans and general calls. The
 * driver tracks resolution, gain, channel and mode.
 */
class MCP3422Full extends MCP3422Minimal {
    async _apply() {
        if (this._continuous) {
            await this._conn.write(Buffer.from([this._config(false)]));
            this._discard = true;
        }
    }

    /**
     * Select the resolution (and with it the data rate).
     * @param {number} bits - 12 (240 SPS), 14 (60 SPS), 16 (15 SPS) or 18 (3.75 SPS).
     * @returns {Promise<void>}
     * @throws {RangeError} for any other value.
     */
    async setResolution(bits) {
        if (!(bits in BITS_TO_S)) throw new RangeError('bits must be 12, 14, 16 or 18');
        this._bits = bits;
        await this._apply();
    }

    /**
     * The selected resolution.
     * @returns {number} 12, 14, 16 or 18.
     */
    getResolution() { return this._bits; }

    /**
     * Select the PGA gain.
     * @param {number} gain - 1, 2, 4 or 8.
     * @returns {Promise<void>}
     * @throws {RangeError} for any other value.
     */
    async setGain(gain) {
        if (!(gain in GAIN_TO_G)) throw new RangeError('gain must be 1, 2, 4 or 8');
        this._gain = gain;
        await this._apply();
    }

    /**
     * The selected PGA gain.
     * @returns {number} 1, 2, 4 or 8.
     */
    getGain() { return this._gain; }

    /**
     * Select the channel used by continuous conversion.
     * @param {number} channel - 0 (CH1) or 1 (CH2).
     * @returns {Promise<void>}
     */
    async setChannel(channel) {
        this._channel = channel < 1 ? 0 : 1;
        await this._apply();
    }

    /**
     * Switch to continuous conversion at the current rate and gain.
     * @param {number} [channel] - Optional channel 0 or 1 to select first.
     * @returns {Promise<void>}
     */
    async startContinuous(channel) {
        if (channel !== undefined && channel !== null) this._channel = channel < 1 ? 0 : 1;
        this._continuous = true;
        this._discard = true;
        await this._conn.write(Buffer.from([this._config(false)]));
    }

    /**
     * Return to one-shot mode / standby (writes O/C = 0, RDY = 0).
     * @returns {Promise<void>}
     */
    async stopContinuous() {
        this._continuous = false;
        this._discard = false;
        await this._conn.write(Buffer.from([this._config(false)]));
    }

    /**
     * Report the conversion mode.
     * @returns {boolean} true in continuous mode.
     */
    isContinuous() { return this._continuous; }

    /**
     * Wait for a fresh result in continuous mode and return the signed code. The
     * first sample after a configuration change is discarded.
     * @returns {Promise<number>}
     * @throws {Error} if the driver is not in continuous mode or no result arrives in time.
     */
    async readContinuousRaw() {
        if (!this._continuous) throw new Error('not in continuous mode; call startContinuous()');
        const conv = CONV_S[this._bits];
        if (this._discard) {
            await this._waitReady(conv);
            this._discard = false;
        }
        return this._waitReady(conv);
    }

    /**
     * Voltage form of readContinuousRaw().
     * @returns {Promise<number>} Differential voltage in volts.
     */
    async readContinuous() {
        return (await this.readContinuousRaw()) * this.lsb() / this._gain;
    }

    /**
     * Read the configuration byte (the last byte of a read).
     * @returns {Promise<number>} Configuration byte; bit 7 is the live RDY flag.
     */
    async readConfig() {
        const data = await this._conn.read(this._nbytes() + 1);
        return data[this._nbytes()];
    }

    /**
     * Report whether a fresh result is available (RDY = 0).
     * @returns {Promise<boolean>}
     */
    async isReady() {
        return !((await this.readConfig()) & CFG_RDY);
    }

    /**
     * Run one one-shot conversion per entry, in order.
     * @param {number[]} channels - Channels (0 or 1).
     * @returns {Promise<number[]>} Voltages in volts, one per entry.
     */
    async readScan(channels) {
        const out = [];
        for (const ch of channels) out.push(await this.readVoltage(ch));
        return out;
    }

    /**
     * Send the general call reset (0x00 0x06): power-on defaults on the chip
     * (continuous, 12 bit, gain x1, CH1); the driver state follows. Needs a
     * Connection that transmits to the general-call address.
     * @returns {Promise<void>}
     */
    async generalCallReset() {
        await this._conn.write(GC_RESET);
        this._bits = 12;
        this._gain = 1;
        this._channel = 0;
        this._continuous = true;
        this._discard = true;
    }

    /**
     * Send the general call conversion (0x00 0x08): a one-shot conversion on every
     * device on the bus; read the result with readPendingRaw().
     * @returns {Promise<void>}
     */
    async generalCallConvert() {
        await this._conn.write(GC_CONVERT);
        this._continuous = false;
    }

    /**
     * Wait for RDY = 0 and return the code of a conversion started elsewhere.
     * @returns {Promise<number>}
     */
    async readPendingRaw() {
        return this._waitReady(CONV_S[this._bits]);
    }

    /**
     * Full-scale differential input range at the current gain.
     * @returns {number} `2.048 / gain` in volts.
     */
    fullScale() {
        return VREF / this._gain;
    }
}

module.exports = { MCP3422Minimal, MCP3422Full };
