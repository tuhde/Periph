'use strict';

const REG_DEVICE_ID = 0x00;
const REG_CONFIG1 = 0x01;
const REG_CONFIG2 = 0x02;
const REG_CONFIG3 = 0x03;
const REG_LOW_TH = 0x04;
const REG_HIGH_TH = 0x06;
const REG_STATUS = 0x08;
const REG_GREEN = 0x09;
const REG_RED = 0x0B;
const REG_BLUE = 0x0D;

const DEVICE_ID = 0x7D;
const RESET_VALUE = 0x46;

const CFG1_SYNC = 0x20;
const CFG1_BITS_12 = 0x10;
const CFG1_RNG_10000 = 0x08;
const CFG1_MODE_MASK = 0x07;
const CFG3_CONVEN = 0x10;

const STATUS_BROWNOUT = 0x04;
const STATUS_CONVERSION = 0x02;
const STATUS_THRESHOLD = 0x01;

const MODE_CODE = {
    power_down: 0, green: 1, red: 2, blue: 3, standby: 4,
    rgb: 5, green_red: 6, green_blue: 7,
};
const CHANNEL_CODE = { none: 0, green: 1, red: 2, blue: 3 };
const PERSISTENCE_CODE = { 1: 0, 2: 1, 4: 2, 8: 3 };

const WAIT_LIMIT_MS = 400;
const POLL_STEP_MS = 10;
const INT_POLL_INTERVAL_MS = 50;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * ISL29125 digital red, green and blue colour light sensor (Renesas / Intersil) — minimal interface.
 *
 * RGB colour sensor with an IR-blocking filter, a 12/16-bit integrating ADC
 * (16 bit: 100 ms), two sensing ranges (375 lux and 10 000 lux full scale) and
 * programmable IR compensation. Results are unsigned 16-bit counts, LSB first.
 * The I²C address is fixed at 0x44. Reading STATUS clears its flags, so the
 * driver keeps the flags it has seen until `pollInterrupt()` / `status()` hands
 * them out.
 */
class ISL29125Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (1-byte register address).
     */
    constructor(connection) {
        this._conn = connection;
        this._pending = 0;
    }

    /** Fixed 7-bit I²C address. */
    static get I2C_ADDRESS() { return 0x44; }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readStatus() {
        const value = await this._readReg(REG_STATUS);
        this._pending |= value & (STATUS_BROWNOUT | STATUS_CONVERSION | STATUS_THRESHOLD);
        return value;
    }

    async _waitData() {
        for (let waited = 0; waited <= WAIT_LIMIT_MS; waited += POLL_STEP_MS) {
            if ((await this._readStatus()) & STATUS_CONVERSION) return true;
            if (ISL29125Minimal.pollStepMs > 0) await sleep(ISL29125Minimal.pollStepMs);
        }
        return false;
    }

    async _readBlock() {
        const raw = await this._conn.readReg(REG_GREEN, 6);
        return {
            red: raw[2] | (raw[3] << 8),
            green: raw[0] | (raw[1] << 8),
            blue: raw[4] | (raw[5] << 8),
        };
    }

    /**
     * Start the sensor: read DEVICE_ID (must be 0x7D), clear the brown-out flag
     * and write CONFIG1 = 0x0D (RGB, 10 000 lux, 16 bit), CONFIG2 = 0xBF
     * (maximum IR compensation) and CONFIG3 = 0x00.
     * @returns {Promise<void>}
     * @throws {Error} if DEVICE_ID does not identify an ISL29125.
     */
    async init() {
        const id = await this._readReg(REG_DEVICE_ID);
        if (id !== DEVICE_ID) {
            throw new Error('ISL29125 not found (DEVICE_ID 0x' + id.toString(16).padStart(2, '0') + ')');
        }
        await this._writeReg(REG_STATUS, 0x00);
        await this._writeReg(REG_CONFIG3, 0x00);
        await this._writeReg(REG_CONFIG2, 0xBF);
        await this._writeReg(REG_CONFIG1, 0x0D);
    }

    /**
     * Read red, green and blue from one conversion. Waits for a new result (at
     * most 400 ms), then block-reads 0x09-0x0E.
     * @returns {Promise<{red: number, green: number, blue: number}>} Unsigned 16-bit counts.
     */
    async read() {
        await this._waitData();
        return this._readBlock();
    }
}

/** Poll step while waiting for CONVENF, in ms (tests may set it to 0). */
ISL29125Minimal.pollStepMs = POLL_STEP_MS;

