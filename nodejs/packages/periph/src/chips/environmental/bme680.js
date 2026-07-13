'use strict';

const CONST_ARRAY1 = [
    2147483647, 2147483647, 2147483647, 2147483647, 2147483647,
    2126008810, 2147483647, 2130303777, 2147483647, 2147483647,
    2143188679, 2136746228, 2147483647, 2126008810, 2147483647,
    2147483647
];

const CONST_ARRAY2 = [
    4096000000, 2048000000, 1024000000, 512000000, 255744255,
    127110228, 64000000, 32258064, 16016016, 8000000,
    4000000, 2000000, 1000000, 500000, 250000,
    125000
];

const _REG_RES_HEAT_VAL   = 0x00;
const _REG_RES_HEAT_RANGE = 0x02;
const _REG_RANGE_SW_ERR   = 0x04;
const _REG_MEAS_STATUS    = 0x1D;
const _REG_PRESS_MSB      = 0x1F;
const _REG_CTRL_GAS_0     = 0x70;
const _REG_CTRL_GAS_1     = 0x71;
const _REG_CTRL_HUM       = 0x72;
const _REG_CTRL_MEAS      = 0x74;
const _REG_CONFIG         = 0x75;
const _REG_CAL_BLOCK1     = 0x8A;
const _REG_ID             = 0xD0;
const _REG_RESET          = 0xE0;
const _REG_CAL_BLOCK2     = 0xE1;

const _CHIP_ID            = 0x61;
const _RESET_CMD          = 0xB6;

const _MEAS_TIME_MS       = 200;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * BME680 4-in-1 environmental sensor: temperature, pressure, humidity, gas resistance — minimal interface.
 *
 * Provides calibrated readings with no configuration beyond the transport.
 * I²C address is 0x76 (SDO=GND) or 0x77 (SDO=VDDIO).
 *
 * Default: forced mode, osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 * heater profile 0 at 320 °C / 150 ms.
 *
 * @param {object} transport - Configured I²C transport.
 */
class BME680Minimal {
    constructor(transport) {
        this._transport = transport;
        this._osrsT = 1;
        this._osrsP = 1;
        this._osrsH = 1;
        this._filter = 0;
        this._tFine = 0;
        this._ambientTemp = 25.0;
        this._heatTemp = 320;
        this._heatDur = 150;
        this._gasEnabled = true;
        this._nbConv = 0;
        this._readCalibration();
        this._writeReg(_REG_CTRL_HUM, this._osrsH);
        this._writeReg(_REG_CTRL_MEAS, (this._osrsT << 5) | (this._osrsP << 2) | 0);
        this._writeReg(_REG_CONFIG, 0);
        this._setupHeater(0, this._heatTemp, this._heatDur);
        this._writeReg(_REG_CTRL_GAS_1, (1 << 4) | 0);
    }

    _readCalibration() {
        const b1 = this._transport.writeRead(Buffer.from([_REG_CAL_BLOCK1]), 23);
        const b2 = this._transport.writeRead(Buffer.from([_REG_CAL_BLOCK2]), 14);
        const s1 = this._transport.writeRead(Buffer.from([_REG_RES_HEAT_VAL]), 1);
        const s2 = this._transport.writeRead(Buffer.from([_REG_RES_HEAT_RANGE]), 1);
        const s3 = this._transport.writeRead(Buffer.from([_REG_RANGE_SW_ERR]), 1);

        this._parT2 = b1.readInt16LE(0);
        this._parT3 = b1.readInt8(2);
        this._parP1 = b1.readUInt16LE(4);
        this._parP2 = b1.readInt16LE(6);
        this._parP3 = b1.readInt8(8);
        this._parP4 = b1.readInt16LE(10);
        this._parP5 = b1.readInt16LE(12);
        this._parP7 = b1.readInt8(14);
        this._parP6 = b1.readInt8(15);
        this._parP8 = b1.readInt16LE(18);
        this._parP9 = b1.readInt16LE(20);
        this._parP10 = b1[22];

        this._parH2 = (b2[0] << 4) | (b2[1] >> 4);
        this._parH1 = (b2[2] << 4) | (b2[1] & 0x0F);
        this._parH3 = b2.readInt8(3);
        this._parH4 = b2.readInt8(4);
        this._parH5 = b2.readInt8(5);
        this._parH6 = b2[6];
        this._parH7 = b2.readInt8(7);
        this._parT1 = b2.readUInt16LE(8);
        this._parG2 = b2.readInt16LE(10);
        this._parG1 = b2.readInt8(12);
        this._parG3 = b2.readInt8(13);

        this._resHeatVal = s1.readInt8(0);
        this._resHeatRange = (s2[0] >> 4) & 0x03;
        const rse = (s3[0] >> 4) & 0x0F;
        this._rangeSwitchingError = rse < 8 ? rse : rse - 16;
    }

