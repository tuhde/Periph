'use strict';

const { ADS111xBase, RATES, REG_CONFIG, SOURCE_CONVERSION_READY, SOURCE_COMPARATOR } = require('./_ads111x_base');

/**
 * ADS1114 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), PGA and comparator) — minimal interface.
 *
 * Texas Instruments I²C ADC, four addresses (0x48–0x4B) via the ADDR strap,
 * no ID register. Construction performs no I²C traffic; every conversion
 * writes the full Config word (single-shot, ±2.048 V, 128 SPS defaults) and
 * leaves the chip in power-down. Registers are 16-bit big-endian behind a
 * write-only Address Pointer; the implementation is shared with its siblings
 * in `_ads111x_base`.
 */
class ADS1114Minimal extends ADS111xBase {
    /**
     * Run a single-shot conversion of AIN0 − AIN1.
     * @returns {Promise<number>} Signed 16-bit code (−32768…32767).
     */
    async readRaw() {
        return this._convert(0);
    }

    /**
     * Run a single-shot conversion and return volts (raw × 62.5 µV at the default range).
     * @returns {Promise<number>} Voltage AIN0 − AIN1 in V.
     */
    async readVoltage() {
        return (await this.readRaw()) * 0.0000625;
    }
}

/**
 * ADS1114 full interface — extends Minimal with gain, data rate, continuous conversion, status and reset, the comparator, thresholds and the ALERT/RDY interrupt API.
 */
class ADS1114Full extends ADS1114Minimal {
    /**
     * Run a single-shot conversion and return volts at the current gain.
     * @returns {Promise<number>} Voltage AIN0 − AIN1 in V.
     */
    async readVoltage() {
        return (await this.readRaw()) * this._lsb();
    }

    /**
     * Start continuous conversion at the current data rate (MODE ← 0).
     * @returns {Promise<void>}
     */
    async startContinuous() {
        await this._startContinuous(0);
    }

    /**
     * Set the full-scale range used by all following reads. Thresholds are not
     * rescaled — rewrite them after a gain change.
     * @param {number} pga - PGA_6_144 … PGA_0_256 (0–5).
     * @returns {Promise<void>}
     * @throws {RangeError} If pga is outside 0–5 (6 and 7 alias ±0.256 V).
     */
    async setGain(pga) {
        await this._setGain(pga);
    }

    /** @returns {number} The configured PGA code (0–5). */
    getGain() { return this._pga; }

    /** @returns {number} ±FSR of the current gain in volts. */
    fullScale() { return this._fullScale(); }

    /** @returns {number} Size of one code in volts (fullScale() / 32768). */
    lsb() { return this._lsb(); }

    /**
     * Set the data rate used by all following conversions.
     * @param {number} dr - DR_8 … DR_860 (0–7).
     * @returns {Promise<void>}
     * @throws {RangeError} If dr is outside 0–7.
     */
    async setDataRate(dr) {
        await this._setDataRate(dr);
    }

    /** @returns {number} The configured DR code (0–7). */
    getDataRate() { return this._dr; }

    /** @returns {number} The configured data rate in samples per second. */
    dataRateSps() { return RATES[this._dr]; }

    /**
     * Read the most recent completed result without touching Config.
     * @returns {Promise<number>} Signed 16-bit code.
     */
    async readContinuousRaw() { return this._readContinuousRaw(); }

    /**
     * Read the most recent continuous result in volts.
     * @returns {Promise<number>} Voltage in V.
     */
    async readContinuous() { return (await this._readContinuousRaw()) * this._lsb(); }

    /**
     * Return to single-shot / power-down mode (MODE ← 1).
     * @returns {Promise<void>}
     */
    async stopContinuous() { await this._stopContinuous(); }

    /** @returns {Promise<boolean>} True while CONFIG.OS reads 0 (single-shot mode only). */
    async isConverting() { return this._isConverting(); }

    /** @returns {Promise<number>} The raw 16-bit CONFIG register. */
    async readConfig() { return this._readReg(REG_CONFIG); }

    /**
     * Write the reset values back and clear cached settings. Not an I²C general-call reset.
     * @returns {Promise<void>}
     */
    async reset() { await this._reset(true); }

