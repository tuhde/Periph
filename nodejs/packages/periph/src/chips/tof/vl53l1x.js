'use strict';

const { VL53Base } = require('./_vl53_base');

const _REG_I2C_SLAVE_DEVICE_ADDRESS = 0x0001;
const _REG_VHV_CONFIG_LOOP_BOUND    = 0x0008;
const _REG_VHV_INIT                 = 0x000B;
const _REG_XTALK_PLANE_OFFSET       = 0x0016;
const _REG_XTALK_X_GRADIENT         = 0x0018;
const _REG_XTALK_Y_GRADIENT         = 0x001A;
const _REG_PART_TO_PART_OFFSET      = 0x001E;
const _REG_MM_INNER_OFFSET          = 0x0020;
const _REG_MM_OUTER_OFFSET          = 0x0022;
const _REG_PAD_I2C_HV_EXTSUP        = 0x002E;
const _REG_GPIO_EXTSUP_HV           = 0x002F;
const _REG_GPIO_HV_MUX_CTRL         = 0x0030;
const _REG_GPIO_TIO_HV_STATUS       = 0x0031;
const _REG_INTERRUPT_CONFIG_GPIO    = 0x0046;
const _REG_PHASECAL_TIMEOUT         = 0x004B;
const _REG_RANGE_TIMEOUT_A          = 0x005E;
const _REG_RANGE_VCSEL_PERIOD_A     = 0x0060;
const _REG_RANGE_TIMEOUT_B          = 0x0061;
const _REG_RANGE_VCSEL_PERIOD_B     = 0x0063;
const _REG_SIGMA_THRESH             = 0x0064;
const _REG_MIN_COUNT_RATE_RTN_LIMIT = 0x0066;
const _REG_RANGE_VALID_PHASE_HIGH   = 0x0069;
const _REG_INTERMEASUREMENT_PERIOD  = 0x006C;
const _REG_THRESH_HIGH              = 0x0072;
const _REG_THRESH_LOW               = 0x0074;
const _REG_SD_WOI_SD0               = 0x0078;
const _REG_SD_INITIAL_PHASE_SD0     = 0x007A;
const _REG_ROI_CENTRE_SPAD          = 0x007F;
const _REG_ROI_XY_SIZE              = 0x0080;
const _REG_INTERRUPT_CLEAR          = 0x0086;
const _REG_MODE_START               = 0x0087;
const _REG_RESULT_RANGE_STATUS      = 0x0089;
const _REG_OSC_CALIBRATE_VAL        = 0x00DE;
const _REG_FIRMWARE_SYSTEM_STATUS   = 0x00E5;
const _REG_MODEL_ID                 = 0x010F;
const _REG_MODULE_TYPE              = 0x0110;
const _REG_REVISION_ID              = 0x0111;
const _REG_MODE_ROI_CENTRE_SPAD     = 0x013E;

const _SENSOR_ID = 0xEACC;
const _RANGE_STATUS_VALID = 0;
const _DEFAULT_CONFIG_START = 0x002D;
const _CALIBRATION_SAMPLES = 50;

// ULD VL51L1X_DEFAULT_CONFIGURATION — opaque, written verbatim to
// 0x002D..0x0087, one byte per register.
const _DEFAULT_CONFIGURATION = [
    0x00, 0x00, 0x00, 0x01, 0x02, 0x00, 0x02, 0x08, 0x00, 0x08, 0x10, 0x01, 0x01, 0x00, 0x00, 0x00,
    0x00, 0xFF, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0B, 0x00, 0x00, 0x02, 0x0A, 0x21,
    0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0xC8, 0x00, 0x00, 0x38, 0xFF, 0x01, 0x00, 0x08, 0x00,
    0x00, 0x01, 0xCC, 0x0F, 0x01, 0xF1, 0x0D, 0x01, 0x68, 0x00, 0x80, 0x08, 0xB8, 0x00, 0x00, 0x00,
    0x00, 0x0F, 0x89, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x0F, 0x0D, 0x0E, 0x0E, 0x00,
    0x00, 0x02, 0xC7, 0xFF, 0x9B, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
];

// ULD status_rtn: raw RESULT__RANGE_STATUS (bits 4:0) -> mapped range status.
const _STATUS_MAP = [255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255,
    255, 255, 10, 6, 255, 255, 11, 12];