    _writeReg(reg, value) {
        this._transport.write(Buffer.from([reg, value]));
    }

    _readReg(reg, n) {
        return this._transport.writeRead(Buffer.from([reg]), n);
    }

    _calcHeaterResistance(targetTemp, ambientTemp) {
        const parG1 = this._parG1;
        const parG2 = this._parG2;
        const parG3 = this._parG3;
        const rhr = this._resHeatRange;
        const rhv = this._resHeatVal;

        const var1 = ((ambientTemp * parG3) / 10 | 0) << 8;
        const var2 = (parG1 + 784) * ((((parG2 + 154009) * targetTemp * 5 / 100 | 0) + 3276800) / 10 | 0);
        const var3 = var1 + (var2 >> 1);
        const var4 = var3 / (rhr + 4) | 0;
        const var5 = (131 * rhv) + 65536;
        const resHeatX100 = ((var4 / var5 | 0) - 250) * 34;
        const resHeatX = (resHeatX100 + 50) / 100 | 0;
        return resHeatX & 0xFF;
    }

    _calcGasWait(targetMs) {
        if (targetMs <= 0x3F) return targetMs;
        if (targetMs <= 0x3F * 4) return (1 << 6) | (targetMs / 4 | 0);
        if (targetMs <= 0x3F * 16) return (2 << 6) | (targetMs / 16 | 0);
        return (3 << 6) | Math.min(targetMs / 64 | 0, 0x3F);
    }

    _setupHeater(index, tempC, durMs) {
        const res = this._calcHeaterResistance(tempC, this._ambientTemp);
        const gw = this._calcGasWait(durMs);
        this._writeReg(0x5A + index, res);
        this._writeReg(0x64 + index, gw);
    }

    _triggerAndRead() {
        this._writeReg(_REG_CTRL_HUM, this._osrsH);
        const ctrl = (this._osrsT << 5) | (this._osrsP << 2) | 1;
        this._writeReg(_REG_CTRL_MEAS, ctrl);
        _delay(_MEAS_TIME_MS);
        const raw = this._readReg(_REG_PRESS_MSB, 13);
        const pressAdc = (raw[0] << 12) | (raw[1] << 4) | (raw[2] >> 4);
        const tempAdc  = (raw[3] << 12) | (raw[4] << 4) | (raw[5] >> 4);
        const humAdc   = (raw[6] << 8) | raw[7];
        const gasAdc   = (raw[11] << 2) | (raw[12] >> 6);
        const gasRange = raw[12] & 0x0F;
        const gasValid = (raw[12] >> 5) & 1;
        const heatStab = (raw[12] >> 4) & 1;
        return { pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab };
    }

    _compensateTemp(adcT) {
        const parT1 = this._parT1;
        const parT2 = this._parT2;
        const parT3 = this._parT3;
        const var1 = (adcT >> 3) - (parT1 << 1);
        const var2 = (var1 * parT2) >> 11;
        const var3 = (((var1 >> 1) * (var1 >> 1)) >> 12) * (parT3 << 4) >> 14;
        this._tFine = var2 + var3;
        return ((this._tFine * 5 + 128) >> 8) / 100.0;
    }

