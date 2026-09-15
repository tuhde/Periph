'use strict';

const _REG_CHIP_ID    = 0x00;
const _REG_STATUS     = 0x03;
const _REG_DATA_0     = 0x04;
const _REG_PWR_CTRL   = 0x1B;
const _REG_OSR        = 0x1C;
const _REG_ODR        = 0x1D;
const _REG_CONFIG     = 0x1F;
const _REG_CMD        = 0x7E;
const _REG_CAL_START  = 0x31;
const _REG_CAL_LEN    = 21;

const _CHIP_ID         = 0x50;
const _SOFT_RESET_CMD  = 0xB6;
const _FIFO_FLUSH_CMD  = 0xB0;

const _MODE_SLEEP  = 0x00;
const _MODE_FORCED = 0x01;
const _MODE_NORMAL = 0x03;

const _PWR_PRESS_EN = 0x01;
const _PWR_TEMP_EN  = 0x02;

const _FIFO_HEADER_PRESS   = 0x84;
const _FIFO_HEADER_TEMP    = 0x90;
const _FIFO_HEADER_SENSORT = 0xA0;
const _FIFO_HEADER_ERROR   = 0x44;
const _FIFO_HEADER_EMPTY   = 0x80;

const _MEAS_TIME_MS = 40;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

function _s8(b) {
    return b >= 128 ? b - 256 : b;
}

