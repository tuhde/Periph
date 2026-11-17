'use strict';

const CMD = {
    OPERATION: 0x01, CLEAR_FAULTS: 0x03, CAPABILITY: 0x19, VOUT_UV_WARN: 0x43, OT_FAULT: 0x4F,
    OT_WARN: 0x51, VIN_OV_WARN: 0x57, VIN_UV_WARN: 0x58, STATUS_BYTE: 0x78, STATUS_WORD: 0x79,
    STATUS_VOUT: 0x7A, STATUS_INPUT: 0x7C, STATUS_TEMP: 0x7D, STATUS_CML: 0x7E, STATUS_MFR: 0x80,
    READ_VIN: 0x88, READ_VOUT: 0x8B, READ_TEMP: 0x8D, MFR_ID: 0x99, MFR_MODEL: 0x9A,
    MFR_REVISION: 0x9B, READ_VAUX: 0xD0, READ_IIN: 0xD1, READ_PIN: 0xD2, IIN_WARN: 0xD3,
    PIN_WARN: 0xD4, READ_PIN_PEAK: 0xD5, CLEAR_PIN_PEAK: 0xD6, GATE_MASK: 0xD7, ALERT_MASK: 0xD8,
    DEVICE_SETUP: 0xD9, BLOCK_READ: 0xDA, SAMPLES_FOR_AVG: 0xDB, READ_AVG_VIN: 0xDC,
    READ_AVG_VOUT: 0xDD, READ_AVG_IIN: 0xDE, READ_AVG_PIN: 0xDF, BLACK_BOX: 0xE0,
    DIAGNOSTIC: 0xE1, AVG_BLOCK_READ: 0xE2,
};

// PMBus DIRECT coefficients [m, b, R]
const VOLT = [22070, -1800, -2];
const VAUX = [3546, -3, 0];
const TEMP = [16000, 0, -3];

function fromY(y, [m, b, r]) {
    return (y * Math.pow(10, -r) - b) / m;
}

function toY(x, [m, b, r]) {
    const y = Math.round((m * x + b) * Math.pow(10, r));
    return Math.max(0, Math.min(0x0FFE, y));
}

function signed16(word) {
    return word & 0x8000 ? word - 0x10000 : word;
}

/**
 * LM25066 hot-swap controller / PMBus power monitor - minimal interface.
 *
 * Reads the monitored rail: input voltage, output voltage, input current, input power and MOSFET
 * temperature. The constructor performs no bus access and never writes the chip. Telemetry is PMBus
 * DIRECT; the current and power coefficients depend on the sense resistor and the CL pin.
 */
class LM25066Minimal {
    /**
     * @param {object} connection - RegisterConnection (I2C/SMBus, 1-byte command; address 0x10-0x17, 0x40-0x47, 0x50-0x5A).
     * @param {number} [rSenseMohm=1.0] - Sense resistor in milliohms.
     * @param {boolean} [currentLimitHigh=false] - CL pin at VDD (46 mV) when true, GND (25 mV) when false.
     */
    constructor(connection, rSenseMohm = 1.0, currentLimitHigh = false) {
        this._conn = connection;
        this._rs = rSenseMohm;
        this._setCl(currentLimitHigh);
    }

    _setCl(high) {
        this._clHigh = !!high;
        const rs = this._rs;
        if (this._clHigh) {
            this._amp = [6854 * rs, -3100, -2];
            this._watt = [369 * rs, -1900, -2];
        } else {
            this._amp = [13661 * rs, -5200, -2];
            this._watt = [736 * rs, -3300, -2];
        }
    }

    async _readWord(cmd) {
        const raw = await this._conn.readReg(cmd, 2);
        return raw[0] | (raw[1] << 8);
    }

    async _writeWord(cmd, value) {
        await this._conn.writeReg(cmd, Buffer.from([value & 0xFF, (value >> 8) & 0xFF]));
    }

    async _readByte(cmd) {
        return (await this._conn.readReg(cmd, 1))[0];
    }

    async _readY(cmd) {
        return (await this._readWord(cmd)) & 0x0FFF;
    }

    /** @returns {Promise<number>} Input voltage VIN in volts. */
    async voltageIn() { return fromY(await this._readY(CMD.READ_VIN), VOLT); }

    /** @returns {Promise<number>} Output voltage VOUT in volts. */
    async voltageOut() { return fromY(await this._readY(CMD.READ_VOUT), VOLT); }

    /** @returns {Promise<number>} Instantaneous input current in amperes. */
    async current() { return fromY(await this._readY(CMD.READ_IIN), this._amp); }

    /** @returns {Promise<number>} Instantaneous input power in watts. */
    async power() { return fromY(await this._readY(CMD.READ_PIN), this._watt); }

    /** @returns {Promise<number>} External MOSFET temperature in degrees Celsius. */
    async temperature() { return signed16(await this._readWord(CMD.READ_TEMP)) / 16; }
}

