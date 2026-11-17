'use strict';

const _REG_BEFORE_RSOC   = 0x04;
const _REG_THERMISTOR_B  = 0x06;
const _REG_INITIAL_RSOC  = 0x07;
const _REG_TEMPERATURE   = 0x08;
const _REG_VOLTAGE       = 0x09;
const _REG_DIRECTION     = 0x0A;
const _REG_APA           = 0x0B;
const _REG_APT           = 0x0C;
const _REG_RSOC          = 0x0D;
const _REG_ITE           = 0x0F;
const _REG_IC_VERSION    = 0x11;
const _REG_PROFILE       = 0x12;
const _REG_ALARM_RSOC    = 0x13;
const _REG_ALARM_VOLTAGE = 0x14;
const _REG_POWER_MODE    = 0x15;
const _REG_STATUS        = 0x16;
const _REG_PROFILE_ID    = 0x1A;

const _APA_TABLE = [[100, 0x08], [200, 0x0B], [500, 0x10], [1000, 0x19], [2000, 0x2D], [3000, 0x36]];

function _sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * CRC-8 (ATM): polynomial 0x07, init 0x00, MSB first, no reflection, no final XOR.
 * @param {Iterable<number>} data - Bytes to checksum.
 * @returns {number} CRC byte.
 */
function crc8Atm(data) {
    let crc = 0;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) {
            crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
    }
    return crc;
}

function _apaFor(capacityMah) {
    const t = _APA_TABLE;
    if (capacityMah <= t[0][0]) return t[0][1];
    if (capacityMah >= t[t.length - 1][0]) return t[t.length - 1][1];
    for (let i = 1; i < t.length; i++) {
        if (capacityMah <= t[i][0]) {
            const [c0, a0] = t[i - 1];
            const [c1, a1] = t[i];
            return Math.round(a0 + (a1 - a0) * (capacityMah - c0) / (c1 - c0));
        }
    }
    return t[t.length - 1][1];
}

/**
 * LC709203F 1-cell Li-ion fuel gauge — minimal interface.
 *
 * Reports cell voltage, state of charge and temperature. The constructor starts the
 * datasheet starting flow (operational mode, APA, battery profile, I²C temperature mode
 * at 25 °C); JS constructors cannot be async, so it runs unawaited and every method awaits
 * it first (errors surface from the first call, or from `ready`). Every transfer carries a
 * CRC-8 that the driver appends on writes and verifies on reads (one retry).
 */
class LC709203FMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x0B).
     * @param {number} [capacityMah=1000] - Design capacity in mAh (clamped to 100-3000 for the APA interpolation).
     * @param {number} [profile=1] - Battery profile for register 0x12 (0 or 1).
     */
    constructor(connection, capacityMah = 1000, profile = 1) {
        this._conn = connection;
        this._ready = this._init(capacityMah, profile);
        this._ready.catch(() => {});
    }

    /** @returns {Promise<void>} Resolves when the starting flow is complete. */
    get ready() { return this._ready; }

    async _init(capacityMah, profile) {
        await this._wakeAndOperational();
        await this._writeWord(_REG_APA, _apaFor(capacityMah));
        await this._writeWord(_REG_PROFILE, profile & 1);
        await _sleep(100); // TINIT: re-initialization like a battery insertion
        await this._wakeAndOperational();
        await this._writeWord(_REG_STATUS, 0x0000);
        await this._setTemperature(25.0);
    }

    async _wakeAndOperational() {
        // Written twice: the first transfer may be lost while the chip wakes up.
        for (let i = 0; i < 2; i++) {
            try { await this._writeWord(_REG_POWER_MODE, 0x0001, false); } catch (e) { /* ignored */ }
        }
        await _sleep(1);
    }

    async _readWord(cmd) {
        let last;
        for (let attempt = 0; attempt < 2; attempt++) {
            try {
                const raw = await this._conn.readReg(cmd, 3);
                if (crc8Atm([0x16, cmd, 0x17, raw[0], raw[1]]) === raw[2]) return raw[0] | (raw[1] << 8);
                last = new Error('LC709203F CRC mismatch on command 0x' + cmd.toString(16));
            } catch (e) {
                last = e;
            }
        }
        throw last;
    }

    async _writeWord(cmd, value, retry = true) {
        const low = value & 0xFF;
        const high = (value >> 8) & 0xFF;
        const data = Buffer.from([low, high, crc8Atm([0x16, cmd, low, high])]);
        try {
            await this._conn.writeReg(cmd, data);
        } catch (e) {
            if (!retry) throw e;
            await this._conn.writeReg(cmd, data);
        }
    }

    async _setTemperature(celsius) {
        const c = Math.max(-20, Math.min(60, celsius));
        await this._writeWord(_REG_TEMPERATURE, Math.round((c + 273.15) * 10));
    }

    /**
     * Read the cell voltage.
     * @returns {Promise<number>} Cell voltage in volts (raw × 1 mV).
     */
    async voltage() { await this._ready; return (await this._readWord(_REG_VOLTAGE)) / 1000; }

    /**
     * Read the state of charge from the ITE register.
     * @returns {Promise<number>} State of charge in % (0.0-100.0).
     */
    async percent() { await this._ready; return (await this._readWord(_REG_ITE)) / 10; }

    /**
     * Read the cell temperature register.
     * @returns {Promise<number>} Cell temperature in °C.
     */
    async temperature() { await this._ready; return (await this._readWord(_REG_TEMPERATURE)) / 10 - 273.15; }

    /**
     * Supply the cell temperature (I²C temperature mode); clamped to -20...+60 °C.
     * @param {number} celsius - Cell temperature in °C.
     * @returns {Promise<void>}
     */
    async setTemperature(celsius) { await this._ready; await this._setTemperature(celsius); }
}