/**
 * BMP384 high-precision barometric pressure and temperature sensor — minimal interface.
 *
 * Provides calibrated temperature (°C) and pressure (hPa) with no configuration
 * beyond the connection. I²C address is 0x76 (SDO=GND) or 0x77 (SDO=VDD).
 *
 * Default: normal mode, osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz.
 *
 * Constructor caveat: JS constructors cannot be async. The calibration read
 * and initial register writes are fired off unawaited, matching the
 * fire-and-forget convention used throughout this port — in practice they
 * settle before any subsequent `await` in caller code is scheduled, since
 * the underlying I2C/SPI connection is synchronous under the hood, but this
 * is not a guaranteed blocking wait the way the pre-async version was. Call
 * `await sensor.temperature()` (or any other async method) once before
 * relying on calibrated readings if this matters for your use case.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class BMP384Minimal {
    constructor(connection, busType = 'i2c') {
        this._conn = connection;
        this._busType = busType;
        this._osrP = 4;     // ×16
        this._osrT = 1;     // ×2
        this._iir   = 2;    // coefficient 3
        this._odr   = 0x03; // 25 Hz
        this._mode  = _MODE_NORMAL;
        this._init();
    }

    async _init() {
        await this._readCalibration();
        await this._applyConfig();
    }

    async _readCalibration() {
        const data = await this._conn.writeRead(Buffer.from([_REG_CAL_START]), _REG_CAL_LEN);
        // NVM_PAR_T1 (u16 LE)
        this._parT1 = data.readUInt16LE(0);
        // NVM_PAR_T2 (u16 LE)
        this._parT2 = data.readUInt16LE(2);
        // NVM_PAR_T3 (s8)
        this._parT3 = _s8(data[4]);
        // NVM_PAR_P1 (s16 LE)
        this._parP1 = data.readInt16LE(5);
        // NVM_PAR_P2 (s16 LE)
        this._parP2 = data.readInt16LE(7);
        // NVM_PAR_P3 (s8)
        this._parP3 = _s8(data[9]);
        // NVM_PAR_P4 (s8)
        this._parP4 = _s8(data[10]);
        // NVM_PAR_P5 (u16 LE)
        this._parP5 = data.readUInt16LE(11);
        // NVM_PAR_P6 (u16 LE)
        this._parP6 = data.readUInt16LE(13);
        // NVM_PAR_P7 (s8)
        this._parP7 = _s8(data[15]);
        // NVM_PAR_P8 (s8)
        this._parP8 = _s8(data[16]);
        // NVM_PAR_P9 (s16 LE)
        this._parP9 = data.readInt16LE(17);
        // NVM_PAR_P10 (s8)
        this._parP10 = _s8(data[19]);
        // NVM_PAR_P11 (s8)
        this._parP11 = _s8(data[20]);

        // Convert raw NVM coefficients to floating-point PAR values per datasheet.
        // PAR_T1 = NVM_PAR_T1 × 256
        this._parT1 = this._parT1 * 256.0;
        this._parT2 = this._parT2 / Math.pow(2, 30);
        this._parT3 = this._parT3 / Math.pow(2, 48);
        this._parP1 = (this._parP1 - Math.pow(2, 14)) / Math.pow(2, 20);
        this._parP2 = (this._parP2 - Math.pow(2, 14)) / Math.pow(2, 29);
        this._parP3 = this._parP3 / Math.pow(2, 32);
        this._parP4 = this._parP4 / Math.pow(2, 37);
        this._parP5 = this._parP5 * 8.0;
        this._parP6 = this._parP6 / Math.pow(2, 6);
        this._parP7 = this._parP7 / Math.pow(2, 8);
        this._parP8 = this._parP8 / Math.pow(2, 15);
        this._parP9 = this._parP9 / Math.pow(2, 48);
        this._parP10 = this._parP10 / Math.pow(2, 48);
        this._parP11 = this._parP11 / Math.pow(2, 65);
    }

    async _applyConfig() {
        const osrReg   = (this._osrT << 3) | (this._osrP << 0);
        const configReg = (this._iir << 1);
        const pwrReg   = (this._mode << 4) | _PWR_TEMP_EN | _PWR_PRESS_EN;
        await this._writeReg(_REG_OSR,      osrReg);
        await this._writeReg(_REG_CONFIG,   configReg);
        await this._writeReg(_REG_ODR,      this._odr);
        await this._writeReg(_REG_PWR_CTRL, pwrReg);
    }

    async _writeReg(reg, value) {
        const addr = this._busType === 'spi' ? (reg & 0x7F) : reg;
        await this._conn.write(Buffer.from([addr, value]));
    }

    async _readReg(reg, n) {
        return this._conn.writeRead(Buffer.from([reg]), n);
    }

    async _readBurst() {
        // DATA_0..DATA_5 (0x04..0x09) = pressure XLSB/LSB/MSB, then temperature XLSB/LSB/MSB
        const raw = await this._readReg(_REG_DATA_0, 6);
        const uncompPress = (raw[2] << 16) | (raw[1] << 8) | raw[0];
        const uncompTemp  = (raw[5] << 16) | (raw[4] << 8) | raw[3];
        return { uncompPress, uncompTemp };
    }

    _compensateTemperature(uncompTemp) {
        const partial1 = uncompTemp - this._parT1;
        const partial2 = partial1 * this._parT2;
        this._tLin = partial2 + (partial1 * partial1) * this._parT3;
        return this._tLin;
    }

    _compensatePressure(uncompPress) {
        const tLin = this._tLin;
        const p = Number(uncompPress);

        const partial1a = this._parP6 * tLin;
        const partial2a = this._parP7 * tLin * tLin;
        const partial3a = this._parP8 * tLin * tLin * tLin;
        const partialOut1 = this._parP5 + partial1a + partial2a + partial3a;

        const partial1b = this._parP2 * tLin;
        const partial2b = this._parP3 * tLin * tLin;
        const partial3b = this._parP4 * tLin * tLin * tLin;
        const partialOut2 = p * (this._parP1 + partial1b + partial2b + partial3b);

        const partial1c = p * p;
        const partial2c = this._parP9 + this._parP10 * tLin;
        const partial3c = partial1c * partial2c;
        const partial4 = partial3c + (p * p * p) * this._parP11;

        return partialOut1 + partialOut2 + partial4;
    }

    /**
     * Read calibrated temperature.
     * @returns {Promise<number>} Temperature in degrees Celsius.
     */
    async temperature() {
        if (this._mode === _MODE_FORCED) {
            const pwrReg = (_MODE_FORCED << 4) | _PWR_TEMP_EN | _PWR_PRESS_EN;
            await this._writeReg(_REG_PWR_CTRL, pwrReg);
            _delay(_MEAS_TIME_MS);
        }
        const { uncompTemp } = await this._readBurst();
        return this._compensateTemperature(uncompTemp);
    }

    /**
     * Read calibrated pressure.
     * @returns {Promise<number>} Pressure in hectopascals (hPa).
     */
    async pressure() {
        if (this._mode === _MODE_FORCED) {
            const pwrReg = (_MODE_FORCED << 4) | _PWR_TEMP_EN | _PWR_PRESS_EN;
            await this._writeReg(_REG_PWR_CTRL, pwrReg);
            _delay(_MEAS_TIME_MS);
        }
        const { uncompPress, uncompTemp } = await this._readBurst();
        this._compensateTemperature(uncompTemp);
        return this._compensatePressure(uncompPress) / 100.0;
    }
}

