'use strict';

const _REG_ID         = 0xD0;
const _REG_RESET      = 0xE0;
const _REG_STATUS     = 0xF3;
const _REG_CTRL_MEAS  = 0xF4;
const _REG_CONFIG     = 0xF5;
const _REG_CAL_START  = 0x88;
const _REG_DATA      = 0xF7;

const _CHIP_ID         = 0x58;
const _RESET_CMD       = 0xB6;
const _CTRL_MEAS_DEFAULT = 0x25;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * BMP280 piezo-resistive pressure + temperature sensor — minimal interface.
 *
 * Provides calibrated temperature (°C) and pressure (hPa) with no configuration
 * beyond the transport. Default configuration: forced mode, oversampling ×1 for
 * both channels, IIR filter off.
 *
 * @param {object} transport - Configured I²C transport.
 * @param {number} [addr=0x76] - 7-bit I²C address (default 0x76, alternate 0x77).
 */
class BMP280Minimal {
    constructor(transport, addr = 0x76) {
        this._transport = transport;
        this._addr = addr;
        this._t_fine = null;
        this._ctrlMeasCache = _CTRL_MEAS_DEFAULT;
        this._loadCalibration();
        this._writeCtrlMeas(_CTRL_MEAS_DEFAULT);
        this._writeConfig(0x00);
    }

    _writeReg(reg, value) {
        this._transport.write(Buffer.from([reg, value]));
    }

    _readReg(reg, len) {
        return this._transport.writeRead(Buffer.from([reg]), len);
    }

    _writeCtrlMeas(value) {
        this._writeReg(_REG_CTRL_MEAS, value);
    }

    _writeConfig(value) {
        this._writeReg(_REG_CONFIG, value);
    }

    _loadCalibration() {
        const raw = this._readReg(_REG_CAL_START, 24);
        this._dig_T1 = raw.readUInt16LE(0);
        this._dig_T2 = raw.readInt16LE(2);
        this._dig_T3 = raw.readInt16LE(4);
        this._dig_P1 = raw.readUInt16LE(6);
        this._dig_P2 = raw.readInt16LE(8);
        this._dig_P3 = raw.readInt16LE(10);
        this._dig_P4 = raw.readInt16LE(12);
        this._dig_P5 = raw.readInt16LE(14);
        this._dig_P6 = raw.readInt16LE(16);
        this._dig_P7 = raw.readInt16LE(18);
        this._dig_P8 = raw.readInt16LE(20);
        this._dig_P9 = raw.readInt16LE(22);
    }

    _triggerReadBurst() {
        const raw = this._readReg(_REG_DATA, 6);
        const adc_P = (raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4);
        const adc_T = (raw[3] << 12) | (raw[4] << 4) | (raw[5] >> 4);
        return [adc_T, adc_P];
    }

    _compensateTemp(adc_T) {
        const T1 = BigInt(this._dig_T1);
        const T2 = BigInt(this._dig_T2);
        const T3 = BigInt(this._dig_T3);
        const t  = BigInt(adc_T);
        const var1 = (((t >> 3n) - (T1 << 1n)) * T2) >> 11n;
        const var2 = (((((t >> 4n) - T1) * ((t >> 4n) - T1)) >> 12n) * T3) >> 14n;
        this._t_fine = Number(var1 + var2);
        return Number(((var1 + var2) * 5n + 128n) >> 8n) / 100.0;
    }

    _compensatePressure(adc_P) {
        const tf = BigInt(this._t_fine !== null ? this._t_fine : 0);
        const P1 = BigInt(this._dig_P1), P2 = BigInt(this._dig_P2), P3 = BigInt(this._dig_P3);
        const P4 = BigInt(this._dig_P4), P5 = BigInt(this._dig_P5), P6 = BigInt(this._dig_P6);
        const P7 = BigInt(this._dig_P7), P8 = BigInt(this._dig_P8), P9 = BigInt(this._dig_P9);
        let var1 = tf - 128000n;
        let var2 = var1 * var1 * P6;
        var2 = var2 + ((var1 * P5) << 17n);
        var2 = var2 + (P4 << 35n);
        var1 = ((var1 * var1 * P3) >> 8n) + ((var1 * P2) << 12n);
        var1 = (((1n << 47n) + var1) * P1) >> 33n;
        if (var1 === 0n) return 0.0;
        let p = BigInt(1048576) - BigInt(adc_P);
        p = (((p << 31n) - var2) * 3125n) / var1;
        var1 = (P9 * (p >> 13n) * (p >> 13n)) >> 25n;
        var2 = (P8 * p) >> 19n;
        p = ((p + var1 + var2) >> 8n) + (P7 << 4n);
        return Number(p) / 25600.0;
    }

