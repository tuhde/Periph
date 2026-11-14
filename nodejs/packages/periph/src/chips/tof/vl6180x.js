'use strict';

/**
 * VL6180X — proximity and ambient-light sensor (STMicroelectronics).
 *
 * VCSEL emitter, SPAD time-of-flight receiver and ambient light sensor (ALS)
 * behind one aperture. Measures absolute distance 0-100 mm (8-bit result in
 * mm) and ambient light (16-bit count, converted to lux). Registers use a
 * 16-bit big-endian index; 16-bit results and thresholds are MSB first.
 *
 * The connection must be created with `regBytes` = 2 and point at address
 * 0x29. The mandatory private-register settings applied by `init()` come from
 * ST application note AN4545 and are not documented in the datasheet.
 */

const _REG_MODEL_ID = 0x000;
const _REG_MODEL_REV_MAJOR = 0x001;
const _REG_MODEL_REV_MINOR = 0x002;
const _REG_MODE_GPIO1 = 0x011;
const _REG_INTERRUPT_CONFIG = 0x014;
const _REG_INTERRUPT_CLEAR = 0x015;
const _REG_FRESH_OUT_OF_RESET = 0x016;
const _REG_GROUPED_PARAMETER_HOLD = 0x017;
const _REG_RANGE_START = 0x018;
const _REG_RANGE_THRESH_HIGH = 0x019;
const _REG_RANGE_THRESH_LOW = 0x01A;
const _REG_RANGE_INTERMEASUREMENT = 0x01B;
const _REG_RANGE_MAX_CONVERGENCE = 0x01C;
const _REG_RANGE_CROSSTALK_RATE = 0x01E;
const _REG_RANGE_OFFSET = 0x024;
const _REG_VHV_RECALIBRATE = 0x02E;
const _REG_VHV_REPEAT_RATE = 0x031;
const _REG_ALS_START = 0x038;
const _REG_ALS_THRESH_HIGH = 0x03A;
const _REG_ALS_THRESH_LOW = 0x03C;
const _REG_ALS_INTERMEASUREMENT = 0x03E;
const _REG_ALS_GAIN = 0x03F;
const _REG_ALS_INTEGRATION = 0x040;
const _REG_RANGE_STATUS = 0x04D;
const _REG_INTERRUPT_STATUS = 0x04F;
const _REG_ALS_VAL = 0x050;
const _REG_HISTORY_BUFFER = 0x052;
const _REG_RANGE_VAL = 0x062;
const _REG_AVERAGING_PERIOD = 0x10A;
const _REG_RESULT_SCALER = 0x120;
const _REG_I2C_ADDRESS = 0x212;
const _REG_INTERLEAVED_ENABLE = 0x2A3;

// Mandatory private-register settings (ST AN4545), applied while
// SYSTEM__FRESH_OUT_OF_RESET = 1.
const _PRIVATE_SETTINGS = [
    [0x0207, 0x01], [0x0208, 0x01], [0x0096, 0x00], [0x0097, 0xFD],
    [0x00E3, 0x01], [0x00E4, 0x03], [0x00E5, 0x02], [0x00E6, 0x01],
    [0x00E7, 0x03], [0x00F5, 0x02], [0x00D9, 0x05], [0x00DB, 0xCE],
    [0x00DC, 0x03], [0x00DD, 0xF8], [0x009F, 0x00], [0x00A3, 0x3C],
    [0x00B7, 0x00], [0x00BB, 0x3C], [0x00B2, 0x09], [0x00CA, 0x09],
    [0x0198, 0x01], [0x01B0, 0x17], [0x01AD, 0x00], [0x00FF, 0x05],
    [0x0100, 0x05], [0x0199, 0x05], [0x01A6, 0x1B], [0x01AC, 0x3E],
    [0x01A7, 0x1F], [0x0030, 0x00],
];

// Recommended public settings (datasheet / AN4545).
const _RECOMMENDED_SETTINGS = [
    [_REG_MODE_GPIO1, 0x10], [_REG_AVERAGING_PERIOD, 0x30], [_REG_ALS_GAIN, 0x46],
    [_REG_VHV_REPEAT_RATE, 0xFF], [_REG_VHV_RECALIBRATE, 0x01],
    [_REG_RANGE_INTERMEASUREMENT, 0x09], [_REG_ALS_INTERMEASUREMENT, 0x31],
    [_REG_INTERRUPT_CONFIG, 0x24],
];

// Actual ALS gain per gain code 0-7.
const _GAIN = [20.0, 10.32, 5.21, 2.60, 1.72, 1.28, 1.01, 40.0];

