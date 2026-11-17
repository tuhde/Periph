'use strict';

const _REG_CONFIG  = 0x00;
const _REG_CURRENT = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_MASK    = 0x06;
const _REG_ALERT   = 0x07;
const _REG_MFR_ID  = 0xFE;
const _REG_DIE_ID  = 0xFF;

const _CONFIG_BASE = 0x6000; // bits 14:12 must be written as 110

/**
 * INA260 36V, 16-bit current/voltage/power monitor with integrated shunt — minimal interface.
 *
 * Provides bus voltage, current, and power readings with no configuration
 * beyond the connection. The shunt is integrated and the current/power
 * registers have fixed LSBs, so no calibration is needed and the constructor
 * makes no bus access.
 *
 * Default configuration (chip power-on state, not rewritten):
 * - MODE = 7: shunt current + bus voltage, continuous
 * - VBUSCT = 4: 1.1 ms bus voltage conversion time
 * - ISHCT = 4: 1.1 ms shunt current conversion time
 * - AVG = 0: 1 sample (no averaging)
 */
class INA260Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (writeRead, write).
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, Buffer.from([(value >> 8) & 0xFF, value & 0xFF]));
    }

    async _readReg(reg) {
        return (await this._conn.readReg(reg, 2)).readUInt16BE(0);
    }

    async _readRegSigned(reg) {
        return (await this._conn.readReg(reg, 2)).readInt16BE(0);
    }

    /**
     * Read bus voltage.
     * @returns {Promise<number>} Bus voltage in volts (raw × 1.25 mV LSB).
     */
    async voltage() { return (await this._readReg(_REG_BUS)) * 1.25e-3; }

    /**
     * Read current through the integrated shunt.
     * @returns {Promise<number>} Current in amperes, signed (raw × 1.25 mA LSB); positive flows IN+ → IN−.
     */
    async current() { return (await this._readRegSigned(_REG_CURRENT)) * 1.25e-3; }

    /**
     * Read calculated power.
     * @returns {Promise<number>} Power in watts (raw × 10 mW LSB), always positive.
     */
    async power()   { return (await this._readReg(_REG_POWER)) * 10e-3; }
}

/**
 * INA260 full interface — extends INA260Minimal with configuration and alert support.
 *
 * Adds Configuration Register programming, triggered conversions,
 * conversion-ready and overflow status, alert configuration, reset, and
 * shutdown/wake.
 *
 * Alert function constants (pass to setAlert):
 * - INA260Full.OCL  — over-current limit
 * - INA260Full.UCL  — under-current limit
 * - INA260Full.BOL  — bus voltage over-limit
 * - INA260Full.BUL  — bus voltage under-limit
 * - INA260Full.POL  — power over-limit
 * - INA260Full.CNVR — conversion ready
 */
class INA260Full extends INA260Minimal {
    static OCL  = 0x8000;
    static UCL  = 0x4000;
    static BOL  = 0x2000;
    static BUL  = 0x1000;
    static POL  = 0x0800;
    static CNVR = 0x0400;
    static AFF  = 0x0010;

    static AVG_1 = 0; static AVG_4 = 1; static AVG_16 = 2; static AVG_64 = 3;
    static AVG_128 = 4; static AVG_256 = 5; static AVG_512 = 6; static AVG_1024 = 7;
    static CT_140US = 0; static CT_204US = 1; static CT_332US = 2; static CT_588US = 3;
    static CT_1100US = 4; static CT_2116US = 5; static CT_4156US = 6; static CT_8244US = 7;
    static MODE_POWER_DOWN = 0;
    static MODE_SHUNT_TRIGGERED = 1;
    static MODE_BUS_TRIGGERED = 2;
    static MODE_SHUNT_BUS_TRIGGERED = 3;
    static MODE_SHUNT_CONT = 5;
    static MODE_BUS_CONT = 6;
    static MODE_SHUNT_BUS_CONT = 7;

    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection.
     */
    constructor(connection) {
        super(connection);
        this._avg = 0;
        this._vbusCt = 4;
        this._ishCt = 4;
        this._mode = 7;
    }

    async _writeConfig(mode) {
        const config = _CONFIG_BASE | (this._avg << 9) | (this._vbusCt << 6) | (this._ishCt << 3) | (mode & 0x07);
        await this._writeReg(_REG_CONFIG, config);
    }

