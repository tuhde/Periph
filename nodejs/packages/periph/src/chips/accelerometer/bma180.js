'use strict';

const _REG_CHIP_ID          = 0x00;
const _REG_VERSION          = 0x01;
const _REG_ACC_X_LSB        = 0x02;
const _REG_ACC_X_MSB        = 0x03;
const _REG_ACC_Y_LSB        = 0x04;
const _REG_ACC_Y_MSB        = 0x05;
const _REG_ACC_Z_LSB        = 0x06;
const _REG_ACC_Z_MSB        = 0x07;
const _REG_TEMP             = 0x08;
const _REG_STATUS_REG1      = 0x09;
const _REG_STATUS_REG2      = 0x0A;
const _REG_STATUS_REG3      = 0x0B;
const _REG_STATUS_REG4      = 0x0C;
const _REG_CTRL_REG0        = 0x0D;
const _REG_CTRL_REG1        = 0x0E;
const _REG_CTRL_REG3        = 0x21;
const _REG_CTRL_REG4        = 0x22;
const _REG_HY               = 0x23;
const _REG_SLOPE_TAPSENS    = 0x24;
const _REG_HIGH_LOW_INFO    = 0x25;
const _REG_LOW_DUR          = 0x26;
const _REG_HIGH_DUR         = 0x27;
const _REG_TAPSENS_TH       = 0x28;
const _REG_LOW_TH           = 0x29;
const _REG_HIGH_TH          = 0x2A;
const _REG_SLOPE_TH         = 0x2B;
const _REG_CD1              = 0x2C;
const _REG_CD2              = 0x2D;
const _REG_TCO_X            = 0x2E;
const _REG_TCO_Y            = 0x2F;
const _REG_TCO_Z            = 0x30;
const _REG_GAIN_T           = 0x31;
const _REG_GAIN_Y           = 0x33;
const _REG_GAIN_Z           = 0x34;
const _REG_OFFSET_LSB1      = 0x35;
const _REG_OFFSET_T         = 0x37;
const _REG_RESET            = 0x10;
const _REG_BW_TCS           = 0x20;

const _CHIP_ID_VALUE = 0x03;
const _CHIP_ID_MASK  = 0x07;

const _RANGE_BITS = { 1: 0x00, 1.5: 0x02, 2: 0x04, 3: 0x06, 4: 0x08, 8: 0x0A, 16: 0x0C };
const _BW_CODES = [10, 20, 40, 75, 150, 300, 600, 1200].map((hz, i) => [hz, i << 4]);
const _RANGE_SCALE = { 1: 8192, 1.5: 5460, 2: 4096, 3: 2730, 4: 2048, 8: 1024, 16: 512 };
const _TAP_DUR_CODES = { 50: 0x00, 75: 0x01, 100: 0x02, 150: 0x03, 250: 0x04, 500: 0x05, 750: 0x06, 1000: 0x07 };
const _WAKEUP_DUR_CODES = { 20: 0x00, 80: 0x01, 320: 0x02, 2560: 0x03 };
const _SLOPE_DUR_CODES = { 1: 0x00, 3: 0x01, 5: 0x02, 7: 0x03 };
const _DUR_LSB_MS = 2.085;

function _nearestBandwidth(bwHz) {
    let best = _BW_CODES[0];
    let bestDiff = Math.abs(_BW_CODES[0][0] - bwHz);
    for (const entry of _BW_CODES) {
        const diff = Math.abs(entry[0] - bwHz);
        if (diff < bestDiff) {
            best = entry;
            bestDiff = diff;
        }
    }
    return best[1];
}

function _nearestTapDur(windowMs) {
    let bestMs = 50;
    let bestCode = _TAP_DUR_CODES[50];
    let bestAbsDiff = Infinity;
    for (const [ms, code] of Object.entries(_TAP_DUR_CODES).map(([k, v]) => [Number(k), v])) {
        const diff = Math.abs(ms - windowMs);
        if (diff < bestAbsDiff) {
            bestMs = ms;
            bestCode = code;
            bestAbsDiff = diff;
        }
    }
    return bestCode;
}

function _delayMs(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) { /* spin */ }
}