const _RANGE_TIMEOUT_MS = 100;
const _VHV_TIMEOUT_MS = 100;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * VL6180X proximity sensor — minimal interface.
 *
 * `init()` waits 1 ms for boot, then — only while SYSTEM__FRESH_OUT_OF_RESET
 * is 1 — applies the mandatory private settings, then the recommended
 * settings (averaging period 48, max convergence 49 ms, ALS gain 1, 100 ms
 * ALS integration, new-sample interrupt configuration). `readRange()` then
 * takes one single-shot measurement per call.
 */
class VL6180XMinimal {
    /**
     * @param {object} connection - I2C RegisterConnection (regBytes = 2) at 0x29.
     */
    constructor(connection) {
        this._conn = connection;
        this._gainCode = 6;
        this._integrationMs = 100;
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
        this._busy = Promise.resolve();
    }

    async _rd8(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }

    async _wr8(reg, value) {
        await this._conn.writeReg(reg, Buffer.from([value & 0xFF]));
    }

    async _rd16(reg) {
        const d = await this._conn.readReg(reg, 2);
        return (d[0] << 8) | d[1];
    }

    async _wr16(reg, value) {
        await this._conn.writeReg(reg, Buffer.from([(value >> 8) & 0xFF, value & 0xFF]));
    }

    // Serialises multi-register sequences against the interrupt poller.
    _locked(fn) {
        const run = this._busy.then(fn);
        this._busy = run.catch(() => {});
        return run;
    }

    async _waitSample(shift, timeoutMs) {
        const start = Date.now();
        for (;;) {
            if ((((await this._rd8(_REG_INTERRUPT_STATUS)) >> shift) & 0x07) === 4) return true;
            if (Date.now() - start > timeoutMs) return false;
            await _sleep(1);
        }
    }

    /**
     * Run the initialization sequence (1 ms boot wait, private settings while
     * fresh out of reset, recommended settings).
     * @returns {Promise<void>}
     */
    async init() {
        await _sleep(1);
        if ((await this._rd8(_REG_FRESH_OUT_OF_RESET)) === 1) {
            for (const [reg, value] of _PRIVATE_SETTINGS) await this._wr8(reg, value);
            await this._wr8(_REG_FRESH_OUT_OF_RESET, 0);
        }
        for (const [reg, value] of _RECOMMENDED_SETTINGS) await this._wr8(reg, value);
        await this._wr16(_REG_ALS_INTEGRATION, 0x0063);
        this._gainCode = 6;
        this._integrationMs = 100;
    }

    async _readRangeResult() {
        const error = (await this._rd8(_REG_RANGE_STATUS)) >> 4;
        const mm = await this._rd8(_REG_RANGE_VAL);
        await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
        return { mm, error };
    }

    async _measureRange() {
        await this._wr8(_REG_RANGE_START, 0x01);
        if (!(await this._waitSample(0, _RANGE_TIMEOUT_MS))) {
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
            throw new Error('VL6180X timeout waiting for range result');
        }
        return this._readRangeResult();
    }

    /**
     * Take one single-shot range measurement.
     * @returns {Promise<number>} Distance in mm (0-255; reliable up to about 100 mm).
     * @throws {Error} On a non-zero range error code or if no result arrives within 100 ms.
     */
    async readRange() {
        const { mm, error } = await this._locked(() => this._measureRange());
        if (error !== 0) throw new Error(`VL6180X range error ${error}`);
        return mm;
    }

    async _pollInterruptStatus() {
        return this._locked(async () => {
            const status = await this._rd8(_REG_INTERRUPT_STATUS);
            let sources = 0;
            if (status & 0x07) sources |= VL6180XMinimal.SOURCE_RANGE;
            if (status & 0x38) sources |= VL6180XMinimal.SOURCE_ALS;
            if (status & 0xC0) sources |= VL6180XMinimal.SOURCE_ERROR;
            if (sources) await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
            return sources;
        });
    }
}

/** Default 7-bit I2C address. */
VL6180XMinimal.I2C_ADDRESS = 0x29;
/** Expected model ID. */
VL6180XMinimal.MODEL_ID = 0xB4;
/** Interrupt source: range event. */
VL6180XMinimal.SOURCE_RANGE = 0x01;
/** Interrupt source: ALS event. */
VL6180XMinimal.SOURCE_ALS = 0x02;
/** Interrupt source: error event. */
VL6180XMinimal.SOURCE_ERROR = 0x04;
/** Interrupt mode: value below the low threshold. */
VL6180XMinimal.MODE_LEVEL_LOW = 1;
/** Interrupt mode: value above the high threshold. */
VL6180XMinimal.MODE_LEVEL_HIGH = 2;
/** Interrupt mode: value outside the threshold window. */
VL6180XMinimal.MODE_OUT_OF_WINDOW = 3;
/** Interrupt mode: new sample ready (default). */
VL6180XMinimal.MODE_NEW_SAMPLE_READY = 4;

