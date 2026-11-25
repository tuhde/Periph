'use strict';

const REG_MAIN_CTRL = 0x00;
const REG_LS_MEAS_RATE = 0x04;
const REG_LS_GAIN = 0x05;
const REG_PART_ID = 0x06;
const REG_MAIN_STATUS = 0x07;
const REG_DATA_IR = 0x0A;
const REG_DATA_GREEN = 0x0D;
const REG_DATA_BLUE = 0x10;
const REG_DATA_RED = 0x13;
const REG_INT_CFG = 0x19;
const REG_INT_PERSISTENCE = 0x1A;
const REG_THRES_UP = 0x21;
const REG_THRES_LOW = 0x24;
const REG_THRES_VAR = 0x27;

const CTRL_SW_RESET = 0x10;
const CTRL_CS_MODE = 0x04;
const CTRL_LS_EN = 0x02;

const INT_EN = 0x04;
const INT_VAR_MODE = 0x08;
const INT_SEL_MASK = 0x30;

const STATUS_POWER_ON = 0x20;
const STATUS_INTERRUPT = 0x10;
const STATUS_DATA_READY = 0x08;

const RESOLUTION_BITS = [20, 19, 18, 17, 16, 13];
const INTEGRATION_S = [0.4, 0.2, 0.1, 0.05, 0.025, 0.003125];
const RATE_CODE = { 25: 0, 50: 1, 100: 2, 200: 3, 500: 4, 1000: 5, 2000: 6 };
const RATE_MS = [25, 50, 100, 200, 500, 1000, 2000, 2000];
const GAINS = [1, 3, 6, 9, 18];
const CHANNEL_CODE = { ir: 0, green: 1, red: 2, blue: 3 };

const WAIT_MARGIN_S = 0.05;
const POLL_STEP_MS = 5;
const INT_POLL_INTERVAL_MS = 50;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _count(b0, b1, b2) {
    return b0 | (b1 << 8) | ((b2 & 0x0F) << 16);
}

/**
 * APDS-9250 digital RGB, IR and ambient light sensor (Broadcom) — minimal interface.
 *
 * Four simultaneously integrating channels (red, green, blue, IR) with a 13- to
 * 20-bit ADC, five gains and a 25 ms to 2 s measurement rate. Results are
 * unsigned counts in 3-byte registers (LSB first). The I²C address is fixed at
 * 0x52. Reading MAIN_STATUS clears its flags, so the driver keeps the interrupt
 * and power-on bits it has seen until `pollInterrupt()` / `status()` hands them out.
 */
class APDS9250Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (1-byte register address).
     */
    constructor(connection) {
        this._conn = connection;
        this._pending = 0;
        this._integrationS = 0.1;
    }

    /** Fixed 7-bit I²C address. */
    static get I2C_ADDRESS() { return 0x52; }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }

    async _readStatus() {
        const value = await this._readReg(REG_MAIN_STATUS);
        this._pending |= value & (STATUS_POWER_ON | STATUS_INTERRUPT);
        return value;
    }

    async _waitData() {
        const limitMs = (this._integrationS + WAIT_MARGIN_S) * 1000;
        for (let waited = 0; waited <= limitMs; waited += POLL_STEP_MS) {
            if ((await this._readStatus()) & STATUS_DATA_READY) return true;
            if (APDS9250Minimal.pollStepMs > 0) await sleep(APDS9250Minimal.pollStepMs);
        }
        return false;
    }

    async _readBlock() {
        const raw = await this._conn.readReg(REG_DATA_IR, 12);
        return {
            red: _count(raw[9], raw[10], raw[11]),
            green: _count(raw[3], raw[4], raw[5]),
            blue: _count(raw[6], raw[7], raw[8]),
            ir: _count(raw[0], raw[1], raw[2]),
        };
    }

    /**
     * Start the sensor: read PART_ID (the upper nibble must be 0xB), write the
     * default configuration (18 bit, 100 ms, gain x3, interrupt off) and set
     * LS_EN and CS_MODE.
     * @returns {Promise<void>}
     * @throws {Error} if PART_ID does not identify an APDS-9250.
     */
    async init() {
        const part = await this._readReg(REG_PART_ID);
        if ((part & 0xF0) !== 0xB0) {
            throw new Error('APDS-9250 not found (PART_ID 0x' + part.toString(16).padStart(2, '0') + ')');
        }
        await this._writeReg(REG_LS_MEAS_RATE, 0x22);
        await this._writeReg(REG_LS_GAIN, 0x01);
        await this._writeReg(REG_INT_CFG, 0x10);
        await this._writeReg(REG_MAIN_CTRL, CTRL_LS_EN | CTRL_CS_MODE);
        this._integrationS = 0.1;
        if (APDS9250Minimal.startWaitMs > 0) await sleep(APDS9250Minimal.startWaitMs);
    }

    /**
     * Read all four channels from one conversion. Waits for a new result (at
     * most one integration time plus 50 ms), then block-reads 0x0A-0x15.
     * @returns {Promise<{red: number, green: number, blue: number, ir: number}>} Unsigned counts.
     */
    async read() {
        await this._waitData();
        return this._readBlock();
    }
}

