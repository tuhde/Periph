'use strict';

const _REG_TEMP_RESULT = 0x00;
const _REG_CONFIG      = 0x01;
const _REG_THIGH       = 0x02;
const _REG_TLOW        = 0x03;
const _REG_EEPROM_UL   = 0x04;
const _REG_TEMP_OFFSET = 0x07;
const _REG_DEVICE_ID   = 0x0F;

const _DEVICE_ID = 0x117;

// CONFIGURATION (0x01) bits.
const _CFG_HIGH_ALERT = 0x8000;
const _CFG_LOW_ALERT  = 0x4000;
const _CFG_DATA_READY = 0x2000;
const _CFG_MOD_SHIFT  = 10;
const _CFG_MOD_MASK   = 0x0C00;
const _CFG_CONV_SHIFT = 7;
const _CFG_CONV_MASK  = 0x0380;
const _CFG_AVG_SHIFT  = 5;
const _CFG_AVG_MASK   = 0x0060;
const _CFG_TNA        = 0x0010;
const _CFG_POL        = 0x0008;
const _CFG_DR_ALERT   = 0x0004;
const _CFG_SOFT_RESET = 0x0002;
// Writable bits: MOD/CONV/AVG/T-nA/POL/DR-Alert. Soft_Reset is set only on purpose.
const _CFG_WRITE_MASK = 0x0FFC;

const _MOD_ONE_SHOT = 0x03;

// EEPROM_UL (0x04) bits.
const _EUN         = 0x8000;
const _EEPROM_BUSY = 0x4000;

// Conversion cycle times in s, indexed by CONV[2:0] (no-averaging column).
const _CYCLES = [0.0155, 0.125, 0.25, 0.5, 1.0, 4.0, 8.0, 16.0];
// Averaging counts, indexed by AVG[1:0].
const _AVERAGINGS = [0, 8, 32, 64];
// Conversion modes, indexed by MOD[1:0] (0b10 reads back as continuous).
const _MODES = ['continuous', 'shutdown', 'continuous', 'one_shot'];
// EEPROM scratch slot -> register address.
const _SCRATCH_REGS = { 1: 0x05, 2: 0x06, 3: 0x08 };

const _LSB_C = 0.0078125;

function _decodeTemperature(raw16) {
    const raw = raw16 & 0x8000 ? raw16 - 0x10000 : raw16;
    return raw * _LSB_C;
}

function _encodeTemperature(celsius) {
    // Round half away from zero, then clamp to the 16-bit two's-complement range.
    const steps = celsius / _LSB_C;
    let value = steps >= 0 ? Math.floor(steps + 0.5) : -Math.floor(-steps + 0.5);
    if (value < -32768) value = -32768;
    if (value > 32767) value = 32767;
    return value & 0xFFFF;
}

/**
 * TMP117 ±0.1°C high-accuracy, low-power digital temperature sensor (Texas
 * Instruments) — minimal interface.
 *
 * NIST-traceable 16-bit temperature sensor (0.0078125 °C per LSB) read over
 * an I²C/SMBus-compatible bus. Registers are 16-bit, big-endian, addressed
 * through a non-incrementing Register Pointer. Four selectable addresses
 * (0x48–0x4B) via the 4-level ADD0 strap.
 *
 * No register writes are made at construction: the POR/EEPROM default
 * (continuous conversion, 8-conversion averaging, 1 s cycle, Alert mode)
 * already serves the primary use case.
 *
 * Identity check: JS constructors cannot be async, so the DEVICE_ID (0x117)
 * check starts in the constructor and every public method awaits it first.
 * A wrong or absent device therefore rejects the first call made on the
 * driver — `await sensor.init()` right after construction to surface it at a
 * predictable point.
 */
class TMP117Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x48–0x4B).
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
     * @throws {Error} If DEVICE_ID bits 11:0 are not 0x117.
     */
    async init() {
        await this._ready;
    }

    async _checkIdentity() {
        const did = (await this._readReg(_REG_DEVICE_ID)) & 0x0FFF;
        if (did !== _DEVICE_ID) {
            throw new Error('TMP117 not found: expected DEVICE_ID 0x117, got 0x' +
                did.toString(16).padStart(3, '0'));
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
     * Read the temperature. Decodes TEMP_RESULT's 16-bit two's-complement
     * value (0.0078125 °C per LSB). Returns -256 until the first conversion
     * after power-up completes.
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        await this._ready;
        return _decodeTemperature(await this._readReg(_REG_TEMP_RESULT));
    }
}