    _compensatePressure(adcP) {
        const tFine = this._tFine;
        const parP1 = this._parP1;
        const parP2 = this._parP2;
        const parP3 = this._parP3;
        const parP4 = this._parP4;
        const parP5 = this._parP5;
        const parP6 = this._parP6;
        const parP7 = this._parP7;
        const parP8 = this._parP8;
        const parP9 = this._parP9;
        const parP10 = this._parP10;

        let var1 = (tFine >> 1) - 64000;
        let var2 = ((((var1 >> 2) * (var1 >> 2)) >> 11) * parP6) >> 2;
        var2 = var2 + ((var1 * parP5) << 1);
        var2 = (var2 >> 2) + (parP4 << 16);
        var1 = (((((var1 >> 2) * (var1 >> 2)) >> 13) * (parP3 << 5)) >> 3) + ((parP2 * var1) >> 1);
        var1 = var1 >> 18;
        var1 = ((32768 + var1) * parP1) >> 15;
        let pressComp = 1048576 - adcP;
        pressComp = ((pressComp - (var2 >> 12)) * 3125);
        if (pressComp >= (1 << 30)) {
            pressComp = (pressComp / var1 | 0) << 1;
        } else {
            pressComp = (pressComp << 1) / var1 | 0;
        }
        var1 = (parP9 * (((pressComp >> 3) * (pressComp >> 3)) >> 13)) >> 12;
        var2 = ((pressComp >> 2) * parP8) >> 13;
        const var3 = ((pressComp >> 8) * (pressComp >> 8) * (pressComp >> 8) * parP10) >> 17;
        pressComp = pressComp + ((var1 + var2 + var3 + (parP7 << 7)) >> 4);
        return pressComp / 100.0;
    }

    _compensateHumidity(humAdc) {
        const tempScaled = this._tFine;
        const parH1 = this._parH1;
        const parH2 = this._parH2;
        const parH3 = this._parH3;
        const parH4 = this._parH4;
        const parH5 = this._parH5;
        const parH6 = this._parH6;
        const parH7 = this._parH7;

        const var1 = humAdc - ((parH1 << 4) + (((tempScaled * parH3) / 100 | 0) >> 1));
        const var2 = (parH2 * (((tempScaled * parH4) / 100 | 0) +
                               ((((tempScaled * ((tempScaled * parH5) / 100 | 0)) >> 6) / 100 | 0)) +
                               (1 << 14))) >> 10;
        const var3 = var1 * var2;
        const var4 = ((parH6 << 7) + ((tempScaled * parH7) / 100 | 0)) >> 4;
        const var5 = ((var3 >> 14) * (var3 >> 14)) >> 10;
        const var6 = (var4 * var5) >> 1;
        let humComp = (((var3 + var6) >> 10) * 1000) >> 12;
        if (humComp < 0) humComp = 0;
        if (humComp > 100000) humComp = 100000;
        return humComp / 1000.0;
    }

    _compensateGas(gasAdc, gasRange) {
        const rse = this._rangeSwitchingError;
        const var1 = ((1340 + 5 * rse) * CONST_ARRAY1[gasRange]) >> 16;
        const var2 = ((gasAdc << 15) - (1 << 24)) + var1;
        if (var2 === 0) return NaN;
        const gasRes = ((CONST_ARRAY2[gasRange] * var1) >> 9) + (var2 >> 1);
        return (gasRes / var2) | 0;
    }

    /**
     * Read calibrated temperature.
     * @returns {number} Temperature in degrees Celsius.
     */
    temperature() {
        const { pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab } = this._triggerAndRead();
        const t = this._compensateTemp(tempAdc);
        this._ambientTemp = t;
        return t;
    }

    /**
     * Read calibrated pressure.
     * @returns {number} Pressure in hPa.
     */
    pressure() {
        const { pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab } = this._triggerAndRead();
        this._compensateTemp(tempAdc);
        return this._compensatePressure(pressAdc);
    }

