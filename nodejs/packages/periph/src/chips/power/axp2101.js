'use strict';

/**
 * AXP2101 single-cell Li-ion NVDC power-management IC (X-Powers).
 *
 * Switch charger (up to 1.5 A), four buck converters and eleven LDOs, a 14-bit
 * ADC, a fuel gauge, a power-key controller, watchdog, JEITA protection and a
 * 24-source interrupt controller. I²C address 0x34. Charger, rail and
 * power-key settings are partly eFuse-programmed per product, so nothing but
 * the ADC channels and the fuel gauge is touched at construction and every
 * setter is a read-modify-write that preserves the bits it does not own.
 */

const _REG_STATUS1 = 0x00;
const _REG_STATUS2 = 0x01;
const _REG_DATA_BUFFER0 = 0x04;
const _REG_COMMON_CONFIG = 0x10;
const _REG_BATFET_CTRL = 0x12;
const _REG_DIE_TEMP_CTRL = 0x13;
const _REG_MIN_VSYS = 0x14;
const _REG_VINDPM = 0x15;
const _REG_IINLIM = 0x16;
const _REG_GAUGE_RESET = 0x17;
const _REG_MODULE_ENABLE = 0x18;
const _REG_WATCHDOG_CTRL = 0x19;
const _REG_LOWBAT_WARN = 0x1A;
const _REG_GPIO1_CTRL = 0x1B;
const _REG_PWRON_STATUS = 0x20;
const _REG_PWROFF_STATUS = 0x21;
const _REG_PWROFF_EN = 0x22;
const _REG_DCDC_PROT = 0x23;
const _REG_VOFF = 0x24;
const _REG_PWROK_CTRL = 0x25;
const _REG_SLEEP_CTRL = 0x26;
const _REG_PWRKEY_LEVEL = 0x27;
const _REG_FAST_PWRON0 = 0x28;
const _REG_ADC_ENABLE = 0x30;
const _REG_VBAT_H = 0x34;
const _REG_TS_H = 0x36;
const _REG_VBUS_H = 0x38;
const _REG_VSYS_H = 0x3A;
const _REG_TDIE_H = 0x3C;
const _REG_IRQ_EN0 = 0x40;
const _REG_IRQ_STATUS0 = 0x48;
const _REG_TS_CTRL = 0x50;
const _REG_TS_HYS_L2H = 0x52;
const _REG_TS_HYS_H2L = 0x53;
const _REG_VLTF_CHG = 0x54;
const _REG_VHTF_CHG = 0x55;
const _REG_VLTF_WORK = 0x56;
const _REG_VHTF_WORK = 0x57;
const _REG_JEITA_EN = 0x58;
const _REG_JEITA_CV = 0x59;
const _REG_JEITA_COOL = 0x5A;
const _REG_JEITA_WARM = 0x5B;
const _REG_IPRECHG = 0x61;
const _REG_ICC = 0x62;
const _REG_ITERM = 0x63;
const _REG_CV = 0x64;
const _REG_THERMAL_REG = 0x65;
const _REG_CHG_TIMER = 0x67;
const _REG_BAT_DETECT = 0x68;
const _REG_CHGLED_CTRL = 0x69;
const _REG_BTN_BAT_VTERM = 0x6A;
const _REG_DCDC_EN = 0x80;
const _REG_DCDC_PWM = 0x81;
const _REG_BAT_PERCENT = 0xA4;

// name -> [enable register, enable bit, voltage register, kind]
const _RAILS = {
    dcdc1: [0x80, 0, 0x82, 'dcdc1'],
    dcdc2: [0x80, 1, 0x83, 'dcdc23'],
    dcdc3: [0x80, 2, 0x84, 'dcdc23'],
    dcdc4: [0x80, 3, 0x85, 'dcdc4'],
    aldo1: [0x90, 0, 0x92, 'ldo100'],
    aldo2: [0x90, 1, 0x93, 'ldo100'],
    aldo3: [0x90, 2, 0x94, 'ldo100'],
    aldo4: [0x90, 3, 0x95, 'ldo100'],
    bldo1: [0x90, 4, 0x96, 'ldo100'],
    bldo2: [0x90, 5, 0x97, 'ldo100'],
    cpusldo: [0x90, 6, 0x98, 'ldo50'],
    dldo1: [0x90, 7, 0x99, 'dldo1'],
    dldo2: [0x91, 0, 0x9A, 'ldo50'],
};

// kind -> [start V, step V, max code]
const _RAIL_KINDS = {
    dcdc1: [1.5, 0.1, 19],
    ldo100: [0.5, 0.1, 30],
    ldo50: [0.5, 0.05, 19],
    dldo1: [0.5, 0.1, 28],
};

const _ICC_LIMIT = { 1: 1.5, 2: 1.5, 3: 1.4, 4: 1.1, 5: 1.0 };
const _CV_CODES = [[4.0, 1], [4.1, 2], [4.2, 3], [4.35, 4], [4.4, 5]];
const _IINLIM_CODES = [[0.1, 0], [0.5, 1], [0.9, 2], [1.0, 3], [1.5, 4], [2.0, 5]];

// rail -> [register offset from 0x28, shift]
const _FAST_RAILS = {
    dcdc1: [0, 0], dcdc2: [0, 2], dcdc3: [0, 4], dcdc4: [0, 6],
    aldo1: [1, 2], aldo2: [1, 4], aldo3: [1, 6],
    aldo4: [2, 0], bldo1: [2, 2], bldo2: [2, 4], cpusldo: [2, 6],
    dldo1: [3, 0], dldo2: [3, 2],
};