/** Wait after enabling the sensor so the support blocks settle, in ms (tests may set it to 0). */
APDS9250Minimal.startWaitMs = 5;
/** Poll step while waiting for LS_DATA, in ms (tests may set it to 0). */
APDS9250Minimal.pollStepMs = POLL_STEP_MS;

/**
 * APDS-9250 full interface — extends Minimal with single-channel reads, mode,
 * resolution, rate and gain configuration, thresholds, variation and
 * persistence, and interrupt delivery.
 */
class APDS9250Full extends APDS9250Minimal {
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
    /** Interrupt source: selected channel changed by more than the variance threshold. */
    static get SOURCE_VARIATION() { return 0x02; }
    /** MAIN_STATUS bit 5: power-up event occurred. */
    static get STATUS_POWER_ON() { return STATUS_POWER_ON; }
    /** MAIN_STATUS bit 4: interrupt condition fulfilled. */
    static get STATUS_INTERRUPT() { return STATUS_INTERRUPT; }
    /** MAIN_STATUS bit 3: new data available. */
    static get STATUS_DATA_READY() { return STATUS_DATA_READY; }

    async _readChannel(reg) {
        const raw = await this._conn.readReg(reg, 3);
        return _count(raw[0], raw[1], raw[2]);
    }

    /** @returns {Promise<number>} Red count. */
    async readRed() { return this._readChannel(REG_DATA_RED); }

    /** @returns {Promise<number>} Green / ambient-light count. */
    async readGreen() { return this._readChannel(REG_DATA_GREEN); }

    /** @returns {Promise<number>} Blue count. */
    async readBlue() { return this._readChannel(REG_DATA_BLUE); }

    /** @returns {Promise<number>} IR count. */
    async readIr() { return this._readChannel(REG_DATA_IR); }

    /**
     * Block-read all four channels without waiting (call after `dataReady()`).
     * @returns {Promise<{red: number, green: number, blue: number, ir: number}>} Counts.
     */
    async readChannels() { return this._readBlock(); }

    /**
     * Report whether a new result is available. Reads MAIN_STATUS, which clears
     * its flags; the interrupt and power-on bits are kept for `pollInterrupt()`.
     * @returns {Promise<boolean>} True if LS_DATA was set.
     */
    async dataReady() {
        return Boolean((await this._readStatus()) & STATUS_DATA_READY);
    }

    async _updateReg(reg, setBits, clearBits) {
        const value = await this._readReg(reg);
        await this._writeReg(reg, (value & ~clearBits) | setBits);
    }

    /**
     * Select colour-sensing or ALS/IR mode.
     * @param {boolean} colour - True for CS mode (red, green, blue, IR), false for ALS/IR (green and IR only).
     * @returns {Promise<void>}
     */
    async setMode(colour) {
        await this._updateReg(REG_MAIN_CTRL, colour ? CTRL_CS_MODE : 0, CTRL_CS_MODE);
    }

