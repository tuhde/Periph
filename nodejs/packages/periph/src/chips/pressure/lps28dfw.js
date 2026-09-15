'use strict';

const _REG_INTERRUPT_CFG = 0x0B;
const _REG_THS_P_L       = 0x0C;
const _REG_THS_P_H       = 0x0D;
const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x10;
const _REG_CTRL_REG2     = 0x11;
const _REG_STATUS        = 0x27;
const _REG_PRESS_OUT_XL  = 0x28;
const _REG_PRESS_OUT_L   = 0x29;
const _REG_PRESS_OUT_H   = 0x2A;
const _REG_TEMP_OUT_L    = 0x2B;
const _REG_TEMP_OUT_H    = 0x2C;
const _REG_FIFO_DATA_PRESS_XL = 0x78;

const _CHIP_ID  = 0xB4;
const _BOOT_WAIT_MS = 2;
const _SENSITIVITY_LSB_PER_HPA_MODE1 = 4096.0;
const _SENSITIVITY_LSB_PER_HPA_MODE2 = 2048.0;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * LPS28DFW dual full-scale digital barometer — minimal interface.
 *
 * Reads absolute pressure and temperature from the CCLGA-7L water-resistant
 * sensor. I²C address is 0x5C (SA0=GND) or 0x5D (SA0=VDD).
 *
 * Default configuration (baked in at construction):
 *     - FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)
 *     - AVG = 010b (16 samples)
 *     - ODR = 0100b (25 Hz)
 *     - BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
 */
class LPS28DFWMinimal {
    constructor(connection) {
        this._conn = connection;
        this._fsMode = 0;
        this._odr = 0x04;
        this._avg = 0x02;
        this._lpfEn = 1;
        this._lpfCfg = 0;
        this._bdu = 1;
        this._init();
    }

    async _init() {
        const who = await this._readReg(_REG_WHO_AM_I, 1);
        if (who[0] !== _CHIP_ID) {
            throw new Error('LPS28DFW WHO_AM_I mismatch: expected 0x' +
                _CHIP_ID.toString(16).toUpperCase().padStart(2, '0') +
                ', got 0x' + who[0].toString(16).toUpperCase().padStart(2, '0'));
        }
        _delay(_BOOT_WAIT_MS);
        const ctrl2 = (this._fsMode << 6) | (this._lpfCfg << 5) | (this._lpfEn << 4) | (this._bdu << 3);
        await this._writeReg(_REG_CTRL_REG2, ctrl2);
        const ctrl1 = (this._odr << 3) | (this._avg & 0x07);
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg, value]));
    }

    async _readReg(reg, n) {
        return this._conn.writeRead(Buffer.from([reg]), n);
    }

    /**
     * Read absolute pressure.
     * @returns {Promise<number>} Pressure in hPa.
     */
    async readPressure() {
        const raw = await this._readReg(_REG_PRESS_OUT_XL, 3);
        let v = (raw[2] << 16) | (raw[1] << 8) | raw[0];
        if (v & 0x800000) v |= 0xFF000000;
        const sens = this._fsMode === 0 ? _SENSITIVITY_LSB_PER_HPA_MODE1 : _SENSITIVITY_LSB_PER_HPA_MODE2;
        return v / sens;
    }

    /**
     * Read sensor temperature.
     * @returns {Promise<number>} Temperature in degrees Celsius.
     */
    async readTemperature() {
        const raw = await this._readReg(_REG_TEMP_OUT_L, 2);
        const v = raw.readInt16BE(0);
        return v / 100.0;
    }
}

/**
 * LPS28DFW full interface — extends LPS28DFWMinimal with full configuration.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
 */
class LPS28DFWFull extends LPS28DFWMinimal {
    static ODR_POWER_DOWN = 0x00;
    static ODR_1_HZ       = 0x01;
    static ODR_4_HZ       = 0x02;
    static ODR_10_HZ      = 0x03;
    static ODR_25_HZ      = 0x04;
    static ODR_50_HZ      = 0x05;
    static ODR_75_HZ      = 0x06;
    static ODR_100_HZ     = 0x07;
    static ODR_200_HZ     = 0x08;