    /**
     * Configure the comparator and route it to ALERT/RDY.
     * @param {number} [mode=COMP_TRADITIONAL] - COMP_TRADITIONAL or COMP_WINDOW.
     * @param {number} [polarity=ACTIVE_LOW] - ACTIVE_LOW or ACTIVE_HIGH.
     * @param {boolean} [latch=false] - True to latch until clearAlert().
     * @param {number} [queue=QUEUE_1] - QUEUE_1, QUEUE_2 or QUEUE_4 consecutive out-of-range samples.
     * @returns {Promise<void>}
     * @throws {RangeError} On an invalid argument or when the last thresholds have high <= low.
     */
    async configureComparator(mode = 0, polarity = 0, latch = false, queue = 0) {
        await this._configureComparator(mode, polarity, latch, queue);
    }

    /** Set COMP_QUE to 11; ALERT/RDY becomes high-impedance. @returns {Promise<void>} */
    async disableComparator() { await this._disableComparator(); }

    /** Write LO_THRESH (signed code, same scale as CONVERSION). @param {number} raw @returns {Promise<void>} */
    async setLowThresholdRaw(raw) { await this._setLowThresholdRaw(raw); }

    /** Write HI_THRESH (signed code, same scale as CONVERSION). @param {number} raw @returns {Promise<void>} */
    async setHighThresholdRaw(raw) { await this._setHighThresholdRaw(raw); }

    /**
     * Write both thresholds from volts at the current gain.
     * @param {number} low - Lower threshold in volts.
     * @param {number} high - Upper threshold in volts.
     * @returns {Promise<void>}
     * @throws {RangeError} If high <= low.
     */
    async setThresholds(low, high) { await this._setThresholds(low, high); }

    /** @returns {Promise<number[]>} [low, high] read back, sign-extended. */
    async getThresholdsRaw() { return this._getThresholdsRaw(); }

    /** Read CONVERSION to release a latched comparator output. @returns {Promise<void>} */
    async clearAlert() { await this._clearAlert(); }

    /**
     * Route one source to ALERT/RDY (the other is disabled).
     * @param {number} source - SOURCE_CONVERSION_READY or SOURCE_COMPARATOR.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) { await this._enableInterrupt(source); }

    /** Release ALERT/RDY (COMP_QUE ← 11). @param {number} source @returns {Promise<void>} */
    async disableInterrupt(source) { await this._disableComparator(); }

    /**
     * Return the enabled source constant if ALERT/RDY is asserted, else 0.
     * Samples `connection.intPin` when present; without a pin
     * SOURCE_CONVERSION_READY falls back to CONFIG.OS (single-shot only). A
     * latched comparator alert is cleared by reading CONVERSION.
     * @returns {Promise<number>}
     */
    async pollInterrupt() { return this._pollInterrupt(); }

    /**
     * Subscribe to ALERT/RDY events: `connection.intPin` edges when wired,
     * otherwise a 5 ms polling loop that calls back when the status changes.
     * @param {function(number): void} callback - Receives the pollInterrupt() value.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback) { await this._onInterrupt(callback); }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
    async offInterrupt() { await this._offInterrupt(); }
}

/** Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
ADS1114Minimal.I2C_ADDRESS = 0x48;

/** Data-rate codes (DR[2:0]). */
Object.assign(ADS1114Full, { DR_8: 0, DR_16: 1, DR_32: 2, DR_64: 3, DR_128: 4, DR_250: 5, DR_475: 6, DR_860: 7 });

/** Full-scale range codes (PGA[2:0]). */
Object.assign(ADS1114Full, { PGA_6_144: 0, PGA_4_096: 1, PGA_2_048: 2, PGA_1_024: 3, PGA_0_512: 4, PGA_0_256: 5 });

/** Comparator mode, polarity, queue and interrupt-source values. */
Object.assign(ADS1114Full, { COMP_TRADITIONAL: 0, COMP_WINDOW: 1, ACTIVE_LOW: 0, ACTIVE_HIGH: 1,
    QUEUE_1: 0, QUEUE_2: 1, QUEUE_4: 2,
    SOURCE_CONVERSION_READY, SOURCE_COMPARATOR });

module.exports = { ADS1114Minimal, ADS1114Full };