    _triggerMeasurement() {
        this._writeCtrlMeas(this._ctrlMeasCache);
        _delay(7);
    }

    /**
     * Read calibrated temperature.
     *
     * Triggers a forced-mode conversion and returns temperature in °C.
     * Caches t_fine for use in subsequent pressure() calls.
     *
     * @returns {number} Temperature in degrees Celsius.
     */
    temperature() {
        this._triggerMeasurement();
        const [adc_T] = this._triggerReadBurst();
        return this._compensateTemp(adc_T);
    }

    /**
     * Read calibrated pressure.
     *
     * Triggers a forced-mode conversion and returns pressure in hPa.
     * Re-reads the temperature ADC alongside pressure to refresh t_fine.
     *
     * @returns {number} Pressure in hPa.
     */
    pressure() {
        this._triggerMeasurement();
        const [adc_T, adc_P] = this._triggerReadBurst();
        this._compensateTemp(adc_T);
        return this._compensatePressure(adc_P);
    }
}

/**
 * BMP280 full interface — extends BMP280Minimal with configuration and altitude helpers.
 *
 * Adds power-mode control, oversampling settings, IIR filter, standby time,
 * status read, altitude / sea-level helpers, chip_id, and reset.
 *
 * @param {object} transport - Configured I²C transport.
 * @param {number} [addr=0x76] - 7-bit I²C address.
 * @param {number} [osrs_t=1] - Temperature oversampling 0–5 (default ×1).
 * @param {number} [osrs_p=1] - Pressure oversampling 0–5 (default ×1).
 * @param {number} [mode=1] - Power mode (default forced).
 * @param {number} [filter=0] - IIR filter coefficient (default off).
 * @param {number} [t_sb=0] - Standby time in normal mode 0–7 (default 0.5 ms).
 */
class BMP280Full extends BMP280Minimal {
    static OSRS_SKIP  = 0;
    static OSRS_X1    = 1;
    static OSRS_X2    = 2;
    static OSRS_X4    = 3;
    static OSRS_X8    = 4;
    static OSRS_X16   = 5;

    static MODE_SLEEP   = 0;
    static MODE_FORCED  = 1;
    static MODE_NORMAL  = 3;

    static FILTER_OFF  = 0;
    static FILTER_2    = 1;
    static FILTER_4    = 2;
    static FILTER_8    = 3;
    static FILTER_16   = 4;

    static T_SB_0_5_MS   = 0;
    static T_SB_62_5_MS  = 1;
    static T_SB_125_MS   = 2;
    static T_SB_250_MS   = 3;
    static T_SB_500_MS   = 4;
    static T_SB_1000_MS  = 5;
    static T_SB_2000_MS  = 6;
    static T_SB_4000_MS  = 7;

    static STATUS_MEASURING = 0x08;
    static STATUS_IM_UPDATE = 0x01;

    constructor(transport, addr = 0x76, osrs_t = 1, osrs_p = 1, mode = 1, filter = 0, t_sb = 0) {
        super(transport, addr);
        this._osrs_t = osrs_t;
        this._osrs_p = osrs_p;
        this._mode = mode;
        this._filter = filter;
        this._t_sb = t_sb;
        this._ctrlMeasCache = this._ctrlMeasValue();
        this._writeCtrlMeas(this._ctrlMeasCache);
        this._writeConfig(this._configValue());
    }

    _ctrlMeasValue() {
        return (this._osrs_t << 5) | (this._osrs_p << 2) | this._mode;
    }

