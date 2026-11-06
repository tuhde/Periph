'use strict';

/**
 * Shared implementation for the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.
 *
 * The three chips share one register map and one Config-register layout; they
 * differ only in which Config fields the silicon honours (ADS1113: fixed
 * ±2.048 V, no comparator, no mux; ADS1114: PGA + comparator; ADS1115: PGA +
 * comparator + 4-input mux). This module holds the whole-word Config builder,
 * the single-shot sequence and the feature implementations as underscore
 * methods on `ADS111xBase`; the per-chip modules publish the subset each chip
 * supports.
 *
 * Registers are 16-bit big-endian behind a write-only Address Pointer. Every
 * Config write is a whole word — never read-modify-write.
 */

const REG_CONVERSION = 0x00;
const REG_CONFIG     = 0x01;
const REG_LO_THRESH  = 0x02;
const REG_HI_THRESH  = 0x03;

const CONFIG_RESET = 0x8583;
const LO_RESET     = 0x8000;
const HI_RESET     = 0x7FFF;

const OS        = 0x8000;
const MODE_SHOT = 0x0100;
const QUE_DISABLED = 3;

const SOURCE_CONVERSION_READY = 0x01;
const SOURCE_COMPARATOR       = 0x02;

// Full-scale range in volts, indexed by PGA[2:0] (codes 6 and 7 alias ±0.256 V).
const FSR = [6.144, 4.096, 2.048, 1.024, 0.512, 0.256];
// Samples per second, indexed by DR[2:0].
const RATES = [8, 16, 32, 64, 128, 250, 475, 860];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function toSigned16(v) {
    return v & 0x8000 ? v - 0x10000 : v;
}