    static AVG_4   = 0x00;
    static AVG_8   = 0x01;
    static AVG_16  = 0x02;
    static AVG_32  = 0x03;
    static AVG_64  = 0x04;
    static AVG_128 = 0x05;
    static AVG_512 = 0x07;

    static FS_MODE_1 = 0;
    static FS_MODE_2 = 1;

    static LFPF_ODR_OVER_4 = 0;
    static LFPF_ODR_OVER_9 = 1;

    static FIFO_BYPASS               = 0;
    static FIFO_FIFO                 = 1;
    static FIFO_CONTINUOUS           = 2;
    static FIFO_BYPASS_TO_FIFO       = 4;
    static FIFO_BYPASS_TO_CONTINUOUS = 5;
    static FIFO_CONTINUOUS_TO_FIFO   = 6;

    static STATUS_P_DA = 0x01;
    static STATUS_T_DA = 0x02;
    static STATUS_P_OR = 0x10;
    static STATUS_T_OR = 0x20;

    /**
     * Set output data rate, averaging, full-scale mode, and IIR filter.
     * @param {number} odr    Output data rate code (0–8).
     * @param {number} avg    Averaging code (0–7).
     * @param {number} fsMode 0=Mode 1, 1=Mode 2.
     * @param {boolean} lpfEn True to enable the IIR low-pass filter.
     * @param {number} lpfCfg 0=ODR/4, 1=ODR/9.
     * @returns {Promise<void>}
     */
    async configure(odr, avg, fsMode, lpfEn, lpfCfg) {
        this._odr = odr;
        this._avg = avg;
        this._fsMode = fsMode;
        this._lpfEn = lpfEn ? 1 : 0;
        this._lpfCfg = lpfCfg;
        const ctrl2 = (this._fsMode << 6) | (this._lpfCfg << 5) | (this._lpfEn << 4) | (this._bdu << 3);
        await this._writeReg(_REG_CTRL_REG2, ctrl2);
        const ctrl1 = (this._odr << 3) | (this._avg & 0x07);
        await this._writeReg(_REG_CTRL_REG1, ctrl1);
    }

    /**
     * Burst-read pressure and temperature.
     * @returns {Promise<{pressure: number, temperature: number}>}
     */
    async read() {
        const raw = await this._readReg(_REG_PRESS_OUT_XL, 5);
        let p = (raw[2] << 16) | (raw[1] << 8) | raw[0];
        if (p & 0x800000) p |= 0xFF000000;
        const t = raw.readInt16BE(3);
        const sens = this._fsMode === 0 ? _SENSITIVITY_LSB_PER_HPA_MODE1 : _SENSITIVITY_LSB_PER_HPA_MODE2;
        return { pressure: p / sens, temperature: t / 100.0 };
    }

    /**
     * Trigger a one-shot measurement (with ODR=0000) and read the result.
     * @returns {Promise<{pressure: number, temperature: number}>}
     */
    async readOneshot() {
        const saved = await this._readReg(_REG_CTRL_REG1, 1);
        const savedOdr = saved[0] >> 3;
        await this._writeReg(_REG_CTRL_REG1, (0 << 3) | (this._avg & 0x07));
        const c2 = await this._readReg(_REG_CTRL_REG2, 1);
        await this._writeReg(_REG_CTRL_REG2, c2[0] | 0x01);
        for (let i = 0; i < 200; i++) {
            const status = await this._readReg(_REG_STATUS, 1);
            if (status[0] & LPS28DFWFull.STATUS_P_DA) break;
            _delay(5);
        }
        const result = await this.read();
        await this._writeReg(_REG_CTRL_REG1, (savedOdr << 3) | (this._avg & 0x07));
        return result;
    }

    /**
     * Check whether STATUS.P_DA is set.
     * @returns {Promise<boolean>}
     */
    async isDataReady() {
        const status = await this._readReg(_REG_STATUS, 1);
        return (status[0] & LPS28DFWFull.STATUS_P_DA) !== 0;
    }

    /**
     * Program the one-point calibration offset (RPDS).
     * @param {number} offsetHpa - Offset in hPa to subtract.
     * @returns {Promise<void>}
     */
    async setOffset(offsetHpa) {
        const sens = this._fsMode === 0 ? _SENSITIVITY_LSB_PER_HPA_MODE1 : _SENSITIVITY_LSB_PER_HPA_MODE2;
        let raw = Math.trunc(offsetHpa * sens);
        if (raw < 0) raw += 0x10000;
        await this._writeReg(0x1A, raw & 0xFF);
        await this._writeReg(0x1B, (raw >> 8) & 0xFF);
    }