/**
 * BMA180 3-axis MEMS accelerometer (Bosch Sensortec) — minimal interface.
 *
 * Triaxial low-g accelerometer with 14-bit digital output and seven selectable
 * full-scale ranges (±1 to ±16 *g*). Communicates over I²C at address
 * 0x40 (SDO = GND) or 0x41 (SDO = VDDIO). The chip also supports 4-wire
 * SPI; out of scope here.
 *
 * Default configuration (written at construction):
 * - Range ±2 *g* (4096 LSB/g)
 * - Bandwidth 150 Hz low-pass
 * - mode_config = 00 (low-noise, factory-calibrated)
 * - 14-bit readout, shadow_dis = 0
 * - All interrupt enables left untouched
 * - Calibration bits preserved everywhere
 */
class BMA180Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Configured I²C connection.
     */
    constructor(connection) {
        this._conn = connection;
        this._rangeG = 2;
        this._init();
    }
    async _init() {
        // First transaction must be something other than an acc LSB read
        // (the chip returns MSB=0 for a first LSB read after power-up).
        const chipId = await this._readReg(_REG_CHIP_ID);
        if ((chipId & _CHIP_ID_MASK) !== _CHIP_ID_VALUE) {
            throw new Error('BMA180 CHIP_ID: expected 0x' + _CHIP_ID_VALUE.toString(16) +
                            ', got 0x' + (chipId & _CHIP_ID_MASK).toString(16));
        }
        // Unlock image registers (0x20-0x3B) by setting ee_w = 1.
        let ctrl0 = await this._readReg(_REG_CTRL_REG0);
        ctrl0 |= 0x10;
        await this._writeReg(_REG_CTRL_REG0, ctrl0 & 0xFF);
        // Range = ±2 g (OFFSET_LSB1 bits 3:1 = 010), preserving cal/smp_skip.
        let olsb1 = await this._readReg(_REG_OFFSET_LSB1);
        olsb1 = (olsb1 & 0xF1) | _RANGE_BITS[2];
        await this._writeReg(_REG_OFFSET_LSB1, olsb1 & 0xFF);
        // bw = 150 Hz (BW_TCS bits 7:4 = 0100), preserving tcs.
        const bwCode = _nearestBandwidth(150);
        let bw = await this._readReg(_REG_BW_TCS);
        bw = (bw & 0x0F) | bwCode;
        await this._writeReg(_REG_BW_TCS, bw & 0xFF);
        _delayMs(4);
    }
    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, value & 0xFF);
    }
    async _readReg(reg) {
        return (await this._conn.readReg(reg, 1))[0];
    }
    /**
     * Read 3-axis linear acceleration.
     *
     * Reads all six LSB-then-MSB data bytes (0x02..0x07) in one burst so the
     * X, Y, Z samples are guaranteed to come from a single measurement with
     * `shadow_dis=0` enforcing correct ordering.
     *
     * @returns {Promise<number[]>} [x, y, z] acceleration in *g*.
     */
    async read() {
        const raw = await this._conn.readReg(_REG_ACC_X_LSB, 6);
        // 14-bit two's complement; bit 1 of each LSB is 0, bit 0 is new_data.
        const rx = (((raw[1] << 6) | (raw[0] >> 2)) & 0x3FFF);
        const ry = (((raw[3] << 6) | (raw[2] >> 2)) & 0x3FFF);
        const rz = (((raw[5] << 6) | (raw[4] >> 2)) & 0x3FFF);
        const sx = (rx + 8192) % 16384 - 8192;
        const sy = (ry + 8192) % 16384 - 8192;
        const sz = (rz + 8192) % 16384 - 8192;
        const scale = _RANGE_SCALE[this._rangeG];
        return [sx / scale, sy / scale, sz / scale];
    }
}

/**
 * BMA180 full interface — extends BMA180Minimal with configuration,
 * range/bandwidth/mode/filter selection, temperature, new-data, shadow,
 * sample-skip, low-g/high-g/slope/alert/tap interrupts with per-axis
 * enable and filter selection, latched or self-resetting interrupts,
 * self-wake-up, sleep, soft reset, electrostatic self-test, offset
 * regulation, version register, and the two CUSTOMER scratch bytes.
 */
