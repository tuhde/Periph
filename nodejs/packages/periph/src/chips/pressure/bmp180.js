'use strict';

const _REG_ID         = 0xD0;
const _REG_CAL_START = 0xAA;
const _REG_CTRL_MEAS = 0xF4;
const _REG_OUT_MSB   = 0xF6;
const _REG_OUT_LSB   = 0xF7;
const _REG_OUT_XLSB  = 0xF8;
const _REG_SOFT_RESET = 0xE0;

const _CMD_TEMP = 0x2E;
const _CMD_PRESSURE = [0x34, 0x74, 0xB4, 0xF4];

const _CHIP_ID       = 0x55;
const _SOFT_RESET_CMD = 0xB6;

const _CONV_TIME = [0.0045, 0.0075, 0.0135, 0.0255];
const _CONV_TIME_TEMP = 0.0045;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * BMP180 piezo-resistive pressure + temperature sensor — minimal interface.
 *
 * Provides calibrated temperature (°C) and pressure (hPa) with no configuration
 * beyond the transport. Fixed I²C address 0x77.
 *
 * Default OSS = 0 (Ultra Low Power, 4.5 ms conversion).
 *
 * @param {object} transport - Configured I²C transport.
 */
class BMP180Minimal {
    constructor(transport) {
        this._transport = transport;
        this._oss = 0;
        this._readCalibration();
    }

    _readCalibration() {
        const data = this._transport.writeRead(Buffer.from([_REG_CAL_START]), 22);
        this._ac1 = data.readInt16BE(0);
        this._ac2 = data.readInt16BE(2);
        this._ac3 = data.readInt16BE(4);
        this._ac4 = data.readUInt16BE(6);
        this._ac5 = data.readUInt16BE(8);
        this._ac6 = data.readUInt16BE(10);
        this._b1  = data.readInt16BE(12);
        this._b2  = data.readInt16BE(14);
        this._mb  = data.readInt16BE(16);
        this._mc  = data.readInt16BE(18);
        this._md  = data.readInt16BE(20);

        const coefficients = [this._ac1, this._ac2, this._ac3, this._ac4,
            this._ac5, this._ac6, this._b1, this._b2, this._mb, this._mc, this._md];
        if (coefficients.some(c => c === 0 || c === 0xFFFF)) {
            throw new Error('BMP180 calibration data invalid');
        }
    }

    _writeReg(reg, value) {
        this._transport.write(Buffer.from([reg, value]));
    }

    _readRawTemp() {
        this._writeReg(_REG_CTRL_MEAS, _CMD_TEMP);
        _delay(_CONV_TIME_TEMP * 1000);
        const data = this._transport.writeRead(Buffer.from([_REG_OUT_MSB]), 2);
        return data.readUInt16BE(0);
    }

    _readRawPressure() {
        const cmd = _CMD_PRESSURE[this._oss];
        this._writeReg(_REG_CTRL_MEAS, cmd);
        _delay(_CONV_TIME[this._oss] * 1000);
        const data = this._transport.writeRead(Buffer.from([_REG_OUT_MSB]), 3);
        let up = ((data.readUInt16BE(0) << 8) | data[2]) >> (8 - this._oss);
        return up;
    }

    _compensateTemp(ut) {
        const ac1 = this._ac1, ac2 = this._ac2, ac3 = this._ac3;
        const ac4 = this._ac4, ac5 = this._ac5, ac6 = this._ac6;
        const b1 = this._b1, b2 = this._b2;
        const mc = this._mc, md = this._md;

        let x1 = Math.floor(((ut - ac6) * ac5) / 32768);
        let x2 = Math.floor((mc << 11) / (x1 + md));
        let b5 = x1 + x2;
        this._b5 = b5;
        return b5;
    }

