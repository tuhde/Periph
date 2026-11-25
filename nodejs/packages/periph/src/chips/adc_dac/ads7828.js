'use strict';

const _INTERNAL_VREF = 2.5;
const _PD_ALL_DOWN = 0;
const _PD_REF_OFF_ADC_ON = 1;
const _PD_REF_ON_ADC_OFF = 2;
const _PD_REF_ON_ADC_ON = 3;
const _CMD_REF_ON = 0x08;     // lone command: SD=0, C=0, PD=10
const _CMD_POWER_DOWN = 0x00; // lone command: SD=0, C=0, PD=00
const _REF_SETTLE_MS = 1;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** C2:C0 for single-ended channel n — the encoding is interleaved. */
function _singleEndedCode(channel) {
    return ((channel & 1) << 2) | (channel >> 1);
}

/**
 * ADS7828 12-bit, 8-channel sampling ADC — minimal interface.
 *
 * The chip has no registers: one command byte selects the input mode, the
 * channel and the power-down state and starts a conversion; the 12-bit
 * result is read back as two bytes (`readReg(command, 2)` is the datasheet's
 * write-command / repeated-START / read sequence). The I²C address is
 * 0x48–0x4B. Reads the eight single-ended inputs (CHn against COM) as raw
 * codes or voltages. Defaults to the internal 2.5 V reference, which is kept
 * on between conversions.
 */
class ADS7828Minimal {
    /**
     * @param {import('../../connection/connection').RegisterConnection} connection - I²C connection pointing at the device (0x48–0x4B).
     * @param {number|null} [vref=null] - null for the internal 2.5 V reference, or the external reference voltage in volts (0.05 V … VDD).
     */
    constructor(connection, vref = null) {
        this._conn = connection;
        this._refOn = false;
        this._selectReference(vref);
    }

    _selectReference(vref) {
        if (vref === null || vref === undefined) {
            this._internal = true;
            this._vref = _INTERNAL_VREF;
            this._pd = _PD_REF_ON_ADC_ON;
        } else {
            this._internal = false;
            this._vref = Number(vref);
            this._pd = _PD_ALL_DOWN;
        }
        this._refOn = false;
    }

    async _enableReference() {
        await this._conn.write(Buffer.from([_CMD_REF_ON]));
        await sleep(_REF_SETTLE_MS);
        this._refOn = true;
    }

    /**
     * Prepare the driver. With the internal reference this sends the
     * reference-on command and waits 1 ms for it to settle; with an external
     * reference there is no I²C traffic. Reads call this lazily if it was skipped.
     * @returns {Promise<void>}
     */
    async init() {
        if (this._internal) await this._enableReference();
    }

    async _convert(singleEnded, code) {
        if (this._internal && !this._refOn) await this._enableReference();
        const command = ((singleEnded ? 1 : 0) << 7) | (code << 4) | (this._pd << 2);
        const data = await this._conn.readReg(command, 2);
        return ((data[0] & 0x0F) << 8) | data[1];
    }

    /**
     * Read a single-ended channel (CHn against COM) as a 12-bit code.
     * @param {number} channel - Channel number 0–7. Clamped to the valid range.
     * @returns {Promise<number>} Raw 12-bit code (0–4095).
     */
    async readRaw(channel) {
        const ch = Math.max(0, Math.min(7, Math.trunc(channel)));
        return this._convert(true, _singleEndedCode(ch));
    }

    /**
     * Read a single-ended channel as a voltage.
     * @param {number} channel - Channel number 0–7. Clamped to the valid range.
     * @returns {Promise<number>} Input voltage in volts (raw / 4096 × vref).
     */
    async readVoltage(channel) {
        return (await this.readRaw(channel)) / 4096 * this._vref;
    }

    /**
     * Read all eight single-ended channels, one conversion each.
     * @returns {Promise<number[]>} Voltages of CH0…CH7 in volts.
     */
    async readAll() {
        const out = [];
        for (let ch = 0; ch < 8; ch++) out.push(await this.readVoltage(ch));
        return out;
    }
}

/**
 * ADS7828 full interface — extends ADS7828Minimal.
 *
 * Adds differential reads (both polarities), a signed differential read,
 * run-time reference switching, power-down control and multi-channel scans.
 */
