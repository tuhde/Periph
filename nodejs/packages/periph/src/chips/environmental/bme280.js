'use strict';

const _REG_CAL_START  = 0x88;
const _REG_H1         = 0xA1;
const _REG_ID         = 0xD0;
const _REG_RESET      = 0xE0;
const _REG_CAL_H2     = 0xE1;
const _REG_CTRL_HUM   = 0xF2;
const _REG_STATUS     = 0xF3;
const _REG_CTRL_MEAS  = 0xF4;
const _REG_CONFIG     = 0xF5;
const _REG_DATA_START = 0xF7;

const _CHIP_ID        = 0x60;
const _RESET_CMD      = 0xB6;

const _MEAS_TIME_MS   = 9;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

function _signExtend12(raw) {
    if (raw & 0x800) return raw - 0x1000;
    return raw;
}

/**
 * BME280 combined humidity + pressure + temperature sensor — minimal interface.
 *
 * Provides calibrated temperature (°C), pressure (hPa), and humidity (%RH)
 * with no configuration beyond the transport. I²C address is 0x76 (SDO=GND)
 * or 0x77 (SDO=VDDIO).
 *
 * Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off.
 *
 * @param {object} transport - Configured I²C or SPI transport.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class BME280Minimal {
    constructor(transport, busType = 'i2c') {
        this._transport = transport;
        this._busType = busType;
        this._mode = 0;
        this._osrsT = 1;
        this._osrsP = 1;
        this._osrsH = 1;
        this._filter = 0;
        this._tSb = 0;
        this._tFine = 0;
        this._readCalibration();
        this._writeReg(_REG_CTRL_HUM, this._osrsH);
        this._writeReg(_REG_CTRL_MEAS, (1 << 5) | (1 << 2) | 0);
        this._writeReg(_REG_CONFIG, 0);
    }

    _readCalibration() {
        const data = this._transport.writeRead(Buffer.from([_REG_CAL_START]), 26);
        this._digT1 = data.readUInt16LE(0);
        this._digT2 = data.readInt16LE(2);
        this._digT3 = data.readInt16LE(4);
        this._digP1 = data.readUInt16LE(6);
        this._digP2 = data.readInt16LE(8);
        this._digP3 = data.readInt16LE(10);
        this._digP4 = data.readInt16LE(12);
        this._digP5 = data.readInt16LE(14);
        this._digP6 = data.readInt16LE(16);
        this._digP7 = data.readInt16LE(18);
        this._digP8 = data.readInt16LE(20);
        this._digP9 = data.readInt16LE(22);
        this._digH1 = data[25];

        const h = this._transport.writeRead(Buffer.from([_REG_CAL_H2]), 7);
        this._digH2 = h.readInt16LE(0);
        this._digH3 = h[2];
        this._digH4 = _signExtend12(((h[3] << 4) | (h[4] & 0x0F)) & 0x0FFF);
        this._digH5 = _signExtend12(((h[5] << 4) | ((h[4] >> 4) & 0x0F)) & 0x0FFF);
        this._digH6 = h.readInt8(6);
    }

    _writeReg(reg, value) {
        const addr = this._busType === 'spi' ? (reg & 0x7F) : reg;
        this._transport.write(Buffer.from([addr, value]));
    }

    _readReg(reg, n) {
        return this._transport.writeRead(Buffer.from([reg]), n);
    }

    _triggerAndRead() {
        if (this._mode !== 3) {
            this._writeReg(_REG_CTRL_HUM, this._osrsH);
            const ctrl = (this._osrsT << 5) | (this._osrsP << 2) | 1;
            this._writeReg(_REG_CTRL_MEAS, ctrl);
            _delay(_MEAS_TIME_MS);
        }
        const raw = this._readReg(_REG_DATA_START, 8);
        const adcP = (raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4);
        const adcT = (raw[3] << 12) | (raw[4] << 4) | (raw[5] >> 4);
        const adcH = (raw[6] << 8) | raw[7];
        return { adcP, adcT, adcH };
    }

    _compensateTemp(adcT) {
        const digT1 = this._digT1;
        const digT2 = this._digT2;
        const digT3 = this._digT3;
        const var1 = (((adcT >> 3) - (digT1 << 1)) * digT2) >> 11;
        const var2 = (((((adcT >> 4) - digT1) * ((adcT >> 4) - digT1)) >> 12) * digT3) >> 14;
        this._tFine = var1 + var2;
        return ((this._tFine * 5 + 128) >> 8) / 100.0;
    }

    _compensatePressure(adcP) {
        const tFine = BigInt(this._tFine);
        const digP1 = BigInt(this._digP1);
        const digP2 = BigInt(this._digP2);
        const digP3 = BigInt(this._digP3);
        const digP4 = BigInt(this._digP4);
        const digP5 = BigInt(this._digP5);
        const digP6 = BigInt(this._digP6);
        const digP7 = BigInt(this._digP7);
        const digP8 = BigInt(this._digP8);
        const digP9 = BigInt(this._digP9);

        let var1 = tFine - 128000n;
        let var2 = var1 * var1 * digP6;
        var2 = var2 + ((var1 * digP5) << 17n);
        var2 = var2 + (digP4 << 35n);
        var1 = ((var1 * var1 * digP3) >> 8n) + ((var1 * digP2) << 12n);
        var1 = (((1n << 47n) + var1) * digP1) >> 33n;
        if (var1 === 0n) return 0.0;
        let p = 1048576n - BigInt(adcP);
        p = (((p << 31n) - var2) * 3125n) / var1;
        var1 = (digP9 * (p >> 13n) * (p >> 13n)) >> 25n;
        var2 = (digP8 * p) >> 19n;
        p = ((p + var1 + var2) >> 8n) + (digP7 << 4n);
        return Number(p) / 256.0 / 100.0;
    }

    _compensateHumidity(adcH) {
        const tFine = this._tFine;
        const digH1 = this._digH1;
        const digH2 = this._digH2;
        const digH3 = this._digH3;
        const digH4 = this._digH4;
        const digH5 = this._digH5;
        const digH6 = this._digH6;

        let v = tFine - 76800;
        v = (((((adcH << 14) - (digH4 << 20) - (digH5 * v)) + 16384) >> 15) *
             (((((((v * digH6) >> 10) * (((v * digH3) >> 11) + 32768)) >> 10) + 2097152) *
               digH2 + 8192) >> 14));
        v = v - (((((v >> 15) * (v >> 15)) >> 7) * digH1) >> 4);
        if (v < 0) v = 0;
        if (v > 419430400) v = 419430400;
        return (v >> 12) / 1024.0;
    }

    /**
     * Read calibrated temperature.
     * @returns {number} Temperature in degrees Celsius.
     */
    temperature() {
        const { adcP, adcT, adcH } = this._triggerAndRead();
        return this._compensateTemp(adcT);
    }

    /**
     * Read calibrated pressure.
     *
     * Reads all three ADCs and refreshes t_fine.
     *
     * @returns {number} Pressure in hPa.
     */
    pressure() {
        const { adcP, adcT, adcH } = this._triggerAndRead();
        this._compensateTemp(adcT);
        return this._compensatePressure(adcP);
    }

    /**
     * Read calibrated humidity.
     *
     * Reads all three ADCs and refreshes t_fine.
     *
     * @returns {number} Relative humidity in %RH.
     */
    humidity() {
        const { adcP, adcT, adcH } = this._triggerAndRead();
        this._compensateTemp(adcT);
        return this._compensateHumidity(adcH);
    }
}

