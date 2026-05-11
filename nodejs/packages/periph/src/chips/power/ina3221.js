'use strict';

const _REG_CONFIG = 0x00;
const _REG_SHUNT1 = 0x01;
const _REG_BUS1   = 0x02;
const _REG_SHUNT2 = 0x03;
const _REG_BUS2   = 0x04;
const _REG_SHUNT3 = 0x05;
const _REG_BUS3   = 0x06;
const _REG_CH1_CRIT = 0x07;
const _REG_CH1_WARN = 0x08;
const _REG_CH2_CRIT = 0x09;
const _REG_CH2_WARN = 0x0A;
const _REG_CH3_CRIT = 0x0B;
const _REG_CH3_WARN = 0x0C;
const _REG_SUM      = 0x0D;
const _REG_SUM_LIMIT = 0x0E;
const _REG_MASK_EN  = 0x0F;
const _REG_PV_UPPER = 0x10;
const _REG_PV_LOWER = 0x11;
const _REG_MFR_ID   = 0xFE;
const _REG_DIE_ID   = 0xFF;

const _SHUNT_REGS = [_REG_SHUNT1, _REG_SHUNT2, _REG_SHUNT3];
const _BUS_REGS   = [_REG_BUS1,   _REG_BUS2,   _REG_BUS3  ];
const _CRIT_REGS  = [_REG_CH1_CRIT, _REG_CH2_CRIT, _REG_CH3_CRIT];
const _WARN_REGS  = [_REG_CH1_WARN, _REG_CH2_WARN, _REG_CH3_WARN];

/**
 * INA3221 three-channel 26V current/voltage/power monitor — minimal interface.
 *
 * Reads bus voltage, shunt voltage, current, and power for each of the three
 * channels with no configuration beyond the transport and shunt resistors.
 * The chip's power-on default (all three channels on, continuous shunt+bus)
 * is used without modification.
 *
 * @param {object} transport - Configured I2C or SMBus transport (writeRead, write).
 * @param {number|number[]} [rShunt=0.1] - Shunt resistor value in ohms. Pass a
 *        single number to apply the same value to all three channels, or an
 *        array of 3 numbers for per-channel values.
 */
class INA3221Minimal {
    constructor(transport, rShunt = 0.1) {
        this._transport = transport;
        if (Array.isArray(rShunt)) {
            this._rShunt = rShunt.map(v => Number(v));
        } else {
            this._rShunt = [Number(rShunt), Number(rShunt), Number(rShunt)];
        }
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

    _channelValid(channel) {
        const ch = Number(channel);
        if (ch < 1 || ch > 3) throw new Error('channel must be 1, 2, or 3');
        return ch;
    }

    /**
     * Read bus voltage for a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @returns {number} Bus voltage in volts.
     */
    voltage(channel) {
        const ch = this._channelValid(channel);
        const raw = this._readReg(_BUS_REGS[ch - 1]);
        return (raw >> 3) * 8e-3;
    }

    /**
     * Read differential shunt voltage for a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @returns {number} Shunt voltage in volts, signed.
     */
    shuntVoltage(channel) {
        const ch = this._channelValid(channel);
        const raw = this._readRegSigned(_SHUNT_REGS[ch - 1]);
        return raw * 5e-6;
    }

    /**
     * Read calculated current through the shunt for a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @returns {number} Current in amperes.
     */
    current(channel) {
        const ch = this._channelValid(channel);
        return this.shuntVoltage(ch) / this._rShunt[ch - 1];
    }

    /**
     * Read calculated power for a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @returns {number} Power in watts.
     */
    power(channel) {
        const ch = this._channelValid(channel);
        return this.voltage(ch) * this.current(ch);
    }
}

/**
 * INA3221 full interface — extends INA3221Minimal with configuration and alert support.
 *
 * Adds Configuration Register programming, channel enables, conversion-ready,
 * per-channel critical and warning alerts, shunt-voltage summation, power-valid
 * monitoring, reset, and shutdown/wake.
 *
 * Alert flag constants (from Mask/Enable register):
 * - INA3221Full.CF1, CF2, CF3 — Channel 1/2/3 critical-alert flag
 * - INA3221Full.WF1, WF2, WF3 — Channel 1/2/3 warning-alert flag
 * - INA3221Full.SF            — Summation-alert flag
 * - INA3221Full.PVF           — Power-valid flag
 * - INA3221Full.TCF           — Timing-control flag
 * - INA3221Full.CVRF          — Conversion-ready flag
 *
 * Mode constants:
 * - INA3221Full.MODE_POWERDOWN      = 0
 * - INA3221Full.MODE_SHUNT_TRIG     = 1
 * - INA3221Full.MODE_BUS_TRIG       = 2
 * - INA3221Full.MODE_SHUNT_BUS_TRIG = 3
 * - INA3221Full.MODE_SHUNT_CONT     = 5
 * - INA3221Full.MODE_BUS_CONT       = 6
 * - INA3221Full.MODE_SHUNT_BUS_CONT = 7
 *
 * @param {object} transport - Configured I2C or SMBus transport.
 * @param {number|number[]} [rShunt=0.1] - Shunt resistor value in ohms.
 */
class INA3221Full extends INA3221Minimal {
    static CF1   = 0x0200;
    static CF2   = 0x0100;
    static CF3   = 0x0080;
    static SF    = 0x0040;
    static WF1   = 0x0020;
    static WF2   = 0x0010;
    static WF3   = 0x0008;
    static PVF   = 0x0004;
    static TCF   = 0x0002;
    static CVRF  = 0x0001;