function _delayMs(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function _round(x) {
    return Math.floor(x + 0.5);
}

function _stepCode(value, start, step, maxCode, what) {
    const code = _round((value - start) / step);
    if (code < 0 || code > maxCode || Math.abs(value - (start + code * step)) > step / 2 + 1e-9) {
        throw new RangeError(what + ' out of range: ' + value);
    }
    return code;
}

function _lookup(value, table, what) {
    for (const [key, code] of table) {
        if (Math.abs(key - value) < 1e-6) return code;
    }
    throw new RangeError(what + ' unsupported value: ' + value);
}

function _railCode(kind, volts, name) {
    if (_RAIL_KINDS[kind]) {
        const [start, step, maxCode] = _RAIL_KINDS[kind];
        return _stepCode(volts, start, step, maxCode, name + ' voltage');
    }
    const top = kind === 'dcdc23' ? 87 : 102;
    if (volts <= 1.21) return _stepCode(volts, 0.5, 0.01, 70, name + ' voltage');
    return 71 + _stepCode(volts, 1.22, 0.02, top - 71, name + ' voltage');
}

function _railVolts(kind, code) {
    if (_RAIL_KINDS[kind]) {
        const [start, step] = _RAIL_KINDS[kind];
        return start + code * step;
    }
    if (code <= 70) return 0.5 + code * 0.01;
    return 1.22 + (code - 71) * 0.02;
}

function _railMask(kind) {
    return (kind === 'dcdc23' || kind === 'dcdc4') ? 0x7F : 0x1F;
}

/**
 * AXP2101 PMIC — minimal interface: battery and power-source monitoring.
 *
 * Constructor caveat: JS constructors cannot be async, so the init sequence
 * (bus check, ADC channels on, fuel gauge on, 20 ms wait) is started
 * unawaited. Every method waits for it and rethrows its failure, so an absent
 * device is reported by the first call (`await chip.ready` works too).
 */
class AXP2101Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C connection bound to address 0x34.
     */
    constructor(connection) {
        this._conn = connection;
        this._ready = this._init();
        this._ready.catch(() => {});
    }

    /** @returns {Promise<void>} Resolves when the init sequence has finished (rejects if the bus check failed). */
    get ready() {
        return this._ready;
    }

    async _init() {
        try {
            await this._rawRead(_REG_STATUS1, 1);
        } catch (e) {
            throw new Error('AXP2101 not responding at I2C 0x34: ' + e.message);
        }
        const adc = (await this._rawRead(_REG_ADC_ENABLE, 1))[0];
        await this._rawWrite(_REG_ADC_ENABLE, adc | 0x1F);
        const mod = (await this._rawRead(_REG_MODULE_ENABLE, 1))[0];
        await this._rawWrite(_REG_MODULE_ENABLE, mod | 0x08);
        await _delayMs(20);
    }

    async _rawRead(reg, n) {
        try {
            return await this._conn.readReg(reg, n);
        } catch (e) {
            return this._conn.readReg(reg, n);
        }
    }

    async _rawWrite(reg, value) {
        try {
            await this._conn.writeReg(reg, value);
        } catch (e) {
            await this._conn.writeReg(reg, value);
        }
    }

    async _readReg(reg) {
        await this._ready;
        return (await this._rawRead(reg, 1))[0];
    }

    async _writeReg(reg, value) {
        await this._ready;
        await this._rawWrite(reg, value & 0xFF);
    }

    async _rmw(reg, mask, value) {
        const cur = await this._readReg(reg);
        await this._writeReg(reg, (cur & ~mask) | (value & mask));
    }

    async _command(reg, bit) {
        const cur = await this._readReg(reg);
        await this._writeReg(reg, cur | bit);
    }

    async _adc14(reg) {
        const hi = await this._readReg(reg);
        const lo = await this._readReg(reg + 1);
        return ((hi & 0x3F) << 8) | lo;
    }

    /** @returns {Promise<number>} Battery voltage in V (1 mV / LSB). */
    async batteryVoltage() {
        return (await this._adc14(_REG_VBAT_H)) * 0.001;
    }

    /** @returns {Promise<number>} Fuel-gauge state of charge, 0-100 %. */
    async batteryPercent() {
        return (await this._readReg(_REG_BAT_PERCENT)) & 0x7F;
    }

    /** @returns {Promise<number>} One of the CHARGE_* constants (STATUS2 bits 2:0). */
    async chargeState() {
        return (await this._readReg(_REG_STATUS2)) & 0x07;
    }

    /** @returns {Promise<boolean>} True when a good input source is connected (STATUS1.VBUS_GOOD). */
    async vbusPresent() {
        return ((await this._readReg(_REG_STATUS1)) & 0x20) !== 0;
    }
}

/** Charge states returned by chargeState(). */
AXP2101Minimal.CHARGE_TRICKLE = 0;
AXP2101Minimal.CHARGE_PRE = 1;
AXP2101Minimal.CHARGE_CC = 2;
AXP2101Minimal.CHARGE_CV = 3;
AXP2101Minimal.CHARGE_DONE = 4;
AXP2101Minimal.CHARGE_NONE = 5;

/**
 * AXP2101 PMIC — full interface.
 *
 * Adds the remaining ADC channels, charger, battery-temperature, power rail,
 * power-key, sleep, watchdog, fuel-gauge and interrupt control.
 *
 * Dangerous operations: disabling or re-voltaging the rail that powers the
 * host (commonly DCDC1 or DCDC3), powerOff(), the power-off thresholds, charge
 * voltage / current beyond the cell's limits and setBatfetOffEnable() can
 * power the board down or damage the battery.
 */