    /** Activate the light sensor (LS_EN = 1). @returns {Promise<void>} */
    async enable() { await this._updateReg(REG_MAIN_CTRL, CTRL_LS_EN, 0); }

    /** Put the light sensor in standby (LS_EN = 0). @returns {Promise<void>} */
    async disable() { await this._updateReg(REG_MAIN_CTRL, 0, CTRL_LS_EN); }

    /**
     * Set the ADC resolution (and the integration time). Restarts the measurement.
     * @param {number} bits - 13, 16, 17, 18, 19 or 20.
     * @returns {Promise<void>}
     */
    async setResolution(bits) {
        const code = RESOLUTION_BITS.indexOf(bits);
        if (code < 0) throw new RangeError('resolution must be 13, 16, 17, 18, 19 or 20');
        await this._updateReg(REG_LS_MEAS_RATE, code << 4, 0x70);
        this._integrationS = INTEGRATION_S[code];
    }

    /**
     * Set the measurement rate.
     * @param {number} milliseconds - 25, 50, 100, 200, 500, 1000 or 2000.
     * @returns {Promise<void>}
     */
    async setMeasurementRate(milliseconds) {
        if (!(milliseconds in RATE_CODE)) throw new RangeError('rate must be 25, 50, 100, 200, 500, 1000 or 2000 ms');
        await this._updateReg(REG_LS_MEAS_RATE, RATE_CODE[milliseconds], 0x07);
    }

    /**
     * Set the analog gain for all channels. Restarts the measurement.
     * @param {number} gain - 1, 3, 6, 9 or 18.
     * @returns {Promise<void>}
     */
    async setGain(gain) {
        const code = GAINS.indexOf(gain);
        if (code < 0) throw new RangeError('gain must be 1, 3, 6, 9 or 18');
        await this._writeReg(REG_LS_GAIN, code);
    }

    /** @returns {Promise<number>} Resolution in bits (13, 16-20). */
    async getResolution() {
        const code = ((await this._readReg(REG_LS_MEAS_RATE)) >> 4) & 0x07;
        return code < 6 ? RESOLUTION_BITS[code] : 18;
    }

    /** @returns {Promise<number>} Measurement rate in milliseconds. */
    async getMeasurementRate() {
        return RATE_MS[(await this._readReg(REG_LS_MEAS_RATE)) & 0x07];
    }

    /** @returns {Promise<number>} Gain (1, 3, 6, 9 or 18). */
    async getGain() {
        const code = (await this._readReg(REG_LS_GAIN)) & 0x07;
        return code < 5 ? GAINS[code] : 18;
    }

    /** @returns {Promise<number>} Integration time of the current resolution in seconds. */
    async integrationTime() {
        const code = ((await this._readReg(REG_LS_MEAS_RATE)) >> 4) & 0x07;
        return code < 6 ? INTEGRATION_S[code] : 0.1;
    }

    /**
     * Convert a count to counts per gain-second: `counts / (gain * integrationTime)`.
     * @param {number} counts - Raw channel count.
     * @returns {Promise<number>} Normalised count.
     */
    async normalized(counts) {
        return counts / ((await this.getGain()) * (await this.integrationTime()));
    }

    /** @returns {Promise<{part: number, revision: number}>} PART_ID nibbles (0xB, 0x5). */
    async partId() {
        const value = await this._readReg(REG_PART_ID);
        return { part: value >> 4, revision: value & 0x0F };
    }

    /**
     * Read MAIN_STATUS (clears the flags on the chip).
     * @returns {Promise<number>} Raw status byte with interrupt/power-on bits seen since the last call merged in.
     */
    async status() {
        let value = await this._readStatus();
        value |= this._pending;
        this._pending = 0;
        return value;
    }

