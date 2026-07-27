'use strict';

const _REG_CONFIG  = 0x00;
const _REG_SHUNT   = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x04;
const _REG_CAL     = 0x05;

/**
 * INA219 26V, 12-bit current/voltage/power monitor — minimal interface.
 *
 * Provides bus voltage, shunt voltage, current, and power readings with no
 * configuration beyond the connection and shunt resistor. Writes the
 * Calibration Register automatically at construction.
 *
 * Default chip configuration (power-on defaults, not rewritten):
 * - BRNG = 1: 32 V bus full-scale range
 * - PG = 11: PGA ÷8, ±320 mV shunt full-scale
 * - BADC = 0011: 12-bit, 532 µs
 * - SADC = 0011: 12-bit, 532 µs
 * - MODE = 111: shunt + bus, continuous
 */
class INA219Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SMBus connection (writeRead, write).
     * @param {number} [rShunt=0.1]        - Shunt resistor value in ohms.
     * @param {number} [maxCurrent=2.0]   - Maximum expected current in amperes.
     */
    constructor(connection, rShunt = 0.1, maxCurrent = 2.0) {
        this._conn = connection;
        this._currentLsb = maxCurrent / 32768;
        this._cal = Math.floor(0.04096 / (this._currentLsb * rShunt)) & 0xFFFE;
        this._writeReg(_REG_CAL, this._cal);
    }

    async _writeReg(reg, value) {
        const buf = Buffer.alloc(3);
        buf[0] = reg;
        buf.writeUInt16BE(value, 1);
        await this._conn.write(buf);
    }

    async _readReg(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 2)).readUInt16BE(0);
    }

    async _readRegSigned(reg) {
        return (await this._conn.writeRead(Buffer.from([reg]), 2)).readInt16BE(0);
    }

    /** @return {Promise<number>} Bus voltage in volts ((raw >> 3) × 4 mV LSB). */
    async voltage()      { return ((await this._readReg(_REG_BUS)) >> 3) * 4e-3; }

    /** @return {Promise<number>} Shunt voltage in volts, signed (raw × 10 µV LSB). */
    async shuntVoltage() { return (await this._readRegSigned(_REG_SHUNT)) * 10e-6; }

    /** @return {Promise<number>} Current in amperes, signed. */
    async current()      { return (await this._readRegSigned(_REG_CURRENT)) * this._currentLsb; }

    /** @return {Promise<number>} Power in watts (raw × 20 × current LSB). */
    async power()        { return (await this._readReg(_REG_POWER)) * 20 * this._currentLsb; }
}

/**
 * INA219 full interface — extends INA219Minimal with full configuration and power management.
 *
 * Adds Configuration Register programming, conversion-ready and overflow status,
 * reset, and shutdown/wake.
 */
class INA219Full extends INA219Minimal {
    static BRNG_16V = 0;
    static BRNG_32V = 1;

    static PGA_1 = 0;
    static PGA_2 = 1;
    static PGA_4 = 2;
    static PGA_8 = 3;

    static ADC_9BIT    = 0x00;
    static ADC_10BIT   = 0x01;
    static ADC_11BIT   = 0x02;
    static ADC_12BIT   = 0x03;
    static ADC_AVG_2    = 0x09;
    static ADC_AVG_4    = 0x0A;
    static ADC_AVG_8    = 0x0B;
    static ADC_AVG_16   = 0x0C;
    static ADC_AVG_32   = 0x0D;
    static ADC_AVG_64   = 0x0E;
    static ADC_AVG_128  = 0x0F;

    static MODE_POWERDOWN       = 0;
    static MODE_SHUNT_TRIG     = 1;
    static MODE_BUS_TRIG       = 2;
    static MODE_SHUNT_BUS_TRIG = 3;
    static MODE_ADC_OFF        = 4;
    static MODE_SHUNT_CONT     = 5;
    static MODE_BUS_CONT       = 6;
    static MODE_SHUNT_BUS_CONT = 7;

    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C or SMBus connection.
     * @param {number} [rShunt=0.1]       - Shunt resistor value in ohms.
     * @param {number} [maxCurrent=2.0]   - Maximum expected current in amperes.
     */
    constructor(connection, rShunt = 0.1, maxCurrent = 2.0) {
        super(connection, rShunt, maxCurrent);
        this._savedMode = INA219Full.MODE_SHUNT_BUS_CONT;
    }

    /**
     * Write the Configuration Register.
     * @param {number} [brng=1]  - Bus voltage range: 0 = 16 V FSR, 1 = 32 V FSR.
     * @param {number} [pga=3]  - Shunt PGA gain: 0 = ÷1, 1 = ÷2, 2 = ÷4, 3 = ÷8.
     * @param {number} [badc=0x03] - Bus ADC resolution/averaging: 0x00–0x0F.
     * @param {number} [sadc=0x03] - Shunt ADC resolution/averaging: 0x00–0x0F.
     * @param {number} [mode=7] - Operating mode 0–7.
     * @returns {Promise<void>}
     */
    async configure(brng = 1, pga = 3, badc = 0x03, sadc = 0x03, mode = 7) {
        const config = ((brng & 1) << 13) | ((pga & 3) << 11) | ((badc & 0x0F) << 7) | ((sadc & 0x0F) << 3) | (mode & 7);
        this._savedMode = mode & 7;
        await this._writeReg(_REG_CONFIG, config);
        await this._writeReg(_REG_CAL, this._cal);
    }

    /** @return {Promise<boolean>} True if a conversion completed since the last read. */
    async conversionReady() { return !!((await this._readReg(_REG_BUS)) & 0x02); }

    /** @return {Promise<boolean>} True if an arithmetic overflow occurred. */
    async overflow()        { return !!((await this._readReg(_REG_BUS)) & 0x01); }

    /** @returns {Promise<void>} */
    async reset() {
        await this._writeReg(_REG_CONFIG, 0x8000);
        await this._writeReg(_REG_CAL, this._cal);
    }

    /** @returns {Promise<void>} */
    async shutdown() {
        const config = await this._readReg(_REG_CONFIG);
        this._savedMode = config & 7;
        await this._writeReg(_REG_CONFIG, config & 0xFFF8);
    }

    /** @returns {Promise<void>} */
    async wake() {
        const config = await this._readReg(_REG_CONFIG);
        await this._writeReg(_REG_CONFIG, (config & 0xFFF8) | this._savedMode);
    }

    /** @returns {Promise<void>} */
    async trigger() {
        const config = await this._readReg(_REG_CONFIG);
        await this._writeReg(_REG_CONFIG, config);
    }
}

module.exports = { INA219Minimal, INA219Full };