class AXP2101Full extends AXP2101Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C connection bound to address 0x34.
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    /** @returns {Promise<number>} VBUS voltage in V. */
    async vbusVoltage() {
        return (await this._adc14(_REG_VBUS_H)) * 0.001;
    }

    /** @returns {Promise<number>} System (VSYS) voltage in V. */
    async systemVoltage() {
        return (await this._adc14(_REG_VSYS_H)) * 0.001;
    }

    /** @returns {Promise<number>} TS pin voltage in V (0.5 mV / LSB). */
    async tsVoltage() {
        return (await this._adc14(_REG_TS_H)) * 0.0005;
    }

    /**
     * Die temperature using the vendor XPowersLib formula (not from the datasheet; verify on hardware).
     * @returns {Promise<number>} Die temperature in °C.
     */
    async dieTemperature() {
        return 22 + (7274 - (await this._adc14(_REG_TDIE_H))) / 20;
    }

    /**
     * Read every measurement.
     * @returns {Promise<{batteryVoltage:number, vbusVoltage:number, systemVoltage:number, tsVoltage:number,
     *   dieTemperature:number, batteryPercent:number, chargeState:number, vbusPresent:boolean}>}
     */
    async readAll() {
        return {
            batteryVoltage: await this.batteryVoltage(),
            vbusVoltage: await this.vbusVoltage(),
            systemVoltage: await this.systemVoltage(),
            tsVoltage: await this.tsVoltage(),
            dieTemperature: await this.dieTemperature(),
            batteryPercent: await this.batteryPercent(),
            chargeState: await this.chargeState(),
            vbusPresent: await this.vbusPresent(),
        };
    }

    /**
     * Decode STATUS1 / STATUS2.
     * @returns {Promise<{vbusGood:boolean, batfetOpen:boolean, batteryPresent:boolean, batteryActive:boolean,
     *   thermalRegulation:boolean, currentLimit:boolean, batteryDirection:string, systemOn:boolean,
     *   vindpm:boolean, chargeState:number}>} batteryDirection is 'standby', 'charge', 'discharge' or 'reserved'.
     */
    async status() {
        const s1 = await this._readReg(_REG_STATUS1);
        const s2 = await this._readReg(_REG_STATUS2);
        return {
            vbusGood: (s1 & 0x20) !== 0,
            batfetOpen: (s1 & 0x10) !== 0,
            batteryPresent: (s1 & 0x08) !== 0,
            batteryActive: (s1 & 0x04) !== 0,
            thermalRegulation: (s1 & 0x02) !== 0,
            currentLimit: (s1 & 0x01) !== 0,
            batteryDirection: ['standby', 'charge', 'discharge', 'reserved'][(s2 >> 5) & 3],
            systemOn: (s2 & 0x10) !== 0,
            vindpm: (s2 & 0x08) !== 0,
            chargeState: s2 & 0x07,
        };
    }

    /**
     * Select the enabled ADC channels (ADC_ENABLE).
     * @param {boolean} [vbat=true] @param {boolean} [ts=true] @param {boolean} [vbus=true]
     * @param {boolean} [vsys=true] @param {boolean} [tdie=true] @param {boolean} [gpadc=false]
     * @returns {Promise<void>}
     */
    async setAdcChannels(vbat = true, ts = true, vbus = true, vsys = true, tdie = true, gpadc = false) {
        const value = (vbat ? 1 : 0) | (ts ? 2 : 0) | (vbus ? 4 : 0) | (vsys ? 8 : 0) | (tdie ? 16 : 0) | (gpadc ? 32 : 0);
        await this._rmw(_REG_ADC_ENABLE, 0x3F, value);
    }

    /**
     * Slow (64 s) ADC sampling when powered off on battery only (TS_H bit 7).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setAdcLowFrequency(enabled) {
        await this._rmw(_REG_TS_H, 0x80, enabled ? 0x80 : 0);
    }

    /**
     * Enable or disable main-cell charging (MODULE_ENABLE bit 1).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setCharging(enabled) {
        await this._rmw(_REG_MODULE_ENABLE, 0x02, enabled ? 0x02 : 0);
    }

    /**
     * Set the charge target voltage.
     * @param {number} volts - 4.0, 4.1, 4.2, 4.35 or 4.4 V (the last two only for high-voltage cells).
     * @returns {Promise<void>}
     * @throws {RangeError} For any other value.
     */
    async setChargeVoltage(volts) {
        await this._rmw(_REG_CV, 0x07, _lookup(volts, _CV_CODES, 'charge voltage'));
    }

    /**
     * Set the constant-current charge current.
     * @param {number} amps - 0-0.2 A in 25 mA steps, 0.2-1.5 A in 100 mA steps; capped by the programmed charge voltage.
     * @returns {Promise<void>}
     * @throws {RangeError} Out of range or above the charge-voltage limit.
     */
    async setChargeCurrent(amps) {
        if (amps < 0 || amps > 1.5 + 1e-9) throw new RangeError('charge current out of range: ' + amps);
        const cv = (await this._readReg(_REG_CV)) & 0x07;
        const limit = _ICC_LIMIT[cv];
        if (limit !== undefined && amps > limit + 1e-9) {
            throw new RangeError('charge current ' + amps + ' A exceeds ' + limit + ' A limit for this charge voltage');
        }
        const code = amps <= 0.25 ? Math.min(8, _round(amps / 0.025)) : 8 + _round((amps - 0.2) / 0.1);
        await this._rmw(_REG_ICC, 0x1F, code);
    }

    /**
     * Set the pre-charge current.
     * @param {number} amps - 0-0.2 A in 25 mA steps.
     * @returns {Promise<void>}
     */
    async setPrechargeCurrent(amps) {
        await this._rmw(_REG_IPRECHG, 0x0F, _stepCode(amps, 0, 0.025, 8, 'precharge current'));
    }

    /**
     * Set the charge termination current.
     * @param {number} amps - 0-0.2 A in 25 mA steps.
     * @param {boolean} [enabled=true] - Termination enable.
     * @returns {Promise<void>}
     */
    async setTermination(amps, enabled = true) {
        const code = _stepCode(amps, 0, 0.025, 8, 'termination current');
        await this._rmw(_REG_ITERM, 0x1F, code | (enabled ? 0x10 : 0));
    }

    /**
     * Set the input current limit.
     * @param {number} amps - 0.1, 0.5, 0.9, 1.0, 1.5 or 2.0 A.
     * @returns {Promise<void>}
     */
    async setInputCurrentLimit(amps) {
        await this._rmw(_REG_IINLIM, 0x07, _lookup(amps, _IINLIM_CODES, 'input current limit'));
    }

    /**
     * Set the input voltage limit (VINDPM).
     * @param {number} volts - 3.88-5.08 V in 80 mV steps.
     * @returns {Promise<void>}
     */
    async setInputVoltageLimit(volts) {
        await this._rmw(_REG_VINDPM, 0x0F, _stepCode(volts, 3.88, 0.08, 15, 'input voltage limit'));
    }

    /**
     * Set the minimum system voltage.
     * @param {number} volts - 3.2-3.9 V in 100 mV steps.
     * @returns {Promise<void>}
     */
    async setMinSystemVoltage(volts) {
        await this._rmw(_REG_MIN_VSYS, 0x07, _stepCode(volts, 3.2, 0.1, 7, 'minimum system voltage'));
    }

    /**
     * Configure the charger safety timers (CHG_TIMER).
     * @param {number} [prechargeMin=60] - 40 / 50 / 60 / 70 min.
     * @param {number} [doneHours=12] - 5 / 8 / 12 / 20 h.
     * @param {boolean} [prechargeEnabled=true]
     * @param {boolean} [doneEnabled=true]
     * @param {boolean} [slowInDpm=true] - Half-speed timers during input DPM / thermal regulation.
     * @returns {Promise<void>}
     */
    async setChargeTimers(prechargeMin = 60, doneHours = 12, prechargeEnabled = true, doneEnabled = true, slowInDpm = true) {
        const pre = _lookup(prechargeMin, [[40, 0], [50, 1], [60, 2], [70, 3]], 'precharge timer');
        const done = _lookup(doneHours, [[5, 0], [8, 1], [12, 2], [20, 3]], 'done timer');
        const value = pre | (prechargeEnabled ? 0x04 : 0) | (done << 4) | (doneEnabled ? 0x40 : 0) | (slowInDpm ? 0x80 : 0);
        await this._rmw(_REG_CHG_TIMER, 0xF7, value);
    }

    /**
     * Set the charger thermal-regulation threshold.
     * @param {number} celsius - 60, 80, 100 or 120 °C.
     * @returns {Promise<void>}
     */
    async setThermalRegulation(celsius) {
        await this._rmw(_REG_THERMAL_REG, 0x03, _lookup(celsius, [[60, 0], [80, 1], [100, 2], [120, 3]], 'thermal regulation'));
    }

    /**
     * Configure die over-temperature level 1 (DIE_TEMP_CTRL).
     * @param {number} [level1Celsius=125] - 115, 125 or 135 °C.
     * @param {boolean} [detect=true] - Die temperature detection enable.
     * @returns {Promise<void>}
     */
    async setDieProtection(level1Celsius = 125, detect = true) {
        const code = _lookup(level1Celsius, [[115, 0], [125, 1], [135, 2]], 'die protection');
        await this._rmw(_REG_DIE_TEMP_CTRL, 0x07, (code << 1) | (detect ? 1 : 0));
    }

    /**
     * Enable or disable battery detection (BAT_DETECT).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setBatteryDetection(enabled) {
        await this._rmw(_REG_BAT_DETECT, 0x01, enabled ? 1 : 0);
    }

    /**
     * Configure charging of the backup (coin) cell.
     * @param {boolean} enabled
     * @param {number} [volts=2.9] - Termination voltage, 2.6-3.3 V in 100 mV steps.
     * @returns {Promise<void>}
     */
    async setBackupCharging(enabled, volts = 2.9) {
        const code = _stepCode(volts, 2.6, 0.1, 7, 'backup charge voltage');
        await this._rmw(_REG_BTN_BAT_VTERM, 0x07, code);
        await this._rmw(_REG_MODULE_ENABLE, 0x04, enabled ? 0x04 : 0);
    }

    /**
     * Configure the CHGLED pin.
     * @param {string} [mode='type_a'] - 'off', 'type_a', 'type_b' or 'manual'.
     * @param {string} [output='hiz'] - For 'manual': 'hiz', 'blink_1hz', 'blink_4hz' or 'low'.
     * @returns {Promise<void>}
     */
    async setChgled(mode = 'type_a', output = 'hiz') {
        const modes = { off: 0x00, type_a: 0x01, type_b: 0x03, manual: 0x05 };
        const outs = { hiz: 0, blink_1hz: 1, blink_4hz: 2, low: 3 };
        if (!(mode in modes)) throw new RangeError('unknown CHGLED mode: ' + mode);
        if (!(output in outs)) throw new RangeError('unknown CHGLED output: ' + output);
        let value = modes[mode];
        if (mode === 'manual') value |= outs[output] << 4;
        await this._rmw(_REG_CHGLED_CTRL, 0x37, value);
    }

    /**
     * Configure the TS pin (TS_CTRL).
     * @param {boolean} [batterySensor=true] - Battery thermistor (true) or external input (false).
     * @param {number} [currentUa=50] - 20 / 40 / 50 / 60 µA.
     * @param {string} [source='when_adc'] - 'off', 'when_adc', 'when_ts' or 'always'.
     * @returns {Promise<void>}
     */
    async configureTs(batterySensor = true, currentUa = 50, source = 'when_adc') {
        const sources = { off: 0, when_adc: 1, when_ts: 2, always: 3 };
        if (!(source in sources)) throw new RangeError('unknown TS source: ' + source);
        const cur = _lookup(currentUa, [[20, 0], [40, 1], [50, 2], [60, 3]], 'TS current');
        await this._rmw(_REG_TS_CTRL, 0x1F, cur | (sources[source] << 2) | (batterySensor ? 0 : 0x10));
    }

    /**
     * Set the battery-temperature limits as TS pin voltages.
     * @param {number} chargeCold - V, 32 mV steps. @param {number} chargeHot - V, 2 mV steps.
     * @param {number} workCold - V, 32 mV steps. @param {number} workHot - V, 2 mV steps.
     * @returns {Promise<void>}
     */
    async setTsLimits(chargeCold, chargeHot, workCold, workHot) {
        const codes = [
            [_REG_VLTF_CHG, _stepCode(chargeCold, 0, 0.032, 255, 'charge cold limit')],
            [_REG_VHTF_CHG, _stepCode(chargeHot, 0, 0.002, 255, 'charge hot limit')],
            [_REG_VLTF_WORK, _stepCode(workCold, 0, 0.032, 255, 'work cold limit')],
            [_REG_VHTF_WORK, _stepCode(workHot, 0, 0.002, 255, 'work hot limit')],
        ];
        for (const [reg, code] of codes) await this._writeReg(reg, code);
    }

    /**
     * Set the TS threshold hysteresis.
     * @param {number} [lowToNormal=0.032] - V, 16 mV steps.
     * @param {number} [highToNormal=0.004] - V, 4 mV steps.
     * @returns {Promise<void>}
     */
    async setTsHysteresis(lowToNormal = 0.032, highToNormal = 0.004) {
        const l2h = _stepCode(lowToNormal, 0, 0.016, 255, 'TS low hysteresis');
        const h2l = _stepCode(highToNormal, 0, 0.004, 255, 'TS high hysteresis');
        await this._writeReg(_REG_TS_HYS_L2H, l2h);
        await this._writeReg(_REG_TS_HYS_H2L, h2l);
    }

    /**
     * Configure the JEITA charging profile.
     * @param {boolean} enabled
     * @param {?number} [coolVoltage=null] - Cool threshold T2 as TS voltage in V (16 mV steps); null keeps it.
     * @param {?number} [warmVoltage=null] - Warm threshold T3 as TS voltage in V (8 mV steps); null keeps it.
     * @param {boolean} [coolHalfCurrent=true] @param {boolean} [warmHalfCurrent=false]
     * @param {number} [coolVoltageDrop=0] - 0-2 gears. @param {number} [warmVoltageDrop=1] - 0-2 gears.
     * @returns {Promise<void>}
     */
    async configureJeita(enabled, coolVoltage = null, warmVoltage = null, coolHalfCurrent = true,
                         warmHalfCurrent = false, coolVoltageDrop = 0, warmVoltageDrop = 1) {
        if (![0, 1, 2].includes(coolVoltageDrop) || ![0, 1, 2].includes(warmVoltageDrop)) {
            throw new RangeError('JEITA voltage drop must be 0-2');
        }
        const value = coolVoltageDrop | (warmVoltageDrop << 2) | (coolHalfCurrent ? 0x10 : 0) | (warmHalfCurrent ? 0x40 : 0);
        await this._rmw(_REG_JEITA_CV, 0x5F, value);
        if (coolVoltage !== null && coolVoltage !== undefined) {
            await this._writeReg(_REG_JEITA_COOL, _stepCode(coolVoltage, 0, 0.016, 255, 'JEITA cool'));
        }
        if (warmVoltage !== null && warmVoltage !== undefined) {
            await this._writeReg(_REG_JEITA_WARM, _stepCode(warmVoltage, 0, 0.008, 255, 'JEITA warm'));
        }
        await this._rmw(_REG_JEITA_EN, 0x01, enabled ? 1 : 0);
    }

    _rail(name) {
        if (!Object.prototype.hasOwnProperty.call(_RAILS, name)) throw new RangeError('unknown rail: ' + name);
        return _RAILS[name];
    }

    /**
     * Read the state of a power rail.
     * @param {string} name - 'dcdc1'..'dcdc4', 'aldo1'..'aldo4', 'bldo1', 'bldo2', 'cpusldo', 'dldo1' or 'dldo2'.
     * @returns {Promise<[boolean, number]>} `[enabled, volts]`.
     */
    async getRail(name) {
        const [enReg, enBit, vReg, kind] = this._rail(name);
        const enabled = ((await this._readReg(enReg)) & (1 << enBit)) !== 0;
        return [enabled, _railVolts(kind, (await this._readReg(vReg)) & _railMask(kind))];
    }

    /**
     * Set a rail voltage, rounded to the nearest step.
     * @param {string} name - Rail name as for getRail().
     * @param {number} volts - Target in V; outside the rail's range raises.
     * @returns {Promise<void>}
     */
    async setRailVoltage(name, volts) {
        const [, , vReg, kind] = this._rail(name);
        await this._rmw(vReg, _railMask(kind), _railCode(kind, volts, name));
    }

    /**
     * Enable or disable a rail. Disabling the rail that powers the host cuts the host's power.
     * @param {string} name - Rail name as for getRail().
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setRailEnabled(name, enabled) {
        const [enReg, enBit] = this._rail(name);
        await this._rmw(enReg, 1 << enBit, enabled ? (1 << enBit) : 0);
    }

    /**
     * Configure one DCDC converter.
     * @param {string} name - 'dcdc1'..'dcdc4'.
     * @param {boolean} [alwaysPwm=false] - Force PWM instead of automatic PWM/PFM.
     * @param {?boolean} [dvm=null] - Dynamic voltage scaling for dcdc2 / dcdc3; null keeps it.
     * @returns {Promise<void>}
     */
    async configureDcdc(name, alwaysPwm = false, dvm = null) {
        if (!['dcdc1', 'dcdc2', 'dcdc3', 'dcdc4'].includes(name)) throw new RangeError('not a DCDC: ' + name);
        const bit = 1 << (parseInt(name.slice(4), 10) + 1);
        await this._rmw(_REG_DCDC_PWM, bit, alwaysPwm ? bit : 0);
        if (dvm !== null && dvm !== undefined) {
            if (name !== 'dcdc2' && name !== 'dcdc3') throw new RangeError('DVM is only available on dcdc2 / dcdc3');
            await this._rmw(_RAILS[name][2], 0x80, dvm ? 0x80 : 0);
        }
    }

    /**
     * Configure settings shared by all DCDC converters.
     * @param {boolean} [forceCcm=false] @param {boolean} [slowRamp=false] - 31.25 µs per DVM step.
     * @param {boolean} [frequencySpread=false] @param {boolean} [spread100khz=false]
     * @param {number} [uvpDebounceUs=60] - 60 / 120 / 180 / 240 µs.
     * @returns {Promise<void>}
     */
    async configureDcdcGlobal(forceCcm = false, slowRamp = false, frequencySpread = false, spread100khz = false, uvpDebounceUs = 60) {
        const deb = _lookup(uvpDebounceUs, [[60, 0], [120, 1], [180, 2], [240, 3]], 'UVP debounce');
        await this._rmw(_REG_DCDC_EN, 0x60, (forceCcm ? 0x40 : 0) | (slowRamp ? 0x20 : 0));
        await this._rmw(_REG_DCDC_PWM, 0xC3, (frequencySpread ? 0x80 : 0) | (spread100khz ? 0x40 : 0) | deb);
    }

    /**
     * Enable output discharge when a rail is off (COMMON_CONFIG bit 5).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setOffDischarge(enabled) {
        await this._rmw(_REG_COMMON_CONFIG, 0x20, enabled ? 0x20 : 0);
    }

    /** Soft power-off: every output except RTCLDO turns off. @returns {Promise<void>} */
    async powerOff() {
        await this._command(_REG_COMMON_CONFIG, 0x01);
    }

    /** Restart the system (power-off / power-on). @returns {Promise<void>} */
    async restart() {
        await this._command(_REG_COMMON_CONFIG, 0x02);
    }

    /** Enter sleep; the host disables / re-voltages rails first. @returns {Promise<void>} */
    async sleep() {
        await this._command(_REG_SLEEP_CTRL, 0x01);
    }

    /** Wake up from sleep. @returns {Promise<void>} */
    async wakeup() {
        await this._command(_REG_SLEEP_CTRL, 0x02);
    }

    /**
     * Configure sleep / wake-up behaviour (SLEEP_CTRL).
     * @param {boolean} [wakeOnIrq=false] @param {boolean} [pwrokLow=true] @param {boolean} [restoreVoltages=false]
     * @returns {Promise<void>}
     */
    async configureSleep(wakeOnIrq = false, pwrokLow = true, restoreVoltages = false) {
        await this._rmw(_REG_SLEEP_CTRL, 0x1C, (wakeOnIrq ? 0x10 : 0) | (pwrokLow ? 0x08 : 0) | (restoreVoltages ? 0x04 : 0));
    }

    /** @returns {Promise<number>} PWRON_STATUS flags. */
    async powerOnSource() {
        return this._readReg(_REG_PWRON_STATUS);
    }

    /** @returns {Promise<number>} PWROFF_STATUS flags. */
    async powerOffSource() {
        return this._readReg(_REG_PWROFF_STATUS);
    }

    /**
     * Configure the power-key timing and actions.
     * @param {number} [onMs=512] - 128 / 512 / 1000 / 2000 ms.
     * @param {number} [offS=6] - 4 / 6 / 8 / 10 s.
     * @param {number} [irqS=1.5] - 1 / 1.5 / 2 / 2.5 s.
     * @param {boolean} [longPressOff=true] @param {boolean} [restartInstead=false] @param {boolean} [forceOff16s=false]
     * @returns {Promise<void>}
     */
    async configurePowerKey(onMs = 512, offS = 6, irqS = 1.5, longPressOff = true, restartInstead = false, forceOff16s = false) {
        const on = _lookup(onMs, [[128, 0], [512, 1], [1000, 2], [2000, 3]], 'power key on time');
        const off = _lookup(offS, [[4, 0], [6, 1], [8, 2], [10, 3]], 'power key off time');
        const irq = _lookup(irqS, [[1, 0], [1.5, 1], [2, 2], [2.5, 3]], 'power key irq time');
        await this._rmw(_REG_PWRKEY_LEVEL, 0x3F, on | (off << 2) | (irq << 4));
        await this._rmw(_REG_PWROFF_EN, 0x03, (longPressOff ? 0x02 : 0) | (restartInstead ? 0x01 : 0));
        await this._rmw(_REG_COMMON_CONFIG, 0x04, forceOff16s ? 0x04 : 0);
    }

    /**
     * Configure the power-off sources and thresholds.
     * @param {boolean} [dieOverTemp=true] @param {number} [offVoltage=2.6] - 2.6-3.3 V in 100 mV steps.
     * @param {boolean} [dcdcOvp=true] @param {number} [dcdcUvp=0x0F] - Mask of DCDC1-4 (bits 0-3).
     * @returns {Promise<void>}
     */
    async configurePowerOff(dieOverTemp = true, offVoltage = 2.6, dcdcOvp = true, dcdcUvp = 0x0F) {
        const code = _stepCode(offVoltage, 2.6, 0.1, 7, 'power-off voltage');
        await this._rmw(_REG_PWROFF_EN, 0x04, dieOverTemp ? 0x04 : 0);
        await this._rmw(_REG_VOFF, 0x07, code);
        await this._rmw(_REG_DCDC_PROT, 0x2F, (dcdcOvp ? 0x20 : 0) | (dcdcUvp & 0x0F));
    }

    /**
     * Configure the PWROK output.
     * @param {boolean} [check=true] @param {number} [delayMs=8] - 8 / 16 / 32 / 64 ms.
     * @param {boolean} [offDelay=true] @param {boolean} [reverseOffSequence=false] @param {boolean} [restartOnLow=false]
     * @returns {Promise<void>}
     */
    async configurePwrok(check = true, delayMs = 8, offDelay = true, reverseOffSequence = false, restartOnLow = false) {
        const delay = _lookup(delayMs, [[8, 0], [16, 1], [32, 2], [64, 3]], 'PWROK delay');
        const value = delay | (reverseOffSequence ? 0x04 : 0) | (offDelay ? 0x08 : 0) | (check ? 0x10 : 0);
        await this._rmw(_REG_PWROK_CTRL, 0x1F, value);
        await this._rmw(_REG_COMMON_CONFIG, 0x08, restartOnLow ? 0x08 : 0);
    }

    /**
     * BATFET on when powered off on battery only (BATFET_CTRL bit 3).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setBatfetOffEnable(enabled) {
        await this._rmw(_REG_BATFET_CTRL, 0x08, enabled ? 0x08 : 0);
    }

    /**
     * Configure fast power-on / wake-up and the start sequence.
     * @param {boolean} poweron @param {boolean} wakeup
     * @param {?Object<string, ?number>} [sequence=null] - Rail -> start-sequence code 0-2, or null to disable the rail.
     * @returns {Promise<void>}
     */
    async configureFastPoweron(poweron, wakeup, sequence = null) {
        if (sequence) {
            for (const name of Object.keys(sequence)) {
                if (!Object.prototype.hasOwnProperty.call(_FAST_RAILS, name)) throw new RangeError('unknown rail: ' + name);
                let code = sequence[name];
                if (code === null || code === undefined) code = 3;
                if (![0, 1, 2, 3].includes(code)) throw new RangeError('start-sequence code must be 0-2 or null');
                const [off, shift] = _FAST_RAILS[name];
                await this._rmw(_REG_FAST_PWRON0 + off, 3 << shift, code << shift);
            }
        }
        await this._rmw(_REG_FAST_PWRON0 + 3, 0xC0, (poweron ? 0x80 : 0) | (wakeup ? 0x40 : 0));
    }

    /**
     * Configure the watchdog.
     * @param {boolean} enabled @param {number} [timeoutS=64] - 1, 2, 4, 8, 16, 32, 64 or 128 s.
     * @param {string} [action='irq'] - 'irq', 'reset', 'reset_pwrok' or 'power_cycle'.
     * @returns {Promise<void>}
     */
    async configureWatchdog(enabled, timeoutS = 64, action = 'irq') {
        const actions = { irq: 0, reset: 1, reset_pwrok: 2, power_cycle: 3 };
        if (!(action in actions)) throw new RangeError('unknown watchdog action: ' + action);
        const code = _lookup(timeoutS, [[1, 0], [2, 1], [4, 2], [8, 3], [16, 4], [32, 5], [64, 6], [128, 7]], 'watchdog timeout');
        await this._rmw(_REG_WATCHDOG_CTRL, 0x37, code | (actions[action] << 4));
        await this._rmw(_REG_MODULE_ENABLE, 0x01, enabled ? 1 : 0);
    }

    /** Clear (feed) the watchdog. @returns {Promise<void>} */
    async feedWatchdog() {
        await this._command(_REG_WATCHDOG_CTRL, 0x08);
    }

    /**
     * Set the low-battery warning levels (LOWBAT_WARN).
     * @param {number} [level2=10] - 5-20 %. @param {number} [level1=1] - 0-15 %.
     * @returns {Promise<void>}
     */
    async configureBatteryWarning(level2 = 10, level1 = 1) {
        if (level2 < 5 || level2 > 20 || level1 < 0 || level1 > 15) throw new RangeError('battery warning levels out of range');
        await this._writeReg(_REG_LOWBAT_WARN, ((level2 - 5) << 4) | level1);
    }

    /**
     * Enable or disable the fuel gauge (MODULE_ENABLE bit 3).
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setGaugeEnabled(enabled) {
        await this._rmw(_REG_MODULE_ENABLE, 0x08, enabled ? 0x08 : 0);
    }

    /**
     * Reset the fuel gauge.
     * @param {boolean} [full=false] - Deeper reset that also clears gauge data beyond the registers.
     * @returns {Promise<void>}
     */
    async resetGauge(full = false) {
        await this._command(_REG_GAUGE_RESET, full ? 0x04 : 0x08);
    }

    /**
     * Drive the open-drain GPIO1 output.
     * @param {boolean} low - True drives low, false is Hi-Z.
     * @returns {Promise<void>}
     */
    async setGpio1(low) {
        await this._rmw(_REG_GPIO1_CTRL, 0x0C, low ? 0x04 : 0);
    }

    /**
     * Read one of the four general-purpose bytes.
     * @param {number} index - 0-3.
     * @returns {Promise<number>}
     */
    async readDataBuffer(index) {
        if (!Number.isInteger(index) || index < 0 || index > 3) throw new RangeError('data buffer index must be 0-3');
        return this._readReg(_REG_DATA_BUFFER0 + index);
    }

    /**
     * Write one of the four general-purpose bytes (survives soft restarts, not a power-on reset).
     * @param {number} index - 0-3. @param {number} value - Byte.
     * @returns {Promise<void>}
     */
    async writeDataBuffer(index, value) {
        if (!Number.isInteger(index) || index < 0 || index > 3) throw new RangeError('data buffer index must be 0-3');
        await this._writeReg(_REG_DATA_BUFFER0 + index, value);
    }

    /**
     * Enable interrupt sources (IRQ_EN0..2 read-modify-write).
     * @param {number} source - Bitwise OR of SOURCE_* constants.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        for (let k = 0; k < 3; k++) {
            const bits = (source >> (8 * k)) & 0xFF;
            if (bits) await this._rmw(_REG_IRQ_EN0 + k, bits, bits);
        }
    }

    /**
     * Disable interrupt sources.
     * @param {number} source - Bitwise OR of SOURCE_* constants.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        for (let k = 0; k < 3; k++) {
            const bits = (source >> (8 * k)) & 0xFF;
            if (bits) await this._rmw(_REG_IRQ_EN0 + k, bits, 0);
        }
    }

    /**
     * Read and clear the interrupt status. Writes back exactly the bits it saw set so an event arriving
     * between the read and the write is not lost.
     * @returns {Promise<number>} 24-bit status: IRQ_STATUS0 in bits 7:0, STATUS1 in 15:8, STATUS2 in 23:16.
     */
    async pollInterrupt() {
        let status = 0;
        for (let k = 0; k < 3; k++) {
            const value = await this._readReg(_REG_IRQ_STATUS0 + k);
            if (value) await this._writeReg(_REG_IRQ_STATUS0 + k, value);
            status |= value << (8 * k);
        }
        return status;
    }

    /**
     * Subscribe to IRQ pin assertions. Uses `connection.intPin` (falling edge) when present, otherwise a 5 ms
     * polling timer.
     * @param {function(number): void} callback - Receives the 24-bit status.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this.offInterrupt();
        this._callback = callback;
        const dispatch = async () => {
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        };
        if (this._conn.intPin) {
            this._edgeHandler = dispatch;
            await this._conn.intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    await dispatch();
                } catch (e) {
                    // ignore transient bus errors while polling
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    /**
     * Unsubscribe from IRQ pin assertions.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
        }
        this._edgeHandler = null;
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }
}

AXP2101Full.SOURCE_BAT_UNDER_TEMP_WORK = 0x000001;
AXP2101Full.SOURCE_BAT_OVER_TEMP_WORK = 0x000002;
AXP2101Full.SOURCE_BAT_UNDER_TEMP_CHG = 0x000004;
AXP2101Full.SOURCE_BAT_OVER_TEMP_CHG = 0x000008;
AXP2101Full.SOURCE_GAUGE_NEW_SOC = 0x000010;
AXP2101Full.SOURCE_GAUGE_WDT = 0x000020;
AXP2101Full.SOURCE_SOC_WARN_L1 = 0x000040;
AXP2101Full.SOURCE_SOC_WARN_L2 = 0x000080;
AXP2101Full.SOURCE_PWRON_POS_EDGE = 0x000100;
AXP2101Full.SOURCE_PWRON_NEG_EDGE = 0x000200;
AXP2101Full.SOURCE_PWRON_LONG = 0x000400;
AXP2101Full.SOURCE_PWRON_SHORT = 0x000800;
AXP2101Full.SOURCE_BAT_REMOVE = 0x001000;
AXP2101Full.SOURCE_BAT_INSERT = 0x002000;
AXP2101Full.SOURCE_VBUS_REMOVE = 0x004000;
AXP2101Full.SOURCE_VBUS_INSERT = 0x008000;
AXP2101Full.SOURCE_BAT_OVER_VOLTAGE = 0x010000;
AXP2101Full.SOURCE_CHARGER_TIMER = 0x020000;
AXP2101Full.SOURCE_DIE_OVER_TEMP_L1 = 0x040000;
AXP2101Full.SOURCE_CHARGE_START = 0x080000;
AXP2101Full.SOURCE_CHARGE_DONE = 0x100000;
AXP2101Full.SOURCE_LDO_OVER_CURRENT = 0x400000;
AXP2101Full.SOURCE_WATCHDOG_EXPIRE = 0x800000;

/** Default 7-bit I²C address. */
AXP2101Minimal.I2C_ADDRESS = 0x34;

module.exports = { AXP2101Minimal, AXP2101Full };
