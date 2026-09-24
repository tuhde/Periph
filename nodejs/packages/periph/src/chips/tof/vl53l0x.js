'use strict';

const _REG_SYSRANGE_START            = 0x00;
const _REG_SYSTEM_SEQUENCE_CONFIG    = 0x01;
const _REG_SYSTEM_INTERMEASUREMENT   = 0x04;
const _REG_SYSTEM_INTERRUPT_CONFIG   = 0x0A;
const _REG_SYSTEM_INTERRUPT_CLEAR    = 0x0B;
const _REG_SYSTEM_THRESH_HIGH        = 0x0C;
const _REG_SYSTEM_THRESH_LOW         = 0x0E;
const _REG_RESULT_INTERRUPT_STATUS   = 0x13;
const _REG_RESULT_RANGE_STATUS       = 0x14;
const _REG_CROSSTALK_COMPENSATION    = 0x20;
const _REG_PART_TO_PART_RANGE_OFFSET = 0x28;
const _REG_PHASECAL_CONFIG_TIMEOUT   = 0x30;
const _REG_GLOBAL_CONFIG_VCSEL_WIDTH = 0x32;
const _REG_FINAL_MIN_COUNT_RATE_RTN  = 0x44;
const _REG_MSRC_CONFIG_TIMEOUT       = 0x46;
const _REG_FINAL_VALID_PHASE_LOW     = 0x47;
const _REG_FINAL_VALID_PHASE_HIGH    = 0x48;
const _REG_DYNAMIC_SPAD_NUM_REQ      = 0x4E;
const _REG_DYNAMIC_SPAD_START_OFFSET = 0x4F;
const _REG_PRE_RANGE_VCSEL_PERIOD    = 0x50;
const _REG_PRE_RANGE_TIMEOUT         = 0x51;
const _REG_PRE_VALID_PHASE_LOW       = 0x56;
const _REG_PRE_VALID_PHASE_HIGH      = 0x57;
const _REG_MSRC_CONFIG_CONTROL       = 0x60;
const _REG_FINAL_RANGE_VCSEL_PERIOD  = 0x70;
const _REG_FINAL_RANGE_TIMEOUT       = 0x71;
const _REG_POWER_FORCE               = 0x80;
const _REG_GPIO_HV_MUX_ACTIVE_HIGH   = 0x84;
const _REG_I2C_MODE                  = 0x88;
const _REG_VHV_PAD_EXTSUP_HV         = 0x89;
const _REG_I2C_SLAVE_DEVICE_ADDRESS  = 0x8A;
const _REG_STOP_VARIABLE             = 0x91;
const _REG_SPAD_ENABLES_REF_0        = 0xB0;
const _REG_REF_EN_START_SELECT       = 0xB6;
const _REG_MODEL_ID                  = 0xC0;
const _REG_REVISION_ID               = 0xC2;
const _REG_OSC_CALIBRATE_VAL         = 0xF8;
const _REG_PAGE_SELECT               = 0xFF;

const _SEQ_TCC         = 0x10;
const _SEQ_DSS         = 0x08;
const _SEQ_MSRC        = 0x04;
const _SEQ_PRE_RANGE   = 0x40;
const _SEQ_FINAL_RANGE = 0x80;
const _SEQ_OPERATING   = 0xE8;

const _MODEL_ID = 0xEE;
const _RANGE_STATUS_VALID = 11;
const _TIMEOUT_MS = 500;
const _MIN_TIMING_BUDGET_US = 20000;

// Timing-budget overheads, µs.
const _START_OVERHEAD       = 1910;
const _END_OVERHEAD         = 960;
const _MSRC_OVERHEAD        = 660;
const _TCC_OVERHEAD         = 590;
const _DSS_OVERHEAD         = 690;
const _PRE_RANGE_OVERHEAD   = 660;
const _FINAL_RANGE_OVERHEAD = 550;

