'use strict';

const _REG_SENSE   = 0x00;
const _REG_VIN     = 0x02;
const _REG_ADIN    = 0x04;
const _REG_CONTROL = 0x06;

const _LSB_SENSE = 20e-6;
const _LSB_VIN   = 0.025;
const _LSB_ADIN  = 0.0005;

const _CTRL_SNAPSHOT  = 0x80;
const _CTRL_TEST      = 0x10;
const _CTRL_PAGE_RW   = 0x08;
const _CTRL_STUCK_BUS = 0x04;

const _SNAPSHOT_TIMEOUT_MS = 100;

function _code(high, low) { return (high << 4) | (low >> 4); }
function _sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }

/**
 * LTC4151 7–80 V high-side current and voltage monitor — minimal interface.
 *
 * Provides supply voltage, load current and power with one call each. The chip
 * scans SENSE, VIN and ADIN continuously after power-up, so no configuration is
 * needed beyond the connection and the sense resistor; the constructor makes
 * no bus access.
 *
 * Default configuration (chip power-on state, not rewritten):
 * - CONTROL = 0x0C: continuous scan, page read/write and stuck-bus timer enabled
 * - 20 mΩ sense resistor (full-scale current 4.096 A)
 */
class LTC4151Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x67–0x6F).
     * @param {number} [rSense=0.02] - Sense resistor in ohms.
     */
    constructor(connection, rSense = 0.02) {
        if (!(rSense > 0)) throw new RangeError('rSense must be > 0');
        this._conn = connection;
        this._rSense = rSense;
    }

    /**
     * Read the supply voltage at VIN.
     * @returns {Promise<number>} VIN in volts (code × 25 mV).
     */
    async voltage() {
        const d = await this._conn.readReg(_REG_VIN, 2);
        return _code(d[0], d[1]) * _LSB_VIN;
    }

    /**
     * Read the load current through the sense resistor.
     * @returns {Promise<number>} Current in amperes (sense voltage / rSense).
     */
    async current() {
        const d = await this._conn.readReg(_REG_SENSE, 2);
        return _code(d[0], d[1]) * _LSB_SENSE / this._rSense;
    }

    /**
     * Read the load power from one consistent block read of SENSE and VIN.
     * @returns {Promise<number>} Power in watts (VIN × current).
     */
    async power() {
        const d = await this._conn.readReg(_REG_SENSE, 4);
        return _code(d[2], d[3]) * _LSB_VIN * (_code(d[0], d[1]) * _LSB_SENSE / this._rSense);
    }
}

/**
 * LTC4151 full interface — extends LTC4151Minimal with all channels, snapshot mode and CONTROL access.
 *
 * Channel constants (pass to readRaw, snapshot, isBusy):
 * - LTC4151Full.SENSE = 0, LTC4151Full.VIN = 1, LTC4151Full.ADIN = 2
 */