// Timing budget (ms) -> [RANGE_CONFIG__TIMEOUT_MACROP_A, _B] per distance mode.
const _BUDGET_SHORT = {
    15: [0x001D, 0x0027], 20: [0x0051, 0x006E], 33: [0x00D6, 0x006E], 50: [0x01AE, 0x01E8],
    100: [0x02E1, 0x0388], 200: [0x03E1, 0x0496], 500: [0x0591, 0x05C1],
};
const _BUDGET_LONG = {
    20: [0x001E, 0x0022], 33: [0x0060, 0x006E], 50: [0x00AD, 0x00C6],
    100: [0x01CC, 0x01EA], 200: [0x02D9, 0x02F8], 500: [0x048F, 0x04A4],
};

// Distance mode -> [PHASECAL timeout, VCSEL period A, VCSEL period B,
// VALID_PHASE_HIGH, WOI_SD0, INITIAL_PHASE_SD0].
const _DISTANCE_MODES = {
    short: [0x14, 0x07, 0x05, 0x38, 0x0705, 0x0606],
    long: [0x0A, 0x0F, 0x0D, 0xB8, 0x0F0D, 0x0E0E],
};

// Logical SOURCE_* -> SYSTEM__INTERRUPT_CONFIG_GPIO value.
const _SOURCE_TO_CONFIG = { 1: 0x00, 2: 0x01, 3: 0x02, 4: 0x20, 5: 0x03 };
// Window mode (bits 1:0) -> logical SOURCE_*.
const _WINDOW_TO_SOURCE = [1, 2, 3, 5];

const _round = (x) => (x >= 0 ? Math.floor(x + 0.5) : -Math.floor(-x + 0.5));

/**
 * VL53L1X long-distance Time-of-Flight laser-ranging sensor
 * (STMicroelectronics) — minimal interface.
 *
 * 940 nm VCSEL emitter, 16×16 SPAD receiving array behind a lens and an
 * embedded ranging microcontroller measuring absolute distance up to 4 m at
 * up to 50 Hz. Two distance modes (short ~1.3 m, robust in sunlight; long
 * ~3.6–4 m in the dark), a 15–500 ms timing budget and a programmable region
 * of interest (4×4 to 16×16 SPADs). The datasheet has no register map:
 * registers, the default configuration block and all sequences follow ST's
 * Ultra Lite Driver (STSW-IMG009). Registers use a 16-bit index; multi-byte
 * registers are big-endian.
 *
 * Pin-to-pin compatible with the VL53L0X and shares its VL53Base (register
 * access, polling, boot wait, interrupt delivery, re-addressing) and public
 * API shape.
 *
 * The constructor starts the full initialization sequence (boot wait,
 * firmware boot poll, sensor ID check, ULD default configuration, 2V8 I/O,
 * GPIO1 active low, settling ranging) and leaves the chip idle in long
 * distance mode with a 100 ms timing budget. JS constructors cannot be
 * async, so every public method awaits it first — `await sensor.init()`
 * right after construction to surface a wrong or absent device at a
 * predictable point. If the connection has an enPin (XSHUT), it is driven
 * high first.
 *
 * Multiple sensors on one bus: all VL53L0X/VL53L1X sensors power up at 0x29.
 * Hold every sensor's XSHUT low (each connection disabled), then for each
 * sensor in turn enable its XSHUT, construct a driver on 0x29, call
 * `setAddress(new)`, and build the real driver on a connection at the new
 * address. The new address is volatile.
 */