/**
 * BMP384 full interface — extends BMP384Minimal with configuration, mode control, and FIFO access.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class BMP384Full extends BMP384Minimal {
    static MODE_SLEEP  = 0x00;
    static MODE_FORCED = 0x01;
    static MODE_NORMAL = 0x03;

    constructor(connection, busType = 'i2c') {
        super(connection, busType);
    }

    /**
     * Write OSR, CONFIG, and ODR; does not enforce ODR ≥ T_conv at the JS layer.
     * @param {number} osrP - Pressure oversampling (0–5).
     * @param {number} osrT - Temperature oversampling (0–5).
     * @param {number} iirFilter - IIR filter coefficient index (0–7).
     * @param {number} odrSel - Output data rate selector (0x00–0x11).
     * @returns {Promise<void>}
     */
    async configure(osrP, osrT, iirFilter, odrSel) {
        this._osrP = osrP;
        this._osrT = osrT;
        this._iir   = iirFilter;
        this._odr   = odrSel;
        await this._writeReg(_REG_OSR,    (osrT << 3) | (osrP << 0));
        await this._writeReg(_REG_CONFIG, (iirFilter << 1));
        await this._writeReg(_REG_ODR,    odrSel);
    }

    /**
     * Read both pressure and temperature in a single burst.
     * @returns {Promise<{pressure:number, temperature:number}>} pressure in hPa, temperature in °C.
     */
    async read() {
        if (this._mode === _MODE_FORCED) {
            await this._triggerForced();
        }
        const { uncompPress, uncompTemp } = await this._readBurst();
        const t = this._compensateTemperature(uncompTemp);
        const p = this._compensatePressure(uncompPress) / 100.0;
        return { pressure: p, temperature: t };
    }

    /**
     * Trigger a forced-mode measurement, wait T_conv, return both values.
     * @returns {Promise<{pressure:number, temperature:number}>}
     */
    async readForced() {
        const prevMode = this._mode;
        try {
            await this.setMode(BMP384Full.MODE_FORCED);
            await this._triggerForced();
            const tConvMs = BMP384Full._computeTConvMs(this._osrP, this._osrT);
            _delay(tConvMs);
            const { uncompPress, uncompTemp } = await this._readBurst();
            const t = this._compensateTemperature(uncompTemp);
            const p = this._compensatePressure(uncompPress) / 100.0;
            return { pressure: p, temperature: t };
        } finally {
            this._mode = prevMode;
            await this._applyPwr();
        }
    }

    /**
     * Set the power mode.
     * @param {number} mode
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        this._mode = mode;
        await this._applyPwr();
    }

    /**
     * True if STATUS.drdy_press is set.
     * @returns {Promise<boolean>}
     */
    async isDataReady() {
        const raw = await this._readReg(_REG_STATUS, 1);
        return (raw[0] & (1 << 5)) !== 0;
    }

    /**
     * Soft reset, re-read calibration, re-apply config.
     * @returns {Promise<void>}
     */
    async softreset() {
        await this._writeReg(_REG_CMD, _SOFT_RESET_CMD);
        _delay(3);
        await this._readCalibration();
        await this._applyConfig();
    }

    /**
     * Configure FIFO source, watermark, and stop-on-full behaviour.
     * @param {boolean} pressEn - Store pressure frames.
     * @param {boolean} tempEn - Store temperature frames.
     * @param {number} wtm - Watermark in bytes (0–511).
     * @param {boolean} [stopOnFull=false]
     * @returns {Promise<void>}
     */
    async fifoConfigure(pressEn, tempEn, wtm, stopOnFull = false) {
        const cfg1 = (1 << 4)
            | ((stopOnFull ? 1 : 0) << 3)
            | ((tempEn ? 1 : 0) << 1)
            | (pressEn ? 1 : 0);
        await this._writeReg(0x17, cfg1);
        await this._writeReg(0x15, wtm & 0xFF);
        await this._writeReg(0x16, (wtm >> 8) & 0x01);
    }

    /**
     * Read and parse every available FIFO frame.
     * @returns {Promise<Array<{type:string, value:number|null}>>}
     */
    async fifoRead() {
        const lenLo = (await this._readReg(0x12, 1))[0];
        const lenHi = (await this._readReg(0x13, 1))[0];
        const length = (lenHi << 8) | lenLo;
        if (length === 0) return [];
        const buf = await this._readReg(0x14, length);
        const frames = [];
        let i = 0;
        while (i < buf.length) {
            const hdr = buf[i];
            if (hdr === _FIFO_HEADER_PRESS) {
                const uncomp = (buf[i + 3] << 16) | (buf[i + 2] << 8) | buf[i + 1];
                const valuePa = this._compensatePressureWithTLin(uncomp, this._tLin);
                frames.push({ type: 'pressure', value: valuePa / 100.0 });
                i += 4;
            } else if (hdr === _FIFO_HEADER_TEMP) {
                const uncomp = (buf[i + 3] << 16) | (buf[i + 2] << 8) | buf[i + 1];
                const t = this._compensateTemperature(uncomp);
                frames.push({ type: 'temperature', value: t });
                i += 4;
            } else if (hdr === _FIFO_HEADER_SENSORT) {
                const uncomp = (buf[i + 3] << 16) | (buf[i + 2] << 8) | buf[i + 1];
                frames.push({ type: 'sensortime', value: uncomp });
                i += 4;
            } else if (hdr === _FIFO_HEADER_ERROR || hdr === _FIFO_HEADER_EMPTY) {
                frames.push({ type: hdr === _FIFO_HEADER_ERROR ? 'error' : 'empty', value: null });
                i += 1;
            } else {
                frames.push({ type: 'unknown', value: null });
                i += 1;
            }
        }
        return frames;
    }

    /**
     * Flush the FIFO contents.
     * @returns {Promise<void>}
     */
    async fifoFlush() {
        await this._writeReg(_REG_CMD, _FIFO_FLUSH_CMD);
    }

    /**
     * Compute altitude above sea level from the current pressure.
     * @param {number} [seaLevelHpa=1013.25]
     * @returns {Promise<number>} Altitude in metres.
     */
    async altitude(seaLevelHpa = 1013.25) {
        const p = await this.pressure();
        if (p <= 0) return 0.0;
        return 44330 * (1 - Math.pow(p / seaLevelHpa, 1 / 5.255));
    }

    async _triggerForced() {
        const pwrReg = (_MODE_FORCED << 4) | _PWR_TEMP_EN | _PWR_PRESS_EN;
        await this._writeReg(_REG_PWR_CTRL, pwrReg);
    }

    async _applyPwr() {
        const pwrReg = (this._mode << 4) | _PWR_TEMP_EN | _PWR_PRESS_EN;
        await this._writeReg(_REG_PWR_CTRL, pwrReg);
    }

    _compensatePressureWithTLin(uncompPress, tLin) {
        this._tLin = tLin;
        return this._compensatePressure(uncompPress);
    }

    static _computeTConvMs(osrP, osrT) {
        // T_conv per spec: 234 + 392 + 2^osr_p*2000 + 313 + 2^osr_t*2000 µs.
        const tConvUs = 234 + 392 + (1 << osrP) * 2000 + 313 + (1 << osrT) * 2000;
        return Math.ceil(tConvUs / 1000) + 1;
    }
}

module.exports = { BMP384Minimal, BMP384Full };