class ADS7828Full extends ADS7828Minimal {
    /** @type {number} PD=00: power down between conversions. */
    static POWER_DOWN_BETWEEN = _PD_ALL_DOWN;
    /** @type {number} PD=01: internal reference off, ADC on. */
    static REF_OFF_ADC_ON = _PD_REF_OFF_ADC_ON;
    /** @type {number} PD=10: internal reference on, ADC off. */
    static REF_ON_ADC_OFF = _PD_REF_ON_ADC_OFF;
    /** @type {number} PD=11: internal reference on, ADC on. */
    static REF_ON_ADC_ON = _PD_REF_ON_ADC_ON;

    /**
     * Read a differential pair as a 12-bit code.
     * @param {number} pair - Pair index 0–3 (CH0/CH1 … CH6/CH7). Clamped.
     * @param {boolean} [inverted=false] - If true the positive input is CH(2·pair+1).
     * @returns {Promise<number>} Raw 12-bit code (0–4095); a negative differential voltage reads 0.
     */
    async readDifferentialRaw(pair, inverted = false) {
        const p = Math.max(0, Math.min(3, Math.trunc(pair)));
        return this._convert(false, inverted ? (4 | p) : p);
    }

    /**
     * Read a differential pair as a voltage.
     * @param {number} pair - Pair index 0–3. Clamped.
     * @param {boolean} [inverted=false] - If true the positive input is CH(2·pair+1).
     * @returns {Promise<number>} Differential voltage in volts (≥ 0).
     */
    async readDifferential(pair, inverted = false) {
        return (await this.readDifferentialRaw(pair, inverted)) / 4096 * this._vref;
    }

    /**
     * Read V(CH2p) − V(CH2p+1) with sign, using two conversions.
     * @param {number} pair - Pair index 0–3. Clamped.
     * @returns {Promise<number>} Signed differential voltage in volts (−vref … +vref).
     */
    async readSignedDifferential(pair) {
        const normal = await this.readDifferentialRaw(pair, false);
        const inverted = await this.readDifferentialRaw(pair, true);
        return (normal - inverted) / 4096 * this._vref;
    }

    /**
     * Switch between the internal and an external reference.
     * @param {number|null} vref - null for the internal 2.5 V reference (re-runs the turn-on and settle wait), or an external reference voltage in volts (PD1 = 0 from then on).
     * @returns {Promise<void>}
     */
    async setReference(vref) {
        this._selectReference(vref);
        if (this._internal) await this._enableReference();
    }

    /**
     * Reference voltage used for scaling.
     * @returns {number} Volts (2.5 for the internal reference).
     */
    getReference() {
        return this._vref;
    }

    /**
     * Whether the internal reference is selected.
     * @returns {boolean}
     */
    usesInternalReference() {
        return this._internal;
    }

    /**
     * Set the PD bits used in all following conversion commands.
     * @param {number} pd - 0 = POWER_DOWN_BETWEEN, 1 = REF_OFF_ADC_ON, 2 = REF_ON_ADC_OFF, 3 = REF_ON_ADC_ON.
     * @throws {RangeError} If pd is outside 0–3, or if the internal reference is selected and pd has PD1 = 0.
     */
    setPowerMode(pd) {
        if (!Number.isInteger(pd) || pd < 0 || pd > 3) throw new RangeError('pd must be 0-3');
        if (this._internal && !(pd & 2)) throw new RangeError('internal reference requires PD1 = 1');
        this._pd = pd;
    }

    /**
     * Power the chip down completely (≈ 400 nA) with a lone command (PD = 00).
     * With the internal reference this turns it off; the next read re-runs the turn-on and settle wait.
     * @returns {Promise<void>}
     */
    async powerDown() {
        await this._conn.write(Buffer.from([_CMD_POWER_DOWN]));
        this._refOn = false;
    }

    /**
     * Read several single-ended channels in order as raw codes.
     * @param {number[]} channels - Channel numbers 0–7.
     * @returns {Promise<number[]>} Raw 12-bit codes, one conversion per channel.
     */
    async readScan(channels) {
        const out = [];
        for (const ch of channels) out.push(await this.readRaw(ch));
        return out;
    }

    /**
     * Size of one code step.
     * @returns {number} vref / 4096 in volts (610 µV at 2.5 V).
     */
    lsb() {
        return this._vref / 4096;
    }
}

ADS7828Minimal.I2C_ADDRESS = 0x48;
ADS7828Full.I2C_ADDRESS = 0x48;

module.exports = { ADS7828Minimal, ADS7828Full };