class VL53L1XMinimal extends VL53Base {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x29).
     */
    constructor(connection) {
        super(connection, 2, 'VL53L1X');
        this._rangeStatus = 255;
        this._ready = this._init();
        // The rejection is re-raised by every public method; mark it handled
        // here so it never surfaces as an unhandled rejection on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the initialization sequence started by the constructor.
     * Optional — every other method awaits it too.
     * @returns {Promise<void>}
     * @throws {Error} If the sensor ID is not 0xEACC or an init poll times out (500 ms).
     */
    async init() {
        await this._ready;
    }

    async _init() {
        await this._bootWait();
        await this._waitUntil(async () => ((await this._rd8(_REG_FIRMWARE_SYSTEM_STATUS)) & 0x01) !== 0, 'boot');

        const sensorId = await this._rd16(_REG_MODEL_ID);
        if (sensorId !== _SENSOR_ID) {
            throw new Error('VL53L1X not found: expected sensor ID 0xEACC, got 0x' +
                sensorId.toString(16).padStart(4, '0'));
        }

        for (let i = 0; i < _DEFAULT_CONFIGURATION.length; i++) {
            await this._wr8(_DEFAULT_CONFIG_START + i, _DEFAULT_CONFIGURATION[i]);
        }

        // 2V8 I/O mode for I²C and GPIO1 pads; GPIO1 active low.
        await this._wr8(_REG_PAD_I2C_HV_EXTSUP, 0x01);
        await this._wr8(_REG_GPIO_EXTSUP_HV, 0x01);
        await this._wr8(_REG_GPIO_HV_MUX_CTRL, 0x11);

        // Settling ranging (ULD SensorInit), then two-bound VHV from the
        // previous temperature.
        await this._wr8(_REG_MODE_START, 0x40);
        await this._waitUntil(() => this._dataReady(), 'data ready');
        await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
        await this._wr8(_REG_MODE_START, 0x00);
        await this._wr8(_REG_VHV_CONFIG_LOOP_BOUND, 0x09);
        await this._wr8(_REG_VHV_INIT, 0x00);
    }

    async _dataReady() {
        // GPIO1 is active low: line asserted (bit 0 == 0) means data ready.
        return ((await this._rd8(_REG_GPIO_TIO_HV_STATUS)) & 0x01) === 0;
    }

    async _readResult() {
        const data = await this._rdBlock(_REG_RESULT_RANGE_STATUS, 17);
        await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
        const raw = data[0] & 0x1F;
        this._rangeStatus = raw < _STATUS_MAP.length ? _STATUS_MAP[raw] : 255;
        return data;
    }

    async _waitAndRead() {
        await this._waitUntil(() => this._dataReady(), 'data ready');
        const data = await this._readResult();
        return data.readUInt16BE(13);
    }

    /**
     * Take one single-shot measurement. Blocks for about one timing budget
     * (100 ms by default). Returns the raw range even when the measurement
     * is not valid; check `rangeValid()`.
     * @returns {Promise<number>} Distance in mm.
     * @throws {Error} If the measurement does not complete within 500 ms.
     */
    async distance() {
        await this._ready;
        return this._locked(async () => {
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
            await this._wr8(_REG_MODE_START, 0x10);
            return this._waitAndRead();
        });
    }

    /**
     * Report whether the most recent measurement was valid.
     * @returns {Promise<boolean>} True iff the mapped range status was 0 (range valid).
     */
    async rangeValid() {
        await this._ready;
        return this._rangeStatus === _RANGE_STATUS_VALID;
    }

    async _activeSource() {
        const value = await this._rd8(_REG_INTERRUPT_CONFIG_GPIO);
        if (value & 0x20) return VL53Base.SOURCE_NEW_SAMPLE_READY;
        return _WINDOW_TO_SOURCE[value & 0x03];
    }

    async _pollInterruptStatus() {
        return this._locked(async () => {
            if (!(await this._dataReady())) return 0;
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
            return this._activeSource();
        });
    }
}

/**
 * VL53L1X full interface — extends Minimal with timed continuous ranging,
 * the full measurement record, distance mode, timing budget,
 * inter-measurement period, signal and sigma thresholds, region of interest,
 * offset and crosstalk compensation with calibration helpers, temperature
 * update, address change, distance thresholds, identification, and the
 * Level-2 interrupt API.
 */
class VL53L1XFull extends VL53L1XMinimal {
    /**
     * Start timed continuous ranging.
     * @param {number} [periodMs=0] - Inter-measurement period in ms, 0–60000.
     *   0 (and any value below the timing budget) runs at the timing budget,
     *   i.e. back-to-back — the chip requires period ≥ budget.
     * @returns {Promise<void>}
     * @throws {RangeError} If periodMs is out of range.
     */
    async startContinuous(periodMs = 0) {
        await this._ready;
        if (!(periodMs >= 0 && periodMs <= 60000)) throw new RangeError('period must be 0 to 60000 ms');
        return this._locked(async () => {
            const budgetMs = Math.floor((await this._timingBudget()) / 1000);
            await this._setInterMeasurement(Math.max(periodMs, budgetMs, 1));
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
            await this._wr8(_REG_MODE_START, 0x40);
        });
    }