/**
 * TMP117 full interface — extends Minimal with conversion mode, averaging
 * and cycle-time control, one-shot triggering, both temperature limits, the
 * calibration offset, soft reset, EEPROM persistence and scratch storage,
 * and the Level-2 Alert/interrupt API.
 */
class TMP117Full extends TMP117Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection (0x48–0x4B).
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

    // -- Conversion ---------------------------------------------------------

    /**
     * Set conversion mode, averaging and cycle time. The cycle time is
     * matched to the nearest CONV[2:0] step from the no-averaging column
     * (15.5 ms, 125 ms, 250 ms, 500 ms, 1 s, 4 s, 8 s, 16 s); at higher
     * averaging the hardware lengthens short cycles automatically. The Alert
     * configuration bits are preserved.
     * @param {'continuous'|'shutdown'|'one_shot'} [mode='continuous'] - Conversion mode.
     * @param {0|8|32|64} [averaging=8] - Conversions averaged per result.
     * @param {number} [cycleSeconds=1.0] - Desired conversion cycle time in s.
     * @returns {Promise<void>}
     * @throws {RangeError} If mode or averaging is not one of the listed values.
     */
    async configure(mode = 'continuous', averaging = 8, cycleSeconds = 1.0) {
        let mod;
        if (mode === 'continuous') mod = 0x00;
        else if (mode === 'shutdown') mod = 0x01;
        else if (mode === 'one_shot') mod = _MOD_ONE_SHOT;
        else throw new RangeError("mode must be 'continuous', 'shutdown' or 'one_shot'");
        const avg = _AVERAGINGS.indexOf(averaging);
        if (avg < 0) throw new RangeError('averaging must be one of 0, 8, 32, 64');
        let conv = 0;
        for (let code = 1; code < _CYCLES.length; code++) {
            if (Math.abs(_CYCLES[code] - cycleSeconds) < Math.abs(_CYCLES[conv] - cycleSeconds)) conv = code;
        }
        await this._ready;
        let config = (await this._readConfig()) & ~(_CFG_MOD_MASK | _CFG_CONV_MASK | _CFG_AVG_MASK);
        config |= (mod << _CFG_MOD_SHIFT) | (conv << _CFG_CONV_SHIFT) | (avg << _CFG_AVG_SHIFT);
        await this._writeConfig(config);
    }

    /**
     * Read the conversion mode, averaging and cycle time.
     * @returns {Promise<{mode: string, averaging: number, cycleSeconds: number}>}
     *     cycleSeconds is the no-averaging CONV[2:0] step, in s.
     */
    async getConfig() {
        await this._ready;
        const config = await this._readReg(_REG_CONFIG);
        return {
            mode: _MODES[(config & _CFG_MOD_MASK) >> _CFG_MOD_SHIFT],
            averaging: _AVERAGINGS[(config & _CFG_AVG_MASK) >> _CFG_AVG_SHIFT],
            cycleSeconds: _CYCLES[(config & _CFG_CONV_MASK) >> _CFG_CONV_SHIFT],
        };
    }

    /**
     * Report whether the sensor is in Shutdown mode.
     * @returns {Promise<boolean>} True if MOD[1:0] is Shutdown.
     */
    async isShutdown() {
        await this._ready;
        return (((await this._readReg(_REG_CONFIG)) & _CFG_MOD_MASK) >> _CFG_MOD_SHIFT) === 0x01;
    }

    /**
     * Start a single conversion (MOD[1:0] = One-Shot). The sensor returns to
     * Shutdown once the conversion (including averaging) completes.
     * @returns {Promise<void>}
     */
    async triggerOneShot() {
        await this._ready;
        const config = (await this._readConfig()) & ~_CFG_MOD_MASK;
        await this._writeConfig(config | (_MOD_ONE_SHOT << _CFG_MOD_SHIFT));
    }

    /**
     * Report whether a fresh conversion result is available. Reading this
     * flag clears it (as does reading TEMP_RESULT).
     * @returns {Promise<boolean>} True if Data_Ready is set.
     */
    async isDataReady() {
        await this._ready;
        return ((await this._readReg(_REG_CONFIG)) & _CFG_DATA_READY) !== 0;
    }

    // -- Limits and offset --------------------------------------------------

    /**
     * Read THIGH_LIMIT.
     * @returns {Promise<number>} High limit in °C.
     */
    async getHighLimit() {
        await this._ready;
        return _decodeTemperature(await this._readReg(_REG_THIGH));
    }

    /**
     * Write THIGH_LIMIT, rounded to the nearest 0.0078125 °C.
     * @param {number} celsius - High limit in °C (-256 to 255.9921875, clamped).
     * @returns {Promise<void>}
     */
    async setHighLimit(celsius) {
        await this._ready;
        await this._writeReg(_REG_THIGH, _encodeTemperature(celsius));
    }

    /**
     * Read TLOW_LIMIT.
     * @returns {Promise<number>} Low limit in °C.
     */
    async getLowLimit() {
        await this._ready;
        return _decodeTemperature(await this._readReg(_REG_TLOW));
    }

    /**
     * Write TLOW_LIMIT, rounded to the nearest 0.0078125 °C. In Therm mode
     * this is HIGH_Alert's reset threshold (hysteresis).
     * @param {number} celsius - Low limit in °C (-256 to 255.9921875, clamped).
     * @returns {Promise<void>}
     */
    async setLowLimit(celsius) {
        await this._ready;
        await this._writeReg(_REG_TLOW, _encodeTemperature(celsius));
    }

    /**
     * Read TEMP_OFFSET.
     * @returns {Promise<number>} Calibration offset in °C.
     */
    async getTemperatureOffset() {
        await this._ready;
        return _decodeTemperature(await this._readReg(_REG_TEMP_OFFSET));
    }

    /**
     * Write TEMP_OFFSET, added to every result after linearization.
     * @param {number} celsius - Calibration offset in °C (-256 to 255.9921875, clamped).
     * @returns {Promise<void>}
     */
    async setTemperatureOffset(celsius) {
        await this._ready;
        await this._writeReg(_REG_TEMP_OFFSET, _encodeTemperature(celsius));
    }

    // -- Reset --------------------------------------------------------------

    /**
     * Software reset (Soft_Reset = 1), then wait the 2 ms reset time. Reloads
     * CONFIGURATION, THIGH_LIMIT, TLOW_LIMIT and TEMP_OFFSET from EEPROM.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._ready;
        await this._writeReg(_REG_CONFIG, _CFG_SOFT_RESET);
        await new Promise((resolve) => setTimeout(resolve, 2));
    }

    // -- EEPROM -------------------------------------------------------------

    /**
     * Unlock the EEPROM (EUN = 1). While unlocked, writes to CONFIGURATION,
     * THIGH_LIMIT, TLOW_LIMIT, TEMP_OFFSET and EEPROM2 also program the
     * EEPROM as the new power-on default. Poll isEepromBusy() after each such
     * write.
     * @returns {Promise<void>}
     */
    async unlockEeprom() {
        await this._ready;
        await this._writeReg(_REG_EEPROM_UL, _EUN);
    }

    /**
     * Lock the EEPROM (EUN = 0); register writes become volatile only.
     * @returns {Promise<void>}
     */
    async lockEeprom() {
        await this._ready;
        await this._writeReg(_REG_EEPROM_UL, 0x0000);
    }

    /**
     * Report whether an EEPROM programming operation is in progress.
     * @returns {Promise<boolean>} True if EEPROM_Busy is set.
     */
    async isEepromBusy() {
        await this._ready;
        return ((await this._readReg(_REG_EEPROM_UL)) & _EEPROM_BUSY) !== 0;
    }

    /**
     * Read a general-purpose EEPROM scratch register.
     * @param {1|2|3} slot - EEPROM1, EEPROM2 or EEPROM3; slots 1 and 3 hold
     *     factory NIST-traceability data.
     * @returns {Promise<number>} 16-bit register value.
     * @throws {RangeError} If slot is not 1, 2 or 3.
     */
    async readEepromScratch(slot) {
        if (!(slot in _SCRATCH_REGS)) throw new RangeError('slot must be 1, 2 or 3');
        await this._ready;
        return this._readReg(_SCRATCH_REGS[slot]);
    }

    /**
     * Write the general-purpose EEPROM2 scratch register. Only slot 2 is
     * writable — EEPROM1/EEPROM3 hold factory NIST-traceability data.
     * Persists across power cycles only while the EEPROM is unlocked.
     * @param {2} slot - Must be 2.
     * @param {number} value - 16-bit value.
     * @returns {Promise<void>}
     * @throws {RangeError} If slot is not 2.
     */
    async writeEepromScratch(slot, value) {
        if (slot !== 2) throw new RangeError('only EEPROM scratch slot 2 is writable');
        await this._ready;
        await this._writeReg(_SCRATCH_REGS[2], value & 0xFFFF);
    }

    // -- Alert output -------------------------------------------------------

    /**
     * Configure the ALERT output's mode, polarity and pin function together.
     * @param {'alert'|'therm'} [mode='alert'] - Window alert or latching Therm
     *     (TLOW_LIMIT becomes the reset threshold).
     * @param {'active_low'|'active_high'} [polarity='active_low'] - Output polarity.
     * @param {'alert'|'data_ready'} [pinFunction='alert'] - Alert/Therm status or Data-Ready.
     * @returns {Promise<void>}
     * @throws {RangeError} If an argument is not one of the listed values.
     */
    async configureAlert(mode = 'alert', polarity = 'active_low', pinFunction = 'alert') {
        if (mode !== 'alert' && mode !== 'therm') throw new RangeError("mode must be 'alert' or 'therm'");
        if (polarity !== 'active_low' && polarity !== 'active_high') throw new RangeError("polarity must be 'active_low' or 'active_high'");
        if (pinFunction !== 'alert' && pinFunction !== 'data_ready') throw new RangeError("pinFunction must be 'alert' or 'data_ready'");
        await this._ready;
        let config = (await this._readConfig()) & ~(_CFG_TNA | _CFG_POL | _CFG_DR_ALERT);
        if (mode === 'therm') config |= _CFG_TNA;
        if (polarity === 'active_high') config |= _CFG_POL;
        if (pinFunction === 'data_ready') config |= _CFG_DR_ALERT;
        await this._writeConfig(config);
    }

    // -- Interrupt API (Level 2) --------------------------------------------

    /**
     * Read CONFIGURATION's HIGH_Alert / LOW_Alert flags. In Alert mode this
     * read also clears both flags (a hardware side effect). In Therm mode
     * HIGH_Alert clears only once the temperature drops below TLOW_LIMIT.
     * @returns {Promise<number>} Mask of SOURCE_HIGH / SOURCE_LOW.
     */
    async pollInterrupt() {
        await this._ready;
        const config = await this._readReg(_REG_CONFIG);
        let status = 0;
        if (config & _CFG_HIGH_ALERT) status |= TMP117Full.SOURCE_HIGH;
        if (config & _CFG_LOW_ALERT) status |= TMP117Full.SOURCE_LOW;
        return status;
    }

    /**
     * Subscribe to ALERT events. Uses `connection.intPin.onEdge()` when an
     * InputPin is wired — the edge follows the configured POL (falling for
     * active-low, rising for active-high) and the callback runs on every
     * edge. Otherwise falls back to a 5 ms polling loop that calls back
     * whenever the status mask changes.
     * @param {function(number): void} callback - Receives the pollInterrupt() mask.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        await this._ready;
        this._callback = callback;
        if (this._conn.intPin) {
            const activeHigh = ((await this._readReg(_REG_CONFIG)) & _CFG_POL) !== 0;
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

/** Default 7-bit I²C address (ADD0 = GND). Valid range 0x48–0x4B. */
TMP117Minimal.I2C_ADDRESS = 0x48;

/** Result > THIGH_LIMIT (HIGH_Alert). */
TMP117Full.SOURCE_HIGH = 0x01;
/** Result < TLOW_LIMIT (LOW_Alert; Alert mode only). */
TMP117Full.SOURCE_LOW = 0x02;

module.exports = { TMP117Minimal, TMP117Full };
