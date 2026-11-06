'use strict';

const { toSigned } = require('../../connection/register');

const _REG_CONVERSION = 0x00;
const _REG_CONFIG     = 0x01;

const _CONFIG_RESET = 0x8583;

const _OS          = 0x8000;
const _MODE_SINGLE = 0x0100;
// Reserved bits written at their reset values: 000010 in 14:9, 00011 in 4:0.
const _RESERVED    = 0x0403;

const _SPS = [128, 250, 490, 920, 1600, 2400, 3300];
const _LSB = 0.001;

function _sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * ADS1013 12-bit I²C ADC with a fixed ±2.048 V range (Texas Instruments) —
 * minimal interface.
 *
 * Smallest member of the ADS101x family: one input (AIN0 − AIN1), no PGA,
 * no multiplexer, no comparator, no ALERT/RDY pin. Registers are 16-bit,
 * big-endian, addressed through a persistent Address Pointer. Four
 * selectable addresses (0x48–0x4B) via the ADDR strap. The chip has no ID
 * register, so construction performs no I²C traffic.
 *
 * Defaults: 1600 SPS, single-shot. The whole Config word is written for
 * every conversion.
 */
class ADS1013Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x48–0x4B).
     */
    constructor(connection) {
        this._conn = connection;
        this._dr = 4;
        this._continuous = false;
    }

    async _readReg(reg) {
        const buf = await this._conn.readReg(reg & 0xFF, 2);
        return buf.readUInt16BE(0);
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg & 0xFF, Buffer.from([(value >> 8) & 0xFF, value & 0xFF]));
    }

    _configWord(osBit, continuous) {
        let word = _RESERVED | (this._dr << 5);
        if (osBit) word |= _OS;
        if (!continuous) word |= _MODE_SINGLE;
        return word;
    }

    async _readConversion() {
        return toSigned(await this._readReg(_REG_CONVERSION), 16) >> 4;
    }

    /**
     * Run one single-shot conversion of AIN0 − AIN1. Writes the whole Config
     * word with OS = 1, polls OS until the chip is idle again, then reads
     * CONVERSION.
     * @returns {Promise<number>} 12-bit two's-complement code, −2048…2047.
     */
    async readRaw() {
        await this._writeReg(_REG_CONFIG, this._configWord(true, false));
        this._continuous = false;
        for (let i = 0; i < 100; i++) {
            if ((await this._readReg(_REG_CONFIG)) & _OS) break;
            await _sleep(1);
        }
        return this._readConversion();
    }

    /**
     * Run one single-shot conversion and convert it to volts.
     * @returns {Promise<number>} Voltage AIN0 − AIN1 in volts (raw × 0.001).
     */
    async readVoltage() {
        return (await this.readRaw()) * _LSB;
    }
}

/**
 * ADS1013 full interface — extends Minimal with data-rate selection and
 * continuous conversion.
 */
class ADS1013Full extends ADS1013Minimal {
    async _writeConfig() {
        await this._writeReg(_REG_CONFIG, this._configWord(false, this._continuous));
    }

    /**
     * Fixed full-scale range.
     * @returns {number} Always 2.048 (volts).
     */
    fullScale() {
        return 2.048;
    }

    /**
     * Size of one conversion code.
     * @returns {number} Always 0.001 (volts).
     */
    lsb() {
        return _LSB;
    }

    /**
     * Select the conversion data rate.
     * @param {number} dr - DR_128 (0) … DR_3300 (6).
     * @returns {Promise<void>}
     * @throws {RangeError} If dr is outside 0–6 (7 aliases 3300 SPS).
     */
    async setDataRate(dr) {
        if (!Number.isInteger(dr) || dr < 0 || dr > 6) throw new RangeError('dr must be 0-6');
        this._dr = dr;
        if (this._continuous) await this._writeConfig();
    }

    /**
     * Read the configured data-rate code.
     * @returns {number} DR code 0–6.
     */
    getDataRate() {
        return this._dr;
    }

    /**
     * Current data rate.
     * @returns {number} 128, 250, 490, 920, 1600, 2400 or 3300 samples per second.
     */
    dataRateSps() {
        return _SPS[this._dr];
    }

    /**
     * Start continuous conversion (MODE = 0) at the current data rate.
     * @returns {Promise<void>}
     */
    async startContinuous() {
        this._continuous = true;
        await this._writeConfig();
    }

    /**
     * Read the most recent completed result without touching Config (valid
     * at least 1/DR after startContinuous()).
     * @returns {Promise<number>} 12-bit two's-complement code, −2048…2047.
     */
    async readContinuousRaw() {
        return this._readConversion();
    }

    /**
     * Read the most recent completed result in volts.
     * @returns {Promise<number>} readContinuousRaw() × lsb().
     */
    async readContinuous() {
        return (await this._readConversion()) * _LSB;
    }

    /**
     * Stop continuous conversion (MODE = 1, single-shot / power-down).
     * @returns {Promise<void>}
     */
    async stopContinuous() {
        this._continuous = false;
        await this._writeConfig();
    }

    /**
     * Report whether a single-shot conversion is running (OS = 0; only
     * meaningful in single-shot mode).
     * @returns {Promise<boolean>} True while converting.
     */
    async isConverting() {
        return ((await this._readReg(_REG_CONFIG)) & _OS) === 0;
    }

    /**
     * Read the raw CONFIG register.
     * @returns {Promise<number>} 16-bit CONFIG value.
     */
    async readConfig() {
        return this._readReg(_REG_CONFIG);
    }

    /**
     * Write CONFIG = 0x8583 back and clear the cached settings. Not an I²C
     * general-call reset.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._writeReg(_REG_CONFIG, _CONFIG_RESET);
        this._dr = 4;
        this._continuous = false;
    }
}

/** Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
ADS1013Minimal.I2C_ADDRESS = 0x48;

/** Data-rate codes. */
ADS1013Full.DR_128 = 0;
ADS1013Full.DR_250 = 1;
ADS1013Full.DR_490 = 2;
ADS1013Full.DR_920 = 3;
ADS1013Full.DR_1600 = 4;
ADS1013Full.DR_2400 = 5;
ADS1013Full.DR_3300 = 6;

module.exports = { ADS1013Minimal, ADS1013Full };