    /**
     * Stop continuous ranging. Does not wait for a running measurement.
     * @returns {Promise<void>}
     */
    async stopContinuous() {
        await this._ready;
        await this._wr8(_REG_MODE_START, 0x00);
    }

    /**
     * Wait for the next continuous-mode result and read it.
     * @returns {Promise<number>} Distance in mm (check `rangeValid()`).
     * @throws {Error} If no result arrives within 500 ms.
     */
    async readContinuous() {
        await this._ready;
        return this._locked(() => this._waitAndRead());
    }

    /**
     * Report whether a measurement is pending (non-blocking).
     * @returns {Promise<boolean>} True if GPIO__TIO_HV_STATUS shows the GPIO1 line asserted.
     */
    async dataReady() {
        await this._ready;
        return this._dataReady();
    }

    /**
     * Read the full result block and clear the interrupt (non-blocking).
     * @returns {Promise<{distanceMm: number, rangeStatus: number, signalRateMcps: number,
     *   ambientRateMcps: number, effectiveSpadCount: number}>} Decoded measurement record.
     */
    async readMeasurement() {
        await this._ready;
        return this._locked(async () => {
            const data = await this._readResult();
            return {
                distanceMm: data.readUInt16BE(13),
                rangeStatus: this._rangeStatus,
                signalRateMcps: data.readUInt16BE(15) / 128,
                ambientRateMcps: data.readUInt16BE(7) / 128,
                effectiveSpadCount: data.readUInt16BE(3) / 256,
            };
        });
    }

    /**
     * Mapped range status of the most recent measurement.
     * @returns {Promise<number>} 0 = valid, 1 = sigma fail, 2 = signal fail,
     *   4 = out of bounds, 7 = wrap-around, 255 = no update.
     */
    async rangeStatus() {
        await this._ready;
        return this._rangeStatus;
    }

    async _timingBudget() {
        const a = await this._rd16(_REG_RANGE_TIMEOUT_A);
        for (const table of [_BUDGET_SHORT, _BUDGET_LONG]) {
            for (const [ms, [ta]] of Object.entries(table)) {
                if (ta === a) return Number(ms) * 1000;
            }
        }
        return 0;
    }

    async _distanceMode() {
        const value = await this._rd8(_REG_PHASECAL_TIMEOUT);
        if (value === 0x14) return 'short';
        if (value === 0x0A) return 'long';
        throw new Error(`unknown distance mode register value 0x${value.toString(16).padStart(2, '0')}`);
    }

    async _setTimingBudget(budgetUs) {
        const table = (await this._distanceMode()) === 'short' ? _BUDGET_SHORT : _BUDGET_LONG;
        const ms = budgetUs / 1000;
        if (!Number.isInteger(ms) || !table[ms]) {
            throw new RangeError('timing budget must be one of ' +
                Object.keys(table).map((k) => Number(k) * 1000).join(', ') + ' us in this distance mode');
        }
        await this._wr16(_REG_RANGE_TIMEOUT_A, table[ms][0]);
        await this._wr16(_REG_RANGE_TIMEOUT_B, table[ms][1]);
    }

    async _setInterMeasurement(periodMs) {
        const clockPll = (await this._rd16(_REG_OSC_CALIBRATE_VAL)) & 0x03FF;
        await this._wr32(_REG_INTERMEASUREMENT_PERIOD, Math.floor((clockPll * periodMs * 1075) / 1000));
    }

    /**
     * Set the per-measurement timing budget (ULD table values only).
     * @param {number} budgetUs - 15000 (short mode only), 20000, 33000, 50000,
     *   100000, 200000 or 500000.
     * @returns {Promise<void>}
     * @throws {RangeError} If not in the table for the current distance mode.
     */
    async setTimingBudget(budgetUs) {
        await this._ready;
        return this._locked(() => this._setTimingBudget(budgetUs));
    }

    /**
     * Decode the timing budget from RANGE_CONFIG__TIMEOUT_MACROP_A.
     * @returns {Promise<number>} Budget in µs, or 0 if the register holds no table value.
     */
    async timingBudget() {
        await this._ready;
        return this._timingBudget();
    }