    /**
     * Write the Configuration Register (bits 14:12 kept at 110).
     * @param {number} [avg=0]    - Averaging count selector 0–7 (AVG_1 … AVG_1024).
     * @param {number} [vbusCt=4] - Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
     * @param {number} [ishCt=4]  - Shunt current conversion time selector 0–7 (default 4 = 1.1 ms).
     * @param {number} [mode=7]   - Operating mode 0–7 (7 = shunt+bus continuous).
     * @returns {Promise<void>}
     */
    async configure(avg = 0, vbusCt = 4, ishCt = 4, mode = 7) {
        this._avg = avg & 0x07;
        this._vbusCt = vbusCt & 0x07;
        this._ishCt = ishCt & 0x07;
        this._mode = mode & 0x07;
        await this._writeConfig(this._mode);
    }

    /**
     * Start one triggered conversion with the current settings (rewrites the
     * Configuration Register). Poll conversionReady() for completion.
     * @param {number} [mode=3] - MODE_SHUNT_TRIGGERED, MODE_BUS_TRIGGERED or MODE_SHUNT_BUS_TRIGGERED.
     * @returns {Promise<void>}
     */
    async trigger(mode = INA260Full.MODE_SHUNT_BUS_TRIGGERED) { await this._writeConfig(mode); }

    /**
     * Read the Conversion Ready Flag (CVRF) from the Mask/Enable Register.
     *
     * Note: Reading Mask/Enable clears CVRF. Read it last if also checking other flags.
     *
     * @returns {Promise<boolean>} True if a conversion completed since the last Mask/Enable read.
     */
    async conversionReady() { return !!((await this._readReg(_REG_MASK)) & 0x0008); }

    /**
     * Read the Math Overflow Flag (OVF) from the Mask/Enable Register.
     * @returns {Promise<boolean>} True if an arithmetic overflow occurred in the power calculation.
     */
    async overflow()        { return !!((await this._readReg(_REG_MASK)) & 0x0004); }

    /**
     * Configure the alert pin function and threshold.
     *
     * Writes the Alert Limit register first, then Mask/Enable. Only one alert
     * function can be active at a time.
     *
     * @param {number} fn           - Alert function constant (OCL/UCL/BOL/BUL/POL/CNVR).
     * @param {number} [limit=0]    - Threshold in natural units (A for current, V for voltage, W for power); ignored for CNVR.
     * @param {number} [polarity=0] - 0 = active-low (default), 1 = active-high.
     * @param {number} [latch=0]    - 0 = transparent (default), 1 = latch until Mask/Enable is read.
     * @returns {Promise<void>}
     */
    async setAlert(fn, limit = 0, polarity = 0, latch = 0) {
        let raw = null;
        if (fn === INA260Full.OCL || fn === INA260Full.UCL ||
            fn === INA260Full.BOL || fn === INA260Full.BUL) raw = Math.round(limit / 1.25e-3);
        else if (fn === INA260Full.POL) raw = Math.round(limit / 10e-3);
        if (raw !== null) await this._writeReg(_REG_ALERT, raw & 0xFFFF);
        const mask = fn | ((polarity & 1) << 1) | (latch & 1);
        await this._writeReg(_REG_MASK, mask);
    }

    /**
     * Disable all alert functions (write Mask/Enable = 0).
     * @returns {Promise<void>}
     */
    async clearAlert() { await this._writeReg(_REG_MASK, 0); }

    /**
     * Read the Mask/Enable Register (clears CVRF and, in latch mode, AFF).
     * @returns {Promise<number>} Raw 16-bit value containing alert and status flags.
     */
    async alertFlags()     { return this._readReg(_REG_MASK); }

    /**
     * Reset all registers to power-on defaults (RST bit).
     * @returns {Promise<void>}
     */
    async reset() {
        await this._writeReg(_REG_CONFIG, 0x8000);
        this._avg = 0;
        this._vbusCt = 4;
        this._ishCt = 4;
        this._mode = 7;
    }

    /**
     * Enter power-down mode (MODE = 000); the previous mode is kept for wake().
     * @returns {Promise<void>}
     */
    async shutdown() { await this._writeConfig(INA260Full.MODE_POWER_DOWN); }

    /**
     * Restore the operating mode that was active before shutdown().
     * @returns {Promise<void>}
     */
    async wake() { await this._writeConfig(this._mode); }

    /**
     * Read the Manufacturer ID register.
     * @returns {Promise<number>} Manufacturer ID; expect 0x5449 (Texas Instruments).
     */
    async manufacturerId() { return this._readReg(_REG_MFR_ID); }

    /**
     * Read the Die ID register.
     * @returns {Promise<number>} Die ID; (value >> 4) is 0x227, the low nibble is the revision.
     */
    async dieId()          { return this._readReg(_REG_DIE_ID); }
}

module.exports = { INA260Minimal, INA260Full };
