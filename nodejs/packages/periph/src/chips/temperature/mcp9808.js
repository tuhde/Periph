'use strict';

const _REG_CONFIG     = 0x01;
const _REG_TUPPER     = 0x02;
const _REG_TLOWER     = 0x03;
const _REG_TCRIT      = 0x04;
const _REG_TA         = 0x05;
const _REG_MFR_ID     = 0x06;
const _REG_DEVICE_ID  = 0x07;
const _REG_RESOLUTION = 0x08;

const _MANUFACTURER_ID = 0x0054;
const _DEVICE_ID       = 0x04;

// CONFIG (0x01) bits.
const _CFG_THYST_SHIFT = 9;
const _CFG_THYST_MASK  = 0x0600;
const _CFG_SHDN        = 0x0100;
const _CFG_CRIT_LOCK   = 0x0080;
const _CFG_WIN_LOCK    = 0x0040;
const _CFG_INT_CLEAR   = 0x0020;
const _CFG_ALERT_STAT  = 0x0010;
const _CFG_ALERT_CNT   = 0x0008;
const _CFG_ALERT_SEL   = 0x0004;
const _CFG_ALERT_POL   = 0x0002;
const _CFG_ALERT_MOD   = 0x0001;
const _CFG_LOCKS       = 0x00C0;
// Writable bits: all but the unimplemented 15:11, the read-only ALERT_STAT,
// and the self-clearing INT_CLEAR (set only on purpose).
const _CFG_WRITE_MASK  = 0x07CF;

const _RESOLUTIONS = [0.5, 0.25, 0.125, 0.0625];
const _HYSTERESES  = [0, 1.5, 3.0, 6.0];

function _decodeTemperature(raw16) {
    let raw = raw16 & 0x1FFF;
    if (raw & 0x1000) raw -= 0x2000;
    return raw / 16;
}

function _decodeLimit(raw16) {
    let value = (raw16 >> 2) & 0x3FF;
    if (raw16 & 0x1000) value -= 1024;
    return value / 4;
}

function _encodeLimit(celsius) {
    // Round half away from zero, then clamp to the 11-bit two's-complement range.
    let quarters = celsius >= 0 ? Math.floor(celsius * 4 + 0.5) : -Math.floor(-celsius * 4 + 0.5);
    if (quarters < -1024) quarters = -1024;
    if (quarters > 1023) quarters = 1023;
    return (quarters & 0x7FF) << 2;
}

function _indexOfStep(table, value) {
    for (let i = 0; i < table.length; i++) {
        if (Math.abs(value - table[i]) < 1e-6) return i;
    }
    return -1;
}

/**
 * MCP9808 ±0.5°C maximum accuracy digital temperature sensor (Microchip)
 * — minimal interface.
 *
 * Band-gap temperature sensor with a delta-sigma ADC, read over I²C.
 * Registers are 16-bit, big-endian, addressed through a non-incrementing
 * Register Pointer. Eight selectable addresses (0x18–0x1F) via the
 * A0/A1/A2 strap pins.
 *
 * No register writes are made at construction: the POR default (continuous
 * conversion at 0.0625 °C resolution, Alert output disabled) already serves
 * the primary use case.
 *
 * Identity check: JS constructors cannot be async, so the MANUFACTURER_ID
 * (0x0054) / DEVICE_ID (0x04) check starts in the constructor and every
 * public method awaits it first. A wrong or absent device therefore rejects
 * the first call made on the driver — `await sensor.init()` right after
 * construction to surface it at a predictable point.
 */