/**
 * ISL29125 full interface — extends Minimal with single-channel reads, mode,
 * range, resolution, synchronisation and IR-compensation configuration,
 * thresholds, persistence, and interrupt delivery.
 */
class ISL29125Full extends ISL29125Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    /** Interrupt source: selected channel outside the threshold window. */
    static get SOURCE_THRESHOLD() { return 0x01; }
    /** Interrupt source: conversion complete. */
    static get SOURCE_CONVERSION() { return 0x02; }
    /** STATUS bit 2: power-down or brown-out occurred. */
    static get STATUS_BROWNOUT() { return STATUS_BROWNOUT; }
    /** STATUS bit 1: conversion completed. */
    static get STATUS_CONVERSION() { return STATUS_CONVERSION; }
    /** STATUS bit 0: threshold window crossed. */
    static get STATUS_THRESHOLD() { return STATUS_THRESHOLD; }

    async _readChannel(reg) {
        const raw = await this._conn.readReg(reg, 2);
        return raw[0] | (raw[1] << 8);
    }

    /** @returns {Promise<number>} Red count. */
    async readRed() { return this._readChannel(REG_RED); }

    /** @returns {Promise<number>} Green count. */
    async readGreen() { return this._readChannel(REG_GREEN); }

    /** @returns {Promise<number>} Blue count. */
    async readBlue() { return this._readChannel(REG_BLUE); }

    /**
     * Block-read all three channels without waiting.
     * @returns {Promise<{red: number, green: number, blue: number}>} Counts.
     */
    async readRgb() { return this._readBlock(); }

    /**
     * Report whether a conversion has completed. Reads STATUS, which clears its
     * flags; the flags are kept for `pollInterrupt()` and `status()`.
     * @returns {Promise<boolean>} True if CONVENF was set.
     */
    async dataReady() {
        return Boolean((await this._readStatus()) & STATUS_CONVERSION);
    }

    async _updateReg(reg, setBits, clearBits) {
        const value = await this._readReg(reg);
        await this._writeReg(reg, (value & ~clearBits) | setBits);
    }

    /**
     * Select the operating mode (conversions are continuous).
     * @param {string} mode - 'power_down', 'standby', 'green', 'red', 'blue', 'rgb', 'green_red' or 'green_blue'.
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        if (!(mode in MODE_CODE)) throw new RangeError('unknown mode ' + mode);
        await this._updateReg(REG_CONFIG1, MODE_CODE[mode], CFG1_MODE_MASK);
    }

    /**
     * Select the sensing range.
     * @param {number} lux - 375 or 10000 (full-scale illuminance).
     * @returns {Promise<void>}
     */
    async setRange(lux) {
        if (lux === 375) await this._updateReg(REG_CONFIG1, 0, CFG1_RNG_10000);
        else if (lux === 10000) await this._updateReg(REG_CONFIG1, CFG1_RNG_10000, 0);
        else throw new RangeError('range must be 375 or 10000');
    }

    /**
     * Select the ADC resolution.
     * @param {number} bits - 12 or 16.
     * @returns {Promise<void>}
     */
    async setResolution(bits) {
        if (bits === 16) await this._updateReg(REG_CONFIG1, 0, CFG1_BITS_12);
        else if (bits === 12) await this._updateReg(REG_CONFIG1, CFG1_BITS_12, 0);
        else throw new RangeError('resolution must be 12 or 16');
    }

    /**
     * Select the conversion start source.
     * @param {boolean} external - True: the rising edge of INT starts the conversion (INT becomes an input); false: the I²C write starts it.
     * @returns {Promise<void>}
     */
    async setSync(external) {
        await this._updateReg(REG_CONFIG1, external ? CFG1_SYNC : 0, CFG1_SYNC);
    }

    /**
     * Set the active IR compensation (CONFIG2).
     * @param {boolean} offset - False: codes 0-63; true: codes 106-169.
     * @param {number} adjust - Compensation adjust, 0-63.
     * @returns {Promise<void>}
     */
    async setIrCompensation(offset, adjust) {
        if (adjust < 0 || adjust > 63) throw new RangeError('adjust must be 0-63');
        await this._writeReg(REG_CONFIG2, (offset ? 0x80 : 0) | adjust);
    }

    /** @returns {Promise<number>} DEVICE_ID (0x7D for the ISL29125). */
    async deviceId() { return this._readReg(REG_DEVICE_ID); }

    /**
     * Read STATUS (clears the flags on the chip).
     * @returns {Promise<number>} Raw status byte with the flags seen since the last call merged in.
     */
    async status() {
        let value = await this._readStatus();
        value |= this._pending;
        this._pending = 0;
        return value;
    }

    /** Reset all registers to their defaults (writes 0x46 to register 0x00). @returns {Promise<void>} */
    async softReset() {
        await this._writeReg(REG_DEVICE_ID, RESET_VALUE);
        if (ISL29125Minimal.pollStepMs > 0) await sleep(10);
        this._pending = 0;
    }

    /**
     * Set the 16-bit threshold window.
     * @param {number} low - Lower threshold (0-65535).
     * @param {number} high - Upper threshold (0-65535).
     * @returns {Promise<void>}
     */
    async setThresholds(low, high) {
        if (low < 0 || high < 0 || low > 0xFFFF || high > 0xFFFF || low > high) {
            throw new RangeError('thresholds must satisfy 0 <= low <= high <= 65535');
        }
        await this._conn.writeReg(REG_LOW_TH, Buffer.from([low & 0xFF, low >> 8]));
        await this._conn.writeReg(REG_HIGH_TH, Buffer.from([high & 0xFF, high >> 8]));
    }

    /** @returns {Promise<{low: number, high: number}>} Threshold window in counts. */
    async getThresholds() {
        const low = await this._conn.readReg(REG_LOW_TH, 2);
        const high = await this._conn.readReg(REG_HIGH_TH, 2);
        return { low: low[0] | (low[1] << 8), high: high[0] | (high[1] << 8) };
    }

    /**
     * Select the channel the threshold interrupt monitors.
     * @param {string} channel - 'none', 'green', 'red' or 'blue'.
     * @returns {Promise<void>}
     */
    async setInterruptChannel(channel) {
        if (!(channel in CHANNEL_CODE)) throw new RangeError("channel must be 'none', 'green', 'red' or 'blue'");
        await this._updateReg(REG_CONFIG3, CHANNEL_CODE[channel], 0x03);
    }

    /**
     * Set the number of integration cycles before the interrupt asserts.
     * @param {number} count - 1, 2, 4 or 8.
     * @returns {Promise<void>}
     */
    async setPersistence(count) {
        if (!(count in PERSISTENCE_CODE)) throw new RangeError('count must be 1, 2, 4 or 8');
        await this._updateReg(REG_CONFIG3, PERSISTENCE_CODE[count] << 2, 0x0C);
    }

    /**
     * Enable an interrupt source. For `SOURCE_THRESHOLD` select the channel
     * beforehand with `setInterruptChannel()` (green is used if none is set).
     * @param {number} [source] - `SOURCE_THRESHOLD` (default) or `SOURCE_CONVERSION`.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source = ISL29125Full.SOURCE_THRESHOLD) {
        if (source === ISL29125Full.SOURCE_CONVERSION) {
            await this._updateReg(REG_CONFIG3, CFG3_CONVEN, 0);
        } else if (((await this._readReg(REG_CONFIG3)) & 0x03) === 0) {
            await this._updateReg(REG_CONFIG3, 0x01, 0);
        }
    }

    /**
     * Disable an interrupt source.
     * @param {number} [source] - `SOURCE_THRESHOLD` (INTSEL = none, default) or `SOURCE_CONVERSION`.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source = ISL29125Full.SOURCE_THRESHOLD) {
        if (source === ISL29125Full.SOURCE_CONVERSION) await this._updateReg(REG_CONFIG3, 0, CFG3_CONVEN);
        else await this._updateReg(REG_CONFIG3, 0, 0x03);
    }

    /**
     * Read STATUS (clearing the flags) and return it.
     * @returns {Promise<number>} Status byte; `STATUS_THRESHOLD` / `STATUS_CONVERSION` are set for events seen since the last poll.
     */
    async pollInterrupt() { return this.status(); }

    async _arm(intPin) {
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, INT_POLL_INTERVAL_MS);
        }
    }

    /**
     * Subscribe to INT assertions. `callback(status)` is called with the STATUS
     * byte after the driver has read (and thereby cleared) it. Delivery is via
     * the INT line (options.intPin or connection.intPin, falling edge) or a
     * 50 ms polling timer.
     * @param {function} callback - Called with (status).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        await this._arm(options.intPin ?? this._conn.intPin ?? null);
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

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if ((status & (STATUS_THRESHOLD | STATUS_CONVERSION)) && this._callback) this._callback(status);
        } finally {
            this._polling = false;
        }
    }
}

module.exports = { ISL29125Minimal, ISL29125Full };