class BMA180Full extends BMA180Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - Configured I²C connection.
     */
    constructor(connection) {
        super(connection);
        this._enabledSources = 0;
        this._sleeping = false;
    }
    async read() {
        return super.read();
    }
    /** @param {number} rangeG - One of 1, 1.5, 2, 3, 4, 8, 16. */
    async setRange(rangeG) {
        if (!(rangeG in _RANGE_BITS)) {
            throw new Error('rangeG must be one of 1, 1.5, 2, 3, 4, 8, 16');
        }
        this._rangeG = rangeG;
        let olsb1 = await this._readReg(_REG_OFFSET_LSB1);
        olsb1 = (olsb1 & 0xF1) | _RANGE_BITS[rangeG];
        await this._writeReg(_REG_OFFSET_LSB1, olsb1 & 0xFF);
    }
    /** @param {number} bandwidthHz - 10..1200 Hz. */
    async setBandwidth(bandwidthHz) {
        const bwCode = _nearestBandwidth(bandwidthHz);
        let bw = await this._readReg(_REG_BW_TCS);
        bw = (bw & 0x0F) | bwCode;
        await this._writeReg(_REG_BW_TCS, bw & 0xFF);
        _delayMs(4);
    }
    /** @param {number} mode - 0=low-pass (use setBandwidth), 1=high-pass 1Hz, 2=band-pass. */
    async setFilterMode(mode) {
        if (mode === 0) return;
        let code;
        if (mode === 1) code = 0x80;
        else if (mode === 2) code = 0x90;
        else throw new Error('mode must be 0, 1, or 2');
        let bw = await this._readReg(_REG_BW_TCS);
        bw = (bw & 0x0F) | code;
        await this._writeReg(_REG_BW_TCS, bw & 0xFF);
        _delayMs(4);
    }
    /** @param {number} mode - 0..3 (mode_config). */
    async setMode(mode) {
        if (mode < 0 || mode > 3) throw new Error('mode must be 0..3');
        let tcoz = await this._readReg(_REG_TCO_Z);
        tcoz = (tcoz & 0xFC) | (mode & 0x03);
        await this._writeReg(_REG_TCO_Z, tcoz & 0xFF);
    }
    /** @param {number} bits - 12 or 14. */
    async setResolution(bits) {
        if (bits !== 12 && bits !== 14) throw new Error('bits must be 12 or 14');
        let ot = await this._readReg(_REG_OFFSET_T);
        if (bits === 12) ot |= 0x01;
        else ot &= ~0x01 & 0xFF;
        await this._writeReg(_REG_OFFSET_T, ot & 0xFF);
    }
    /** @returns {Promise<number[]>} Signed 14-bit counts. */
    async readRaw() {
        const raw = await this._conn.readReg(_REG_ACC_X_LSB, 6);
        const rx = (((raw[1] << 6) | (raw[0] >> 2)) & 0x3FFF);
        const ry = (((raw[3] << 6) | (raw[2] >> 2)) & 0x3FFF);
        const rz = (((raw[5] << 6) | (raw[4] >> 2)) & 0x3FFF);
        return [(rx + 8192) % 16384 - 8192, (ry + 8192) % 16384 - 8192, (rz + 8192) % 16384 - 8192];
    }
    /** @returns {Promise<number>} Temperature in °C. */
    async readTemperature() {
        const raw = await this._readReg(_REG_TEMP);
        const s = raw < 128 ? raw : raw - 256;
        return 25.0 + ((s - 2) * 0.5);
    }
    /** @returns {Promise<boolean>} True if all three new_data_X/Y/Z bits are set. */
    async newDataAvailable() {
        const x = await this._readReg(_REG_ACC_X_LSB);
        const y = await this._readReg(_REG_ACC_Y_LSB);
        const z = await this._readReg(_REG_ACC_Z_LSB);
        return (x & 0x01) && (y & 0x01) && (z & 0x01);
    }
    /** @param {boolean} enabled - True to allow MSB-only reads. */
    async setShadow(enabled) {
        let gy = await this._readReg(_REG_GAIN_Y);
        if (enabled) gy &= ~0x01 & 0xFF;
        else gy |= 0x01;
        await this._writeReg(_REG_GAIN_Y, gy & 0xFF);
    }
    /** @param {boolean} enabled - True to enable smp_skip. */
    async setSampleSkip(enabled) {
        let olsb1 = await this._readReg(_REG_OFFSET_LSB1);
        if (enabled) olsb1 |= 0x01;
        else olsb1 &= ~0x01 & 0xFF;
        await this._writeReg(_REG_OFFSET_LSB1, olsb1 & 0xFF);
    }
    async setLowG(thresholdG, durationMs, hysteresisG = 0, axes = 0x07, counter = 0, filtered = true) {
        await this._writeThreshold(_REG_LOW_TH, thresholdG);
        await this._writeLowDur(durationMs);
        await this._writeLowHysteresis(hysteresisG);
        await this._writeAxisEnablesLow(axes);
        await this._writeFiltBit(_REG_HIGH_LOW_INFO, 0x01, filtered);
        await this._writeDebounce('lg', counter);
        await this._enableSource(BMA180Full.SOURCE_LOW_G);
    }
    async setHighG(thresholdG, durationMs, hysteresisG = 0, axes = 0x07, counter = 0, filtered = true) {
        await this._writeThreshold(_REG_HIGH_TH, thresholdG);
        await this._writeHighDur(durationMs);
        await this._writeHighHysteresis(hysteresisG);
        await this._writeAxisEnablesHigh(axes);
        await this._writeFiltBit(_REG_HIGH_LOW_INFO, 0x10, filtered);
        await this._writeDebounce('hg', counter);
        await this._enableSource(BMA180Full.SOURCE_HIGH_G);
    }
    async setSlope(thresholdG, samples = 1, axes = 0x07, filtered = true) {
        await this._writeSlopeThreshold(_REG_SLOPE_TH, thresholdG);
        await this._writeSlopeDur(samples);
        await this._writeAxisEnablesSlope(axes);
        await this._writeFiltBit(_REG_SLOPE_TAPSENS, 0x10, filtered);
        await this._writeCr3Bit(0x40, true);   // slope_int
        await this._writeCr3Bit(0x80, false);  // slope_alert
        await this._writeCr3Bit(0x04, true);   // adv_int
        await this._enableSource(BMA180Full.SOURCE_SLOPE);
    }
    async setAlert(enabled) {
        if (enabled) {
            this._enabledSources &= ~BMA180Full.SOURCE_SLOPE;
            await this._writeCr3Bit(0x40, false);
            await this._writeCr3Bit(0x80, true);
            await this._writeCr3Bit(0x04, true);
            await this._enableSource(BMA180Full.SOURCE_ALERT);
        } else {
            await this._disableSource(BMA180Full.SOURCE_ALERT);
            await this._writeCr3Bit(0x80, false);
        }
    }
    async setTap(thresholdG, windowMs = 250, axes = 0x07, filtered = true) {
        await this._writeSlopeThreshold(_REG_TAPSENS_TH, thresholdG);
        await this._writeTapDur(windowMs);
        await this._writeAxisEnablesTap(axes);
        await this._writeFiltBit(_REG_SLOPE_TAPSENS, 0x01, filtered);
        await this._enableSource(BMA180Full.SOURCE_TAP);
    }
    async setLatch(enabled) {
        await this._writeCr3Bit(0x01, enabled);
    }
    async clearInterrupt() {
        if (this._sleeping) return;
        let ctrl0 = await this._readReg(_REG_CTRL_REG0);
        await this._writeReg(_REG_CTRL_REG0, (ctrl0 | 0x40) & 0xFF);
    }
    async enableInterrupt(source) {
        if (source === BMA180Full.SOURCE_NEW_DATA) {
            await this._writeCr3Bit(0x02, true);
        } else {
            await this._writeCr3Bit(0x02, false);
            if (source === BMA180Full.SOURCE_SLOPE) {
                await this._writeCr3Bit(0x80, false);
                await this._writeCr3Bit(0x40, true);
                await this._writeCr3Bit(0x04, true);
                await this._disableSource(BMA180Full.SOURCE_ALERT);
            } else if (source === BMA180Full.SOURCE_ALERT) {
                await this._writeCr3Bit(0x40, false);
                await this._writeCr3Bit(0x80, true);
                await this._writeCr3Bit(0x04, true);
                await this._disableSource(BMA180Full.SOURCE_SLOPE);
            } else if (source === BMA180Full.SOURCE_HIGH_G) {
                await this._writeCr3Bit(0x20, true);
            } else if (source === BMA180Full.SOURCE_LOW_G) {
                await this._writeCr3Bit(0x10, true);
            } else if (source === BMA180Full.SOURCE_TAP) {
                await this._writeCr3Bit(0x08, true);
            }
        }
        await this._enableSource(source);
    }
    async disableInterrupt(source) {
        if (source === BMA180Full.SOURCE_NEW_DATA) {
            await this._writeCr3Bit(0x02, false);
        } else if (source === BMA180Full.SOURCE_SLOPE) {
            await this._writeCr3Bit(0x40, false);
        } else if (source === BMA180Full.SOURCE_ALERT) {
            await this._writeCr3Bit(0x80, false);
            await this._writeCr3Bit(0x04, false);
        } else if (source === BMA180Full.SOURCE_HIGH_G) {
            await this._writeCr3Bit(0x20, false);
        } else if (source === BMA180Full.SOURCE_LOW_G) {
            await this._writeCr3Bit(0x10, false);
        } else if (source === BMA180Full.SOURCE_TAP) {
            await this._writeCr3Bit(0x08, false);
        }
        await this._disableSource(source);
    }
    /** @returns {Promise<number>} STATUS_REG3 byte. */
    async pollInterrupt() {
        return this._readReg(_REG_STATUS_REG3);
    }
    async readStatus() {
        return [
            await this._readReg(_REG_STATUS_REG1),
            await this._readReg(_REG_STATUS_REG2),
            await this._readReg(_REG_STATUS_REG3),
            await this._readReg(_REG_STATUS_REG4),
        ];
    }
    async setWakeUp(enabled, pauseMs = 20) {
        if (!(pauseMs in _WAKEUP_DUR_CODES)) {
            throw new Error('pauseMs must be 20, 80, 320, or 2560');
        }
        let tcoy = await this._readReg(_REG_TCO_Y);
        tcoy = (tcoy & 0xFC) | _WAKEUP_DUR_CODES[pauseMs];
        await this._writeReg(_REG_TCO_Y, tcoy & 0xFF);
        let gz = await this._readReg(_REG_GAIN_Z);
        if (enabled) gz |= 0x01;
        else gz &= ~0x01 & 0xFF;
        await this._writeReg(_REG_GAIN_Z, gz & 0xFF);
    }
    async sleep() {
        if (this._sleeping) return;
        let ctrl0 = await this._readReg(_REG_CTRL_REG0);
        await this._writeReg(_REG_CTRL_REG0, (ctrl0 | 0x02) & 0xFF);
        this._sleeping = true;
    }
    async wake() {
        if (!this._sleeping) return;
        let ctrl0 = await this._readReg(_REG_CTRL_REG0);
        await this._writeReg(_REG_CTRL_REG0, ctrl0 & ~0x02 & 0xFF);
        _delayMs(2);
        this._sleeping = false;
    }
    async softReset() {
        await this._writeReg(_REG_RESET, 0xB6);
        _delayMs(30);
        this._rangeG = 2;
        const chipId = await this._readReg(_REG_CHIP_ID);
        if ((chipId & _CHIP_ID_MASK) !== _CHIP_ID_VALUE) {
            throw new Error('BMA180 CHIP_ID after reset: expected 0x' + _CHIP_ID_VALUE.toString(16));
        }
        let ctrl0 = await this._readReg(_REG_CTRL_REG0);
        ctrl0 |= 0x10;
        await this._writeReg(_REG_CTRL_REG0, ctrl0 & 0xFF);
        let olsb1 = await this._readReg(_REG_OFFSET_LSB1);
        olsb1 = (olsb1 & 0xF1) | _RANGE_BITS[2];
        await this._writeReg(_REG_OFFSET_LSB1, olsb1 & 0xFF);
        const bwCode = _nearestBandwidth(150);
        let bw = await this._readReg(_REG_BW_TCS);
        bw = (bw & 0x0F) | bwCode;
        await this._writeReg(_REG_BW_TCS, bw & 0xFF);
        _delayMs(4);
        this._sleeping = false;
    }
    /** @returns {Promise<boolean>} True if self-test passed. */
    async selfTest() {
        const ctrl0 = await this._readReg(_REG_CTRL_REG0);
        await this._writeReg(_REG_CTRL_REG0, (ctrl0 | 0x04) & 0xFF);
        _delayMs(10);
        const [x, y, z] = await this.readRaw();
        await this._writeReg(_REG_CTRL_REG0, ctrl0 & 0xFF);
        const passed = Math.abs(x) > 200 && Math.abs(y) > 200 && Math.abs(z) > 200;
        await this.softReset();
        return passed;
    }
    async calibrateOffset(axes = 0x07, mode = 1) {
        if (mode < 0 || mode > 3) throw new Error('mode must be 0..3');
        let cr4 = await this._readReg(_REG_CTRL_REG4);
        cr4 = (cr4 & 0xFC) | (mode & 0x03);
        await this._writeReg(_REG_CTRL_REG4, cr4 & 0xFF);
        const bits = [
            { mask: 0x01, ctrl1Bit: 0x80 },
            { mask: 0x02, ctrl1Bit: 0x40 },
            { mask: 0x04, ctrl1Bit: 0x20 },
        ];
        for (const { mask, ctrl1Bit } of bits) {
            if (!(axes & mask)) continue;
            let ctrl1 = await this._readReg(_REG_CTRL_REG1);
            ctrl1 |= ctrl1Bit;
            await this._writeReg(_REG_CTRL_REG1, ctrl1 & 0xFF);
            for (let t = 0; t < 100; t++) {
                const s1 = await this._readReg(_REG_STATUS_REG1);
                if (s1 & 0x02) break;
                _delayMs(100);
            }
            ctrl1 = await this._readReg(_REG_CTRL_REG1);
            ctrl1 &= ~ctrl1Bit & 0xFF;
            await this._writeReg(_REG_CTRL_REG1, ctrl1 & 0xFF);
        }
        cr4 = await this._readReg(_REG_CTRL_REG4);
        cr4 &= 0xFC;
        await this._writeReg(_REG_CTRL_REG4, cr4 & 0xFF);
    }
    /** @returns {Promise<[number, number]>} (al_version, ml_version). */
    async readVersion() {
        const raw = await this._readReg(_REG_VERSION);
        return [(raw >> 4) & 0x0F, raw & 0x0F];
    }
    async readCustomer(index) {
        return this._readReg(index === 0 ? _REG_CD1 : _REG_CD2);
    }
    async writeCustomer(index, value) {
        await this._writeReg(index === 0 ? _REG_CD1 : _REG_CD2, value & 0xFF);
    }
    async _writeThreshold(reg, thresholdG) {
        let code = Math.round(thresholdG / this._rangeG * 255.0);
        if (code < 0) code = 0;
        if (code > 255) code = 255;
        await this._writeReg(reg, code & 0xFF);
    }
    async _writeSlopeThreshold(reg, thresholdG) {
        let code = Math.round(thresholdG / (0.0156 * this._rangeG / 2.0));
        if (code < 0) code = 0;
        if (code > 255) code = 255;
        await this._writeReg(reg, code & 0xFF);
    }
    async _writeLowDur(durationMs) {
        let code = Math.round(durationMs / _DUR_LSB_MS);
        if (code < 0) code = 0;
        if (code > 127) code = 127;
        const ld = await this._readReg(_REG_LOW_DUR);
        await this._writeReg(_REG_LOW_DUR, ((ld & 0x01) | ((code & 0x7F) << 1)) & 0xFF);
    }
    async _writeHighDur(durationMs) {
        let code = Math.round(durationMs / _DUR_LSB_MS);
        if (code < 0) code = 0;
        if (code > 127) code = 127;
        const hd = await this._readReg(_REG_HIGH_DUR);
        await this._writeReg(_REG_HIGH_DUR, ((hd & 0x01) | ((code & 0x7F) << 1)) & 0xFF);
    }
    async _writeLowHysteresis(hysteresisG) {
        let code = Math.round(hysteresisG / this._rangeG * 255.0 / 32.0);
        if (code < 0) code = 0;
        if (code > 31) code = 31;
        const hy = await this._readReg(_REG_HY);
        await this._writeReg(_REG_HY, (hy & 0xF8) | (code & 0x07));
        const cr4 = await this._readReg(_REG_CTRL_REG4);
        await this._writeReg(_REG_CTRL_REG4, (cr4 & ~(0x03 << 6)) | (((code >> 3) & 0x03) << 6));
    }
    async _writeHighHysteresis(hysteresisG) {
        let code = Math.round(hysteresisG / this._rangeG * 255.0 / 32.0);
        if (code < 0) code = 0;
        if (code > 31) code = 31;
        const hy = await this._readReg(_REG_HY);
        await this._writeReg(_REG_HY, ((hy & 0x07) | ((code & 0x1F) << 3)) & 0xFF);
    }
    async _writeAxisEnablesLow(axes) {
        const hli = await this._readReg(_REG_HIGH_LOW_INFO);
        await this._writeReg(_REG_HIGH_LOW_INFO, ((hli & 0xF1) | ((axes & 0x07) << 1)) & 0xFF);
    }
    async _writeAxisEnablesHigh(axes) {
        const hli = await this._readReg(_REG_HIGH_LOW_INFO);
        await this._writeReg(_REG_HIGH_LOW_INFO, ((hli & 0x0F) | ((axes & 0x07) << 5)) & 0xFF);
    }
    async _writeAxisEnablesSlope(axes) {
        const st = await this._readReg(_REG_SLOPE_TAPSENS);
        await this._writeReg(_REG_SLOPE_TAPSENS, ((st & 0x0F) | ((axes & 0x07) << 5)) & 0xFF);
    }
    async _writeAxisEnablesTap(axes) {
        const st = await this._readReg(_REG_SLOPE_TAPSENS);
        await this._writeReg(_REG_SLOPE_TAPSENS, ((st & 0xF1) | ((axes & 0x07) << 1)) & 0xFF);
    }
    async _writeFiltBit(reg, bit, enabled) {
        const v = await this._readReg(reg);
        if (enabled) await this._writeReg(reg, (v | bit) & 0xFF);
        else await this._writeReg(reg, (v & ~bit) & 0xFF);
    }
    async _writeDebounce(kind, counter) {
        if (counter < 0 || counter > 3) throw new Error('counter must be 0..3');
        const code = (counter & 0x03) << 2;
        const cr4 = await this._readReg(_REG_CTRL_REG4);
        if (kind === 'lg') {
            await this._writeReg(_REG_CTRL_REG4, (cr4 & ~(0x03 << 2)) | (code & (0x03 << 2)));
        } else {
            await this._writeReg(_REG_CTRL_REG4, (cr4 & ~(0x03 << 4)) | ((code << 2) & (0x03 << 4)));
        }
    }
    async _writeSlopeDur(samples) {
        if (!(samples in _SLOPE_DUR_CODES)) throw new Error('samples must be 1, 3, 5, or 7');
        const tcox = await this._readReg(_REG_TCO_X);
        await this._writeReg(_REG_TCO_X, ((tcox & 0xFC) | _SLOPE_DUR_CODES[samples]) & 0xFF);
    }
    async _writeTapDur(windowMs) {
        const code = _nearestTapDur(windowMs);
        const gt = await this._readReg(_REG_GAIN_T);
        await this._writeReg(_REG_GAIN_T, ((gt & 0xF8) | code) & 0xFF);
    }
    async _writeCr3Bit(bit, enabled) {
        if (this._sleeping) return;
        const cr3 = await this._readReg(_REG_CTRL_REG3);
        if (enabled) await this._writeReg(_REG_CTRL_REG3, (cr3 | bit) & 0xFF);
        else await this._writeReg(_REG_CTRL_REG3, (cr3 & ~bit) & 0xFF);
    }
    async _enableSource(source) {
        if (this._sleeping) return;
        this._enabledSources |= source;
    }
    async _disableSource(source) {
        this._enabledSources &= ~source & 0xFF;
    }
}

BMA180Full.SOURCE_LOW_G    = 0x01;
BMA180Full.SOURCE_HIGH_G   = 0x02;
BMA180Full.SOURCE_SLOPE    = 0x04;
BMA180Full.SOURCE_ALERT    = 0x08;
BMA180Full.SOURCE_TAP      = 0x10;
BMA180Full.SOURCE_NEW_DATA = 0x20;

module.exports = { BMA180Minimal, BMA180Full };