    static MODE_POWERDOWN      = 0;
    static MODE_SHUNT_TRIG     = 1;
    static MODE_BUS_TRIG       = 2;
    static MODE_SHUNT_BUS_TRIG = 3;
    static MODE_SHUNT_CONT     = 5;
    static MODE_BUS_CONT       = 6;
    static MODE_SHUNT_BUS_CONT = 7;

    constructor(transport, rShunt = 0.1) {
        super(transport, rShunt);
        this._mode = 0x07;
    }

    /**
     * Write the Configuration Register.
     * @param {number} [avg=0]    - Averaging count selector 0–7 (0=1 sample, 7=1024 samples).
     * @param {number} [vbusCt=4] - Bus voltage conversion time selector 0–7 (default 4=1.1 ms).
     * @param {number} [vshCt=4]  - Shunt voltage conversion time selector 0–7 (default 4=1.1 ms).
     * @param {number} [mode=7]   - Operating mode (default 7=shunt+bus continuous).
     */
    configure(avg = 0, vbusCt = 4, vshCt = 4, mode = 7) {
        const cfg = this._readReg(_REG_CONFIG);
        const config = ((avg & 0x07) << 9) | ((vbusCt & 0x07) << 6) | ((vshCt & 0x07) << 3) | (mode & 0x07);
        this._mode = mode & 0x07;
        this._writeReg(_REG_CONFIG, config | (cfg & 0x7000));
    }

    /**
     * Enable or disable a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @param {boolean} enabled - true to enable, false to disable.
     */
    enableChannel(channel, enabled) {
        const ch = this._channelValid(channel);
        const cfg = this._readReg(_REG_CONFIG);
        const bit = 14 - (ch - 1);
        if (enabled) this._writeReg(_REG_CONFIG, cfg | (1 << bit));
        else         this._writeReg(_REG_CONFIG, cfg & ~(1 << bit));
    }

    /**
     * Read whether a channel is enabled.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @returns {boolean} true if the channel is enabled.
     */
    channelEnabled(channel) {
        const ch = this._channelValid(channel);
        const cfg = this._readReg(_REG_CONFIG);
        const bit = 14 - (ch - 1);
        return (cfg & (1 << bit)) !== 0;
    }

    /**
     * Read the Conversion Ready Flag (CVRF).
     * @returns {boolean} true if a conversion completed.
     */
    conversionReady() {
        return (this._readReg(_REG_MASK_EN) & INA3221Full.CVRF) !== 0;
    }

    /**
     * Set the critical-alert limit for a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @param {number} limitV - Voltage limit in volts.
     * @param {boolean} [latch=false] - If true, use latched mode.
     */
    setCriticalAlert(channel, limitV, latch = false) {
        const ch = this._channelValid(channel);
        const raw = (Math.floor(limitV / 40e-6) << 3) & 0xFFF8;
        this._writeReg(_CRIT_REGS[ch - 1], raw);
        const cfg = this._readReg(_REG_MASK_EN);
        this._writeReg(_REG_MASK_EN, latch ? (cfg | 0x0400) : (cfg & ~0x0400));
    }