class ADS111xBase {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x48–0x4B). Its optional `intPin` receives ALERT/RDY.
     */
    constructor(connection) {
        this._conn = connection;
        this._resetState();
    }

    _resetState() {
        this._mux = 0;
        this._pga = 2;
        this._dr = 4;
        this._compMode = 0;
        this._compPol = 0;
        this._compLat = 0;
        this._compQue = QUE_DISABLED;
        this._queue = 0;
        this._continuous = false;
        this._loRaw = -32768;
        this._hiRaw = 32767;
        this._source = 0;
    }

    async _readReg(reg) {
        const raw = await this._conn.readReg(reg, 2);
        return (raw[0] << 8) | raw[1];
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg, Buffer.from([(value >> 8) & 0xFF, value & 0xFF]));
    }

    _configWord(osBit, mux) {
        return ((osBit ? OS : 0) | (mux << 12) | (this._pga << 9) |
            (this._continuous ? 0 : MODE_SHOT) | (this._dr << 5) |
            (this._compMode << 4) | (this._compPol << 3) | (this._compLat << 2) | this._compQue);
    }

    async _writeConfig(osBit = false) {
        await this._writeReg(REG_CONFIG, this._configWord(osBit, this._mux));
    }

    /** Single-shot conversion on `mux`; resolves to the signed 16-bit code. */
    async _convert(mux) {
        this._continuous = false;
        await this._writeReg(REG_CONFIG, this._configWord(true, mux));
        const wait = 1000 / RATES[this._dr];
        const deadline = Date.now() + 2 * wait + 50;
        await sleep(wait);
        while (!((await this._readReg(REG_CONFIG)) & OS)) {
            if (Date.now() > deadline) throw new Error('ADS111x conversion timeout');
            await sleep(0.5);
        }
        return toSigned16(await this._readReg(REG_CONVERSION));
    }

    async _setDataRate(dr) {
        if (!Number.isInteger(dr) || dr < 0 || dr > 7) throw new RangeError('dr must be 0-7');
        this._dr = dr;
        if (this._continuous) await this._writeConfig();
    }

    async _setGain(pga) {
        if (!Number.isInteger(pga) || pga < 0 || pga > 5) throw new RangeError('pga must be 0-5');
        this._pga = pga;
        if (this._continuous) await this._writeConfig();
    }

    async _startContinuous(mux) {
        if (!Number.isInteger(mux) || mux < 0 || mux > 7) throw new RangeError('mux must be 0-7');
        this._mux = mux;
        this._continuous = true;
        await this._writeConfig();
    }

    async _readContinuousRaw() {
        return toSigned16(await this._readReg(REG_CONVERSION));
    }

    async _stopContinuous() {
        this._continuous = false;
        await this._writeConfig();
    }

    async _isConverting() {
        return !((await this._readReg(REG_CONFIG)) & OS);
    }

    _fullScale() { return FSR[this._pga]; }

    _lsb() { return FSR[this._pga] / 32768; }

    async _reset(withThresholds) {
        await this._writeReg(REG_CONFIG, CONFIG_RESET);
        if (withThresholds) {
            await this._writeReg(REG_LO_THRESH, LO_RESET);
            await this._writeReg(REG_HI_THRESH, HI_RESET);
        }
        this._resetState();
    }

    // -- Comparator and thresholds ---------------------------------------

    async _configureComparator(mode, polarity, latch, queue) {
        if (![0, 1].includes(mode) || ![0, 1].includes(polarity) || ![0, 1, 2].includes(queue)) {
            throw new RangeError('invalid comparator argument');
        }
        if (this._hiRaw <= this._loRaw) throw new RangeError('high threshold must exceed low threshold');
        this._compMode = mode;
        this._compPol = polarity;
        this._compLat = latch ? 1 : 0;
        this._queue = queue;
        this._compQue = queue;
        this._source = SOURCE_COMPARATOR;
        await this._writeConfig();
    }

    async _disableComparator() {
        this._compQue = QUE_DISABLED;
        this._source = 0;
        await this._writeConfig();
    }

    async _setLowThresholdRaw(raw) {
        this._loRaw = raw;
        await this._writeReg(REG_LO_THRESH, raw & 0xFFFF);
    }

    async _setHighThresholdRaw(raw) {
        this._hiRaw = raw;
        await this._writeReg(REG_HI_THRESH, raw & 0xFFFF);
    }

    _voltsToCode(volts) {
        const steps = volts / this._lsb();
        const code = steps >= 0 ? Math.floor(steps + 0.5) : -Math.floor(-steps + 0.5);
        return Math.max(-32768, Math.min(32767, code));
    }

    async _setThresholds(low, high) {
        if (high <= low) throw new RangeError('high must exceed low');
        await this._setLowThresholdRaw(this._voltsToCode(low));
        await this._setHighThresholdRaw(this._voltsToCode(high));
    }

    async _getThresholdsRaw() {
        return [toSigned16(await this._readReg(REG_LO_THRESH)), toSigned16(await this._readReg(REG_HI_THRESH))];
    }

    async _clearAlert() {
        await this._readReg(REG_CONVERSION);
    }

    // -- Interrupt API -----------------------------------------------------

    async _enableInterrupt(source) {
        if (source === SOURCE_CONVERSION_READY) {
            await this._writeReg(REG_HI_THRESH, 0x8000);
            await this._writeReg(REG_LO_THRESH, 0x0000);
            this._compQue = 0;
            this._source = SOURCE_CONVERSION_READY;
            await this._writeConfig();
        } else if (source === SOURCE_COMPARATOR) {
            await this._setLowThresholdRaw(this._loRaw);
            await this._setHighThresholdRaw(this._hiRaw);
            await this._configureComparator(this._compMode, this._compPol, this._compLat === 1, this._queue);
        } else {
            throw new RangeError('unknown interrupt source');
        }
    }

    async _pollInterrupt() {
        if (this._source === 0) return 0;
        const pin = this._conn.intPin;
        let asserted;
        if (pin) asserted = (await pin.isHigh()) === (this._compPol === 1);
        else if (this._source === SOURCE_CONVERSION_READY) asserted = ((await this._readReg(REG_CONFIG)) & OS) !== 0;
        else asserted = false;
        if (!asserted) return 0;
        if (this._source === SOURCE_COMPARATOR && this._compLat) await this._clearAlert();
        return this._source;
    }

    async _onInterrupt(callback) {
        this._callback = callback;
        if (this._conn.intPin) {
            this._edgeHandler = async () => {
                const status = await this._pollInterrupt();
                if (this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, this._compPol ? 'rising' : 'falling');
        } else {
            let last = await this._pollInterrupt();
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try {
                    const status = await this._pollInterrupt();
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

    async _offInterrupt() {
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

module.exports = {
    ADS111xBase, RATES, FSR, REG_CONFIG, REG_CONVERSION, REG_LO_THRESH, REG_HI_THRESH,
    SOURCE_CONVERSION_READY, SOURCE_COMPARATOR,
};