/**
 * LM25066 full interface - averaging, limits, status, control, alerts.
 *
 * WARNING: operationOff() cuts the load rail and setGateMask() can stop hardware faults from
 * switching the MOSFET off (which may destroy it). Neither is ever called by the constructor.
 * SOURCE_* constants are ALERT_MASK bit positions (1 = masked).
 */
class LM25066Full extends LM25066Minimal {
    constructor(connection, rSenseMohm = 1.0, currentLimitHigh = false) {
        super(connection, rSenseMohm, currentLimitHigh);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    async _block(cmd) {
        const raw = await this._conn.readReg(cmd, 13);
        if (raw[0] !== 12) throw new Error(`LM25066 block read count ${raw[0]} != 12`);
        const w = [];
        for (let i = 0; i < 6; i++) w.push(raw[1 + 2 * i] | (raw[2 + 2 * i] << 8));
        return {
            diagnostic: w[0],
            current: fromY(w[1] & 0x0FFF, this._amp),
            voltageOut: fromY(w[2] & 0x0FFF, VOLT),
            voltageIn: fromY(w[3] & 0x0FFF, VOLT),
            power: fromY(w[4] & 0x0FFF, this._watt),
            temperature: signed16(w[5]) / 16,
        };
    }

    async _text(cmd, n) {
        const raw = await this._conn.readReg(cmd, n + 1);
        if (raw[0] !== n) throw new Error(`LM25066 block read count ${raw[0]} != ${n}`);
        return Buffer.from(raw.subarray(1)).filter((c) => c !== 0).toString('latin1');
    }

    /** @returns {Promise<number>} VAUX pin voltage in volts (0 ... 1.16 V). */
    async voltageAux() { return fromY(await this._readY(CMD.READ_VAUX), VAUX); }

    /** @returns {Promise<object>} BLOCK_READ: {diagnostic, current, voltageOut, voltageIn, power, temperature}. */
    async readBlock() { return this._block(CMD.BLOCK_READ); }

    /** @returns {Promise<object>} AVG_BLOCK_READ: same keys as readBlock(). */
    async readAverage() { return this._block(CMD.AVG_BLOCK_READ); }

    /** @returns {Promise<object>} BLACK_BOX_READ snapshot latched at the first SMBA since clearFaults(). */
    async blackBox() { return this._block(CMD.BLACK_BOX); }

    /** @returns {Promise<number>} Averaged VIN in volts. */
    async voltageInAvg() { return fromY(await this._readY(CMD.READ_AVG_VIN), VOLT); }

    /** @returns {Promise<number>} Averaged VOUT in volts. */
    async voltageOutAvg() { return fromY(await this._readY(CMD.READ_AVG_VOUT), VOLT); }

    /** @returns {Promise<number>} Averaged input current in amperes. */
    async currentAvg() { return fromY(await this._readY(CMD.READ_AVG_IIN), this._amp); }

    /** @returns {Promise<number>} Averaged input power in watts. */
    async powerAvg() { return fromY(await this._readY(CMD.READ_AVG_PIN), this._watt); }

    /** @returns {Promise<number>} Peak input power since reset / clearPowerPeak(), in watts. */
    async powerPeak() { return fromY(await this._readY(CMD.READ_PIN_PEAK), this._watt); }

    /** Reset the peak-power register (CLEAR_PIN_PEAK, send byte). @returns {Promise<void>} */
    async clearPowerPeak() { await this._conn.writeReg(CMD.CLEAR_PIN_PEAK, Buffer.alloc(0)); }

    /**
     * Average over 2^exponent samples.
     * @param {number} exponent - 0-12.
     * @returns {Promise<void>}
     * @throws {RangeError} If exponent is outside 0-12.
     */
    async setAveraging(exponent) {
        if (!Number.isInteger(exponent) || exponent < 0 || exponent > 12) throw new RangeError('exponent must be 0-12');
        await this._conn.writeReg(CMD.SAMPLES_FOR_AVG, Buffer.from([exponent]));
    }

    /** @returns {Promise<number>} SAMPLES_FOR_AVG exponent. */
    async getAveraging() { return this._readByte(CMD.SAMPLES_FOR_AVG); }

    async _setLimit(cmd, value, coef, disabled) {
        await this._writeWord(cmd, value === null || value === undefined ? disabled : toY(value, coef));
    }

    async _getLimit(cmd, coef, disabled) {
        const y = (await this._readWord(cmd)) & 0x0FFF;
        return y === disabled ? null : fromY(y, coef);
    }

    /** VIN over-voltage warning in volts (null disables). @param {number|null} volts @returns {Promise<void>} */
    async setVinOvWarn(volts) { await this._setLimit(CMD.VIN_OV_WARN, volts, VOLT, 0x0FFF); }

    /** VIN under-voltage warning in volts (null disables). @param {number|null} volts @returns {Promise<void>} */
    async setVinUvWarn(volts) { await this._setLimit(CMD.VIN_UV_WARN, volts, VOLT, 0x0000); }

    /** VOUT under-voltage warning in volts (null disables). @param {number|null} volts @returns {Promise<void>} */
    async setVoutUvWarn(volts) { await this._setLimit(CMD.VOUT_UV_WARN, volts, VOLT, 0x0000); }

    /** Over-temperature warning in Celsius (null disables). @param {number|null} celsius @returns {Promise<void>} */
    async setOtWarn(celsius) { await this._setLimit(CMD.OT_WARN, celsius, TEMP, 0x0FFF); }

    /** Over-temperature fault in Celsius (null disables). @param {number|null} celsius @returns {Promise<void>} */
    async setOtFault(celsius) { await this._setLimit(CMD.OT_FAULT, celsius, TEMP, 0x0FFF); }

    /** Input over-current warning in amperes (null disables). @param {number|null} amps @returns {Promise<void>} */
    async setIinWarn(amps) { await this._setLimit(CMD.IIN_WARN, amps, this._amp, 0x0FFF); }

    /** Input over-power warning in watts (null disables). @param {number|null} watts @returns {Promise<void>} */
    async setPinWarn(watts) { await this._setLimit(CMD.PIN_WARN, watts, this._watt, 0x0FFF); }

    /**
     * Read all warning / fault thresholds.
     * @returns {Promise<object>} {vinOvWarn, vinUvWarn, voutUvWarn, otWarn, otFault, iinWarn, pinWarn}; null where disabled.
     */
    async getLimits() {
        return {
            vinOvWarn: await this._getLimit(CMD.VIN_OV_WARN, VOLT, 0x0FFF),
            vinUvWarn: await this._getLimit(CMD.VIN_UV_WARN, VOLT, 0x0000),
            voutUvWarn: await this._getLimit(CMD.VOUT_UV_WARN, VOLT, 0x0000),
            otWarn: await this._getLimit(CMD.OT_WARN, TEMP, 0x0FFF),
            otFault: await this._getLimit(CMD.OT_FAULT, TEMP, 0x0FFF),
            iinWarn: await this._getLimit(CMD.IIN_WARN, this._amp, 0x0FFF),
            pinWarn: await this._getLimit(CMD.PIN_WARN, this._watt, 0x0FFF),
        };
    }

    /** @returns {Promise<number>} STATUS_BYTE. */
    async statusByte() { return this._readByte(CMD.STATUS_BYTE); }
    /** @returns {Promise<number>} STATUS_WORD. */
    async statusWord() { return this._readWord(CMD.STATUS_WORD); }
    /** @returns {Promise<number>} STATUS_VOUT. */
    async statusVout() { return this._readByte(CMD.STATUS_VOUT); }
    /** @returns {Promise<number>} STATUS_INPUT. */
    async statusInput() { return this._readByte(CMD.STATUS_INPUT); }
    /** @returns {Promise<number>} STATUS_TEMPERATURE. */
    async statusTemperature() { return this._readByte(CMD.STATUS_TEMP); }
    /** @returns {Promise<number>} STATUS_CML. */
    async statusCml() { return this._readByte(CMD.STATUS_CML); }
    /** @returns {Promise<number>} STATUS_MFR_SPECIFIC. */
    async statusMfr() { return this._readByte(CMD.STATUS_MFR); }
    /** @returns {Promise<number>} READ_DIAGNOSTIC_WORD. */
    async diagnosticWord() { return this._readWord(CMD.DIAGNOSTIC); }

    /** CLEAR_FAULTS (send byte); does not switch the MOSFET on. @returns {Promise<void>} */
    async clearFaults() { await this._conn.writeReg(CMD.CLEAR_FAULTS, Buffer.alloc(0)); }

    /** OPERATION = 0x80: switches the load on. @returns {Promise<void>} */
    async operationOn() { await this._conn.writeReg(CMD.OPERATION, Buffer.from([0x80])); }

    /** OPERATION = 0x00: CUTS THE LOAD RAIL. @returns {Promise<void>} */
    async operationOff() { await this._conn.writeReg(CMD.OPERATION, Buffer.from([0x00])); }

    /** @returns {Promise<boolean>} True if OPERATION reads 0x80 and the STATUS_BYTE OFF bit is clear. */
    async isOn() {
        return (await this._readByte(CMD.OPERATION)) === 0x80 && !((await this._readByte(CMD.STATUS_BYTE)) & 0x40);
    }

    /** @returns {Promise<number>} DEVICE_SETUP. */
    async deviceSetup() { return this._readByte(CMD.DEVICE_SETUP); }

    /**
     * Override the RETRY / CL / CB pin settings from software; only the given options are overridden.
     * Updates the driver coefficients when currentLimitHigh is given.
     * @param {object} [options]
     * @param {number} [options.retryCode] - 0-7 (bits 7:5; 0 pin, 1 none, 2 = 1 retry, 3 = 2, 4 = 4, 5 = 8, 6 = 16, 7 = unlimited).
     * @param {boolean} [options.currentLimitHigh] - true = 46 mV, false = 25 mV.
     * @param {boolean} [options.cbRatioHigh] - true = 3.6x, false = 1.8x.
     * @returns {Promise<void>}
     * @throws {RangeError} If retryCode is outside 0-7.
     */
    async configureDevice(options = {}) {
        const { retryCode, currentLimitHigh, cbRatioHigh } = options;
        let value = 0;
        if (retryCode !== undefined && retryCode !== null) {
            if (!Number.isInteger(retryCode) || retryCode < 0 || retryCode > 7) throw new RangeError('retryCode must be 0-7');
            value |= retryCode << 5;
        }
        if (currentLimitHigh !== undefined && currentLimitHigh !== null) value |= 0x04 | (currentLimitHigh ? 0x10 : 0);
        if (cbRatioHigh !== undefined && cbRatioHigh !== null) value |= 0x02 | (cbRatioHigh ? 0x08 : 0);
        await this._conn.writeReg(CMD.DEVICE_SETUP, Buffer.from([value]));
        if (currentLimitHigh !== undefined && currentLimitHigh !== null) this._setCl(currentLimitHigh);
    }

    /**
     * Write GATE_MASK. DANGEROUS: can disable fault shut-off of the MOSFET.
     * @param {number} mask - Bits 5, 4, 2 only.
     * @returns {Promise<void>}
     * @throws {RangeError} If other bits are set.
     */
    async setGateMask(mask) {
        if (mask & ~0x34) throw new RangeError('only GATE_MASK bits 5, 4, 2 may be set');
        await this._conn.writeReg(CMD.GATE_MASK, Buffer.from([mask]));
    }

    /** @returns {Promise<number>} GATE_MASK. */
    async getGateMask() { return this._readByte(CMD.GATE_MASK); }

    /** @returns {Promise<number>} CAPABILITY. */
    async capability() { return this._readByte(CMD.CAPABILITY); }

    /** @returns {Promise<string>} MFR_ID. */
    async manufacturerId() { return this._text(CMD.MFR_ID, 3); }

    /** @returns {Promise<string>} MFR_MODEL. */
    async model() { return this._text(CMD.MFR_MODEL, 8); }

    /** @returns {Promise<string>} MFR_REVISION. */
    async revision() { return this._text(CMD.MFR_REVISION, 2); }

    /** Unmask alert source bit(s) in ALERT_MASK. @param {number} source @returns {Promise<void>} */
    async enableInterrupt(source) {
        await this._writeWord(CMD.ALERT_MASK, (await this._readWord(CMD.ALERT_MASK)) & ~source & 0xFFFF);
    }

    /** Mask alert source bit(s) in ALERT_MASK. @param {number} source @returns {Promise<void>} */
    async disableInterrupt(source) {
        await this._writeWord(CMD.ALERT_MASK, (await this._readWord(CMD.ALERT_MASK)) | source);
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
     * Subscribe to SMBA (falling edge); falls back to a 1 s poll without an interrupt pin.
     * @param {function(number): void} callback - Receives READ_DIAGNOSTIC_WORD.
     * @param {object} [options]
     * @param {object} [options.intPin] - InputPin overriding connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        const intPin = options.intPin ?? this._conn.intPin ?? null;
        this._intPinUsed = intPin;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intPin) {
            await intPin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 1000);
        }
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

    /** Read READ_DIAGNOSTIC_WORD (does not clear; call clearFaults()). @returns {Promise<number>} */
    async pollInterrupt() { return this._readWord(CMD.DIAGNOSTIC); }
}

Object.assign(LM25066Full, {
    SOURCE_VOUT_UV_WARN: 0x8000, SOURCE_IIN_WARN: 0x4000, SOURCE_VIN_UV_WARN: 0x2000,
    SOURCE_VIN_OV_WARN: 0x1000, SOURCE_POWER_GOOD: 0x0800, SOURCE_OT_WARN: 0x0400,
    SOURCE_PIN_OP_WARN: 0x0100, SOURCE_MOSFET_SHORTED: 0x0040, SOURCE_VIN_UV_FAULT: 0x0020,
    SOURCE_VIN_OV_FAULT: 0x0010, SOURCE_IIN_FAULT: 0x0008, SOURCE_OT_FAULT: 0x0004,
    SOURCE_CML_FAULT: 0x0002, SOURCE_CB_FAULT: 0x0001,
});

module.exports = { LM25066Minimal, LM25066Full };