    /**
     * Read calibrated humidity.
     * @returns {number} Relative humidity in %RH.
     */
    humidity() {
        const { pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab } = this._triggerAndRead();
        this._compensateTemp(tempAdc);
        return this._compensateHumidity(humAdc);
    }

    /**
     * Read gas sensor resistance.
     * @returns {number} Gas resistance in Ohms, or NaN on invalid reading.
     */
    gasResistance() {
        const { pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab } = this._triggerAndRead();
        this._compensateTemp(tempAdc);
        if (!gasValid || !heatStab) return NaN;
        return this._compensateGas(gasAdc, gasRange);
    }
}

/**
 * BME680 full interface — extends BME680Minimal with configuration, multi-profile heater, and status.
 *
 * @param {object} transport - Configured I²C transport.
 */
class BME680Full extends BME680Minimal {
    static OSRS_SKIP = 0;
    static OSRS_X1   = 1;
    static OSRS_X2   = 2;
    static OSRS_X4   = 3;
    static OSRS_X8   = 4;
    static OSRS_X16  = 5;

    static MODE_SLEEP  = 0;
    static MODE_FORCED = 1;

    static FILTER_0   = 0;
    static FILTER_1   = 1;
    static FILTER_3   = 2;
    static FILTER_7   = 3;
    static FILTER_15  = 4;
    static FILTER_31  = 5;
    static FILTER_63  = 6;
    static FILTER_127 = 7;

    static STATUS_NEW_DATA      = 0x80;
    static STATUS_GAS_MEASURING = 0x40;
    static STATUS_MEASURING     = 0x20;
    static STATUS_GAS_VALID     = 0x20;
    static STATUS_HEATER_STABLE = 0x10;

    constructor(transport) {
        super(transport);
    }

    /**
     * Write ctrl_hum, ctrl_meas, and config registers in the correct order.
     * @param {number} osrsT - Temperature oversampling (0–5).
     * @param {number} osrsP - Pressure oversampling (0–5).
     * @param {number} osrsH - Humidity oversampling (0–5).
     * @param {number} mode - Power mode (0=sleep, 1=forced).
     * @param {number} filter - IIR filter coefficient (0–7).
     */
    configure(osrsT, osrsP, osrsH, mode, filter) {
        this._osrsT = osrsT;
        this._osrsP = osrsP;
        this._osrsH = osrsH;
        this._filter = filter;
        this._writeReg(_REG_CTRL_HUM, osrsH);
        this._writeReg(_REG_CONFIG, filter << 2);
        this._writeReg(_REG_CTRL_MEAS, (osrsT << 5) | (osrsP << 2) | mode);
    }

    /**
     * Update oversampling for all three TPH channels.
     * @param {number} osrsT - Temperature oversampling (0–5).
     * @param {number} osrsP - Pressure oversampling (0–5).
     * @param {number} osrsH - Humidity oversampling (0–5).
     */
    setOversampling(osrsT, osrsP, osrsH) {
        this._osrsT = osrsT;
        this._osrsP = osrsP;
        this._osrsH = osrsH;
        this._writeReg(_REG_CTRL_HUM, osrsH);
        this._writeReg(_REG_CTRL_MEAS, (osrsT << 5) | (osrsP << 2) | 0);
    }

    /**
     * Update IIR filter coefficient.
     * @param {number} coeff - Filter coefficient (0–7).
     */
    setFilter(coeff) {
        this._filter = coeff;
        this._writeReg(_REG_CONFIG, coeff << 2);
    }

    /**
     * Configure heater profile 0 and activate it.
     * @param {number} tempC - Target heater temperature in degrees Celsius.
     * @param {number} durationMs - Heater on-time in milliseconds (1–4032).
     */
    setHeater(tempC, durationMs) {
        this._heatTemp = tempC;
        this._heatDur = durationMs;
        this._setupHeater(0, tempC, durationMs);
        this._writeReg(_REG_CTRL_GAS_1, (1 << 4) | 0);
    }