class LTC4151Full extends LTC4151Minimal {
    static SENSE = 0;
    static VIN = 1;
    static ADIN = 2;

    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x67–0x6F).
     * @param {number} [rSense=0.02] - Sense resistor in ohms.
     */
    constructor(connection, rSense = 0.02) {
        super(connection, rSense);
        this._pageRw = true;
    }

    async _readBlock(reg, length) {
        if (this._pageRw || length === 1) return this._conn.readReg(reg, length);
        const out = Buffer.alloc(length);
        for (let i = 0; i < length; i++) {
            out[i] = (await this._conn.readReg(reg + i, 1))[0];
        }
        return out;
    }

    async _readControlRaw() {
        return (await this._conn.readReg(_REG_CONTROL, 1))[0];
    }

    async _writeControlRaw(value) {
        await this._conn.writeReg(_REG_CONTROL, Buffer.from([value & 0xFF]));
    }

    static _checkChannel(channel) {
        if (channel !== 0 && channel !== 1 && channel !== 2) throw new RangeError('channel must be 0, 1 or 2');
    }

    /**
     * Read the voltage across the sense resistor.
     * @returns {Promise<number>} SENSE+ − SENSE− in volts (code × 20 µV).
     */
    async senseVoltage() {
        const d = await this._readBlock(_REG_SENSE, 2);
        return _code(d[0], d[1]) * _LSB_SENSE;
    }

    /**
     * Read the ADIN pin voltage.
     * @returns {Promise<number>} ADIN in volts (code × 0.5 mV).
     */
    async adinVoltage() {
        const d = await this._readBlock(_REG_ADIN, 2);
        return _code(d[0], d[1]) * _LSB_ADIN;
    }

    /**
     * Read the supply voltage at VIN.
     * @returns {Promise<number>} VIN in volts (code × 25 mV).
     */
    async voltage() {
        const d = await this._readBlock(_REG_VIN, 2);
        return _code(d[0], d[1]) * _LSB_VIN;
    }

    /**
     * Read the load current through the sense resistor.
     * @returns {Promise<number>} Current in amperes (sense voltage / rSense).
     */
    async current() { return (await this.senseVoltage()) / this._rSense; }

    /**
     * Read the load power from one consistent block read of SENSE and VIN.
     * @returns {Promise<number>} Power in watts (VIN × current).
     */
    async power() {
        const d = await this._readBlock(_REG_SENSE, 4);
        return _code(d[2], d[3]) * _LSB_VIN * (_code(d[0], d[1]) * _LSB_SENSE / this._rSense);
    }

    /**
     * Read all three channels from one block read of registers 0x00–0x05.
     * @returns {Promise<{vin: number, senseV: number, current: number, power: number, adin: number}>}
     *   VIN in V, sense voltage in V, current in A, power in W, ADIN in V.
     */
    async readAll() {
        const d = await this._readBlock(_REG_SENSE, 6);
        const senseV = _code(d[0], d[1]) * _LSB_SENSE;
        const vin = _code(d[2], d[3]) * _LSB_VIN;
        const adin = _code(d[4], d[5]) * _LSB_ADIN;
        const current = senseV / this._rSense;
        return { vin, senseV, current, power: vin * current, adin };
    }

    /**
     * Read the 12-bit code of a channel.
     * @param {number} channel - SENSE (0), VIN (1) or ADIN (2).
     * @returns {Promise<number>} 12-bit conversion code, 0–4095.
     */
    async readRaw(channel) {
        LTC4151Full._checkChannel(channel);
        const d = await this._readBlock(channel * 2, 2);
        return _code(d[0], d[1]);
    }

    /**
     * Set the sense resistor value used for current and power.
     * @param {number} ohms - Sense resistor in ohms; must be greater than 0.
     */
    setSenseResistor(ohms) {
        if (!(ohms > 0)) throw new RangeError('ohms must be > 0');
        this._rSense = ohms;
    }

    /**
     * Get the sense resistor value.
     * @returns {number} Sense resistor in ohms.
     */
    getSenseResistor() { return this._rSense; }

    /**
     * Run one snapshot conversion of a channel, then return to continuous scan.
     *
     * Clears the snapshot bit first, then writes the channel with the snapshot bit
     * set, polls the channel's BUSY bit (timeout 100 ms), reads the result and
     * restores continuous scan.
     *
     * @param {number} channel - SENSE (0), VIN (1) or ADIN (2).
     * @returns {Promise<number>} Converted value in volts (sense voltage, VIN or ADIN).
     */
    async snapshot(channel) {
        LTC4151Full._checkChannel(channel);
        const base = (await this._readControlRaw()) & (_CTRL_PAGE_RW | _CTRL_STUCK_BUS);
        await this._writeControlRaw(base);
        await this._writeControlRaw(base | _CTRL_SNAPSHOT | (channel << 5));
        const start = Date.now();
        while (await this.isBusy(channel)) {
            if (Date.now() - start > _SNAPSHOT_TIMEOUT_MS) {
                await this._writeControlRaw(base);
                throw new Error('LTC4151 snapshot timeout');
            }
            await _sleep(2);
        }
        const code = await this.readRaw(channel);
        await this._writeControlRaw(base);
        return code * [_LSB_SENSE, _LSB_VIN, _LSB_ADIN][channel];
    }

    /** Switch to continuous scan mode (snapshot bit cleared). */
    async setContinuous() {
        const value = (await this._readControlRaw()) & ~(_CTRL_SNAPSHOT | _CTRL_TEST);
        await this._writeControlRaw(value);
    }

    /**
     * Read the snapshot bit of CONTROL.
     * @returns {Promise<boolean>} True if the chip is in snapshot mode.
     */
    async isSnapshotMode() { return ((await this._readControlRaw()) & _CTRL_SNAPSHOT) !== 0; }

    /**
     * Read the BUSY bit of a channel (snapshot mode).
     * @param {number} channel - SENSE (0), VIN (1) or ADIN (2).
     * @returns {Promise<boolean>} True while that channel is being converted.
     */
    async isBusy(channel) {
        LTC4151Full._checkChannel(channel);
        return (((await this._conn.readReg(channel * 2 + 1, 1))[0]) & 0x08) !== 0;
    }

    /**
     * Enable or disable the stuck-bus reset timer (CONTROL.G2, read-modify-write).
     * @param {boolean} enabled - True to enable (power-on default).
     */
    async setStuckBusTimer(enabled) {
        let value = (await this._readControlRaw()) & ~_CTRL_TEST;
        value = enabled ? (value | _CTRL_STUCK_BUS) : (value & ~_CTRL_STUCK_BUS);
        await this._writeControlRaw(value);
    }

    /**
     * Enable or disable I²C page read/write (CONTROL.G3, read-modify-write).
     * When disabled the chip only supports byte/word transfers, so this driver
     * then reads each register separately.
     * @param {boolean} enabled - True to enable (power-on default).
     */
    async setPageRw(enabled) {
        let value = (await this._readControlRaw()) & ~_CTRL_TEST;
        value = enabled ? (value | _CTRL_PAGE_RW) : (value & ~_CTRL_PAGE_RW);
        await this._writeControlRaw(value);
        this._pageRw = !!enabled;
    }

    /**
     * Read the CONTROL register.
     * @returns {Promise<number>} Raw CONTROL value (power-on default 0x0C).
     */
    async readControl() { return this._readControlRaw(); }

    /**
     * Write the CONTROL register. Test mode (bit 4) is always masked to 0.
     * @param {number} value - Raw CONTROL value.
     */
    async writeControl(value) {
        const v = value & ~_CTRL_TEST & 0xFF;
        await this._writeControlRaw(v);
        this._pageRw = (v & _CTRL_PAGE_RW) !== 0;
    }

    /**
     * Convert an ADIN/VIN code pair to temperature (datasheet NTC example circuit).
     * Valid for roughly 20–60 °C and only for the datasheet circuit.
     * @param {number} adinCode - 12-bit ADIN code.
     * @param {number} vinCode - 12-bit VIN code.
     * @returns {number} Temperature in °C, 58.82 × (adinCode / vinCode − 0.1066).
     */
    static temperatureFromAdin(adinCode, vinCode) {
        return 58.82 * (adinCode / vinCode - 0.1066);
    }
}

module.exports = { LTC4151Minimal, LTC4151Full };