// ST DefaultTuningSettings — opaque, written verbatim in this order as (reg, value) pairs.
const _TUNING = [
    0xFF, 0x01, 0x00, 0x00, 0xFF, 0x00, 0x09, 0x00, 0x10, 0x00, 0x11, 0x00, 0x24, 0x01, 0x25, 0xFF, 0x75, 0x00,
    0xFF, 0x01, 0x4E, 0x2C, 0x48, 0x00, 0x30, 0x20, 0xFF, 0x00, 0x30, 0x09, 0x54, 0x00, 0x31, 0x04, 0x32, 0x03,
    0x40, 0x83, 0x46, 0x25, 0x60, 0x00, 0x27, 0x00, 0x50, 0x06, 0x51, 0x00, 0x52, 0x96, 0x56, 0x08, 0x57, 0x30,
    0x61, 0x00, 0x62, 0x00, 0x64, 0x00, 0x65, 0x00, 0x66, 0xA0, 0xFF, 0x01, 0x22, 0x32, 0x47, 0x14, 0x49, 0xFF,
    0x4A, 0x00, 0xFF, 0x00, 0x7A, 0x0A, 0x7B, 0x00, 0x78, 0x21, 0xFF, 0x01, 0x23, 0x34, 0x42, 0x00, 0x44, 0xFF,
    0x45, 0x26, 0x46, 0x05, 0x40, 0x40, 0x0E, 0x06, 0x20, 0x1A, 0x43, 0x40, 0xFF, 0x00, 0x34, 0x03, 0x35, 0x44,
    0xFF, 0x01, 0x31, 0x04, 0x4B, 0x09, 0x4C, 0x05, 0x4D, 0x04, 0xFF, 0x00, 0x44, 0x00, 0x45, 0x20, 0x47, 0x08,
    0x48, 0x28, 0x67, 0x00, 0x70, 0x04, 0x71, 0x01, 0x72, 0xFE, 0x76, 0x00, 0x77, 0x00, 0xFF, 0x01, 0x0D, 0x01,
    0xFF, 0x00, 0x80, 0x01, 0x01, 0xF8, 0xFF, 0x01, 0x8E, 0x01, 0x00, 0x01, 0xFF, 0x00, 0x80, 0x00,
];

// Pre-range PRE_RANGE_CONFIG_VALID_PHASE_HIGH per VCSEL period (PCLKs).
const _PRE_PHASE_HIGH = { 12: 0x18, 14: 0x30, 16: 0x40, 18: 0x50 };

// Final-range [VALID_PHASE_HIGH, VALID_PHASE_LOW, VCSEL_WIDTH, PHASECAL_CONFIG_TIMEOUT,
// page-1 PHASECAL_LIM] per VCSEL period (PCLKs).
const _FINAL_PHASE = {
    8:  [0x10, 0x08, 0x02, 0x0C, 0x30],
    10: [0x28, 0x08, 0x03, 0x09, 0x20],
    12: [0x38, 0x08, 0x03, 0x08, 0x20],
    14: [0x48, 0x08, 0x03, 0x07, 0x20],
};

// [signal-rate limit MCPS, pre-range PCLKs, final-range PCLKs, timing budget µs].
const _PROFILES = {
    default:       [0.25, 14, 10, 33000],
    long_range:    [0.10, 18, 14, 33000],
    high_speed:    [0.25, 14, 10, 20000],
    high_accuracy: [0.25, 14, 10, 200000],
};

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const _decodeVcsel = (reg) => (reg + 1) << 1;
const _encodeVcsel = (pclks) => (pclks >> 1) - 1;
const _macroPeriodNs = (pclks) => Math.floor((2304 * pclks * 1655 + 500) / 1000);
const _mclksToUs = (mclks, pclks) => Math.floor((mclks * _macroPeriodNs(pclks) + 500) / 1000);

function _usToMclks(us, pclks) {
    const period = _macroPeriodNs(pclks);
    return Math.floor((us * 1000 + Math.floor(period / 2)) / period);
}

const _decodeTimeout = (reg16) => (reg16 & 0xFF) * 2 ** (reg16 >> 8) + 1;

function _encodeTimeout(mclks) {
    if (mclks <= 0) return 0;
    let ls = mclks - 1;
    let ms = 0;
    while (ls > 0xFF) {
        ls >>>= 1;
        ms++;
    }
    return (ms << 8) | (ls & 0xFF);
}

/**
 * VL53L0X Time-of-Flight laser-ranging sensor (STMicroelectronics) — minimal
 * interface.
 *
 * 940 nm VCSEL emitter, SPAD receiving array and an embedded ranging
 * microcontroller measuring absolute distance up to ~2 m, largely
 * independent of target reflectance. The datasheet has no register map:
 * registers, the tuning table and the init/calibration sequences follow ST's
 * STSW-IMG005 API (the same derivation as Pololu's VL53L0X library).
 * Multi-byte registers are big-endian.
 *
 * The constructor starts the full initialization sequence (boot wait, model
 * ID check, 2V8 I/O mode, reference SPADs, default tuning, GPIO1 = new
 * sample ready active low, ~33 ms timing budget, VHV + phase reference
 * calibration) and leaves the chip idle. JS constructors cannot be async, so
 * every public method awaits it first — `await sensor.init()` right after
 * construction to surface a wrong or absent device at a predictable point.
 * If the connection has an enPin (XSHUT), it is driven high first.
 *
 * Multiple sensors on one bus: all power up at 0x29. Hold every sensor's
 * XSHUT low (each connection disabled), then for each sensor in turn enable
 * its XSHUT, construct a driver on 0x29, call `setAddress(new)`, and build
 * the real driver on a connection at the new address. The new address is
 * volatile — it reverts to 0x29 on power-up or an XSHUT low pulse.
 */