    _configValue() {
        return (this._t_sb << 5) | (this._filter << 2);
    }

    /**
     * Update chip configuration.
     *
     * @param {number} [osrs_t] - Temperature oversampling 0–5.
     * @param {number} [osrs_p] - Pressure oversampling 0–5.
     * @param {number} [mode] - Power mode (0=sleep, 1=forced, 3=normal).
     * @param {number} [filter] - IIR filter coefficient (0=off, 1, 2, 3, 4=×16).
     * @param {number} [t_sb] - Standby time in normal mode (0–7).
     */
    configure(osrs_t, osrs_p, mode, filter, t_sb) {
        if (osrs_t !== undefined) this._osrs_t = osrs_t;
        if (osrs_p !== undefined) this._osrs_p = osrs_p;
        if (mode !== undefined) this._mode = mode;
        if (filter !== undefined) this._filter = filter;
        if (t_sb !== undefined) this._t_sb = t_sb;
        this._ctrlMeasCache = this._ctrlMeasValue();
        this._writeCtrlMeas(this._ctrlMeasCache);
        this._writeConfig(this._configValue());
    }

    /**
     * Update oversampling settings.
     *
     * @param {number} osrs_t - Temperature oversampling 0–5.
     * @param {number} osrs_p - Pressure oversampling 0–5.
     */
    setOversampling(osrs_t, osrs_p) {
        this._osrs_t = osrs_t;
        this._osrs_p = osrs_p;
        this._ctrlMeasCache = this._ctrlMeasValue();
        this._writeCtrlMeas(this._ctrlMeasCache);
    }

    /**
     * Update power mode.
     *
     * @param {number} mode - 0=sleep, 1=forced, 3=normal.
     */
    setMode(mode) {
        this._mode = mode;
        this._ctrlMeasCache = this._ctrlMeasValue();
        this._writeCtrlMeas(this._ctrlMeasCache);
    }

    /**
     * Update IIR filter coefficient.
     *
     * @param {number} coeff - 0=off, 1=×2, 2=×4, 3=×8, 4=×16.
     */
    setFilter(coeff) {
        this._filter = coeff;
        this._writeConfig(this._configValue());
    }

    /**
     * Update standby time (only relevant in normal mode).
     *
     * @param {number} t_sb - 0=0.5ms, 1=62.5ms, 2=125ms, 3=250ms, 4=500ms, 5=1s, 6=2s, 7=4s.
     */
    setStandby(t_sb) {
        this._t_sb = t_sb;
        this._writeConfig(this._configValue());
    }

    /**
     * Read status register.
     *
     * @returns {number} Status byte; check STATUS_MEASURING and STATUS_IM_UPDATE bits.
     */
    status() {
        return this._readReg(_REG_STATUS, 1)[0];
    }

    /**
     * Compute altitude above sea level from current pressure.
     *
     * @param {number} [seaLevelHpa=1013.25] - Reference sea-level pressure in hPa.
     * @returns {number} Altitude in metres.
     */
    altitude(seaLevelHpa = 1013.25) {
        const p = this.pressure();
        return 44330 * (1 - Math.pow(p / seaLevelHpa, 1 / 5.255));
    }

    /**
     * Compute sea-level pressure for a known altitude.
     *
     * @param {number} altitudeM - Altitude in metres.
     * @returns {number} Sea-level pressure in hPa.
     */
    seaLevelPressure(altitudeM) {
        const p = this.pressure();
        return p / Math.pow(1 - altitudeM / 44330, 5.255);
    }

    /**
     * Read chip ID register.
     *
     * @returns {number} Chip ID; expect 0x58 for BMP280.
     */
    chipId() {
        return this._readReg(_REG_ID, 1)[0];
    }

    /**
     * Perform soft reset and re-read calibration coefficients.
     *
     * Re-applies the current ctrl_meas and config settings.
     */
    reset() {
        this._writeReg(_REG_RESET, _RESET_CMD);
        _delay(2);
        this._loadCalibration();
        this._writeCtrlMeas(this._ctrlMeasValue());
        this._writeConfig(this._configValue());
    }
}

module.exports = { BMP280Minimal, BMP280Full };