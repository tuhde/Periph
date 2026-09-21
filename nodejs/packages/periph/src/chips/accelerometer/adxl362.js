'use strict';

/**
 * ADXL362 3-axis MEMS accelerometer driver (SPI).
 *
 * The ADXL362 is an ultralow power 3-axis accelerometer with 12-bit
 * output resolution, a 512-sample FIFO, on-chip temperature sensor,
 * autonomous activity/inactivity (motion) detection with two independent
 * interrupt pins, and a wake-up mode that consumes ~270 nA. The
 * ADXL362 is SPI-only — there is no I²C mode.
 *
 * SPI command structure:
 *   WRITE register   0x0A + addr + data...
 *   READ  register   0x0B + addr + data...
 *   READ  FIFO       0x0D       + data...
 *
 * Default configuration baked into Minimal:
 *   - ±2 g measurement range
 *   - 100 Hz output data rate, ODR/4 antialiasing bandwidth
 *   - Normal noise mode (POWER_CTL.LOW_NOISE=00)
 *   - Continuous measurement mode (POWER_CTL.MEASURE=10)
 *   - FIFO disabled
 *   - No interrupts mapped; INT1/INT2 high-impedance
 */

// SPI command bytes.
const CMD_WRITE_REG = 0x0A;
const CMD_READ_REG  = 0x0B;
const CMD_READ_FIFO = 0x0D;

// Soft-reset key (ASCII 'R').
const SOFT_RESET_KEY = 0x52;

// Per-range sensitivity (g/LSB), typical. The ±8 g value is intentionally
// not 4× the ±2 g value (4.255 mg/LSB vs. 1 mg/LSB) per datasheet.
const SENSITIVITY_G_PER_LSB = [0.001, 0.002, 0.004255];

// ODR codes (FILTER_CTL bits 2:0) and their actual rates in Hz. Codes
// 0x05–0x07 all map to 400 Hz.
const ODR_CODES = [
    [0x00, 12.5],
    [0x01, 25.0],
    [0x02, 50.0],
    [0x03, 100.0],
    [0x04, 200.0],
    [0x05, 400.0],
    [0x06, 400.0],
    [0x07, 400.0],
];

// Register map (6-bit addresses).
const REG_DEVID_AD        = 0x00;
const REG_DEVID_MST       = 0x01;
const REG_PARTID          = 0x02;
const REG_XDATA           = 0x08;
const REG_YDATA           = 0x09;
const REG_ZDATA           = 0x0A;
const REG_STATUS          = 0x0B;
const REG_FIFO_ENTRIES_L  = 0x0C;
const REG_FIFO_ENTRIES_H  = 0x0D;
const REG_XDATA_L         = 0x0E;
const REG_XDATA_H         = 0x0F;
const REG_YDATA_L         = 0x10;
const REG_YDATA_H         = 0x11;
const REG_ZDATA_L         = 0x12;
const REG_ZDATA_H         = 0x13;
const REG_TEMP_L          = 0x14;
const REG_TEMP_H          = 0x15;
const REG_SOFT_RESET      = 0x1F;
const REG_THRESH_ACT_L    = 0x20;
const REG_THRESH_ACT_H    = 0x21;
const REG_TIME_ACT        = 0x22;
const REG_THRESH_INACT_L  = 0x23;
const REG_THRESH_INACT_H  = 0x24;
const REG_TIME_INACT_L    = 0x25;
const REG_TIME_INACT_H    = 0x26;
const REG_ACT_INACT_CTL   = 0x27;
const REG_FIFO_CONTROL    = 0x28;
const REG_FIFO_SAMPLES    = 0x29;
const REG_INTMAP1         = 0x2A;
const REG_INTMAP2         = 0x2B;
const REG_FILTER_CTL      = 0x2C;
const REG_POWER_CTL       = 0x2D;
const REG_SELF_TEST       = 0x2E;

const DEVID_AD_VALUE  = 0xAD;
const DEVID_MST_VALUE = 0x1D;
const PARTID_VALUE    = 0xF2;