    _compensatePressure(up) {
        const oss = this._oss;
        const ac1 = this._ac1, ac2 = this._ac2, ac3 = this._ac3;
        const ac4 = this._ac4;
        const b1 = this._b1, b2 = this._b2;
        const b5 = this._b5;

        let b6 = b5 - 4000;
        let x1 = Math.floor((b2 * Math.floor((b6 * b6) / 4096)) / 2048);
        let x2 = Math.floor((ac2 * b6) / 2048);
        let x3 = x1 + x2;
        let b3 = Math.floor((((ac1 * 4) + x3) << oss) / 4);
        x1 = Math.floor((ac3 * b6) / 8192);
        x2 = Math.floor((b1 * Math.floor((b6 * b6) / 4096)) / 65536);
        x3 = Math.floor((x1 + x2 + 2) / 4);
        let b4 = Math.floor((ac4 * (x3 + 32768)) / 32768);
        let b7 = Math.floor((up - b3) * (50000 >>> oss));

        let p;
        if (b7 < 0x80000000) {
            p = Math.floor((b7 * 2) / b4);
        } else {
            p = Math.floor((b7 / b4) * 2);
        }

        x1 = Math.floor((p >> 8) * (p >> 8));
        x1 = Math.floor((x1 * 3038) / 65536);
        x2 = Math.floor((-7357 * p) / 65536);
        p = p + Math.floor((x1 + x2 + 3791) / 16);

        return p;
    }

    /**
     * Read calibrated temperature.
     * @returns {number} Temperature in degrees Celsius.
     */
    temperature() {
        const ut = this._readRawTemp();
        const b5 = this._compensateTemp(ut);
        return ((b5 + 8) / 160.0);
    }

    /**
     * Read calibrated pressure.
     *
     * Reads temperature first to refresh B5, then reads pressure.
     * Self-contained — may be called without a prior temperature() call.
     *
     * @returns {number} Pressure in hPa.
     */
    pressure() {
        const ut = this._readRawTemp();
        const b5 = this._compensateTemp(ut);
        (void)b5;
        const up = this._readRawPressure();
        const p_pa = this._compensatePressure(up);
        return p_pa / 100.0;
    }
}

/**
 * BMP180 full interface — extends BMP180Minimal with OSS control and altitude helpers.
 *
 * OSS constants:
 * - BMP180Full.OSS_ULP — Ultra Low Power (oss=0, 4.5 ms)
 * - BMP180Full.OSS_STANDARD — Standard (oss=1, 7.5 ms)
 * - BMP180Full.OSS_HIGH_RES — High Resolution (oss=2, 13.5 ms)
 * - BMP180Full.OSS_ULTRA_HIGH_RES — Ultra High Resolution (oss=3, 25.5 ms)
 *
 * @param {object} transport - Configured I²C transport.
 * @param {number} [oss=0] - Oversampling mode 0–3.
 */
class BMP180Full extends BMP180Minimal {
    static OSS_ULP            = 0;
    static OSS_STANDARD        = 1;
    static OSS_HIGH_RES        = 2;
    static OSS_ULTRA_HIGH_RES = 3;

    constructor(transport, oss = 0) {
        super(transport);
        this._oss = oss & 0x03;
    }

    /**
     * Read the current oversampling mode.
     * @returns {number} OSS value 0–3.
     */
    oversampling() {
        return this._oss;
    }

    /**
     * Change the oversampling mode for subsequent pressure() calls.
     * @param {number} oss - New OSS value 0–3.
     */
    setOversampling(oss) {
        this._oss = oss & 0x03;
    }

    /**
     * Compute altitude above sea level from the current pressure.
     * @param {number} [seaLevelHpa=1013.25] - Reference sea-level pressure in hPa.
     * @returns {number} Altitude in metres.
     */
    altitude(seaLevelHpa = 1013.25) {
        const p = this.pressure();
        return 44330 * (1 - Math.pow(p / seaLevelHpa, 1 / 5.255));
    }

    /**
     * Compute sea-level pressure for a known altitude.
     * @param {number} altitudeM - Altitude in metres.
     * @returns {number} Sea-level pressure in hPa.
     */
    seaLevelPressure(altitudeM) {
        const p = this.pressure();
        return p / Math.pow(1 - altitudeM / 44330, 5.255);
    }

    /**
     * Read the chip ID register.
     * @returns {number} Chip ID; expect 0x55.
     */
    chipId() {
        const data = this._transport.writeRead(Buffer.from([_REG_ID]), 1);
        return data[0];
    }

    /**
     * Perform a soft reset and re-read calibration coefficients.
     */
    reset() {
        this._writeReg(_REG_SOFT_RESET, _SOFT_RESET_CMD);
        _delay(10);
        this._readCalibration();
    }
}

module.exports = { BMP180Minimal, BMP180Full };