    /**
     * Issue a software reset and wait for the chip to reboot (~2 ms).
     * @returns {Promise<void>}
     */
    async softreset() {
        const c2 = await this._readReg(_REG_CTRL_REG2, 1);
        await this._writeReg(_REG_CTRL_REG2, c2[0] | 0x02);
        _delay(_BOOT_WAIT_MS);
    }

    /**
     * Configure FIFO mode, watermark level, and stop-on-watermark.
     * @param {number} mode - FIFO mode code (0–6).
     * @param {number} wtm  - Watermark level, 0–127.
     * @param {boolean} stopOnWtm
     * @returns {Promise<void>}
     */
    async fifoConfigure(mode, wtm, stopOnWtm) {
        if (mode === LPS28DFWFull.FIFO_BYPASS) {
            await this._writeReg(0x14, 0x00);
        }
        const trig = mode >= 4 ? 1 : 0;
        const fMode = mode & 0x03;
        const ctrl = (trig << 2) | ((stopOnWtm ? 1 : 0) << 3) | fMode;
        await this._writeReg(0x14, ctrl);
        await this._writeReg(0x15, wtm & 0x7F);
    }

    /**
     * Drain up to `count` pressure samples from the FIFO.
     * @param {number} count - Number of samples to read.
     * @returns {Promise<number[]>} Pressure values in hPa.
     */
    async fifoRead(count) {
        if (count <= 0) return [];
        if (count > 128) count = 128;
        const raw = await this._readReg(_REG_FIFO_DATA_PRESS_XL, count * 3);
        const out = [];
        const sens = this._fsMode === 0 ? _SENSITIVITY_LSB_PER_HPA_MODE1 : _SENSITIVITY_LSB_PER_HPA_MODE2;
        for (let i = 0; i < count; i++) {
            const b = i * 3;
            let v = (raw[b + 2] << 16) | (raw[b + 1] << 8) | raw[b];
            if (v & 0x800000) v |= 0xFF000000;
            out.push(v / sens);
        }
        return out;
    }

    /**
     * Return the number of unread samples in the FIFO.
     * @returns {Promise<number>}
     */
    async fifoLevel() {
        const buf = await this._readReg(0x25, 1);
        return buf[0];
    }

    /**
     * Program the pressure threshold and enable interrupt sources.
     * @param {number} thresholdHpa - Pressure threshold in hPa.
     * @param {boolean} high - True to assert PH when pressure exceeds threshold.
     * @param {boolean} low  - True to assert PL when pressure falls below threshold.
     * @returns {Promise<void>}
     */
    async setThreshold(thresholdHpa, high, low) {
        const sens = this._fsMode === 0 ? 16.0 : 8.0;
        let raw = Math.trunc(thresholdHpa * sens);
        if (raw < 0) raw = 0;
        if (raw > 0x7FFF) raw = 0x7FFF;
        await this._writeReg(0x0C, raw & 0xFF);
        await this._writeReg(0x0D, (raw >> 8) & 0x7F);
        const cfg = await this._readReg(_REG_INTERRUPT_CFG, 1);
        let v = cfg[0] & ~0x03;
        if (high) v |= 0x01;
        if (low)  v |= 0x02;
        await this._writeReg(_REG_INTERRUPT_CFG, v);
    }

    /**
     * Read the WHO_AM_I register.
     * @returns {Promise<number>} Chip ID; expect 0xB4 for LPS28DFW.
     */
    async chipId() {
        const buf = await this._readReg(_REG_WHO_AM_I, 1);
        return buf[0];
    }

    /**
     * Compute altitude above sea level from the current pressure.
     * @param {number} [seaLevelHpa=1013.25]
     * @returns {Promise<number>} Altitude in metres.
     */
    async altitude(seaLevelHpa = 1013.25) {
        const p = await this.readPressure();
        return 44330 * (1 - Math.pow(p / seaLevelHpa, 1 / 5.255));
    }
}

module.exports = { LPS28DFWMinimal, LPS28DFWFull };