/**
 * BME280 full interface — extends BME280Minimal with configuration, dew point, and altitude helpers.
 *
 * @param {object} transport - Configured I²C or SPI transport.
 * @param {string} [busType='i2c'] - Bus type: 'i2c' or 'spi'.
 */
class BME280Full extends BME280Minimal {
    static OSRS_SKIP = 0;
    static OSRS_X1   = 1;
    static OSRS_X2   = 2;
    static OSRS_X4   = 3;
    static OSRS_X8   = 4;
    static OSRS_X16  = 5;

    static MODE_SLEEP  = 0;
    static MODE_FORCED = 1;
    static MODE_NORMAL = 3;

    static FILTER_OFF = 0;
    static FILTER_2   = 1;
    static FILTER_4   = 2;
    static FILTER_8   = 3;
    static FILTER_16  = 4;

    static T_SB_0_5_MS    = 0;
    static T_SB_62_5_MS   = 1;
    static T_SB_125_MS    = 2;
    static T_SB_250_MS    = 3;
    static T_SB_500_MS    = 4;
    static T_SB_1000_MS   = 5;
    static T_SB_10_MS     = 6;
    static T_SB_20_MS     = 7;

    static STATUS_MEASURING = 0x08;
    static STATUS_IM_UPDATE = 0x01;

    constructor(transport, busType = 'i2c') {
        super(transport, busType);
    }

