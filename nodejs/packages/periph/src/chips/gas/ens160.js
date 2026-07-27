'use strict';

const _REG_PART_ID       = 0x00;
const _REG_OPMODE        = 0x10;
const _REG_CONFIG        = 0x11;
const _REG_COMMAND       = 0x12;
const _REG_TEMP_IN       = 0x13;
const _REG_RH_IN         = 0x15;
const _REG_DEVICE_STATUS = 0x20;
const _REG_DATA_AQI      = 0x21;
const _REG_DATA_TVOC     = 0x22;
const _REG_DATA_ECO2     = 0x24;
const _REG_DATA_T        = 0x30;
const _REG_DATA_RH       = 0x32;
const _REG_DATA_MISR     = 0x38;
const _REG_GPR_WRITE     = 0x40;
const _REG_GPR_READ      = 0x48;

const _OPMODE_DEEP_SLEEP = 0x00;
const _OPMODE_IDLE       = 0x01;
const _OPMODE_STANDARD   = 0x02;
const _OPMODE_RESET      = 0xF0;

const _PART_ID_EXPECTED  = 0x0160;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * ENS160 digital multi-gas sensor — minimal interface.
 *
 * Provides calibrated air quality readings (AQI, TVOC, eCO2) with no
 * configuration required beyond the connection. The sensor performs automatic
 * baseline correction and on-chip signal processing.
 *
 * Default: STANDARD mode (gas sensing active), polling only, no external
 * T/RH compensation.
 *
 * Constructor caveat: JS constructors cannot be async, but the original
 * synchronous constructor validated PART_ID and threw synchronously on
 * mismatch — that guarantee cannot be preserved exactly. The validation is
 * fired off unawaited (matching the fire-and-forget convention used
 * throughout this port); a PART_ID mismatch now surfaces as an *unhandled
 * promise rejection* shortly after construction rather than a synchronous
 * throw from `new ENS160Minimal(...)`. Callers that need to detect "wrong or
 * absent device" deterministically should call an async method (e.g.
 * `await sensor.status()`) right after construction and handle its rejection.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 */
class ENS160Minimal {
    constructor(connection) {
        this._conn = connection;
        this._init();
    }

    async _init() {
        await this._writeReg(_REG_OPMODE, _OPMODE_IDLE);
        _delay(1);
        const partId = await this._readRegLE16(_REG_PART_ID);
        if (partId !== _PART_ID_EXPECTED) {
            throw new Error('ENS160 not found: expected PART_ID 0x0160, got 0x' + partId.toString(16).padStart(4, '0'));
        }
        await this._writeReg(_REG_OPMODE, _OPMODE_STANDARD);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg, value]));
    }

    async _writeRegLE16(reg, value) {
        await this._conn.write(Buffer.from([reg, value & 0xFF, (value >> 8) & 0xFF]));
    }

    async _readReg(reg, n) {
        return this._conn.writeRead(Buffer.from([reg]), n);
    }

    async _readRegLE16(reg) {
        const data = await this._readReg(reg, 2);
        return data[0] | (data[1] << 8);
    }

    async _readDeviceStatus() {
        const data = await this._readReg(_REG_DEVICE_STATUS, 1);
        return data[0];
    }

    async _waitForNewData(timeoutMs = 5000) {
        const start = Date.now();
        while (true) {
            const status = await this._readDeviceStatus();
            if (status & 0x02) {
                return status;
            }
            if (Date.now() - start > timeoutMs) {
                throw new Error('ENS160: NEWDAT not set within ' + timeoutMs + ' ms');
            }
            _delay(10);
        }
    }

    /**
     * Read the VALIDITY_FLAG from DEVICE_STATUS.
     * @returns {Promise<number>} Validity flag (0=OK, 1=Warm-up, 2=Initial Start-up, 3=No valid output).
     */
    async status() {
        const status = await this._readDeviceStatus();
        return (status >> 2) & 0x03;
    }

    /**
     * Read calibrated air quality values.
     *
     * Polls until NEWDAT is set, then checks VALIDITY_FLAG. Only returns
     * data when validity is 0 (OK). Reads AQI, TVOC, and eCO2 in a single
     * burst to ensure consistency.
     *
     * @returns {Promise<object>} Keys: aqi (number, 1–5), tvocPpb (number), eco2Ppm (number).
     * @throws {Error} If VALIDITY_FLAG is not 0 after NEWDAT is set.
     */
    async readAirQuality() {
        const status = await this._waitForNewData();
        const validity = (status >> 2) & 0x03;
        if (validity !== 0) {
            throw new Error('ENS160: data not valid (VALIDITY_FLAG=' + validity + ')');
        }
        const data = await this._readReg(_REG_DATA_AQI, 5);
        const aqi = data[0] & 0x07;
        const tvocPpb = data[1] | (data[2] << 8);
        const eco2Ppm = data[3] | (data[4] << 8);
        return { aqi, tvocPpb, eco2Ppm };
    }
}

/**
 * ENS160 full interface — extends ENS160Minimal with compensation, raw readings, and power control.
 *
 * Adds external temperature/humidity compensation, individual gas readings,
 * raw sensor resistance, firmware version query, interrupt configuration,
 * and sleep/wake control.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C or SPI connection.
 */