    /**
     * Select short or long distance mode, keeping the timing budget (100 ms
     * if the current budget is unknown).
     * @param {'short'|'long'} mode - Short (~1.3 m, robust in sunlight) or long (up to 4 m).
     * @returns {Promise<void>}
     * @throws {RangeError} If mode is unknown, or switching to long at a 15 ms budget.
     */
    async setDistanceMode(mode) {
        await this._ready;
        if (!_DISTANCE_MODES[mode]) throw new RangeError("mode must be 'short' or 'long'");
        return this._locked(async () => {
            const budget = (await this._timingBudget()) || 100000;
            if (mode === 'long' && budget === 15000) {
                throw new RangeError('15 ms timing budget is only available in short distance mode');
            }
            const [phasecal, vcselA, vcselB, phaseHigh, woi, initialPhase] = _DISTANCE_MODES[mode];
            await this._wr8(_REG_PHASECAL_TIMEOUT, phasecal);
            await this._wr8(_REG_RANGE_VCSEL_PERIOD_A, vcselA);
            await this._wr8(_REG_RANGE_VCSEL_PERIOD_B, vcselB);
            await this._wr8(_REG_RANGE_VALID_PHASE_HIGH, phaseHigh);
            await this._wr16(_REG_SD_WOI_SD0, woi);
            await this._wr16(_REG_SD_INITIAL_PHASE_SD0, initialPhase);
            await this._setTimingBudget(budget);
        });
    }

    /**
     * Read the current distance mode.
     * @returns {Promise<'short'|'long'>} Distance mode.
     * @throws {Error} If PHASECAL_CONFIG__TIMEOUT_MACROP holds neither mode's value.
     */
    async distanceMode() {
        await this._ready;
        return this._distanceMode();
    }

    /**
     * Set the continuous-mode inter-measurement period; should be ≥ the
     * timing budget (`startContinuous()` enforces this).
     * @param {number} periodMs - Period in ms, 1–60000.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setInterMeasurement(periodMs) {
        await this._ready;
        if (!(periodMs >= 1 && periodMs <= 60000)) throw new RangeError('inter-measurement period must be 1 to 60000 ms');
        await this._setInterMeasurement(periodMs);
    }

    /**
     * Read the continuous-mode inter-measurement period.
     * @returns {Promise<number>} Period in ms (0 if the oscillator calibration reads 0).
     */
    async interMeasurement() {
        await this._ready;
        const clockPll = (await this._rd16(_REG_OSC_CALIBRATE_VAL)) & 0x03FF;
        if (clockPll === 0) return 0;
        return Math.floor(((await this._rd32(_REG_INTERMEASUREMENT_PERIOD)) * 1000) / (clockPll * 1075));
    }

    /**
     * Set the minimum return signal rate for a valid result.
     * @param {number} limitMcps - Limit in MCPS, 0 to 511.99 (default 1.0).
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setSignalRateLimit(limitMcps) {
        await this._ready;
        if (!(limitMcps >= 0 && limitMcps <= 511.99)) throw new RangeError('signal rate limit must be 0 to 511.99 MCPS');
        await this._wr16(_REG_MIN_COUNT_RATE_RTN_LIMIT, Math.floor(limitMcps * 128 + 0.5));
    }

    /**
     * Read the minimum return signal rate.
     * @returns {Promise<number>} Limit in MCPS.
     */
    async signalRateLimit() {
        await this._ready;
        return (await this._rd16(_REG_MIN_COUNT_RATE_RTN_LIMIT)) / 128;
    }

    /**
     * Set the maximum estimated standard deviation for a valid result.
     * @param {number} sigmaMm - Threshold in mm, 0–16383 (default 90).
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setSigmaThreshold(sigmaMm) {
        await this._ready;
        if (!(Number.isInteger(sigmaMm) && sigmaMm >= 0 && sigmaMm <= 16383)) {
            throw new RangeError('sigma threshold must be an integer 0 to 16383 mm');
        }
        await this._wr16(_REG_SIGMA_THRESH, sigmaMm << 2);
    }

    /**
     * Read the sigma threshold.
     * @returns {Promise<number>} Threshold in mm.
     */
    async sigmaThreshold() {
        await this._ready;
        return (await this._rd16(_REG_SIGMA_THRESH)) >> 2;
    }