class VL53L0XMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x29).
     */
    constructor(connection) {
        this._conn = connection;
        this._stopVariable = 0;
        this._rangeStatus = 0;
        this._timingBudgetUs = 0;
        // Serializes multi-register sequences against the Full class's
        // interrupt polling timer (page-select windows, calibration and
        // data-ready polls must not interleave with a status read/clear).
        this._queue = Promise.resolve();
        this._ready = this._init();
        // The rejection is re-raised by every public method; mark it handled
        // here so it never surfaces as an unhandled rejection on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the initialization sequence started by the constructor.
     * Optional — every other method awaits it too.
     * @returns {Promise<void>}
     * @throws {Error} If the model ID is not 0xEE or an init poll times out (500 ms).
     */
    async init() {
        await this._ready;
    }

    _locked(fn) {
        const run = this._queue.then(fn);
        this._queue = run.catch(() => {});
        return run;
    }

    async _wr(reg, value) {
        await this._conn.write(Buffer.from([reg, value & 0xFF]));
    }

    async _rd(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 1))[0];
    }

    async _wr16(reg, value) {
        await this._conn.write(Buffer.from([reg, (value >> 8) & 0xFF, value & 0xFF]));
    }

    async _rd16(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 2)).readUInt16BE(0);
    }

    async _wr32(reg, value) {
        const buf = Buffer.alloc(5);
        buf[0] = reg;
        buf.writeUInt32BE(value >>> 0, 1);
        await this._conn.write(buf);
    }

    async _wait(reg, mask, untilSet, what) {
        const start = Date.now();
        for (;;) {
            if ((((await this._rd(reg)) & mask) !== 0) === untilSet) return;
            if (Date.now() - start > _TIMEOUT_MS) throw new Error(`VL53L0X timeout waiting for ${what}`);
        }
    }

    async _init() {
        if (this._conn.enPin) await this._conn.enable();
        await _sleep(2);

        const model = await this._rd(_REG_MODEL_ID);
        if (model !== _MODEL_ID) {
            throw new Error('VL53L0X not found: expected model ID 0xEE, got 0x' +
                model.toString(16).padStart(2, '0'));
        }

        // 2V8 I/O mode, standard I²C mode.
        await this._wr(_REG_VHV_PAD_EXTSUP_HV, (await this._rd(_REG_VHV_PAD_EXTSUP_HV)) | 0x01);
        await this._wr(_REG_I2C_MODE, 0x00);

        // Stop variable.
        await this._wr(_REG_POWER_FORCE, 0x01);
        await this._wr(_REG_PAGE_SELECT, 0x01);
        await this._wr(_REG_SYSRANGE_START, 0x00);
        this._stopVariable = await this._rd(_REG_STOP_VARIABLE);
        await this._wr(_REG_SYSRANGE_START, 0x01);
        await this._wr(_REG_PAGE_SELECT, 0x00);
        await this._wr(_REG_POWER_FORCE, 0x00);

        // Disable MSRC and pre-range signal-rate limit checks; 0.25 MCPS limit.
        await this._wr(_REG_MSRC_CONFIG_CONTROL, (await this._rd(_REG_MSRC_CONFIG_CONTROL)) | 0x12);
        await this._wr16(_REG_FINAL_MIN_COUNT_RATE_RTN, 0x0020);
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0xFF);

        const { count, isAperture } = await this._spadInfo();

        // Reference SPADs.
        const refMap = Buffer.from(await this._conn.writeRead(Buffer.from([_REG_SPAD_ENABLES_REF_0]), 6));
        await this._wr(_REG_PAGE_SELECT, 0x01);
        await this._wr(_REG_DYNAMIC_SPAD_START_OFFSET, 0x00);
        await this._wr(_REG_DYNAMIC_SPAD_NUM_REQ, 0x2C);
        await this._wr(_REG_PAGE_SELECT, 0x00);
        await this._wr(_REG_REF_EN_START_SELECT, 0xB4);
        const first = isAperture ? 12 : 0;
        let enabled = 0;
        for (let i = 0; i < 48; i++) {
            const byte = i >> 3;
            const bit = 1 << (i & 7);
            if (i < first || enabled === count) {
                refMap[byte] &= ~bit & 0xFF;
            } else if (refMap[byte] & bit) {
                enabled++;
            }
        }
        await this._conn.write(Buffer.concat([Buffer.from([_REG_SPAD_ENABLES_REF_0]), refMap]));

        // Default tuning settings.
        for (let i = 0; i < _TUNING.length; i += 2) await this._wr(_TUNING[i], _TUNING[i + 1]);

        // GPIO1 = new sample ready, active low.
        await this._wr(_REG_SYSTEM_INTERRUPT_CONFIG, 0x04);
        await this._wr(_REG_GPIO_HV_MUX_ACTIVE_HIGH, (await this._rd(_REG_GPIO_HV_MUX_ACTIVE_HIGH)) & ~0x10 & 0xFF);
        await this._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01);

        const budget = await this._getTimingBudget();
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, _SEQ_OPERATING);
        await this._setTimingBudget(budget);

        await this._refCalibration();
    }

    async _spadInfo() {
        await this._wr(_REG_POWER_FORCE, 0x01);
        await this._wr(_REG_PAGE_SELECT, 0x01);
        await this._wr(_REG_SYSRANGE_START, 0x00);
        await this._wr(_REG_PAGE_SELECT, 0x06);
        await this._wr(0x83, (await this._rd(0x83)) | 0x04);
        await this._wr(_REG_PAGE_SELECT, 0x07);
        await this._wr(0x81, 0x01);
        await this._wr(_REG_POWER_FORCE, 0x01);
        await this._wr(0x94, 0x6B);
        await this._wr(0x83, 0x00);
        await this._wait(0x83, 0xFF, true, 'SPAD info');
        await this._wr(0x83, 0x01);
        const tmp = await this._rd(0x92);
        await this._wr(0x81, 0x00);
        await this._wr(_REG_PAGE_SELECT, 0x06);
        await this._wr(0x83, (await this._rd(0x83)) & ~0x04 & 0xFF);
        await this._wr(_REG_PAGE_SELECT, 0x01);
        await this._wr(_REG_SYSRANGE_START, 0x01);
        await this._wr(_REG_PAGE_SELECT, 0x00);
        await this._wr(_REG_POWER_FORCE, 0x00);
        return { count: tmp & 0x7F, isAperture: ((tmp >> 7) & 0x01) === 1 };
    }

    async _singleRefCalibration(vhvInit) {
        await this._wr(_REG_SYSRANGE_START, 0x01 | vhvInit);
        await this._wait(_REG_RESULT_INTERRUPT_STATUS, 0x07, true, 'reference calibration');
        await this._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01);
        await this._wr(_REG_SYSRANGE_START, 0x00);
    }

    async _refCalibration() {
        const seq = await this._rd(_REG_SYSTEM_SEQUENCE_CONFIG);
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x01);
        await this._singleRefCalibration(0x40);
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x02);
        await this._singleRefCalibration(0x00);
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, seq);
    }

    async _stepTimeouts(enables) {
        const prePclks = _decodeVcsel(await this._rd(_REG_PRE_RANGE_VCSEL_PERIOD));
        const msrcUs = _mclksToUs((await this._rd(_REG_MSRC_CONFIG_TIMEOUT)) + 1, prePclks);
        const preMclks = _decodeTimeout(await this._rd16(_REG_PRE_RANGE_TIMEOUT));
        const preUs = _mclksToUs(preMclks, prePclks);
        const finalPclks = _decodeVcsel(await this._rd(_REG_FINAL_RANGE_VCSEL_PERIOD));
        let finalMclks = _decodeTimeout(await this._rd16(_REG_FINAL_RANGE_TIMEOUT));
        if (enables & _SEQ_PRE_RANGE) finalMclks -= preMclks;
        const finalUs = _mclksToUs(finalMclks, finalPclks);
        return { prePclks, msrcUs, preMclks, preUs, finalPclks, finalUs };
    }

    _fixedOverheadUs(enables, t) {
        let budget = _START_OVERHEAD + _END_OVERHEAD;
        if (enables & _SEQ_TCC) budget += t.msrcUs + _TCC_OVERHEAD;
        if (enables & _SEQ_DSS) budget += 2 * (t.msrcUs + _DSS_OVERHEAD);
        else if (enables & _SEQ_MSRC) budget += t.msrcUs + _MSRC_OVERHEAD;
        if (enables & _SEQ_PRE_RANGE) budget += t.preUs + _PRE_RANGE_OVERHEAD;
        return budget;
    }

    async _getTimingBudget() {
        const enables = await this._rd(_REG_SYSTEM_SEQUENCE_CONFIG);
        const t = await this._stepTimeouts(enables);
        let budget = this._fixedOverheadUs(enables, t);
        if (enables & _SEQ_FINAL_RANGE) budget += t.finalUs + _FINAL_RANGE_OVERHEAD;
        return budget;
    }

    async _setTimingBudget(budgetUs) {
        if (!Number.isInteger(budgetUs) || budgetUs < _MIN_TIMING_BUDGET_US) {
            throw new RangeError(`timing budget must be an integer >= ${_MIN_TIMING_BUDGET_US} us`);
        }
        const enables = await this._rd(_REG_SYSTEM_SEQUENCE_CONFIG);
        const t = await this._stepTimeouts(enables);
        let used = this._fixedOverheadUs(enables, t);
        if (enables & _SEQ_FINAL_RANGE) {
            used += _FINAL_RANGE_OVERHEAD;
            if (used > budgetUs) {
                throw new RangeError(`timing budget ${budgetUs} us is below the enabled steps overhead ${used} us`);
            }
            let finalMclks = _usToMclks(budgetUs - used, t.finalPclks);
            if (enables & _SEQ_PRE_RANGE) finalMclks += t.preMclks;
            await this._wr16(_REG_FINAL_RANGE_TIMEOUT, _encodeTimeout(finalMclks));
        }
        this._timingBudgetUs = budgetUs;
    }

    async _stopVariablePreamble() {
        await this._wr(_REG_POWER_FORCE, 0x01);
        await this._wr(_REG_PAGE_SELECT, 0x01);
        await this._wr(_REG_SYSRANGE_START, 0x00);
        await this._wr(_REG_STOP_VARIABLE, this._stopVariable);
        await this._wr(_REG_SYSRANGE_START, 0x01);
        await this._wr(_REG_PAGE_SELECT, 0x00);
        await this._wr(_REG_POWER_FORCE, 0x00);
    }

    async _readResult() {
        const data = await this._conn.writeRead(Buffer.from([_REG_RESULT_RANGE_STATUS]), 12);
        await this._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01);
        this._rangeStatus = (data[0] & 0x78) >> 3;
        return data;
    }

    async _waitAndRead() {
        await this._wait(_REG_RESULT_INTERRUPT_STATUS, 0x07, true, 'data ready');
        const data = await this._readResult();
        return data.readUInt16BE(10);
    }

    /**
     * Take one single-shot measurement. Blocks for about one timing budget
     * (33 ms by default). Returns the raw range even when the measurement is
     * not valid — typically 8190 or 8191 with no target in range; check
     * `rangeValid()`.
     * @returns {Promise<number>} Distance in mm.
     * @throws {Error} If the measurement does not start or complete within 500 ms.
     */
    async distance() {
        await this._ready;
        return this._locked(async () => {
            await this._stopVariablePreamble();
            await this._wr(_REG_SYSRANGE_START, 0x01);
            await this._wait(_REG_SYSRANGE_START, 0x01, false, 'ranging start');
            return this._waitAndRead();
        });
    }

    /**
     * Report whether the most recent measurement was valid.
     * @returns {Promise<boolean>} True iff the device range status was 11 (range complete).
     */
    async rangeValid() {
        await this._ready;
        return this._rangeStatus === _RANGE_STATUS_VALID;
    }
}