    /** Trigger a software reset (all registers to defaults, sensor in standby). @returns {Promise<void>} */
    async softReset() {
        await this._writeReg(REG_MAIN_CTRL, CTRL_SW_RESET);
        if (APDS9250Minimal.startWaitMs > 0) await sleep(10);
        this._pending = 0;
        this._integrationS = 0.1;
    }

    /**
     * Set the 20-bit threshold window.
     * @param {number} low - Lower threshold (0-1048575).
     * @param {number} high - Upper threshold (0-1048575).
     * @returns {Promise<void>}
     */
    async setThresholds(low, high) {
        if (low < 0 || high < 0 || low > 0xFFFFF || high > 0xFFFFF || low > high) {
            throw new RangeError('thresholds must satisfy 0 <= low <= high <= 1048575');
        }
        await this._conn.writeReg(REG_THRES_LOW, Buffer.from([low & 0xFF, (low >> 8) & 0xFF, (low >> 16) & 0x0F]));
        await this._conn.writeReg(REG_THRES_UP, Buffer.from([high & 0xFF, (high >> 8) & 0xFF, (high >> 16) & 0x0F]));
    }

    /** @returns {Promise<{low: number, high: number}>} Threshold window in counts. */
    async getThresholds() {
        const low = await this._conn.readReg(REG_THRES_LOW, 3);
        const high = await this._conn.readReg(REG_THRES_UP, 3);
        return { low: _count(low[0], low[1], low[2]), high: _count(high[0], high[1], high[2]) };
    }

    /**
     * Select the channel the interrupt monitors.
     * @param {string} channel - 'ir', 'green', 'red' or 'blue'.
     * @returns {Promise<void>}
     */
    async setInterruptChannel(channel) {
        if (!(channel in CHANNEL_CODE)) throw new RangeError("channel must be 'ir', 'green', 'red' or 'blue'");
        await this._updateReg(REG_INT_CFG, CHANNEL_CODE[channel] << 4, INT_SEL_MASK);
    }

    /**
     * Set the number of consecutive events before the interrupt asserts.
     * @param {number} count - 1-16.
     * @returns {Promise<void>}
     */
    async setPersistence(count) {
        if (count < 1 || count > 16) throw new RangeError('count must be 1-16');
        await this._writeReg(REG_INT_PERSISTENCE, (count - 1) << 4);
    }

    /**
     * Set the variation-interrupt threshold.
     * @param {number} counts - 8, 16, 32, ... 1024.
     * @returns {Promise<void>}
     */
    async setVariance(counts) {
        for (let n = 0; n < 8; n++) {
            if (counts === (8 << n)) { await this._writeReg(REG_THRES_VAR, n); return; }
        }
        throw new RangeError('counts must be 8, 16, 32, ... 1024');
    }

    /**
     * Select threshold or variation mode and enable the interrupt.
     * @param {number} [source] - `SOURCE_THRESHOLD` (default) or `SOURCE_VARIATION`.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source = APDS9250Full.SOURCE_THRESHOLD) {
        const mode = source === APDS9250Full.SOURCE_VARIATION ? INT_VAR_MODE : 0;
        await this._updateReg(REG_INT_CFG, INT_EN | mode, INT_EN | INT_VAR_MODE);
    }

    /**
     * Disable the interrupt (clears LS_INT_EN; both sources share the bit).
     * @returns {Promise<void>}
     */
    async disableInterrupt() { await this._updateReg(REG_INT_CFG, 0, INT_EN); }

    /**
     * Read MAIN_STATUS (clearing the flags) and return it.
     * @returns {Promise<number>} Status byte; `STATUS_INTERRUPT` is set if the condition was fulfilled.
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
     * Subscribe to INT assertions. `callback(status)` is called with the
     * MAIN_STATUS byte after the driver has read (and thereby cleared) it.
     * Delivery is via the INT line (options.intPin or connection.intPin,
     * falling edge) or a 50 ms polling timer.
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
            if ((status & STATUS_INTERRUPT) && this._callback) this._callback(status);
        } finally {
            this._polling = false;
        }
    }
}

module.exports = { APDS9250Minimal, APDS9250Full };