    /**
     * Set the receiving region-of-interest size; sizes above 10 SPADs
     * re-centre the ROI on SPAD 199 (array centre) so it stays on the array.
     * @param {number} width - ROI width in SPADs, 4–16.
     * @param {number} height - ROI height in SPADs, 4–16.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setRoi(width, height) {
        await this._ready;
        if (!(width >= 4 && width <= 16 && height >= 4 && height <= 16)) {
            throw new RangeError('ROI width and height must be 4 to 16 SPADs');
        }
        return this._locked(async () => {
            if (width > 10 || height > 10) await this._wr8(_REG_ROI_CENTRE_SPAD, 199);
            await this._wr8(_REG_ROI_XY_SIZE, ((height - 1) << 4) | (width - 1));
        });
    }

    /**
     * Read the region-of-interest size.
     * @returns {Promise<{width: number, height: number}>} Size in SPADs.
     */
    async roi() {
        await this._ready;
        const value = await this._rd8(_REG_ROI_XY_SIZE);
        return { width: (value & 0x0F) + 1, height: (value >> 4) + 1 };
    }

    /**
     * Move the region of interest to a centre SPAD (ST UM2555 numbering, 199
     * = array centre). The caller keeps the ROI inside the array.
     * @param {number} spad - SPAD number 0–255.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setRoiCenter(spad) {
        await this._ready;
        if (!(spad >= 0 && spad <= 255)) throw new RangeError('ROI centre SPAD must be 0 to 255');
        await this._wr8(_REG_ROI_CENTRE_SPAD, spad);
    }

    /**
     * Read the region-of-interest centre SPAD.
     * @returns {Promise<number>} SPAD number.
     */
    async roiCenter() {
        await this._ready;
        return this._rd8(_REG_ROI_CENTRE_SPAD);
    }

    /**
     * Read the factory-measured optical-centre SPAD from NVM.
     * @returns {Promise<number>} SPAD number; pass to `setRoiCenter()` to align the ROI with the lens.
     */
    async opticalCenter() {
        await this._ready;
        return this._rd8(_REG_MODE_ROI_CENTRE_SPAD);
    }

    async _setOffset(offsetMm) {
        if (!(offsetMm >= -1024 && offsetMm <= 1023.75)) throw new RangeError('offset must be -1024.0 to 1023.75 mm');
        await this._wr16(_REG_PART_TO_PART_OFFSET, _round(offsetMm * 4) & 0x1FFF);
        await this._wr16(_REG_MM_INNER_OFFSET, 0);
        await this._wr16(_REG_MM_OUTER_OFFSET, 0);
    }

    /**
     * Override the part-to-part range offset (volatile).
     * @param {number} offsetMm - Offset in mm, −1024.0 to 1023.75 (0.25 mm steps).
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setOffset(offsetMm) {
        await this._ready;
        return this._locked(() => this._setOffset(offsetMm));
    }

    /**
     * Read the part-to-part range offset.
     * @returns {Promise<number>} Offset in mm.
     */
    async offset() {
        await this._ready;
        let raw = (await this._rd16(_REG_PART_TO_PART_OFFSET)) & 0x1FFF;
        if (raw & 0x1000) raw -= 0x2000;
        return raw * 0.25;
    }

    async _setCrosstalk(rateMcps) {
        if (!(rateMcps >= 0 && rateMcps < 0.128)) throw new RangeError('crosstalk rate must be 0 (off) or below 0.128 MCPS');
        await this._wr16(_REG_XTALK_X_GRADIENT, 0);
        await this._wr16(_REG_XTALK_Y_GRADIENT, 0);
        await this._wr16(_REG_XTALK_PLANE_OFFSET, Math.min(Math.floor(rateMcps * 512000 + 0.5), 0xFFFF));
    }

    /**
     * Set the per-SPAD crosstalk compensation rate (volatile).
     * @param {number} rateMcps - 0 disables; otherwise 0 < rate < 0.128 MCPS.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setCrosstalkCompensation(rateMcps) {
        await this._ready;
        return this._locked(() => this._setCrosstalk(rateMcps));
    }

    /**
     * Read the per-SPAD crosstalk compensation rate.
     * @returns {Promise<number>} Rate in MCPS.
     */
    async crosstalkCompensation() {
        await this._ready;
        return (await this._rd16(_REG_XTALK_PLANE_OFFSET)) / 512000;
    }