    /**
     * Configure one of the 10 heater profiles.
     * @param {number} index - Profile index (0–9).
     * @param {number} tempC - Target heater temperature in degrees Celsius.
     * @param {number} durationMs - Heater on-time in milliseconds (1–4032).
     */
    setHeaterProfile(index, tempC, durationMs) {
        this._setupHeater(index, tempC, durationMs);
    }

    /**
     * Select which heater profile to use in the next forced cycle.
     * @param {number} index - Profile index (0–9).
     */
    selectHeaterProfile(index) {
        this._nbConv = index;
        const gas1 = this._gasEnabled ? ((1 << 4) | index) : index;
        this._writeReg(_REG_CTRL_GAS_1, gas1);
    }

    /**
     * Enable or disable gas conversion.
     * @param {boolean} enabled - True to enable gas measurement.
     */
    setGasEnabled(enabled) {
        this._gasEnabled = enabled;
        const gas1 = enabled ? ((1 << 4) | this._nbConv) : this._nbConv;
        this._writeReg(_REG_CTRL_GAS_1, gas1);
    }

    /**
     * Turn the heater off or on via ctrl_gas_0.
     * @param {boolean} off - True to disable the heater.
     */
    setHeaterOff(off) {
        this._writeReg(_REG_CTRL_GAS_0, off ? 0x08 : 0x00);
    }

    /**
     * Override the ambient temperature used for heater-resistance calculation.
     * @param {number} tempC - Ambient temperature in degrees Celsius.
     */
    setAmbientTemperature(tempC) {
        this._ambientTemp = tempC;
        this._setupHeater(this._nbConv, this._heatTemp, this._heatDur);
    }

    /**
     * Read all four sensor values from a single TPHG cycle.
     * @returns {{ temperature: number, pressure: number, humidity: number, gasResistance: number }}
     */
    readAll() {
        const { pressAdc, tempAdc, humAdc, gasAdc, gasRange, gasValid, heatStab } = this._triggerAndRead();
        const t = this._compensateTemp(tempAdc);
        this._ambientTemp = t;
        const p = this._compensatePressure(pressAdc);
        const h = this._compensateHumidity(humAdc);
        const g = (gasValid && heatStab) ? this._compensateGas(gasAdc, gasRange) : NaN;
        return { temperature: t, pressure: p, humidity: h, gasResistance: g };
    }

    /**
     * Check if the most recent gas reading is valid.
     * @returns {boolean} True if gas_valid_r was set.
     */
    gasValid() {
        const raw = this._readReg(0x2B, 1);
        return ((raw[0] >> 5) & 1) === 1;
    }

    /**
     * Check if the heater reached its target temperature.
     * @returns {boolean} True if heat_stab_r was set.
     */
    heaterStable() {
        const raw = this._readReg(0x2B, 1);
        return ((raw[0] >> 4) & 1) === 1;
    }

    /**
     * Read the measurement status register.
     * @returns {number} Status byte with flags.
     */
    status() {
        const raw = this._readReg(_REG_MEAS_STATUS, 1);
        return raw[0];
    }

    /**
     * Read the chip ID register.
     * @returns {number} Chip ID; expect 0x61.
     */
    chipId() {
        const raw = this._readReg(_REG_ID, 1);
        return raw[0];
    }

    /**
     * Perform a soft reset, re-read calibration, and re-apply configuration.
     */
    reset() {
        this._writeReg(_REG_RESET, _RESET_CMD);
        _delay(2);
        this._readCalibration();
        this._writeReg(_REG_CTRL_HUM, this._osrsH);
        this._writeReg(_REG_CONFIG, this._filter << 2);
        this._writeReg(_REG_CTRL_MEAS, (this._osrsT << 5) | (this._osrsP << 2) | 0);
        this._setupHeater(this._nbConv, this._heatTemp, this._heatDur);
        const gas1 = this._gasEnabled ? ((1 << 4) | this._nbConv) : this._nbConv;
        this._writeReg(_REG_CTRL_GAS_1, gas1);
    }
}

module.exports = { BME680Minimal, BME680Full };
