'use strict';

const { toSigned } = require('../../connection/register');

const _REG_CONVERSION = 0x00;
const _REG_CONFIG     = 0x01;
const _REG_LO_THRESH  = 0x02;
const _REG_HI_THRESH  = 0x03;

const _CONFIG_RESET = 0x8583;
const _LO_RESET     = 0x8000;
const _HI_RESET     = 0x7FFF;

const _OS            = 0x8000;
const _MODE_SINGLE   = 0x0100;
const _QUEUE_DISABLE = 3;

const _FULL_SCALE = [6.144, 4.096, 2.048, 1.024, 0.512, 0.256];
const _SPS        = [128, 250, 490, 920, 1600, 2400, 3300];

const SOURCE_CONVERSION_READY = 0x01;
const SOURCE_COMPARATOR       = 0x02;

function _sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * ADS1014 12-bit I²C ADC with PGA and comparator (Texas Instruments) —
 * minimal interface.
 *
 * Single-input (AIN0 − AIN1) ΔΣ ADC with internal reference and oscillator.
 * Registers are 16-bit, big-endian, addressed through a persistent Address
 * Pointer. Four selectable addresses (0x48–0x4B) via the ADDR strap. The
 * chip has no ID register, so construction performs no I²C traffic.
 *
 * Defaults: ±2.048 V range (1 mV/LSB), 1600 SPS, single-shot, comparator
 * disabled. The whole Config word is written for every conversion.
 */
class ADS1014Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x48–0x4B).
     */
    constructor(connection) {
        this._conn = connection;
        this._pga = 2;
        this._dr = 4;
        this._continuous = false;
        this._compMode = 0;
        this._compPol = 0;
        this._compLat = false;
        this._queue = 0;
        this._source = 0;
    }

    async _readReg(reg) {
        const buf = await this._conn.readReg(reg & 0xFF, 2);
        return buf.readUInt16BE(0);
    }

    async _writeReg(reg, value) {
        await this._conn.writeReg(reg & 0xFF, Buffer.from([(value >> 8) & 0xFF, value & 0xFF]));
    }

    _compQue() {
        if (this._source === SOURCE_CONVERSION_READY) return 0;
        if (this._source === SOURCE_COMPARATOR) return this._queue;
        return _QUEUE_DISABLE;
    }

    _configWord(osBit, continuous) {
        let word = osBit ? _OS : 0;
        word |= this._pga << 9;
        if (!continuous) word |= _MODE_SINGLE;
        word |= this._dr << 5;
        word |= this._compMode << 4;
        word |= this._compPol << 3;
        word |= (this._compLat ? 1 : 0) << 2;
        word |= this._compQue();
        return word;
    }

    async _readConversion() {
        return toSigned(await this._readReg(_REG_CONVERSION), 16) >> 4;
    }

    /**
     * Run one single-shot conversion of AIN0 − AIN1. Writes the whole Config
     * word with OS = 1, polls OS until the chip is idle again, then reads
     * CONVERSION.
     * @returns {Promise<number>} 12-bit two's-complement code, −2048…2047.
     */
    async readRaw() {
        await this._writeReg(_REG_CONFIG, this._configWord(true, false));
        this._continuous = false;
        for (let i = 0; i < 100; i++) {
            if ((await this._readReg(_REG_CONFIG)) & _OS) break;
            await _sleep(1);
        }
        return this._readConversion();
    }

    /**
     * Run one single-shot conversion and convert it to volts.
     * @returns {Promise<number>} Voltage AIN0 − AIN1 in volts (raw × full-scale / 2048).
     */
    async readVoltage() {
        return (await this.readRaw()) * _FULL_SCALE[this._pga] / 2048;
    }
}

/**
 * ADS1014 full interface — extends Minimal with PGA gain, data rate,
 * continuous conversion, the programmable comparator and the Level-2
 * ALERT/RDY interrupt API.
 */
