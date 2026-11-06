'use strict';

const { ADS111xBase, RATES, REG_CONFIG, SOURCE_CONVERSION_READY, SOURCE_COMPARATOR } = require('./_ads111x_base');

/**
 * ADS1113 16-bit ΔΣ ADC (one differential input (AIN0 − AIN1), fixed ±2.048 V) — minimal interface.
 *
 * Texas Instruments I²C ADC, four addresses (0x48–0x4B) via the ADDR strap,
 * no ID register. Construction performs no I²C traffic; every conversion
 * writes the full Config word (single-shot, ±2.048 V, 128 SPS defaults) and
 * leaves the chip in power-down. Registers are 16-bit big-endian behind a
 * write-only Address Pointer; the implementation is shared with its siblings
 * in `_ads111x_base`.
 */
class ADS1113Minimal extends ADS111xBase {
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
 * ADS1113 full interface — extends Minimal with data rate, continuous conversion, status and reset.
 */
class ADS1113Full extends ADS1113Minimal {
    /**
     * Run a single-shot conversion and return volts at the fixed ±2.048 V range.
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
    async reset() { await this._reset(false); }
}

/** Default 7-bit I²C address (ADDR = GND). Valid range 0x48–0x4B. */
ADS1113Minimal.I2C_ADDRESS = 0x48;

/** Data-rate codes (DR[2:0]). */
Object.assign(ADS1113Full, { DR_8: 0, DR_16: 1, DR_32: 2, DR_64: 3, DR_128: 4, DR_250: 5, DR_475: 6, DR_860: 7 });

module.exports = { ADS1113Minimal, ADS1113Full };