// FILTER_CTL reset value: RANGE=±2 g, HALF_BW=1, ODR=100 Hz.
const FILTER_CTL_DEFAULT = 0x13;
// POWER_CTL measurement-mode value: MEASURE=10.
const POWER_CTL_MEASURE  = 0x02;

const STATUS_AWAKE     = 0x40;
const STATUS_DATA_READY = 0x01;

function _delayMs(ms) {
    return new Promise(r => setTimeout(r, ms));
}

function _signExtend12(v) {
    v &= 0x0FFF;
    return (v & 0x0800) ? (v - 0x1000) : v;
}

/**
 * ADXL362 minimal driver — read X, Y, Z acceleration in *g*.
 *
 * Performs the chip's full power-up sequence at construction: verifies
 * DEVID_AD=0xAD, DEVID_MST=0x1D, PARTID=0xF2; writes the reset FILTER_CTL
 * and switches POWER_CTL into measurement mode.
 */
class ADXL362Minimal {
    /**
     * @param {object} connection - Configured SPI connection bound to the device.
     */
    constructor(connection) {
        this._connection = connection;
        this._rangeBits = 0x00;  // ±2 g default
        this._odrHz = 100.0;
        this._init();
    }

    /**
     * Run the chip's full power-up sequence.
     *
     * Verifies DEVID_AD=0xAD, DEVID_MST=0x1D, PARTID=0xF2; writes the
     * reset FILTER_CTL and switches POWER_CTL into measurement mode.
     *
     * @returns {Promise<void>}
     */
    async init() {
        await _delayMs(5);  // power-up to standby turn-on time
        const ids = await this._readBurst(REG_DEVID_AD, 3);
        if (ids[0] !== DEVID_AD_VALUE) {
            throw new Error(`ADXL362 DEVID_AD: expected 0xAD, got 0x${ids[0].toString(16)}`);
        }
        if (ids[1] !== DEVID_MST_VALUE) {
            throw new Error(`ADXL362 DEVID_MST: expected 0x1D, got 0x${ids[1].toString(16)}`);
        }
        if (ids[2] !== PARTID_VALUE) {
            throw new Error(`ADXL362 PARTID: expected 0xF2, got 0x${ids[2].toString(16)}`);
        }
        await this._writeReg(REG_FILTER_CTL, FILTER_CTL_DEFAULT);
        await this._writeReg(REG_POWER_CTL, POWER_CTL_MEASURE);
        await _delayMs(40);  // 4/ODR at 100 Hz
    }

    async _readBurst(reg, n) {
        return this._connection.writeRead(Buffer.from([CMD_READ_REG, reg & 0x3F]), n);
    }

    async _readFifo(n) {
        return this._connection.writeRead(Buffer.from([CMD_READ_FIFO]), n);
    }

    async _writeReg(reg, value) {
        await this._connection.write(Buffer.from([CMD_WRITE_REG, reg & 0x3F, value & 0xFF]));
    }

    async _readReg(reg) {
        const out = await this._readBurst(reg, 1);
        return out[0];
    }

    _sensitivity() {
        if (this._rangeBits === 0x40) return SENSITIVITY_G_PER_LSB[1];
        if (this._rangeBits === 0x80 || this._rangeBits === 0xC0) return SENSITIVITY_G_PER_LSB[2];
        return SENSITIVITY_G_PER_LSB[0];
    }

    /**
     * Read 3-axis linear acceleration.
     *
     * Burst-reads the 12-bit XDATA_L/H, YDATA_L/H, ZDATA_L/H sextet so
     * the three samples come from a single measurement.
     *
     * @returns {Promise<{x:number, y:number, z:number}>} Acceleration in *g*.
     */
    async read() {
        const raw = await this._readBurst(REG_XDATA_L, 6);
        const rx = _signExtend12(((raw[1] & 0x0F) << 8) | raw[0]);
        const ry = _signExtend12(((raw[3] & 0x0F) << 8) | raw[2]);
        const rz = _signExtend12(((raw[5] & 0x0F) << 8) | raw[4]);
        const sens = this._sensitivity();
        return { x: rx * sens, y: ry * sens, z: rz * sens };
    }
}