class MCP9808Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x18–0x1F).
     */
    constructor(connection) {
        this._conn = connection;
        this._ready = this._checkIdentity();
        // The rejection is re-raised by every public method; mark it handled
        // here so it never surfaces as an unhandled rejection on its own.
        this._ready.catch(() => {});
    }

    /**
     * Wait for the identity check started by the constructor. Makes no
     * register writes. Optional — every other method awaits the same check —
     * but calling it right after construction surfaces a wrong or absent
     * device at a predictable point.
     * @returns {Promise<void>}
     * @throws {Error} If MANUFACTURER_ID or DEVICE_ID does not match.
     */
    async init() {
        await this._ready;
    }

    async _checkIdentity() {
        const mfr = await this._readReg(_REG_MFR_ID);
        if (mfr !== _MANUFACTURER_ID) {
            throw new Error('MCP9808 not found: expected MANUFACTURER_ID 0x0054, got 0x' +
                mfr.toString(16).padStart(4, '0'));
        }
        const dev = (await this._readReg(_REG_DEVICE_ID)) >> 8;
        if (dev !== _DEVICE_ID) {
            throw new Error('MCP9808 not found: expected DEVICE_ID 0x04, got 0x' +
                dev.toString(16).padStart(2, '0'));
        }
    }

    async _readReg(reg) {
        const buf = await this._conn.writeRead(Buffer.from([reg & 0xFF]), 2);
        return buf.readUInt16BE(0);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg & 0xFF, (value >> 8) & 0xFF, value & 0xFF]));
    }

    /**
     * Read the ambient temperature. Masks off TA's three boundary-status bits
     * and decodes the 13-bit two's-complement value (0.0625 °C per LSB).
     * @returns {Promise<number>} Ambient temperature in °C.
     */
    async readTemperature() {
        await this._ready;
        return _decodeTemperature(await this._readReg(_REG_TA));
    }
}

/**
 * MCP9808 full interface — extends Minimal with resolution control,
 * Shutdown mode, the TUPPER/TLOWER/TCRIT boundaries, hysteresis, the
 * one-way register locks, and the Level-2 Alert/interrupt API.
 */