/**
 * VL53L0X full interface — extends Minimal with continuous and timed
 * ranging, the full measurement record, timing budget, signal-rate limit,
 * VCSEL pulse periods, ranging profiles, offset and crosstalk compensation,
 * reference recalibration, address change, distance thresholds,
 * identification, and the Level-2 interrupt API.
 */
class VL53L0XFull extends VL53L0XMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x29).
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    /**
     * Start continuous ranging.
     * @param {number} [periodMs=0] - 0 for back-to-back mode; otherwise timed
     *   mode with this inter-measurement period in ms (should be ≥ the timing budget).
     * @returns {Promise<void>}
     */
    async startContinuous(periodMs = 0) {
        await this._ready;
        return this._locked(async () => {
            await this._stopVariablePreamble();
            if (periodMs > 0) {
                const osc = await this._rd16(_REG_OSC_CALIBRATE_VAL);
                await this._wr32(_REG_SYSTEM_INTERMEASUREMENT, osc !== 0 ? periodMs * osc : periodMs);
                await this._wr(_REG_SYSRANGE_START, 0x04);
            } else {
                await this._wr(_REG_SYSRANGE_START, 0x02);
            }
        });
    }

    /**
     * Stop continuous ranging. Does not wait for a running measurement.
     * @returns {Promise<void>}
     */
    async stopContinuous() {
        await this._ready;
        return this._locked(async () => {
            await this._wr(_REG_SYSRANGE_START, 0x01);
            await this._wr(_REG_PAGE_SELECT, 0x01);
            await this._wr(_REG_SYSRANGE_START, 0x00);
            await this._wr(_REG_STOP_VARIABLE, 0x00);
            await this._wr(_REG_SYSRANGE_START, 0x01);
            await this._wr(_REG_PAGE_SELECT, 0x00);
        });
    }

    /**
     * Wait for the next continuous-mode result and read it.
     * @returns {Promise<number>} Distance in mm (check `rangeValid()`).
     * @throws {Error} If no result arrives within 500 ms.
     */
    async readContinuous() {
        await this._ready;
        return this._locked(async () => {
            return this._waitAndRead();
        });
    }

    /**
     * Report whether a measurement is pending (non-blocking).
     * @returns {Promise<boolean>} True if RESULT_INTERRUPT_STATUS bits 2:0 are non-zero.
     */
    async dataReady() {
        await this._ready;
        return ((await this._rd(_REG_RESULT_INTERRUPT_STATUS)) & 0x07) !== 0;
    }

    /**
     * Read the full result block and clear the interrupt (non-blocking).
     * @returns {Promise<{distanceMm: number, rangeStatus: number, signalRateMcps: number,
     *   ambientRateMcps: number, effectiveSpadCount: number}>} Measurement record
     *   (rates in MCPS).
     */
    async readMeasurement() {
        await this._ready;
        return this._locked(async () => {
            const data = await this._readResult();
            return {
                distanceMm: data.readUInt16BE(10),
                rangeStatus: this._rangeStatus,
                signalRateMcps: data.readUInt16BE(6) / 128,
                ambientRateMcps: data.readUInt16BE(8) / 128,
                effectiveSpadCount: data.readUInt16BE(2) / 256,
            };
        });
    }

    /**
     * Device range status of the most recent measurement.
     * @returns {Promise<number>} 0–15; 11 = valid, 4 = no target (MSRC).
     */
    async rangeStatus() {
        await this._ready;
        return this._rangeStatus;
    }

    /**
     * Set the per-measurement timing budget.
     * @param {number} budgetUs - Budget in µs, ≥ 20000.
     * @returns {Promise<void>}
     * @throws {RangeError} If below 20000 µs or below the enabled steps' overhead.
     */
    async setTimingBudget(budgetUs) {
        await this._ready;
        return this._locked(async () => {
            await this._setTimingBudget(budgetUs);
        });
    }

    /**
     * Compute the timing budget from the current registers.
     * @returns {Promise<number>} Budget in µs.
     */
    async timingBudget() {
        await this._ready;
        return this._locked(async () => {
            return this._getTimingBudget();
        });
    }

    /**
     * Set the final-range return signal-rate limit. Lower values extend range
     * but admit noisier readings.
     * @param {number} limitMcps - Limit in MCPS, 0 to 511.99.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setSignalRateLimit(limitMcps) {
        await this._ready;
        if (!(limitMcps >= 0 && limitMcps <= 511.99)) throw new RangeError('signal rate limit must be 0 to 511.99 MCPS');
        await this._wr16(_REG_FINAL_MIN_COUNT_RATE_RTN, Math.round(limitMcps * 128));
    }

    /**
     * Read the final-range return signal-rate limit.
     * @returns {Promise<number>} Limit in MCPS.
     */
    async signalRateLimit() {
        await this._ready;
        return (await this._rd16(_REG_FINAL_MIN_COUNT_RATE_RTN)) / 128;
    }

    /**
     * Set a VCSEL pulse period, then re-apply the timing budget and redo the
     * phase reference calibration.
     * @param {'pre_range'|'final_range'} periodType - Which period to set.
     * @param {number} pclks - Pre-range 12, 14, 16 or 18; final-range 8, 10, 12 or 14.
     * @returns {Promise<void>}
     * @throws {RangeError} If periodType or pclks is invalid.
     * @throws {Error} If the phase calibration times out.
     */
    async setVcselPulsePeriod(periodType, pclks) {
        await this._ready;
        return this._locked(() => this._setVcselPulsePeriod(periodType, pclks));
    }

    async _setVcselPulsePeriod(periodType, pclks) {
        if (periodType === 'pre_range') {
            if (!(pclks in _PRE_PHASE_HIGH)) throw new RangeError('pre-range VCSEL period must be 12, 14, 16 or 18');
        } else if (periodType === 'final_range') {
            if (!(pclks in _FINAL_PHASE)) throw new RangeError('final-range VCSEL period must be 8, 10, 12 or 14');
        } else {
            throw new RangeError("periodType must be 'pre_range' or 'final_range'");
        }

        const enables = await this._rd(_REG_SYSTEM_SEQUENCE_CONFIG);
        const t = await this._stepTimeouts(enables);
        const vcsel = _encodeVcsel(pclks);

        if (periodType === 'pre_range') {
            await this._wr(_REG_PRE_VALID_PHASE_HIGH, _PRE_PHASE_HIGH[pclks]);
            await this._wr(_REG_PRE_VALID_PHASE_LOW, 0x08);
            await this._wr(_REG_PRE_RANGE_VCSEL_PERIOD, vcsel);
            await this._wr16(_REG_PRE_RANGE_TIMEOUT, _encodeTimeout(_usToMclks(t.preUs, pclks)));
            const m = _usToMclks(t.msrcUs, pclks);
            await this._wr(_REG_MSRC_CONFIG_TIMEOUT, m > 256 ? 255 : m - 1);
        } else {
            const [high, low, width, phasecal, lim] = _FINAL_PHASE[pclks];
            await this._wr(_REG_FINAL_VALID_PHASE_HIGH, high);
            await this._wr(_REG_FINAL_VALID_PHASE_LOW, low);
            await this._wr(_REG_GLOBAL_CONFIG_VCSEL_WIDTH, width);
            await this._wr(_REG_PHASECAL_CONFIG_TIMEOUT, phasecal);
            await this._wr(_REG_PAGE_SELECT, 0x01);
            await this._wr(_REG_PHASECAL_CONFIG_TIMEOUT, lim);
            await this._wr(_REG_PAGE_SELECT, 0x00);
            await this._wr(_REG_FINAL_RANGE_VCSEL_PERIOD, vcsel);
            let f = _usToMclks(t.finalUs, pclks);
            if (enables & _SEQ_PRE_RANGE) f += t.preMclks;
            await this._wr16(_REG_FINAL_RANGE_TIMEOUT, _encodeTimeout(f));
        }

        await this._setTimingBudget(this._timingBudgetUs);
        const seq = await this._rd(_REG_SYSTEM_SEQUENCE_CONFIG);
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, 0x02);
        await this._singleRefCalibration(0x00);
        await this._wr(_REG_SYSTEM_SEQUENCE_CONFIG, seq);
    }

    /**
     * Read a VCSEL pulse period.
     * @param {'pre_range'|'final_range'} periodType - Which period to read.
     * @returns {Promise<number>} Period in PCLKs.
     * @throws {RangeError} If periodType is invalid.
     */
    async vcselPulsePeriod(periodType) {
        await this._ready;
        if (periodType === 'pre_range') return _decodeVcsel(await this._rd(_REG_PRE_RANGE_VCSEL_PERIOD));
        if (periodType === 'final_range') return _decodeVcsel(await this._rd(_REG_FINAL_RANGE_VCSEL_PERIOD));
        throw new RangeError("periodType must be 'pre_range' or 'final_range'");
    }

    /**
     * Apply a ranging profile: signal-rate limit, VCSEL periods (pre first),
     * then timing budget. 'default' 0.25 MCPS 14/10 PCLKs 33 ms; 'long_range'
     * 0.10 MCPS 18/14 PCLKs 33 ms (dark conditions); 'high_speed' 20 ms;
     * 'high_accuracy' 200 ms.
     * @param {'default'|'long_range'|'high_speed'|'high_accuracy'} profile - Profile name.
     * @returns {Promise<void>}
     * @throws {RangeError} If profile is unknown.
     */
    async setProfile(profile) {
        await this._ready;
        if (!Object.prototype.hasOwnProperty.call(_PROFILES, profile)) {
            throw new RangeError("profile must be 'default', 'long_range', 'high_speed' or 'high_accuracy'");
        }
        const [limit, pre, fin, budget] = _PROFILES[profile];
        await this.setSignalRateLimit(limit);
        await this._locked(async () => {
            await this._setVcselPulsePeriod('pre_range', pre);
            await this._setVcselPulsePeriod('final_range', fin);
            await this._setTimingBudget(budget);
        });
    }

    /**
     * Override the part-to-part range offset (volatile).
     * @param {number} offsetMm - Offset in mm, −512.0 to 511.75 (0.25 mm steps).
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setOffset(offsetMm) {
        await this._ready;
        if (!(offsetMm >= -512 && offsetMm <= 511.75)) throw new RangeError('offset must be -512.0 to 511.75 mm');
        const q = offsetMm * 4;
        const steps = q >= 0 ? Math.floor(q + 0.5) : -Math.floor(-q + 0.5);
        await this._wr16(_REG_PART_TO_PART_RANGE_OFFSET, steps & 0x0FFF);
    }

    /**
     * Read the part-to-part range offset.
     * @returns {Promise<number>} Offset in mm.
     */
    async offset() {
        await this._ready;
        let raw = (await this._rd16(_REG_PART_TO_PART_RANGE_OFFSET)) & 0x0FFF;
        if (raw & 0x0800) raw -= 0x1000;
        return raw * 0.25;
    }

    /**
     * Set the crosstalk compensation peak rate (volatile).
     * @param {number} rateMcps - 0 disables; otherwise 0 < rate < 8.0 MCPS,
     *   from the host's own cover-glass calibration.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setCrosstalkCompensation(rateMcps) {
        await this._ready;
        if (!(rateMcps >= 0 && rateMcps < 8)) throw new RangeError('crosstalk rate must be 0 (off) or below 8.0 MCPS');
        await this._wr16(_REG_CROSSTALK_COMPENSATION, Math.round(rateMcps * 8192));
    }

    /**
     * Re-run the VHV and phase reference calibrations. Call in software
     * standby (not while continuous ranging), and after the die temperature
     * changes by more than 8 °C.
     * @returns {Promise<void>}
     * @throws {Error} If a calibration times out.
     */
    async recalibrate() {
        await this._ready;
        return this._locked(async () => {
            await this._refCalibration();
        });
    }

    /**
     * Change the chip's I²C address (volatile). The chip answers on the new
     * address immediately; this driver instance becomes unusable. Construct a
     * new connection at the new address and a new driver.
     * @param {number} address - New 7-bit address, 0x08–0x77.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setAddress(address) {
        await this._ready;
        if (!(address >= 0x08 && address <= 0x77)) throw new RangeError('address must be 0x08 to 0x77');
        await this._wr(_REG_I2C_SLAVE_DEVICE_ADDRESS, address & 0x7F);
    }

    /**
     * Set the distance thresholds used by the threshold interrupt sources.
     * @param {number} lowMm - Low threshold in mm (2 mm resolution).
     * @param {number} highMm - High threshold in mm, lowMm ≤ highMm ≤ 8190.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setInterruptThresholds(lowMm, highMm) {
        await this._ready;
        if (!(lowMm >= 0 && highMm >= lowMm && highMm <= 8190)) {
            throw new RangeError('thresholds must satisfy 0 <= low <= high <= 8190 mm');
        }
        await this._wr16(_REG_SYSTEM_THRESH_LOW, Math.floor(lowMm / 2) & 0x0FFF);
        await this._wr16(_REG_SYSTEM_THRESH_HIGH, Math.floor(highMm / 2) & 0x0FFF);
    }

    /**
     * Read the distance thresholds.
     * @returns {Promise<{lowMm: number, highMm: number}>} Thresholds in mm.
     */
    async interruptThresholds() {
        await this._ready;
        return {
            lowMm: ((await this._rd16(_REG_SYSTEM_THRESH_LOW)) & 0x0FFF) * 2,
            highMm: ((await this._rd16(_REG_SYSTEM_THRESH_HIGH)) & 0x0FFF) * 2,
        };
    }

    /**
     * Read IDENTIFICATION_MODEL_ID.
     * @returns {Promise<number>} 0xEE.
     */
    async modelId() {
        await this._ready;
        return this._rd(_REG_MODEL_ID);
    }

    /**
     * Read IDENTIFICATION_REVISION_ID.
     * @returns {Promise<number>} Revision ID (0x10 on current silicon).
     */
    async revisionId() {
        await this._ready;
        return this._rd(_REG_REVISION_ID);
    }

    /**
     * Select the GPIO1 interrupt source (replaces the active one). With a
     * threshold source active, `dataReady()`/`readContinuous()` only see a
     * pending status when the threshold condition is met.
     * @param {number} source - One of the SOURCE_* constants.
     * @returns {Promise<void>}
     * @throws {RangeError} If source is not 1–4.
     */
    async enableInterrupt(source) {
        await this._ready;
        if (!(source >= VL53L0XFull.SOURCE_LEVEL_LOW && source <= VL53L0XFull.SOURCE_NEW_SAMPLE_READY)) {
            throw new RangeError('source must be one of the SOURCE_* constants');
        }
        await this._wr(_REG_SYSTEM_INTERRUPT_CONFIG, source);
    }

    /**
     * Disable GPIO1 interrupts if source is the active one.
     * @param {number} source - One of the SOURCE_* constants.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        await this._ready;
        if (((await this._rd(_REG_SYSTEM_INTERRUPT_CONFIG)) & 0x07) === source) {
            await this._wr(_REG_SYSTEM_INTERRUPT_CONFIG, 0x00);
        }
    }

    /**
     * Read and clear the pending interrupt status.
     * @returns {Promise<number>} The SOURCE_* value that fired, or 0 if nothing is pending.
     */
    async pollInterrupt() {
        await this._ready;
        return this._locked(async () => {
            const status = (await this._rd(_REG_RESULT_INTERRUPT_STATUS)) & 0x07;
            if (status) await this._wr(_REG_SYSTEM_INTERRUPT_CLEAR, 0x01);
            return status;
        });
    }

    /**
     * Subscribe to GPIO1 interrupt events. With `connection.intPin` wired the
     * callback runs on every falling edge (GPIO1 is active low); otherwise a
     * 5 ms polling timer is used. The driver reads and clears the status
     * before invoking the callback; the polling fallback consumes results, so
     * don't mix it with `readContinuous()`.
     * @param {(status: number) => void} callback - Receives the SOURCE_* value that fired.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this._ready;
        this._callback = callback;
        if (this._conn.intPin) {
            this._edgeHandler = async () => {
                const status = await this.pollInterrupt();
                if (status && this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this.pollInterrupt();
                    if (status && this._callback) this._callback(status);
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
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

/** Default 7-bit I²C address. */
VL53L0XMinimal.I2C_ADDRESS = 0x29;
/** Device range status meaning "range complete — valid". */
VL53L0XMinimal.RANGE_STATUS_VALID = _RANGE_STATUS_VALID;

/** Range < low threshold. */
VL53L0XFull.SOURCE_LEVEL_LOW = 0x01;
/** Range > high threshold. */
VL53L0XFull.SOURCE_LEVEL_HIGH = 0x02;
/** Range < low threshold or > high threshold. */
VL53L0XFull.SOURCE_OUT_OF_WINDOW = 0x03;
/** A new measurement is available (driver default). */
VL53L0XFull.SOURCE_NEW_SAMPLE_READY = 0x04;

module.exports = { VL53L0XMinimal, VL53L0XFull };