class ADS1014Full extends ADS1014Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection (0x48–0x4B).
     */
    constructor(connection) {
        super(connection);
        this._loRaw = -2048;
        this._hiRaw = 2047;
        this._callback = null;
        this._edgeHandler = null;
        this._pollTimer = null;
    }

    async _writeConfig() {
        await this._writeReg(_REG_CONFIG, this._configWord(false, this._continuous));
    }

    /**
     * Select the full-scale range used by all following reads. Thresholds
     * must be rewritten after a gain change.
     * @param {number} pga - PGA_6_144 (0) … PGA_0_256 (5).
     * @returns {Promise<void>}
     * @throws {RangeError} If pga is outside 0–5 (6 and 7 alias ±0.256 V).
     */
    async setGain(pga) {
        if (!Number.isInteger(pga) || pga < 0 || pga > 5) throw new RangeError('pga must be 0-5');
        this._pga = pga;
        if (this._continuous) await this._writeConfig();
    }

    /**
     * Read the configured PGA code.
     * @returns {number} PGA code 0–5.
     */
    getGain() {
        return this._pga;
    }

    /**
     * Full-scale range of the current gain.
     * @returns {number} ±FSR in volts (6.144, 4.096, 2.048, 1.024, 0.512 or 0.256).
     */
    fullScale() {
        return _FULL_SCALE[this._pga];
    }

    /**
     * Size of one conversion code.
     * @returns {number} fullScale() / 2048 in volts.
     */
    lsb() {
        return _FULL_SCALE[this._pga] / 2048;
    }

    /**
     * Select the conversion data rate.
     * @param {number} dr - DR_128 (0) … DR_3300 (6).
     * @returns {Promise<void>}
     * @throws {RangeError} If dr is outside 0–6 (7 aliases 3300 SPS).
     */
    async setDataRate(dr) {
        if (!Number.isInteger(dr) || dr < 0 || dr > 6) throw new RangeError('dr must be 0-6');
        this._dr = dr;
        if (this._continuous) await this._writeConfig();
    }

    /**
     * Read the configured data-rate code.
     * @returns {number} DR code 0–6.
     */
    getDataRate() {
        return this._dr;
    }

    /**
     * Current data rate.
     * @returns {number} 128, 250, 490, 920, 1600, 2400 or 3300 samples per second.
     */
    dataRateSps() {
        return _SPS[this._dr];
    }

    /**
     * Start continuous conversion (MODE = 0) at the current data rate.
     * @returns {Promise<void>}
     */
    async startContinuous() {
        this._continuous = true;
        await this._writeConfig();
    }

    /**
     * Read the most recent completed result without touching Config (valid
     * at least 1/DR after startContinuous()).
     * @returns {Promise<number>} 12-bit two's-complement code, −2048…2047.
     */
    async readContinuousRaw() {
        return this._readConversion();
    }

    /**
     * Read the most recent completed result in volts.
     * @returns {Promise<number>} readContinuousRaw() × lsb().
     */
    async readContinuous() {
        return (await this._readConversion()) * this.lsb();
    }

    /**
     * Stop continuous conversion (MODE = 1, single-shot / power-down).
     * @returns {Promise<void>}
     */
    async stopContinuous() {
        this._continuous = false;
        await this._writeConfig();
    }

    /**
     * Report whether a single-shot conversion is running (OS = 0; only
     * meaningful in single-shot mode).
     * @returns {Promise<boolean>} True while converting.
     */
    async isConverting() {
        return ((await this._readReg(_REG_CONFIG)) & _OS) === 0;
    }

    /**
     * Read the raw CONFIG register.
     * @returns {Promise<number>} 16-bit CONFIG value.
     */
    async readConfig() {
        return this._readReg(_REG_CONFIG);
    }

    /**
     * Write the reset values back (CONFIG 0x8583, LO_THRESH 0x8000, HI_THRESH
     * 0x7FFF) and clear the cached settings. Not an I²C general-call reset.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._writeReg(_REG_CONFIG, _CONFIG_RESET);
        await this._writeReg(_REG_LO_THRESH, _LO_RESET);
        await this._writeReg(_REG_HI_THRESH, _HI_RESET);
        this._pga = 2;
        this._dr = 4;
        this._continuous = false;
        this._compMode = 0;
        this._compPol = 0;
        this._compLat = false;
        this._queue = 0;
        this._source = 0;
        this._loRaw = -2048;
        this._hiRaw = 2047;
    }

    /**
     * Configure the comparator and enable it as the ALERT/RDY source.
     * @param {number} [mode=COMP_TRADITIONAL] - COMP_TRADITIONAL (0) or COMP_WINDOW (1).
     * @param {number} [polarity=ACTIVE_LOW] - ACTIVE_LOW (0) or ACTIVE_HIGH (1).
     * @param {boolean} [latch=false] - Latch the alert until CONVERSION is read.
     * @param {number} [queue=QUEUE_1] - QUEUE_1 (0), QUEUE_2 (1) or QUEUE_4 (2) consecutive out-of-range conversions.
     * @returns {Promise<void>}
     * @throws {RangeError} If an argument is out of range.
     */
    async configureComparator(mode = 0, polarity = 0, latch = false, queue = 0) {
        if (mode !== 0 && mode !== 1) throw new RangeError('mode must be COMP_TRADITIONAL or COMP_WINDOW');
        if (polarity !== 0 && polarity !== 1) throw new RangeError('polarity must be ACTIVE_LOW or ACTIVE_HIGH');
        if (!Number.isInteger(queue) || queue < 0 || queue > 2) throw new RangeError('queue must be QUEUE_1, QUEUE_2 or QUEUE_4');
        this._compMode = mode;
        this._compPol = polarity;
        this._compLat = !!latch;
        this._queue = queue;
        this._source = SOURCE_COMPARATOR;
        await this._writeConfig();
    }

    /**
     * Disable the comparator (COMP_QUE = 11); ALERT/RDY goes high-impedance.
     * @returns {Promise<void>}
     */
    async disableComparator() {
        this._source = 0;
        await this._writeConfig();
    }

    async _writeThreshold(reg, raw) {
        if (!Number.isInteger(raw) || raw < -2048 || raw > 2047) throw new RangeError('raw must be -2048..2047');
        await this._writeReg(reg, (raw & 0x0FFF) << 4);
    }

    /**
     * Write the comparator low threshold (LO_THRESH = raw << 4).
     * @param {number} raw - 12-bit two's-complement code, −2048…2047.
     * @returns {Promise<void>}
     * @throws {RangeError} If raw is out of range.
     */
    async setLowThresholdRaw(raw) {
        await this._writeThreshold(_REG_LO_THRESH, raw);
        this._loRaw = raw;
    }

    /**
     * Write the comparator high threshold (HI_THRESH = raw << 4).
     * @param {number} raw - 12-bit two's-complement code, −2048…2047.
     * @returns {Promise<void>}
     * @throws {RangeError} If raw is out of range.
     */
    async setHighThresholdRaw(raw) {
        await this._writeThreshold(_REG_HI_THRESH, raw);
        this._hiRaw = raw;
    }

    /**
     * Write both comparator thresholds from volts (converted with lsb(),
     * clamped to the code range).
     * @param {number} low - Low threshold in volts.
     * @param {number} high - High threshold in volts.
     * @returns {Promise<void>}
     * @throws {RangeError} If high is not greater than low.
     */
    async setThresholds(low, high) {
        if (high <= low) throw new RangeError('high must be greater than low');
        const step = this.lsb();
        const toCode = (v) => Math.max(-2048, Math.min(2047, Math.round(v / step)));
        await this.setLowThresholdRaw(toCode(low));
        await this.setHighThresholdRaw(toCode(high));
    }

    /**
     * Read both thresholds back.
     * @returns {Promise<[number, number]>} [low, high] as sign-extended 12-bit codes.
     */
    async getThresholdsRaw() {
        const lo = toSigned(await this._readReg(_REG_LO_THRESH), 16) >> 4;
        const hi = toSigned(await this._readReg(_REG_HI_THRESH), 16) >> 4;
        return [lo, hi];
    }

    /**
     * Release a latched comparator output by reading CONVERSION.
     * @returns {Promise<void>}
     */
    async clearAlert() {
        await this._readReg(_REG_CONVERSION);
    }

    // -- Interrupt API (Level 2) --------------------------------------------

    /**
     * Route one source to ALERT/RDY; the other is disabled.
     * SOURCE_CONVERSION_READY writes HI_THRESH = 0x8000 and LO_THRESH = 0x0000
     * and sets COMP_QUE = 00. SOURCE_COMPARATOR restores the thresholds last
     * set through the driver and the configured queue.
     * @param {number} source - SOURCE_CONVERSION_READY or SOURCE_COMPARATOR.
     * @returns {Promise<void>}
     * @throws {RangeError} For an unknown source, or for SOURCE_COMPARATOR when the thresholds set through the driver have high ≤ low.
     */
    async enableInterrupt(source) {
        if (source === SOURCE_CONVERSION_READY) {
            await this._writeReg(_REG_HI_THRESH, 0x8000);
            await this._writeReg(_REG_LO_THRESH, 0x0000);
        } else if (source === SOURCE_COMPARATOR) {
            if (this._hiRaw <= this._loRaw) {
                throw new RangeError('set thresholds (high > low) before enabling the comparator');
            }
            await this._writeReg(_REG_LO_THRESH, (this._loRaw & 0x0FFF) << 4);
            await this._writeReg(_REG_HI_THRESH, (this._hiRaw & 0x0FFF) << 4);
        } else {
            throw new RangeError('unknown interrupt source');
        }
        this._source = source;
        await this._writeConfig();
    }

    /**
     * Release ALERT/RDY (COMP_QUE = 11). The two sources share the pin, so it
     * is released whichever source is given.
     * @param {number} _source - SOURCE_CONVERSION_READY or SOURCE_COMPARATOR.
     * @returns {Promise<void>}
     */
    async disableInterrupt(_source) {
        this._source = 0;
        await this._writeConfig();
    }

    /**
     * Report whether the enabled source is asserted. The chip has no
     * interrupt-status register: with an InputPin on the connection, the
     * ALERT/RDY level is sampled against COMP_POL (and a latched comparator
     * alert is cleared by reading CONVERSION). Without a pin,
     * SOURCE_CONVERSION_READY falls back to CONFIG.OS (single-shot only) and
     * SOURCE_COMPARATOR returns 0.
     * @returns {Promise<number>} The enabled SOURCE_* constant if asserted, else 0.
     */
    async pollInterrupt() {
        if (this._source === 0) return 0;
        const pin = this._conn.intPin;
        if (pin) {
            const asserted = pin.isHigh() === (this._compPol === 1);
            if (!asserted) return 0;
            if (this._source === SOURCE_COMPARATOR && this._compLat) await this.clearAlert();
            return this._source;
        }
        if (this._source === SOURCE_CONVERSION_READY && !this._continuous &&
            ((await this._readReg(_REG_CONFIG)) & _OS)) {
            return SOURCE_CONVERSION_READY;
        }
        return 0;
    }

    /**
     * Subscribe to ALERT/RDY events. With `connection.intPin` the callback
     * runs on every asserting edge (falling for ACTIVE_LOW, rising for
     * ACTIVE_HIGH). Otherwise a 5 ms polling loop calls back whenever
     * pollInterrupt() changes. Call enableInterrupt() first.
     * @param {function(number): void} callback - Receives the pollInterrupt() value.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) {
        this._callback = callback;
        if (this._conn.intPin) {
            this._edgeHandler = async () => {
                const status = await this.pollInterrupt();
                if (status && this._callback) this._callback(status);
            };
            await this._conn.intPin.onEdge(this._edgeHandler, this._compPol === 1 ? 'rising' : 'falling');
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

/** Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
ADS1014Minimal.I2C_ADDRESS = 0x48;

/** PGA full-scale settings. */
ADS1014Full.PGA_6_144 = 0;
ADS1014Full.PGA_4_096 = 1;
ADS1014Full.PGA_2_048 = 2;
ADS1014Full.PGA_1_024 = 3;
ADS1014Full.PGA_0_512 = 4;
ADS1014Full.PGA_0_256 = 5;
/** Data-rate codes. */
ADS1014Full.DR_128 = 0;
ADS1014Full.DR_250 = 1;
ADS1014Full.DR_490 = 2;
ADS1014Full.DR_920 = 3;
ADS1014Full.DR_1600 = 4;
ADS1014Full.DR_2400 = 5;
ADS1014Full.DR_3300 = 6;
/** Comparator mode, polarity and queue settings. */
ADS1014Full.COMP_TRADITIONAL = 0;
ADS1014Full.COMP_WINDOW = 1;
ADS1014Full.ACTIVE_LOW = 0;
ADS1014Full.ACTIVE_HIGH = 1;
ADS1014Full.QUEUE_1 = 0;
ADS1014Full.QUEUE_2 = 1;
ADS1014Full.QUEUE_4 = 2;
/** Interrupt sources — only one can drive ALERT/RDY at a time. */
ADS1014Full.SOURCE_CONVERSION_READY = SOURCE_CONVERSION_READY;
ADS1014Full.SOURCE_COMPARATOR = SOURCE_COMPARATOR;

module.exports = { ADS1014Minimal, ADS1014Full };