    async _collect() {
        const samples = [];
        await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
        await this._wr8(_REG_MODE_START, 0x40);
        try {
            for (let i = 0; i < _CALIBRATION_SAMPLES; i++) {
                await this._waitUntil(() => this._dataReady(), 'data ready');
                samples.push(await this._readResult());
            }
        } finally {
            await this._wr8(_REG_MODE_START, 0x00);
        }
        return samples;
    }

    /**
     * Measure and apply the range offset against a target at a known distance
     * (ULD CalibrateOffset; ST recommends 88 % white at 140 mm). Ranges 50
     * times with the offset zeroed; must not be called while ranging. Store
     * the result and re-apply it with `setOffset()` after each power-up.
     * @param {number} targetMm - True target distance in mm.
     * @returns {Promise<number>} Applied offset in mm (target − mean measured distance).
     * @throws {RangeError} If the resulting offset is out of range.
     * @throws {Error} If a result does not arrive within 500 ms.
     */
    async calibrateOffset(targetMm) {
        await this._ready;
        return this._locked(async () => {
            await this._wr16(_REG_PART_TO_PART_OFFSET, 0);
            await this._wr16(_REG_MM_INNER_OFFSET, 0);
            await this._wr16(_REG_MM_OUTER_OFFSET, 0);
            const samples = await this._collect();
            const mean = samples.reduce((acc, d) => acc + d.readUInt16BE(13), 0) / samples.length;
            const offset = targetMm - mean;
            await this._setOffset(offset);
            return offset;
        });
    }

    /**
     * Measure and apply crosstalk compensation for a cover glass (ULD
     * CalibrateXtalk; ST uses a 17 % grey target where the sensor starts to
     * under-range). Ranges 50 times with compensation off; must not be called
     * while ranging. Store the result and re-apply it with
     * `setCrosstalkCompensation()` after each power-up.
     * @param {number} targetMm - True target distance in mm (> 0).
     * @returns {Promise<number>} Applied per-SPAD crosstalk rate in MCPS (0–0.127).
     * @throws {RangeError} If targetMm is not positive.
     * @throws {Error} If a result does not arrive within 500 ms.
     */
    async calibrateCrosstalk(targetMm) {
        await this._ready;
        if (!(targetMm > 0)) throw new RangeError('target distance must be positive');
        return this._locked(async () => {
            await this._wr16(_REG_XTALK_PLANE_OFFSET, 0);
            const samples = await this._collect();
            const n = samples.length;
            const meanDistance = samples.reduce((acc, d) => acc + d.readUInt16BE(13), 0) / n;
            const meanSignal = samples.reduce((acc, d) => acc + d.readUInt16BE(15) / 128, 0) / n;
            const meanSpads = samples.reduce((acc, d) => acc + d.readUInt16BE(3) / 256, 0) / n;
            let rate = meanSpads > 0 ? (meanSignal * (1 - meanDistance / targetMm)) / meanSpads : 0;
            rate = Math.min(Math.max(rate, 0), 0.127);
            await this._setCrosstalk(rate);
            return rate;
        });
    }