/**
 * LC709203F full interface — RSOC control, thermistor mode, alarms, profiles, power mode.
 *
 * Alarm bitmask constants: `ALARM_RSOC` (1), `ALARM_VOLTAGE` (2). The chip has no status
 * register; `pollInterrupt()` evaluates RSOC and voltage against the cached thresholds.
 */
class LC709203FFull extends LC709203FMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x0B).
     * @param {number} [capacityMah=1000] - Design capacity in mAh.
     * @param {number} [profile=1] - Battery profile (0 or 1).
     */
    constructor(connection, capacityMah = 1000, profile = 1) {
        super(connection, capacityMah, profile);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._alarmRsoc = 0;
        this._alarmVoltage = 0;
        this._edgeHandler = () => { this._service(); };
        this._ready = this._ready.then(async () => {
            this._alarmRsoc = await this._readWord(_REG_ALARM_RSOC);
            this._alarmVoltage = await this._readWord(_REG_ALARM_VOLTAGE);
        });
        this._ready.catch(() => {});
    }

    /** @returns {Promise<number>} State of charge in % (register 0x0D, 0-100). */
    async rsoc() { await this._ready; return this._readWord(_REG_RSOC); }

    /**
     * Force the RSOC register (use sleep mode to keep the value).
     * @param {number} percent - 0-100.
     * @returns {Promise<void>}
     */
    async setRsoc(percent) { await this._ready; await this._writeWord(_REG_RSOC, Math.max(0, Math.min(100, Math.trunc(percent)))); }

    /** Initialize RSOC from the present voltage (0x07 ← 0xAA55); waits 2 ms. @returns {Promise<void>} */
    async initRsoc() { await this._ready; await this._writeWord(_REG_INITIAL_RSOC, 0xAA55); await _sleep(2); }

    /** Initialize RSOC from the maximum voltage since reset (0x04 ← 0xAA55); waits 2 ms. @returns {Promise<void>} */
    async beforeRsoc() { await this._ready; await this._writeWord(_REG_BEFORE_RSOC, 0xAA55); await _sleep(2); }

    /**
     * Switch to thermistor temperature mode.
     * @param {number} [bConstant=3380] - Thermistor B constant in K.
     * @returns {Promise<void>}
     */
    async useThermistor(bConstant = 3380) {
        await this._ready;
        await this._writeWord(_REG_THERMISTOR_B, bConstant);
        await this._writeWord(_REG_STATUS, 0x0001);
    }

    /**
     * Switch to I²C temperature mode and write a temperature.
     * @param {number} [celsius=25] - Cell temperature in °C.
     * @returns {Promise<void>}
     */
    async useI2cTemperature(celsius = 25.0) {
        await this._ready;
        await this._writeWord(_REG_STATUS, 0x0000);
        await this._setTemperature(celsius);
    }

    /** @returns {Promise<boolean>} True if the temperature source is the thermistor. */
    async isThermistorMode() { await this._ready; return (await this._readWord(_REG_STATUS)) === 0x0001; }

    /** @returns {Promise<number>} Thermistor B constant in K. */
    async getBConstant() { await this._ready; return this._readWord(_REG_THERMISTOR_B); }

    /** Write the Adjustment Pack Application register. @param {number} value - 0-255. @returns {Promise<void>} */
    async setApa(value) { await this._ready; await this._writeWord(_REG_APA, value & 0xFF); }

    /** @returns {Promise<number>} Adjustment Pack Application register. */
    async getApa() { await this._ready; return this._readWord(_REG_APA); }

    /** Write the Adjustment Pack Thermistor register. @param {number} value - 0-65535. @returns {Promise<void>} */
    async setApt(value) { await this._ready; await this._writeWord(_REG_APT, value & 0xFFFF); }

    /** @returns {Promise<number>} Adjustment Pack Thermistor register. */
    async getApt() { await this._ready; return this._readWord(_REG_APT); }

    /**
     * Set the current direction.
     * @param {number} mode - `LC709203FFull.DIRECTION_AUTO`, `DIRECTION_CHARGE` or `DIRECTION_DISCHARGE`.
     * @returns {Promise<void>}
     * @throws {Error} For any other value.
     */
    async setCurrentDirection(mode) {
        if (mode !== 0x0000 && mode !== 0x0001 && mode !== 0xFFFF) throw new Error('invalid current direction');
        await this._ready;
        await this._writeWord(_REG_DIRECTION, mode);
    }

    /** @returns {Promise<number>} Current direction register. */
    async getCurrentDirection() { await this._ready; return this._readWord(_REG_DIRECTION); }

    /**
     * Select battery profile 0 or 1 (re-initializes the chip; waits 90 ms).
     * @param {number} profile - 0 or 1.
     * @returns {Promise<void>}
     */
    async setProfile(profile) {
        await this._ready;
        await this._writeWord(_REG_PROFILE, profile & 1);
        await _sleep(100);
        await this._wakeAndOperational();
    }

    /** @returns {Promise<number>} Selected battery profile (0 or 1). */
    async getProfile() { await this._ready; return this._readWord(_REG_PROFILE); }

    /** @returns {Promise<number>} Number of the Parameter register (e.g. 0x0301). */
    async profileId() { await this._ready; return this._readWord(_REG_PROFILE_ID); }

    /** @returns {Promise<number>} IC Version register. */
    async icVersion() { await this._ready; return this._readWord(_REG_IC_VERSION); }

    /** Set the low-RSOC alarm threshold. @param {number} percent - 0 disables, 1-100. @returns {Promise<void>} */
    async setAlarmRsoc(percent) {
        await this._ready;
        const p = Math.max(0, Math.min(100, Math.trunc(percent)));
        await this._writeWord(_REG_ALARM_RSOC, p);
        this._alarmRsoc = p;
    }

    /** @returns {Promise<number>} Low-RSOC alarm threshold in %. */
    async getAlarmRsoc() { await this._ready; return this._readWord(_REG_ALARM_RSOC); }

    /** Set the low-voltage alarm threshold. @param {number} volts - 0 disables; resolution 1 mV. @returns {Promise<void>} */
    async setAlarmVoltage(volts) {
        await this._ready;
        const raw = Math.max(0, Math.min(0xFFFF, Math.round(volts * 1000)));
        await this._writeWord(_REG_ALARM_VOLTAGE, raw);
        this._alarmVoltage = raw;
    }

    /** @returns {Promise<number>} Low-voltage alarm threshold in volts. */
    async getAlarmVoltage() { await this._ready; return (await this._readWord(_REG_ALARM_VOLTAGE)) / 1000; }

    /** Enter sleep mode (only I²C stays alive; charge counting stops). @returns {Promise<void>} */
    async sleep() { await this._ready; await this._writeWord(_REG_POWER_MODE, 0x0002); }

    /** Wake from sleep: dummy transaction (SDA pulse), wait 400 µs, enter operational mode. @returns {Promise<void>} */
    async wake() { await this._ready; await this._wakeAndOperational(); }

    /** @returns {Promise<boolean>} True if the chip is in sleep mode (wakes the bus first). */
    async isSleeping() {
        await this._ready;
        try { await this._conn.writeReg(_REG_POWER_MODE, Buffer.alloc(0)); } catch (e) { /* ignored */ }
        await _sleep(1);
        return (await this._readWord(_REG_POWER_MODE)) === 0x0002;
    }

    async _arm(intPin) {
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 1000);
        }
    }

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        } finally {
            this._polling = false;
        }
    }

    /**
     * Subscribe to ALARMB (falling edge). `callback(status)` receives the ALARM_RSOC / ALARM_VOLTAGE
     * bitmask. Delivery is via the INT line (options.intPin or connection.intPin) or a 1 s polling timer.
     * @param {function} callback - Called with (status).
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        await this._ready;
        this._callback = callback;
        await this._arm(options.intPin ?? this._conn.intPin ?? null);
    }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    /**
     * Evaluate RSOC and voltage against the cached alarm thresholds.
     * @returns {Promise<number>} Bitmask of ALARM_RSOC / ALARM_VOLTAGE (0 = no alarm).
     */
    async pollInterrupt() {
        await this._ready;
        let status = 0;
        if (this._alarmRsoc && (await this._readWord(_REG_RSOC)) < this._alarmRsoc) status |= LC709203FFull.ALARM_RSOC;
        if (this._alarmVoltage && (await this._readWord(_REG_VOLTAGE)) < this._alarmVoltage) status |= LC709203FFull.ALARM_VOLTAGE;
        return status;
    }
}

LC709203FFull.DIRECTION_AUTO = 0x0000;
LC709203FFull.DIRECTION_CHARGE = 0x0001;
LC709203FFull.DIRECTION_DISCHARGE = 0xFFFF;
LC709203FFull.ALARM_RSOC = 0x01;
LC709203FFull.ALARM_VOLTAGE = 0x02;

module.exports = { LC709203FMinimal, LC709203FFull, crc8Atm };