/**
 * VL6180X full interface — extends Minimal with ALS (lux), continuous and
 * interleaved modes, thresholds, gain / integration / scaler, convergence and
 * averaging, range offset and crosstalk, VHV recalibration, address change,
 * identification, the history buffer and the Level-2 interrupt API.
 */
class VL6180XFull extends VL6180XMinimal {
    /**
     * Take one single-shot range measurement without throwing on range errors.
     * @returns {Promise<{mm: number, error: number}>} Range in mm and the error code (0 = valid).
     * @throws {Error} If no result arrives within 100 ms.
     */
    async readRangeStatus() {
        return this._locked(() => this._measureRange());
    }

    /**
     * Start continuous ranging.
     * @param {number} periodMs - Period in ms, 10-2550 in 10 ms steps.
     * @throws {RangeError} If out of range.
     */
    async startContinuousRange(periodMs) {
        if (periodMs < 10 || periodMs > 2550) throw new RangeError('period must be 10 to 2550 ms');
        await this._locked(async () => {
            await this._wr8(_REG_RANGE_INTERMEASUREMENT, Math.floor(periodMs / 10) - 1);
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
            await this._wr8(_REG_RANGE_START, 0x03);
        });
    }

    /** Stop continuous ranging (the start/stop bit toggles the running mode). */
    async stopContinuousRange() {
        await this._wr8(_REG_RANGE_START, 0x01);
    }

    /**
     * Wait for the next continuous-mode range sample and read it.
     * @returns {Promise<number>} Distance in mm.
     * @throws {Error} On a non-zero range error code or a timeout.
     */
    async readRangeContinuous() {
        const { mm, error } = await this._locked(async () => {
            const period = ((await this._rd8(_REG_RANGE_INTERMEASUREMENT)) + 1) * 10;
            if (!(await this._waitSample(0, _RANGE_TIMEOUT_MS + period))) {
                throw new Error('VL6180X timeout waiting for range sample');
            }
            return this._readRangeResult();
        });
        if (error !== 0) throw new Error(`VL6180X range error ${error}`);
        return mm;
    }

    /**
     * Take one single-shot ALS measurement.
     * @returns {Promise<number>} 16-bit ALS count.
     * @throws {Error} If no result arrives within the integration time + 100 ms.
     */
    async readAlsRaw() {
        return this._locked(async () => {
            await this._wr8(_REG_ALS_START, 0x01);
            if (!(await this._waitSample(3, this._integrationMs + 100))) {
                await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
                throw new Error('VL6180X timeout waiting for ALS result');
            }
            const raw = await this._rd16(_REG_ALS_VAL);
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
            return raw;
        });
    }

    /**
     * Take one single-shot ALS measurement and convert it to lux:
     * 0.32 * (count / gain) * (100 / integration_ms), actual gain of the current code.
     * @returns {Promise<number>} Illuminance in lux (without cover glass).
     */
    async readLux() {
        const raw = await this.readAlsRaw();
        return 0.32 * (raw / _GAIN[this._gainCode]) * (100.0 / this._integrationMs);
    }