/**
 * Mixin that adds Full-stage functionality on top of `ADXL362Minimal`.
 *
 * Usage: `class ADXL362Full extends _ADXL362FullMixin(ADXL362Minimal) {}`.
 */
const _ADXL362FullMixin = (Base) => class extends Base {
    /**
     * Return raw device-ID bytes (DEVID_AD, DEVID_MST, PARTID, REVID).
     * @returns {Promise<{devidAd:number, devidMst:number, partid:number, revid:number}>}
     */
    async deviceId() {
        const ids = await this._readBurst(REG_DEVID_AD, 4);
        return { devidAd: ids[0], devidMst: ids[1], partid: ids[2], revid: ids[3] };
    }

    /**
     * Soft-reset the chip (writes 0x52 to SOFT_RESET, waits ≥0.5 ms).
     * @returns {Promise<void>}
     */
    async softReset() {
        await this._writeReg(REG_SOFT_RESET, SOFT_RESET_KEY);
        await _delayMs(1);
        this._rangeBits = 0x00;
        this._odrHz = 100.0;
    }

    /**
     * Set the measurement range to ±2/±4/±8 g.
     * @param {number} rangeG - 2, 4, or 8.
     * @returns {Promise<void>}
     */
    async setRange(rangeG) {
        let code = 0;
        if (rangeG === 2) code = 0x00;
        else if (rangeG === 4) code = 0x40;
        else if (rangeG === 8) code = 0x80;
        else throw new RangeError('rangeG must be one of 2, 4, 8');
        const f = await this._readReg(REG_FILTER_CTL);
        await this._writeReg(REG_FILTER_CTL, (f & 0x3F) | (code & 0xC0));
        this._rangeBits = code;
        if (this._odrHz > 0) {
            await _delayMs(1000.0 / this._odrHz + 1);
        }
    }

    /**
     * Set the output data rate to the nearest supported value (12.5–400 Hz).
     * @param {number} odrHz - Requested ODR in Hz.
     * @returns {Promise<void>}
     */
    async setOdr(odrHz) {
        let bestCode = ODR_CODES[0][0];
        let bestRate = ODR_CODES[0][1];
        let bestDiff = Math.abs(bestRate - odrHz);
        for (const [code, rate] of ODR_CODES) {
            const d = Math.abs(rate - odrHz);
            if (d < bestDiff) {
                bestCode = code; bestRate = rate; bestDiff = d;
            }
        }
        const f = await this._readReg(REG_FILTER_CTL);
        await this._writeReg(REG_FILTER_CTL, (f & 0xF8) | (bestCode & 0x07));
        this._odrHz = bestRate;
    }

    /**
     * Set FILTER_CTL.HALF_BW (antialiasing bandwidth = ODR/4 or ODR/2).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setHalfBandwidth(enabled) {
        const f = await this._readReg(REG_FILTER_CTL);
        await this._writeReg(REG_FILTER_CTL, enabled ? (f | 0x10) : (f & ~0x10));
    }

    /**
     * Set POWER_CTL.LOW_NOISE (0=normal, 1=low, 2=ultralow noise).
     * @param {number} mode - 0, 1, or 2.
     * @returns {Promise<void>}
     */
    async setNoiseMode(mode) {
        if (mode !== 0 && mode !== 1 && mode !== 2) {
            throw new RangeError('mode must be 0 (normal), 1 (low), or 2 (ultralow)');
        }
        const p = await this._readReg(REG_POWER_CTL);
        await this._writeReg(REG_POWER_CTL, (p & 0xCF) | ((mode << 4) & 0x30));
    }

    /**
     * Set POWER_CTL.WAKEUP (270 nA idle mode).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setWakeupMode(enabled) {
        const p = await this._readReg(REG_POWER_CTL);
        await this._writeReg(REG_POWER_CTL, enabled ? (p | 0x08) : (p & ~0x08));
    }

    /**
     * Set POWER_CTL.AUTOSLEEP; effective only in linked/loop mode.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setAutosleep(enabled) {
        const p = await this._readReg(REG_POWER_CTL);
        await this._writeReg(REG_POWER_CTL, enabled ? (p | 0x04) : (p & ~0x04));
    }

    /**
     * Set POWER_CTL.EXT_CLK; INT1 is repurposed as clock input.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setExternalClock(enabled) {
        const p = await this._readReg(REG_POWER_CTL);
        await this._writeReg(REG_POWER_CTL, enabled ? (p | 0x40) : (p & ~0x40));
    }

    /**
     * Set FILTER_CTL.EXT_SAMPLE; INT2 is repurposed as sync trigger input.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setExternalSampleTrigger(enabled) {
        const f = await this._readReg(REG_FILTER_CTL);
        await this._writeReg(REG_FILTER_CTL, enabled ? (f | 0x08) : (f & ~0x08));
    }

    /**
     * Read 3-axis acceleration using the 8-bit XDATA/YDATA/ZDATA registers.
     * @returns {Promise<{x:number, y:number, z:number}>} Acceleration in *g*, ~16-LSB resolution.
     */
    async read8bit() {
        const raw = await this._readBurst(REG_XDATA, 3);
        const s8 = v => (v & 0x80) ? (v - 256) : v;
        const sens = this._sensitivity() * 16;
        return { x: s8(raw[0]) * sens, y: s8(raw[1]) * sens, z: s8(raw[2]) * sens };
    }

    /**
     * Read the on-chip temperature sensor (typical bias/sensitivity).
     * @returns {Promise<number>} Temperature in °C.
     */
    async temperature() {
        const raw = await this._readBurst(REG_TEMP_L, 2);
        const raw12 = _signExtend12(((raw[1] & 0x0F) << 8) | raw[0]);
        return 25.0 + (raw12 - 350) * 0.065;
    }

    /**
     * Read the raw STATUS register byte.
     * @returns {Promise<number>}
     */
    async status() {
        return await this._readReg(REG_STATUS);
    }

    /**
     * Return STATUS.AWAKE.
     * @returns {Promise<boolean>}
     */
    async awake() {
        return (await this.status()) & STATUS_AWAKE ? true : false;
    }

    /**
     * Return STATUS.DATA_READY.
     * @returns {Promise<boolean>}
     */
    async dataReady() {
        return (await this.status()) & STATUS_DATA_READY ? true : false;
    }

    /**
     * Return the 10-bit FIFO entry count (0–512).
     * @returns {Promise<number>}
     */
    async fifoEntries() {
        const lo = await this._readReg(REG_FIFO_ENTRIES_L);
        const hi = await this._readReg(REG_FIFO_ENTRIES_H);
        return lo | ((hi & 0x03) << 8);
    }

    /**
     * Configure the FIFO mode, optional temperature storage, and watermark.
     * @param {number} mode - 0=disabled, 1=oldest saved, 2=stream, 3=triggered.
     * @param {boolean} [storeTemp=false]
     * @param {number} [watermark=128] - 9-bit watermark (0–511).
     * @returns {Promise<void>}
     */
    async configureFifo(mode, storeTemp = false, watermark = 128) {
        if (mode < 0 || mode > 3) throw new RangeError('mode must be 0/1/2/3');
        if (watermark < 0 || watermark > 0x1FF) throw new RangeError('watermark must be 0–511');
        const fc = (mode & 0x03) | (((watermark >> 8) & 0x01) << 3) | (storeTemp ? 0x04 : 0x00);
        await this._writeReg(REG_FIFO_CONTROL, fc);
        await this._writeReg(REG_FIFO_SAMPLES, watermark & 0xFF);
    }

    /**
     * Read all available FIFO entries.
     *
     * Each 16-bit entry's top two bits encode the axis (0=X, 1=Y,
     * 2=Z, 3=temperature); the low 12 bits are signed axis/temperature
     * data, scaled with the current range.
     *
     * @returns {Promise<Array<{axis:number, value:number}>>}
     */
    async readFifo() {
        const n = await this.fifoEntries();
        if (n === 0) return [];
        const raw = await this._readFifo(n * 2);
        const sens = this._sensitivity();
        const out = [];
        for (let i = 0; i < n; i++) {
            const lo = raw[2 * i];
            const hi = raw[2 * i + 1];
            const raw16 = (hi << 8) | lo;
            const axis = (raw16 >> 14) & 0x03;
            const raw12 = _signExtend12(raw16 & 0x0FFF);
            const value = (axis === 3) ? 25.0 + (raw12 - 350) * 0.065 : raw12 * sens;
            out.push({ axis, value });
        }
        return out;
    }

    /**
     * Set the activity threshold in *g* (clamped to 10-bit range).
     * @param {number} thresholdG
     * @param {boolean} [referenced=false]
     * @returns {Promise<void>}
     */
    async setActivityThreshold(thresholdG, referenced = false) {
        let raw = Math.round(thresholdG / this._sensitivity());
        if (raw < 0) raw = 0;
        if (raw > 0x3FF) raw = 0x3FF;
        await this._writeReg(REG_THRESH_ACT_L, raw & 0xFF);
        await this._writeReg(REG_THRESH_ACT_H, (raw >> 8) & 0x03);
        const aic = await this._readReg(REG_ACT_INACT_CTL);
        await this._writeReg(REG_ACT_INACT_CTL, referenced ? (aic | 0x02) : (aic & ~0x02));
    }

    /**
     * Set the activity-time filter (0–255 samples).
     * @param {number} samples
     * @returns {Promise<void>}
     */
    async setActivityTime(samples) {
        if (samples < 0 || samples > 0xFF) throw new RangeError('samples must be 0–255');
        await this._writeReg(REG_TIME_ACT, samples & 0xFF);
    }

    /**
     * Set the inactivity threshold in *g* (clamped to 10-bit range).
     * @param {number} thresholdG
     * @param {boolean} [referenced=false]
     * @returns {Promise<void>}
     */
    async setInactivityThreshold(thresholdG, referenced = false) {
        let raw = Math.round(thresholdG / this._sensitivity());
        if (raw < 0) raw = 0;
        if (raw > 0x3FF) raw = 0x3FF;
        await this._writeReg(REG_THRESH_INACT_L, raw & 0xFF);
        await this._writeReg(REG_THRESH_INACT_H, (raw >> 8) & 0x03);
        const aic = await this._readReg(REG_ACT_INACT_CTL);
        await this._writeReg(REG_ACT_INACT_CTL, referenced ? (aic | 0x08) : (aic & ~0x08));
    }

    /**
     * Set the inactivity-time filter (0–65535 samples).
     * @param {number} samples
     * @returns {Promise<void>}
     */
    async setInactivityTime(samples) {
        if (samples < 0 || samples > 0xFFFF) throw new RangeError('samples must be 0–65535');
        await this._writeReg(REG_TIME_INACT_L, samples & 0xFF);
        await this._writeReg(REG_TIME_INACT_H, (samples >> 8) & 0xFF);
    }

    /**
     * Set ACT_INACT_CTL.ACT_EN.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async enableActivityDetection(enabled) {
        const aic = await this._readReg(REG_ACT_INACT_CTL);
        await this._writeReg(REG_ACT_INACT_CTL, enabled ? (aic | 0x01) : (aic & ~0x01));
    }

    /**
     * Set ACT_INACT_CTL.INACT_EN.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async enableInactivityDetection(enabled) {
        const aic = await this._readReg(REG_ACT_INACT_CTL);
        await this._writeReg(REG_ACT_INACT_CTL, enabled ? (aic | 0x04) : (aic & ~0x04));
    }

    /**
     * Set ACT_INACT_CTL.LINKLOOP (0=default, 1=linked, 3=loop).
     * @param {number} mode
     * @returns {Promise<void>}
     */
    async setLinkLoopMode(mode) {
        if (mode !== 0 && mode !== 1 && mode !== 3) {
            throw new RangeError('mode must be 0 (default), 1 (linked), or 3 (loop)');
        }
        const aic = await this._readReg(REG_ACT_INACT_CTL);
        await this._writeReg(REG_ACT_INACT_CTL, (aic & 0xCF) | ((mode << 4) & 0x30));
    }

    static _INTMAP_BIT(source) {
        switch (source) {
            case 0: return 0x01;  // SOURCE_DATA_READY
            case 1: return 0x02;  // SOURCE_FIFO_READY
            case 2: return 0x04;  // SOURCE_FIFO_WATERMARK
            case 3: return 0x08;  // SOURCE_FIFO_OVERRUN
            case 4: return 0x10;  // SOURCE_ACT
            case 5: return 0x20;  // SOURCE_INACT
            case 6: return 0x40;  // SOURCE_AWAKE
            default: throw new RangeError('invalid source');
        }
    }

    /**
     * Map one interrupt source to the named INT pin.
     * @param {number} pin - 1 or 2.
     * @param {number} source - One of SOURCE_* (0–6).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setInterrupt(pin, source, enabled) {
        if (pin !== 1 && pin !== 2) throw new RangeError('pin must be 1 or 2');
        const reg = (pin === 1) ? REG_INTMAP1 : REG_INTMAP2;
        const cur = await this._readReg(reg);
        const bit = this.constructor._INTMAP_BIT(source);
        await this._writeReg(reg, enabled ? (cur | bit) : (cur & ~bit));
    }

    /**
     * Set the active-low polarity for one INT pin.
     * @param {number} pin - 1 or 2.
     * @param {boolean} activeLow
     * @returns {Promise<void>}
     */
    async setInterruptPolarity(pin, activeLow) {
        if (pin !== 1 && pin !== 2) throw new RangeError('pin must be 1 or 2');
        const reg = (pin === 1) ? REG_INTMAP1 : REG_INTMAP2;
        const cur = await this._readReg(reg);
        await this._writeReg(reg, activeLow ? (cur | 0x80) : (cur & ~0x80));
    }

    /**
     * Enable or disable the electrostatic self-test force on all axes.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async selfTest(enabled) {
        const st = await this._readReg(REG_SELF_TEST);
        await this._writeReg(REG_SELF_TEST, enabled ? (st | 0x01) : (st & ~0x01));
        if (enabled && this._odrHz > 0) {
            await _delayMs(4000.0 / this._odrHz + 1);
        }
    }
};

/**
 * ADXL362 full driver — extends Minimal with the full chip API.
 */
class ADXL362Full extends _ADXL362FullMixin(ADXL362Minimal) {
}

// Interrupt source constants (used by setInterrupt).
ADXL362Full.SOURCE_DATA_READY    = 0;
ADXL362Full.SOURCE_FIFO_READY    = 1;
ADXL362Full.SOURCE_FIFO_WATERMARK = 2;
ADXL362Full.SOURCE_FIFO_OVERRUN  = 3;
ADXL362Full.SOURCE_ACT           = 4;
ADXL362Full.SOURCE_INACT         = 5;
ADXL362Full.SOURCE_AWAKE         = 6;

// Noise mode constants (POWER_CTL.LOW_NOISE[5:4]).
ADXL362Full.NOISE_NORMAL   = 0;
ADXL362Full.NOISE_LOW      = 1;
ADXL362Full.NOISE_ULTRALOW = 2;

// Link/loop mode constants (ACT_INACT_CTL.LINKLOOP[5:4]).
ADXL362Full.LINKLOOP_DEFAULT = 0;
ADXL362Full.LINKLOOP_LINKED  = 1;
ADXL362Full.LINKLOOP_LOOP    = 3;

// FIFO mode constants (FIFO_CONTROL.FIFO_MODE[1:0]).
ADXL362Full.FIFO_DISABLED     = 0;
ADXL362Full.FIFO_OLDEST_SAVED = 1;
ADXL362Full.FIFO_STREAM       = 2;
ADXL362Full.FIFO_TRIGGERED    = 3;

// FIFO entry-axis constants (top 2 bits of each 16-bit FIFO entry).
ADXL362Full.AXIS_X    = 0;
ADXL362Full.AXIS_Y    = 1;
ADXL362Full.AXIS_Z    = 2;
ADXL362Full.AXIS_TEMP = 3;

module.exports = { ADXL362Minimal, ADXL362Full };