    /**
     * Run the temperature update (ULD StartTemperatureUpdate). Call in
     * software standby (not while ranging), after the temperature changes by
     * more than about 8 °C.
     * @returns {Promise<void>}
     * @throws {Error} If the update ranging times out.
     */
    async recalibrate() {
        await this._ready;
        return this._locked(async () => {
            await this._wr8(_REG_VHV_CONFIG_LOOP_BOUND, 0x81);
            await this._wr8(_REG_VHV_INIT, 0x92);
            await this._wr8(_REG_MODE_START, 0x40);
            await this._waitUntil(() => this._dataReady(), 'temperature update');
            await this._wr8(_REG_INTERRUPT_CLEAR, 0x01);
            await this._wr8(_REG_MODE_START, 0x00);
            await this._wr8(_REG_VHV_CONFIG_LOOP_BOUND, 0x09);
            await this._wr8(_REG_VHV_INIT, 0x00);
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
        await this._setAddressReg(_REG_I2C_SLAVE_DEVICE_ADDRESS, address);
    }

    /**
     * Set the distance thresholds used by the threshold interrupt sources.
     * @param {number} lowMm - Low threshold in mm.
     * @param {number} highMm - High threshold in mm, lowMm ≤ highMm ≤ 65535.
     * @returns {Promise<void>}
     * @throws {RangeError} If out of range.
     */
    async setInterruptThresholds(lowMm, highMm) {
        await this._ready;
        if (!(lowMm >= 0 && highMm >= lowMm && highMm <= 65535)) {
            throw new RangeError('thresholds must satisfy 0 <= low <= high <= 65535 mm');
        }
        return this._locked(async () => {
            await this._wr16(_REG_THRESH_HIGH, highMm);
            await this._wr16(_REG_THRESH_LOW, lowMm);
        });
    }

    /**
     * Read the distance thresholds.
     * @returns {Promise<{lowMm: number, highMm: number}>} Thresholds in mm.
     */
    async interruptThresholds() {
        await this._ready;
        return { lowMm: await this._rd16(_REG_THRESH_LOW), highMm: await this._rd16(_REG_THRESH_HIGH) };
    }

    /**
     * Read IDENTIFICATION__MODEL_ID.
     * @returns {Promise<number>} 0xEA.
     */
    async modelId() {
        await this._ready;
        return this._rd8(_REG_MODEL_ID);
    }

    /**
     * Read IDENTIFICATION__MODULE_TYPE.
     * @returns {Promise<number>} 0xCC.
     */
    async moduleType() {
        await this._ready;
        return this._rd8(_REG_MODULE_TYPE);
    }

    /**
     * Read IDENTIFICATION__REVISION_ID (mask revision).
     * @returns {Promise<number>} 0x10.
     */
    async revisionId() {
        await this._ready;
        return this._rd8(_REG_REVISION_ID);
    }

    /**
     * Select the GPIO1 interrupt source (replaces the active one). With a
     * threshold source active, `dataReady()`/`readContinuous()` only see a
     * pending result when the threshold condition is met.
     * @param {number} source - One of the SOURCE_* constants (1–5).
     * @returns {Promise<void>}
     * @throws {RangeError} If source is not 1–5.
     */
    async enableInterrupt(source) {
        await this._ready;
        if (!(source in _SOURCE_TO_CONFIG)) throw new RangeError('source must be one of the SOURCE_* constants');
        await this._wr8(_REG_INTERRUPT_CONFIG_GPIO, _SOURCE_TO_CONFIG[source]);
    }

    /**
     * Revert to SOURCE_NEW_SAMPLE_READY if source is the active threshold
     * source. The chip has no disabled state, so disabling
     * SOURCE_NEW_SAMPLE_READY is a no-op; use `offInterrupt()` to stop callbacks.
     * @param {number} source - One of the SOURCE_* constants.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        await this._ready;
        return this._locked(async () => {
            if (source !== VL53Base.SOURCE_NEW_SAMPLE_READY && (await this._activeSource()) === source) {
                await this._wr8(_REG_INTERRUPT_CONFIG_GPIO, 0x20);
            }
        });
    }

    /**
     * Read and clear a pending interrupt.
     * @returns {Promise<number>} The active SOURCE_* value if GPIO1 is asserted, else 0.
     */
    async pollInterrupt() {
        await this._ready;
        return this._pollInterruptStatus();
    }

    /**
     * Subscribe to GPIO1 interrupt events. With `connection.intPin` wired the
     * callback runs on every falling edge (GPIO1 is active low); otherwise a
     * 5 ms polling timer is used. The driver clears the interrupt before
     * invoking the callback; the polling fallback consumes results, so don't
     * mix it with `readContinuous()`.
     * @param {(status: number) => void} callback - Receives the active SOURCE_* value.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this._ready;
        await this._subscribe(callback);
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        await this._unsubscribe();
    }
}

/** Expected IDENTIFICATION__MODEL_ID + MODULE_TYPE word. */
VL53L1XMinimal.SENSOR_ID = _SENSOR_ID;
/** IDENTIFICATION__MODEL_ID. */
VL53L1XMinimal.MODEL_ID = 0xEA;
/** IDENTIFICATION__MODULE_TYPE. */
VL53L1XMinimal.MODULE_TYPE = 0xCC;
/** Mapped range status meaning "range valid". */
VL53L1XMinimal.RANGE_STATUS_VALID = _RANGE_STATUS_VALID;

module.exports = { VL53L1XMinimal, VL53L1XFull };
