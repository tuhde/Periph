'use strict';

const _REG_CONFIG  = 0x00;
const _REG_SHUNT   = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x04;
const _REG_CAL     = 0x05;
const _REG_MASK    = 0x06;
const _REG_ALERT   = 0x07;
const _REG_MFR_ID  = 0xFE;
const _REG_DIE_ID  = 0xFF;

const _CONFIG_DEFAULT = 0x4127;

/**
 * INA226 36V, 16-bit current/voltage/power monitor — minimal interface.
 *
 * Provides bus voltage, shunt voltage, current, and power readings with no
 * configuration beyond the transport and shunt resistor. Writes the
 * Calibration Register automatically at construction.
 *
 * Default configuration (written at construction):
 * - MODE = 7: shunt + bus, continuous
 * - VBUSCT = 4: 1.1 ms bus voltage conversion time
 * - VSHCT = 4: 1.1 ms shunt voltage conversion time
 * - AVG = 0: 1 sample (no averaging)
 */
class INA226Minimal {
    /**
     * @param {object} transport          - Configured I²C or SMBus transport (writeRead, write).
     * @param {number} [rShunt=0.1]       - Shunt resistor value in ohms.
     * @param {number} [maxCurrent=2.0]   - Maximum expected current in amperes.
     */
    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        this._transport = transport;
        this._currentLsb = maxCurrent / 32768;
        this._cal = Math.floor(0.00512 / (this._currentLsb * rShunt));
        this._writeReg(_REG_CONFIG, _CONFIG_DEFAULT);
        this._writeReg(_REG_CAL, this._cal);
    }

    _writeReg(reg, value) {
        const buf = Buffer.alloc(3);
        buf[0] = reg;
        buf.writeUInt16BE(value, 1);
        this._transport.write(buf);
    }

    _readReg(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 2).readUInt16BE(0);
    }

    _readRegSigned(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 2).readInt16BE(0);
    }

    /**
     * Read bus voltage.
     * @returns {number} Bus voltage in volts (raw × 1.25 mV LSB).
     */
    voltage()      { return this._readReg(_REG_BUS) * 1.25e-3; }

    /**
     * Read differential shunt voltage.
     * @returns {number} Shunt voltage in volts, signed (raw × 2.5 µV LSB).
     */
    shuntVoltage() { return this._readRegSigned(_REG_SHUNT) * 2.5e-6; }

    /**
     * Read calculated current through the shunt.
     * @returns {number} Current in amperes, signed.
     */
    current()      { return this._readRegSigned(_REG_CURRENT) * this._currentLsb; }

    /**
     * Read calculated power.
     * @returns {number} Power in watts (raw × 25 × current LSB).
     */
    power()        { return this._readReg(_REG_POWER) * 25 * this._currentLsb; }
}

/**
 * INA226 full interface — extends INA226Minimal with configuration and alert support.
 *
 * Adds Configuration Register programming, conversion-ready and overflow status,
 * alert configuration, reset, and shutdown/wake.
 *
 * Alert function constants (pass to setAlert):
 * - INA226Full.SOL  — shunt voltage over-limit
 * - INA226Full.SUL  — shunt voltage under-limit
 * - INA226Full.BOL  — bus voltage over-limit
 * - INA226Full.BUL  — bus voltage under-limit
 * - INA226Full.POL  — power over-limit
 * - INA226Full.CNVR — conversion ready
 */
class INA226Full extends INA226Minimal {
    static SOL  = 0x8000;
    static SUL  = 0x4000;
    static BOL  = 0x2000;
    static BUL  = 0x1000;
    static POL  = 0x0800;
    static CNVR = 0x0400;
    static AFF  = 0x0010;

    /**
     * @param {object} transport          - Configured I²C or SMBus transport.
     * @param {number} [rShunt=0.1]       - Shunt resistor value in ohms.
     * @param {number} [maxCurrent=2.0]   - Maximum expected current in amperes.
     */
    constructor(transport, rShunt = 0.1, maxCurrent = 2.0) {
        super(transport, rShunt, maxCurrent);
        this._mode = 0x07;
    }