class MCP9808Full extends MCP9808Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x18–0x1F).
     */
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    async _readConfig() {
        return (await this._readReg(_REG_CONFIG)) & _CFG_WRITE_MASK;
    }

    async _writeConfig(value) {
        await this._writeReg(_REG_CONFIG, value & _CFG_WRITE_MASK);
    }

    // -- Resolution ---------------------------------------------------------

    /**
     * Set the measurement resolution. Finer steps take longer to convert:
     * 0.5 °C = 30 ms, 0.25 °C = 65 ms, 0.125 °C = 130 ms, 0.0625 °C = 250 ms.
     * @param {number} celsius - One of 0.5, 0.25, 0.125, 0.0625.
     * @returns {Promise<void>}
     * @throws {RangeError} If celsius is not a supported step.
     */
    async setResolution(celsius) {
        const code = _indexOfStep(_RESOLUTIONS, celsius);
        if (code < 0) throw new RangeError('resolution must be one of 0.5, 0.25, 0.125, 0.0625');
        await this._ready;
        await this._conn.write(Buffer.from([_REG_RESOLUTION, code]));
    }

    /**
     * Read the measurement resolution.
     * @returns {Promise<number>} Resolution step in °C.
     */
    async getResolution() {
        await this._ready;
        const buf = await this._conn.writeRead(Buffer.from([_REG_RESOLUTION]), 1);
        return _RESOLUTIONS[buf[0] & 0x03];
    }

    // -- Shutdown -----------------------------------------------------------

    /**
     * Enter Shutdown (low-power) mode; TA holds its last value. No-op while
     * either lock bit is set (the chip ignores SHDN=1 then).
     * @returns {Promise<void>}
     */
    async shutdown() {
        await this._ready;
        const config = await this._readConfig();
        if (config & _CFG_LOCKS) return;
        await this._writeConfig(config | _CFG_SHDN);
    }

    /**
     * Leave Shutdown mode and resume continuous conversion.
     * @returns {Promise<void>}
     */
    async wake() {
        await this._ready;
        await this._writeConfig((await this._readConfig()) & ~_CFG_SHDN);
    }

    /**
     * Report whether the sensor is in Shutdown mode.
     * @returns {Promise<boolean>} True if SHDN is set.
     */
    async isShutdown() {
        await this._ready;
        return ((await this._readReg(_REG_CONFIG)) & _CFG_SHDN) !== 0;
    }

    // -- Boundaries ---------------------------------------------------------

    /**
     * Read the TUPPER boundary.
     * @returns {Promise<number>} Upper boundary in °C (0.25 °C steps).
     */
    async getUpperLimit() {
        await this._ready;
        return _decodeLimit(await this._readReg(_REG_TUPPER));
    }

    /**
     * Write the TUPPER boundary, rounded to the nearest 0.25 °C (ignored by
     * the chip while WIN_LOCK is set).
     * @param {number} celsius - Upper boundary in °C (−256.0 to 255.75, clamped).
     * @returns {Promise<void>}
     */
    async setUpperLimit(celsius) {
        await this._ready;
        await this._writeReg(_REG_TUPPER, _encodeLimit(celsius));
    }

    /**
     * Read the TLOWER boundary.
     * @returns {Promise<number>} Lower boundary in °C (0.25 °C steps).
     */
    async getLowerLimit() {
        await this._ready;
        return _decodeLimit(await this._readReg(_REG_TLOWER));
    }

    /**
     * Write the TLOWER boundary, rounded to the nearest 0.25 °C (ignored by
     * the chip while WIN_LOCK is set).
     * @param {number} celsius - Lower boundary in °C (−256.0 to 255.75, clamped).
     * @returns {Promise<void>}
     */
    async setLowerLimit(celsius) {
        await this._ready;
        await this._writeReg(_REG_TLOWER, _encodeLimit(celsius));
    }

    /**
     * Read the TCRIT boundary.
     * @returns {Promise<number>} Critical boundary in °C (0.25 °C steps).
     */
    async getCriticalLimit() {
        await this._ready;
        return _decodeLimit(await this._readReg(_REG_TCRIT));
    }

    /**
     * Write the TCRIT boundary, rounded to the nearest 0.25 °C (ignored by
     * the chip while CRIT_LOCK is set).
     * @param {number} celsius - Critical boundary in °C (−256.0 to 255.75, clamped).
     * @returns {Promise<void>}
     */
    async setCriticalLimit(celsius) {
        await this._ready;
        await this._writeReg(_REG_TCRIT, _encodeLimit(celsius));
    }

    // -- Hysteresis ---------------------------------------------------------

    /**
     * Set the boundary hysteresis (applies to the cooling edge only; ignored
     * by the chip while either lock bit is set).
     * @param {number} celsius - One of 0, 1.5, 3.0, 6.0.
     * @returns {Promise<void>}
     * @throws {RangeError} If celsius is not a supported value.
     */
    async setHysteresis(celsius) {
        const code = _indexOfStep(_HYSTERESES, celsius);
        if (code < 0) throw new RangeError('hysteresis must be one of 0, 1.5, 3.0, 6.0');
        await this._ready;
        const config = (await this._readConfig()) & ~_CFG_THYST_MASK;
        await this._writeConfig(config | (code << _CFG_THYST_SHIFT));
    }

    /**
     * Read the boundary hysteresis.
     * @returns {Promise<number>} Hysteresis in °C.
     */
    async getHysteresis() {
        await this._ready;
        const config = await this._readReg(_REG_CONFIG);
        return _HYSTERESES[(config & _CFG_THYST_MASK) >> _CFG_THYST_SHIFT];
    }

    // -- Locks --------------------------------------------------------------

    /**
     * Lock TCRIT (and ALERT_SEL/POL/MOD). Irreversible except by power-on reset.
     * @returns {Promise<void>}
     */
    async lockCriticalLimit() {
        await this._ready;
        await this._writeConfig((await this._readConfig()) | _CFG_CRIT_LOCK);
    }

    /**
     * Lock TUPPER/TLOWER (and ALERT_SEL/POL/MOD). Irreversible except by
     * power-on reset.
     * @returns {Promise<void>}
     */
    async lockWindowLimits() {
        await this._ready;
        await this._writeConfig((await this._readConfig()) | _CFG_WIN_LOCK);
    }

    /**
     * Report whether CRIT_LOCK is set.
     * @returns {Promise<boolean>} True if TCRIT is locked.
     */
    async isCriticalLimitLocked() {
        await this._ready;
        return ((await this._readReg(_REG_CONFIG)) & _CFG_CRIT_LOCK) !== 0;
    }

    /**
     * Report whether WIN_LOCK is set.
     * @returns {Promise<boolean>} True if TUPPER/TLOWER are locked.
     */
    async isWindowLimitsLocked() {
        await this._ready;
        return ((await this._readReg(_REG_CONFIG)) & _CFG_WIN_LOCK) !== 0;
    }

    // -- Alert output -------------------------------------------------------

    /**
     * Configure the Alert output's source, mode and polarity together.
     * @param {'all'|'critical_only'} [mode='all'] - Boundaries that drive the Alert output.
     * @param {'comparator'|'interrupt'} [output='comparator'] - Comparator or latching interrupt output.
     * @param {'active_low'|'active_high'} [polarity='active_low'] - Output polarity.
     * @returns {Promise<void>}
     * @throws {RangeError} If an argument is not one of the listed values.
     * @throws {Error} If either lock bit is set (the bits are frozen).
     */
    async configureAlert(mode = 'all', output = 'comparator', polarity = 'active_low') {
        if (mode !== 'all' && mode !== 'critical_only') throw new RangeError("mode must be 'all' or 'critical_only'");
        if (output !== 'comparator' && output !== 'interrupt') throw new RangeError("output must be 'comparator' or 'interrupt'");
        if (polarity !== 'active_low' && polarity !== 'active_high') throw new RangeError("polarity must be 'active_low' or 'active_high'");
        await this._ready;
        let config = await this._readConfig();
        if (config & _CFG_LOCKS) throw new Error('MCP9808: Alert configuration is locked until power-on reset');
        config &= ~(_CFG_ALERT_SEL | _CFG_ALERT_POL | _CFG_ALERT_MOD);
        if (mode === 'critical_only') config |= _CFG_ALERT_SEL;
        if (polarity === 'active_high') config |= _CFG_ALERT_POL;
        if (output === 'interrupt') config |= _CFG_ALERT_MOD;
        await this._writeConfig(config);
    }

    /**
     * Enable the Alert output (ALERT_CNT = 1).
     * @returns {Promise<void>}
     */
    async enableAlert() {
        await this._ready;
        await this._writeConfig((await this._readConfig()) | _CFG_ALERT_CNT);
    }

    /**
     * Disable the Alert output (ALERT_CNT = 0).
     * @returns {Promise<void>}
     */
    async disableAlert() {
        await this._ready;
        await this._writeConfig((await this._readConfig()) & ~_CFG_ALERT_CNT);
    }

    /**
     * Report whether the Alert output is currently asserted.
     * @returns {Promise<boolean>} True if ALERT_STAT is set.
     */
    async isAlertAsserted() {
        await this._ready;
        return ((await this._readReg(_REG_CONFIG)) & _CFG_ALERT_STAT) !== 0;
    }

    /**
     * Clear an asserted interrupt-mode Alert output (INT_CLEAR = 1). Has no
     * effect in comparator mode.
     * @returns {Promise<void>}
     */
    async clearInterrupt() {
        await this._ready;
        await this._writeReg(_REG_CONFIG, (await this._readConfig()) | _CFG_INT_CLEAR);
    }

    // -- Interrupt API (Level 2) --------------------------------------------

    /**
     * Read TA's live boundary-status bits. Nothing is cleared — the bits are
     * a live comparison, always current.
     * @returns {Promise<number>} Mask of SOURCE_LOWER / SOURCE_UPPER / SOURCE_CRITICAL.
     */
    async pollInterrupt() {
        await this._ready;
        return ((await this._readReg(_REG_TA)) >> 13) & 0x07;
    }

    /**
     * Subscribe to Alert events. Uses `connection.intPin.onEdge()` when an
     * InputPin is wired — the edge follows the configured ALERT_POL (falling
     * for active-low, rising for active-high) and the callback runs on every
     * edge. Otherwise falls back to a 5 ms polling loop that calls back
     * whenever the status mask changes. The Alert output must be enabled
     * (`enableAlert()`) for a pin to see edges.
     * @param {function(number): void} callback - Receives the pollInterrupt() mask.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this._ready;
        this._callback = callback;
        if (this._conn.intPin) {
            const activeHigh = ((await this._readReg(_REG_CONFIG)) & _CFG_ALERT_POL) !== 0;
            this._edgeHandler = async () => {
                const status = await this.pollInterrupt();
                if (this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, activeHigh ? 'rising' : 'falling');
        } else {
            let last = await this.pollInterrupt();
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this.pollInterrupt();
                    if (status !== last) {
                        last = status;
                        if (this._callback) this._callback(status);
                    }
                } finally {
                    busy = false;
                }
            }, 5);
        }
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        if (this._conn.intPin && this._edgeHandler) {
            await this._conn.intPin.offEdge(this._edgeHandler);
            this._edgeHandler = null;
        }
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        this._callback = null;
    }
}

/** Default 7-bit I²C address (A0 = A1 = A2 = GND). Valid range 0x18–0x1F. */
MCP9808Minimal.I2C_ADDRESS = 0x18;

/** TA < TLOWER. */
MCP9808Full.SOURCE_LOWER = 0x01;
/** TA > TUPPER. */
MCP9808Full.SOURCE_UPPER = 0x02;
/** TA ≥ TCRIT. */
MCP9808Full.SOURCE_CRITICAL = 0x04;

module.exports = { MCP9808Minimal, MCP9808Full };