class ENS160Full extends ENS160Minimal {
    static VALIDITY_OK              = 0;
    static VALIDITY_WARMUP          = 1;
    static VALIDITY_INITIAL_STARTUP = 2;
    static VALIDITY_INVALID         = 3;

    constructor(connection) {
        super(connection);
    }

    /**
     * Write external temperature and humidity for compensation.
     * @param {number} tempCelsius - Ambient temperature in degrees Celsius.
     * @param {number} rhPercent - Ambient relative humidity in percent (0–100).
     * @returns {Promise<void>}
     */
    async setCompensation(tempCelsius, rhPercent) {
        const tempRaw = Math.round((tempCelsius + 273.15) * 64);
        const rhRaw = Math.round(rhPercent * 512);
        await this._writeRegLE16(_REG_TEMP_IN, tempRaw);
        await this._writeRegLE16(_REG_RH_IN, rhRaw);
    }

    /**
     * Read TVOC concentration.
     * @returns {Promise<number>} TVOC in ppb.
     */
    async readTvoc() {
        await this._waitForNewData();
        return this._readRegLE16(_REG_DATA_TVOC);
    }

    /**
     * Read equivalent CO2 concentration.
     * @returns {Promise<number>} eCO2 in ppm.
     */
    async readEco2() {
        await this._waitForNewData();
        return this._readRegLE16(_REG_DATA_ECO2);
    }

    /**
     * Read Air Quality Index (UBA scale).
     * @returns {Promise<number>} AQI value 1–5 (1=Excellent, 5=Unhealthy).
     */
    async readAqi() {
        await this._waitForNewData();
        const data = await this._readReg(_REG_DATA_AQI, 1);
        return data[0] & 0x07;
    }

    /**
     * Read ethanol concentration estimate.
     * @returns {Promise<number>} Ethanol estimate in ppb (alias of DATA_TVOC at 0x22).
     */
    async readEthanol() {
        await this._waitForNewData();
        return this._readRegLE16(_REG_DATA_TVOC);
    }

    /**
     * Read raw sensor resistance from GPR_READ registers.
     * @param {number} sensor - Sensor number (1 or 4).
     * @returns {Promise<number>} Resistance in Ohms.
     * @throws {Error} If sensor is not 1 or 4.
     */
    async readRawResistance(sensor) {
        let offset;
        if (sensor === 1) {
            offset = 0;
        } else if (sensor === 4) {
            offset = 6;
        } else {
            throw new Error('sensor must be 1 or 4, got ' + sensor);
        }
        const raw = await this._readRegLE16(_REG_GPR_READ + offset);
        return Math.pow(2.0, raw / 2048.0);
    }

    /**
     * Read the temperature and humidity values used by the sensor.
     * @returns {Promise<object>} Keys: tempCelsius (number), rhPercent (number).
     */
    async readCompensationActuals() {
        const data = await this._readReg(_REG_DATA_T, 4);
        const tempRaw = data[0] | (data[1] << 8);
        const rhRaw = data[2] | (data[3] << 8);
        const tempCelsius = (tempRaw / 64.0) - 273.15;
        const rhPercent = rhRaw / 512.0;
        return { tempCelsius, rhPercent };
    }

    /**
     * Query firmware version (requires IDLE mode).
     *
     * Switches to IDLE, issues GET_APPVER command, reads GPR_READ, then
     * returns to STANDARD mode.
     *
     * @returns {Promise<object>} Keys: major (number), minor (number), release (number).
     */
    async getFirmwareVersion() {
        await this._writeReg(_REG_OPMODE, _OPMODE_IDLE);
        _delay(1);
        await this._writeReg(_REG_COMMAND, 0x0E);
        _delay(1);
        const data = await this._readReg(_REG_GPR_READ + 4, 3);
        const major = data[0];
        const minor = data[1];
        const release = data[2];
        await this._writeReg(_REG_OPMODE, _OPMODE_STANDARD);
        return { major, minor, release };
    }

    /**
     * Configure the INTn interrupt pin.
     * @param {boolean} enabled - Enable interrupt pin.
     * @param {boolean} activeHigh - True for active-high polarity, false for active-low.
     * @param {boolean} pushPull - True for push-pull drive, false for open-drain.
     * @param {boolean} onData - Assert on new DATA_xxx data.
     * @param {boolean} onGpr - Assert on new GPR_READ data.
     * @returns {Promise<void>}
     */
    async configureInterrupt(enabled, activeHigh, pushPull, onData, onGpr) {
        let config = 0;
        if (enabled) config |= 0x01;
        if (onData) config |= 0x02;
        if (onGpr) config |= 0x08;
        if (pushPull) config |= 0x20;
        if (activeHigh) config |= 0x40;
        await this._writeReg(_REG_CONFIG, config);
    }

    /**
     * Enter DEEP SLEEP mode for power saving.
     * @returns {Promise<void>}
     */
    async sleep() {
        await this._writeReg(_REG_OPMODE, _OPMODE_DEEP_SLEEP);
    }

    /**
     * Wake from DEEP SLEEP and resume STANDARD gas sensing.
     * @returns {Promise<void>}
     */
    async wake() {
        await this._writeReg(_REG_OPMODE, _OPMODE_IDLE);
        _delay(1);
        await this._writeReg(_REG_OPMODE, _OPMODE_STANDARD);
    }
}

module.exports = { ENS160Minimal, ENS160Full };