    /**
     * Start continuous ALS measurements.
     * @param {number} periodMs - Period in ms, 10-2550 in 10 ms steps.
     * @throws {RangeError} If out of range.
     */
    async startContinuousAls(periodMs) {
        if (periodMs < 10 || periodMs > 2550) throw new RangeError('period must be 10 to 2550 ms');
        await this._locked(async () => {
            await this._wr8(_REG_ALS_INTERMEASUREMENT, Math.floor(periodMs / 10) - 1);
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x07);
            await this._wr8(_REG_ALS_START, 0x03);
        });
    }

    /** Stop continuous ALS measurements (the start/stop bit toggles the running mode). */
    async stopContinuousAls() {
        await this._wr8(_REG_ALS_START, 0x01);
    }

    /**
     * Enable or disable interleaved ALS + range mode (started with startContinuousAls()).
     * @param {boolean} on
     */
    async setInterleaved(on) {
        await this._wr8(_REG_INTERLEAVED_ENABLE, on ? 1 : 0);
    }

    /**
     * Set the ALS analogue gain.
     * @param {number} code - 0-7 (0 = 20, 1 = 10, 2 = 5, 3 = 2.5, 4 = 1.67, 5 = 1.25, 6 = 1, 7 = 40).
     * @throws {RangeError} If out of range.
     */
    async setAlsGain(code) {
        if (code < 0 || code > 7) throw new RangeError('gain code must be 0 to 7');
        await this._locked(async () => {
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 1);
            await this._wr8(_REG_ALS_GAIN, 0x40 | code);
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 0);
            this._gainCode = code;
        });
    }

    /**
     * Set the ALS integration time.
     * @param {number} ms - 1-512 ms.
     * @throws {RangeError} If out of range.
     */
    async setAlsIntegrationTime(ms) {
        if (ms < 1 || ms > 512) throw new RangeError('integration time must be 1 to 512 ms');
        await this._locked(async () => {
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 1);
            await this._wr16(_REG_ALS_INTEGRATION, ms - 1);
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 0);
            this._integrationMs = ms;
        });
    }

    /**
     * Set the ALS result scaler (FIRMWARE__RESULT_SCALER).
     * @param {number} scaler - 1-16.
     * @throws {RangeError} If out of range.
     */
    async setAlsScaler(scaler) {
        if (scaler < 1 || scaler > 16) throw new RangeError('scaler must be 1 to 16');
        await this._wr8(_REG_RESULT_SCALER, scaler);
    }

    /**
     * Set the range interrupt thresholds (grouped-parameter-hold protocol).
     * @param {number} low - Low threshold in mm, 0-255.
     * @param {number} high - High threshold in mm, low <= high <= 255.
     * @throws {RangeError} If out of range.
     */
    async setRangeThresholds(low, high) {
        if (low < 0 || high > 255 || low > high) throw new RangeError('thresholds must satisfy 0 <= low <= high <= 255');
        await this._locked(async () => {
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 1);
            await this._wr8(_REG_RANGE_THRESH_HIGH, high);
            await this._wr8(_REG_RANGE_THRESH_LOW, low);
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 0);
        });
    }

    /**
     * Set the ALS interrupt thresholds (grouped-parameter-hold protocol).
     * @param {number} low - Low threshold, ALS counts.
     * @param {number} high - High threshold, low <= high <= 65535.
     * @throws {RangeError} If out of range.
     */
    async setAlsThresholds(low, high) {
        if (low < 0 || high > 65535 || low > high) throw new RangeError('thresholds must satisfy 0 <= low <= high <= 65535');
        await this._locked(async () => {
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 1);
            await this._wr16(_REG_ALS_THRESH_HIGH, high);
            await this._wr16(_REG_ALS_THRESH_LOW, low);
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 0);
        });
    }

    /**
     * Set the maximum ranging convergence time.
     * @param {number} ms - 1-63 ms.
     * @throws {RangeError} If out of range.
     */
    async setMaxConvergenceTime(ms) {
        if (ms < 1 || ms > 63) throw new RangeError('convergence time must be 1 to 63 ms');
        await this._wr8(_REG_RANGE_MAX_CONVERGENCE, ms);
    }

    /**
     * Set the ranging averaging sample period.
     * @param {number} units - 0-255; about 64.5 us per unit (48 is the default).
     * @throws {RangeError} If out of range.
     */
    async setAveragingPeriod(units) {
        if (units < 0 || units > 255) throw new RangeError('averaging period must be 0 to 255');
        await this._wr8(_REG_AVERAGING_PERIOD, units);
    }

    /**
     * Set the part-to-part range offset.
     * @param {number} mm - -128 to 127 (two's complement on the chip).
     * @throws {RangeError} If out of range.
     */
    async setRangeOffset(mm) {
        if (mm < -128 || mm > 127) throw new RangeError('offset must be -128 to 127 mm');
        await this._wr8(_REG_RANGE_OFFSET, mm & 0xFF);
    }

    /**
     * Read the part-to-part range offset.
     * @returns {Promise<number>} Offset in mm (signed).
     */
    async getRangeOffset() {
        const v = await this._rd8(_REG_RANGE_OFFSET);
        return v & 0x80 ? v - 256 : v;
    }

    /**
     * Set the crosstalk compensation rate.
     * @param {number} rate - Rate in Mcps (stored as 9.7 fixed point).
     * @throws {RangeError} If out of range.
     */
    async setCrosstalkRate(rate) {
        const code = Math.floor(rate * 128 + 0.5);
        if (!(rate >= 0) || code > 0xFFFF) throw new RangeError('crosstalk rate out of range');
        await this._wr16(_REG_RANGE_CROSSTALK_RATE, code);
    }

    /**
     * Start a VHV recalibration and wait for it to finish.
     * @throws {Error} If the start bit does not clear within 100 ms.
     */
    async recalibrateVhv() {
        await this._locked(async () => {
            await this._wr8(_REG_VHV_RECALIBRATE, 0x01);
            const start = Date.now();
            while (((await this._rd8(_REG_VHV_RECALIBRATE)) & 0x01) !== 0) {
                if (Date.now() - start > _VHV_TIMEOUT_MS) throw new Error('VL6180X timeout waiting for VHV recalibration');
                await _sleep(1);
            }
        });
    }

    /**
     * Change the chip's I2C address (volatile). The connection passed at
     * construction still points at the old address; create a new one.
     * @param {number} address - New 7-bit address, 0x08-0x77.
     * @throws {RangeError} If out of range.
     */
    async setAddress(address) {
        if (address < 0x08 || address > 0x77) throw new RangeError('address must be 0x08 to 0x77');
        await this._wr8(_REG_I2C_ADDRESS, address & 0x7F);
    }

    /**
     * Read the model ID.
     * @returns {Promise<number>} 0xB4 for the VL6180X.
     */
    async modelId() {
        return this._rd8(_REG_MODEL_ID);
    }

    /**
     * Read the model revision.
     * @returns {Promise<{major: number, minor: number}>} Bits 2:0 of each revision register.
     */
    async revision() {
        return {
            major: (await this._rd8(_REG_MODEL_REV_MAJOR)) & 0x07,
            minor: (await this._rd8(_REG_MODEL_REV_MINOR)) & 0x07,
        };
    }

    /**
     * Read the eight-entry history buffer (enable it through the history control register first).
     * @returns {Promise<number[]>} Eight 16-bit entries.
     */
    async readHistory() {
        const d = await this._conn.readReg(_REG_HISTORY_BUFFER, 16);
        const out = [];
        for (let i = 0; i < 8; i++) out.push((d[2 * i] << 8) | d[2 * i + 1]);
        return out;
    }

    async _setInterruptMode(source, mode) {
        let shift;
        if (source === VL6180XMinimal.SOURCE_RANGE) shift = 0;
        else if (source === VL6180XMinimal.SOURCE_ALS) shift = 3;
        else throw new RangeError('source must be SOURCE_RANGE or SOURCE_ALS');
        await this._locked(async () => {
            let value = await this._rd8(_REG_INTERRUPT_CONFIG);
            value = (value & ~(0x07 << shift) & 0xFF) | (mode << shift);
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 1);
            await this._wr8(_REG_INTERRUPT_CONFIG, value);
            await this._wr8(_REG_GROUPED_PARAMETER_HOLD, 0);
        });
    }

    /**
     * Select the interrupt mode of the range or ALS source.
     * @param {number} source - SOURCE_RANGE or SOURCE_ALS.
     * @param {number} mode - MODE_LEVEL_LOW, MODE_LEVEL_HIGH, MODE_OUT_OF_WINDOW or MODE_NEW_SAMPLE_READY.
     * @throws {RangeError} If source or mode is invalid.
     */
    async enableInterrupt(source, mode) {
        if (mode < 1 || mode > 4) throw new RangeError('mode must be 1 to 4');
        await this._setInterruptMode(source, mode);
    }

    /**
     * Disable the interrupt of the range or ALS source (mode 0).
     * @param {number} source - SOURCE_RANGE or SOURCE_ALS.
     * @throws {RangeError} If source is invalid.
     */
    async disableInterrupt(source) {
        await this._setInterruptMode(source, 0);
    }

    /**
     * Read the interrupt status and clear all pending sources.
     * @returns {Promise<number>} Bit mask of SOURCE_RANGE, SOURCE_ALS, SOURCE_ERROR (0 if none).
     */
    async pollInterrupt() {
        return this._pollInterruptStatus();
    }

    /**
     * Subscribe to GPIO1 interrupt events: falling edge on `connection.intPin`,
     * otherwise a 5 ms polling timer. Pending sources are cleared before the
     * callback runs.
     * @param {function(number): void} callback - Called with the SOURCE_* bit mask.
     */
    async onInterrupt(callback) {
        this._callback = callback;
        if (this._conn.intPin) {
            this._edgeHandler = async () => {
                const status = await this._pollInterruptStatus();
                if (status && this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this._pollInterruptStatus();
                    if (status && this._callback) this._callback(status);
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    /** Unsubscribe and stop delivery. */
    async offInterrupt() {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
            this._edgeHandler = null;
        }
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }
}

module.exports = { VL6180XMinimal, VL6180XFull };