    /**
     * Write ctrl_hum, config, and ctrl_meas registers in the correct order.
     * @param {number} osrsT - Temperature oversampling (0–5).
     * @param {number} osrsP - Pressure oversampling (0–5).
     * @param {number} osrsH - Humidity oversampling (0–5).
     * @param {number} mode - Power mode (0=sleep, 1=forced, 3=normal).
     * @param {number} filter - IIR filter coefficient (0–4).
     * @param {number} tSb - Standby time in normal mode (0–7; codes 6/7 mean
     *                       10 ms / 20 ms on the BME280, not 2000 ms / 4000 ms).
     */
    configure(osrsT, osrsP, osrsH, mode, filter, tSb) {
        this._osrsT = osrsT;
        this._osrsP = osrsP;
        this._osrsH = osrsH;
        this._mode = mode;
        this._filter = filter;
        this._tSb = tSb;
        this._writeReg(_REG_CTRL_HUM, osrsH);
        this._writeReg(_REG_CONFIG, (tSb << 5) | (filter << 2));
        this._writeReg(_REG_CTRL_MEAS, (osrsT << 5) | (osrsP << 2) | mode);
    }

    /**
     * Update temperature, pressure, and humidity oversampling.
     * @param {number} osrsT - Temperature oversampling (0–5).
     * @param {number} osrsP - Pressure oversampling (0–5).
     * @param {number} osrsH - Humidity oversampling (0–5).
     */
    setOversampling(osrsT, osrsP, osrsH) {
        this._osrsT = osrsT;
        this._osrsP = osrsP;
        this._osrsH = osrsH;
        this._writeReg(_REG_CTRL_HUM, osrsH);
        this._writeReg(_REG_CTRL_MEAS, (osrsT << 5) | (osrsP << 2) | this._mode);
    }

    /**
     * Update power mode.
     * @param {number} mode - Power mode (0=sleep, 1=forced, 3=normal).
     */
    setMode(mode) {
        this._mode = mode;
        this._writeReg(_REG_CTRL_MEAS, (this._osrsT << 5) | (this._osrsP << 2) | mode);
    }

    /**
     * Update IIR filter coefficient.
     * @param {number} coeff - Filter coefficient (0–4).
     */
    setFilter(coeff) {
        this._filter = coeff;
        this._writeReg(_REG_CONFIG, (this._tSb << 5) | (coeff << 2));
    }

    /**
     * Update standby time for normal mode.
     * @param {number} tSb - Standby time value (0–7). On the BME280 codes 6/7
     *                       mean 10 ms / 20 ms (not 2000 ms / 4000 ms).
     */
    setStandby(tSb) {
        this._tSb = tSb;
        this._writeReg(_REG_CONFIG, (tSb << 5) | (this._filter << 2));
    }

    /**
     * Read the status register.
     * @returns {number} Status byte; bit 3 = measuring, bit 0 = im_update.
     */
    status() {
        const data = this._readReg(_REG_STATUS, 1);
        return data[0];
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
     * Compute sea-level pressure from current pressure and known altitude.
     * @param {number} altitudeM - Altitude in metres.
     * @returns {number} Sea-level pressure in hPa.
     */
    seaLevelPressure(altitudeM) {
        const p = this.pressure();
        return p / Math.pow(1 - altitudeM / 44330, 5.255);
    }

    /**
     * Compute dew point from current temperature and humidity.
     * @returns {number} Dew point in degrees Celsius.
     */
    dewPoint() {
        const t = this.temperature();
        const h = this.humidity();
        if (h <= 0) return -Infinity;
        const a = 17.27;
        const b = 237.7;
        const alpha = (a * t) / (b + t) + Math.log(h / 100.0);
        return (b * alpha) / (a - alpha);
    }

    /**
     * Read the chip ID register.
     * @returns {number} Chip ID; expect 0x60.
     */
    chipId() {
        const data = this._readReg(_REG_ID, 1);
        return data[0];
    }

    /**
     * Perform a soft reset, re-read calibration, and re-apply configuration.
     */
    reset() {
        this._writeReg(_REG_RESET, _RESET_CMD);
        _delay(2);
        this._readCalibration();
        this._writeReg(_REG_CTRL_HUM, this._osrsH);
        this._writeReg(_REG_CONFIG, (this._tSb << 5) | (this._filter << 2));
        this._writeReg(_REG_CTRL_MEAS, (this._osrsT << 5) | (this._osrsP << 2) | this._mode);
    }
}

module.exports = { BME280Minimal, BME280Full };