    /**
     * Set the warning-alert limit for a channel.
     * @param {number} channel - Channel number 1, 2, or 3.
     * @param {number} limitV - Voltage limit in volts.
     * @param {boolean} [latch=false] - If true, use latched mode.
     */
    setWarningAlert(channel, limitV, latch = false) {
        const ch = this._channelValid(channel);
        const raw = (Math.floor(limitV / 40e-6) << 3) & 0xFFF8;
        this._writeReg(_WARN_REGS[ch - 1], raw);
        const cfg = this._readReg(_REG_MASK_EN);
        this._writeReg(_REG_MASK_EN, latch ? (cfg | 0x0800) : (cfg & ~0x0800));
    }

    /**
     * Read the Mask/Enable Register.
     *
     * Reading this register clears the latched alert flags (CF1/CF2/CF3,
     * WF1/WF2/WF3, SF) when latch mode is enabled.
     *
     * @returns {number} Raw 16-bit Mask/Enable register value.
     */
    alertFlags() {
        return this._readReg(_REG_MASK_EN);
    }

    /**
     * Configure the shunt-voltage summation function.
     * @param {number[]} channels - Array of channel numbers to sum (e.g. [1, 2, 3]).
     * @param {number} limitV - Shunt-voltage sum limit in volts.
     */
    setSummationChannels(channels, limitV) {
        let cfg = this._readReg(_REG_MASK_EN) & ~0xE000;
        for (const ch of channels) {
            this._channelValid(ch);
            cfg |= 1 << (15 - (Number(ch) - 1));
        }
        this._writeReg(_REG_MASK_EN, cfg);
        const raw = (Math.floor(limitV / 40e-6) << 1) & 0xFFFE;
        this._writeReg(_REG_SUM_LIMIT, raw);
    }

    /**
     * Read the shunt-voltage sum.
     * @returns {number} Sum of selected channels' shunt voltages in volts.
     */
    summationValue() {
        const raw = this._readRegSigned(_REG_SUM);
        return raw * 5e-6;
    }

    /**
     * Set the Power-Valid upper and lower voltage limits.
     * @param {number} upperV - Upper bus voltage limit in volts.
     * @param {number} lowerV - Lower bus voltage limit in volts.
     */
    setPowerValidLimits(upperV, lowerV) {
        const rawUpper = (Math.floor(upperV / 8e-3) << 3) & 0xFFF8;
        const rawLower = (Math.floor(lowerV / 8e-3) << 3) & 0xFFF8;
        this._writeReg(_REG_PV_UPPER, rawUpper);
        this._writeReg(_REG_PV_LOWER, rawLower);
    }

    /**
     * Read the Power-Valid flag (PVF).
     * @returns {boolean} true if all enabled bus voltages are within the PV limits.
     */
    powerValid() {
        return (this._readReg(_REG_MASK_EN) & INA3221Full.PVF) !== 0;
    }

    /**
     * Enter power-down mode and save the current mode for wake().
     */
    shutdown() {
        const cfg = this._readReg(_REG_CONFIG);
        this._mode = cfg & 0x07;
        this._writeReg(_REG_CONFIG, cfg & 0xFFF8);
    }

    /**
     * Restore the operating mode saved by shutdown().
     */
    wake() {
        const cfg = this._readReg(_REG_CONFIG);
        this._writeReg(_REG_CONFIG, (cfg & 0xFFF8) | this._mode);
    }

    /**
     * Reset all registers to power-on defaults.
     */
    reset() {
        this._writeReg(_REG_CONFIG, 0x8000);
    }

    /**
     * Read the Manufacturer ID register.
     * @returns {number} Manufacturer ID; expect 0x5449 (Texas Instruments).
     */
    manufacturerId() {
        return this._readReg(_REG_MFR_ID);
    }

    /**
     * Read the Die ID register.
     * @returns {number} Die revision ID; expect 0x3220.
     */
    dieId() {
        return this._readReg(_REG_DIE_ID);
    }
}

module.exports = { INA3221Minimal, INA3221Full };