    /**
     * Write the Configuration Register.
     * @param {number} [avg=0]    - Averaging count selector 0–7 (0 = 1 sample … 7 = 1024 samples).
     * @param {number} [vbusCt=4] - Bus voltage conversion time selector 0–7 (default 4 = 1.1 ms).
     * @param {number} [vshCt=4]  - Shunt voltage conversion time selector 0–7 (default 4 = 1.1 ms).
     * @param {number} [mode=7]   - Operating mode 0–7 (7 = shunt+bus continuous).
     */
    configure(avg = 0, vbusCt = 4, vshCt = 4, mode = 7) {
        const config = ((avg & 0x07) << 9) | ((vbusCt & 0x07) << 6) | ((vshCt & 0x07) << 3) | (mode & 0x07);
        this._mode = mode & 0x07;
        this._writeReg(_REG_CONFIG, config);
    }

    /**
     * Read the Conversion Ready Flag (CVRF) from the Mask/Enable Register.
     *
     * Note: Reading Mask/Enable clears CVRF. Read it last if also checking other flags.
     *
     * @returns {boolean} True if a conversion completed since the last Mask/Enable read.
     */
    conversionReady() { return !!(this._readReg(_REG_MASK) & 0x0008); }

    /**
     * Read the Math Overflow Flag (OVF) from the Mask/Enable Register.
     * @returns {boolean} True if an arithmetic overflow occurred in the power calculation.
     */
    overflow()        { return !!(this._readReg(_REG_MASK) & 0x0004); }

    /**
     * Configure the alert pin function and threshold.
     *
     * Only one alert function can be active at a time.
     *
     * @param {number} fn           - Alert function constant (SOL/SUL/BOL/BUL/POL/CNVR).
     * @param {number} [limit=0]    - Threshold in natural units (V for voltage, W for power).
     * @param {number} [polarity=0] - 0 = active-low (default), 1 = active-high.
     * @param {number} [latch=0]    - 0 = transparent (default), 1 = latch until Mask/Enable is read.
     */
    setAlert(fn, limit = 0, polarity = 0, latch = 0) {
        let raw = 0;
        if (fn === INA226Full.SOL || fn === INA226Full.SUL) raw = Math.floor(limit / 2.5e-6);
        else if (fn === INA226Full.BOL || fn === INA226Full.BUL) raw = Math.floor(limit / 1.25e-3);
        else if (fn === INA226Full.POL) raw = Math.floor(limit / (25 * this._currentLsb));
        const mask = fn | ((polarity & 1) << 1) | (latch & 1);
        this._writeReg(_REG_MASK, mask);
        this._writeReg(_REG_ALERT, raw & 0xFFFF);
    }

    /**
     * Read the Mask/Enable Register.
     * @returns {number} Raw 16-bit value containing alert and status flags.
     */
    alertFlags()     { return this._readReg(_REG_MASK); }

    /**
     * Reset all registers to power-on defaults, then re-write the Calibration Register.
     */
    reset() {
        this._writeReg(_REG_CONFIG, 0x8000);
        this._writeReg(_REG_CAL, this._cal);
    }

    /**
     * Enter power-down mode (MODE = 000) and save the current mode for wake().
     */
    shutdown() {
        const config = this._readReg(_REG_CONFIG);
        this._mode = config & 0x07;
        this._writeReg(_REG_CONFIG, config & 0xFFF8);
    }

    /**
     * Restore the operating mode saved by shutdown().
     */
    wake() {
        const config = this._readReg(_REG_CONFIG);
        this._writeReg(_REG_CONFIG, (config & 0xFFF8) | this._mode);
    }

    /**
     * Read the Manufacturer ID register.
     * @returns {number} Manufacturer ID; expect 0x5449 (Texas Instruments).
     */
    manufacturerId() { return this._readReg(_REG_MFR_ID); }

    /**
     * Read the Die ID register.
     * @returns {number} Die revision ID; expect 0x2260.
     */
    dieId()          { return this._readReg(_REG_DIE_ID); }
}

module.exports = { INA226Minimal, INA226Full };
