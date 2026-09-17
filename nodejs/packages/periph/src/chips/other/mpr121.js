'use strict';

const _REG_ELE0_7_TOUCH  = 0x00;
const _REG_ELE8_PROX_TCH = 0x01;
const _REG_ELE0_7_OOR    = 0x02;
const _REG_MHDR          = 0x2B;
const _REG_NHDR          = 0x2C;
const _REG_MHDF          = 0x2F;
const _REG_NHDF          = 0x30;
const _REG_E0TTH         = 0x41;
const _REG_E0RTH         = 0x42;
const _REG_EPROXTTH      = 0x59;
const _REG_EPROXRTH      = 0x5A;
const _REG_DEBOUNCE      = 0x5B;
const _REG_CDC_CONFIG    = 0x5C;
const _REG_CDT_CONFIG    = 0x5D;
const _REG_ECR           = 0x5E;
const _REG_AUTOCONFIG0   = 0x7B;
const _REG_AUTOCONFIG1   = 0x7C;
const _REG_USL           = 0x7D;
const _REG_LSL           = 0x7E;
const _REG_TL            = 0x7F;
const _REG_SRST          = 0x80;

const _SOFT_RESET_KEY      = 0x63;
const _TOUCH_DEFAULT       = 12;
const _RELEASE_DEFAULT     = 6;
const _CDC_CONFIG_DEFAULT  = 0x10;
const _CDT_CONFIG_DEFAULT  = 0x24;
const _AUTOCONFIG0_DEFAULT = 0x0B;
const _ECR_DEFAULT         = 0x8C;
const _USL_3V3 = 0xC9;
const _TL_3V3  = 0xB4;
const _LSL_3V3 = 0x82;

function _sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

/**
 * MPR121 proximity capacitive touch sensor controller — minimal interface.
 *
 * Provides 12-electrode touch/release detection with no configuration
 * beyond the connection. Performs soft reset, applies default
 * touch/release thresholds, enables the chip's automatic CDC/CDT
 * configuration, and enters Run Mode on all 12 electrodes at
 * construction.
 *
 * Default configuration (written at construction):
 * - Touch threshold = 12 for each of ELE0..ELE11
 * - Release threshold = 6 for each of ELE0..ELE11
 * - MHDR = NHDR = MHDF = NHDF = 1
 * - CDC_CONFIG = 0x10 (16 µA global CDC; FFI = 6 samples)
 * - CDT_CONFIG = 0x24 (CDT = 1 µS, ESI = 16 ms sample interval)
 * - AUTOCONFIG0 = 0x0B
 * - USL = 0xC9, TL = 0xB4, LSL = 0x82 (3.3 V VDD)
 * - ECR = 0x8C (all 12 electrodes enabled)
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device (address 0x5A by default).
 */
class MPR121Minimal {
    constructor(connection) {
        this._conn = connection;
        this._init();
    }

    async _init() {
        await this._reset();
        await this._writeReg(_REG_MHDR, 0x01);
        await this._writeReg(_REG_NHDR, 0x01);
        await this._writeReg(_REG_MHDF, 0x01);
        await this._writeReg(_REG_NHDF, 0x01);
        await this._writeReg(_REG_CDC_CONFIG, _CDC_CONFIG_DEFAULT);
        await this._writeReg(_REG_CDT_CONFIG, _CDT_CONFIG_DEFAULT);
        await this._writeReg(_REG_USL, _USL_3V3);
        await this._writeReg(_REG_TL,  _TL_3V3);
        await this._writeReg(_REG_LSL, _LSL_3V3);
        await this._writeReg(_REG_AUTOCONFIG0, _AUTOCONFIG0_DEFAULT);
        for (let n = 0; n < 12; n++) {
            await this._writeReg(_REG_E0TTH + 2 * n, _TOUCH_DEFAULT);
            await this._writeReg(_REG_E0RTH + 2 * n, _RELEASE_DEFAULT);
        }
        await this._writeReg(_REG_ECR, _ECR_DEFAULT);
    }

    async _reset() {
        await this._writeReg(_REG_SRST, _SOFT_RESET_KEY);
        _sleep(1);
    }

    async _writeReg(reg, value) {
        await this._conn.write(Buffer.from([reg & 0xFF, value & 0xFF]));
    }

    async _readReg(reg) {
        return (await this._conn.writeRead(Buffer.from([reg & 0xFF]), 1))[0];
    }

    async _readReg16(reg) {
        const buf = await this._conn.writeRead(Buffer.from([reg & 0xFF]), 2);
        return (buf[0]) | ((buf[1] & 0x03) << 8);
    }

    /**
     * Read the 12-bit electrode touch bitmask.
     *
     * Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte
     * snapshot from register 0x00; ELEPROX is masked out.
     *
     * @returns {Promise<number>} 12-bit bitmask; bit n = 1 if ELEn is touched.
     */
    async touched() {
        const buf = await this._conn.writeRead(Buffer.from([_REG_ELE0_7_TOUCH]), 2);
        return buf[0] | ((buf[1] & 0x0F) << 8);
    }

    /**
     * Check whether a single electrode is currently touched.
     * @param {number} electrode Electrode index 0-11.
     * @returns {Promise<boolean>} True if ELE_electrode is currently touched.
     */
    async isTouched(electrode) {
        if (electrode < 0 || electrode > 11) throw new Error('electrode must be in 0..11');
        return ((await this.touched()) & (1 << electrode)) !== 0;
    }
}

/**
 * MPR121 full interface — extends MPR121Minimal.
 *
 * Adds explicit Stop/Run control, per-electrode and per-proximity
 * threshold configuration, filtered-data and baseline access, baseline
 * filter and AFE (sampling) configuration, debounce, autoconfig
 * recomputation, OOR status, and over-current flag clear.
 */
class MPR121Full extends MPR121Minimal {
    static get SOURCE_OOR() { return 0x04; }
    static get SOURCE_ARF() { return 0x02; }
    static get SOURCE_ACF() { return 0x01; }

    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I2C connection pointing at the device.
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Software-reset the chip and re-apply Minimal defaults.
     * @returns {Promise<void>}
     */
    async reset() {
        await this._reset();
        await this._writeReg(_REG_MHDR, 0x01);
        await this._writeReg(_REG_NHDR, 0x01);
        await this._writeReg(_REG_MHDF, 0x01);
        await this._writeReg(_REG_NHDF, 0x01);
        await this._writeReg(_REG_CDC_CONFIG, _CDC_CONFIG_DEFAULT);
        await this._writeReg(_REG_CDT_CONFIG, _CDT_CONFIG_DEFAULT);
        await this._writeReg(_REG_USL, _USL_3V3);
        await this._writeReg(_REG_TL,  _TL_3V3);
        await this._writeReg(_REG_LSL, _LSL_3V3);
        await this._writeReg(_REG_AUTOCONFIG0, _AUTOCONFIG0_DEFAULT);
        for (let n = 0; n < 12; n++) {
            await this._writeReg(_REG_E0TTH + 2 * n, _TOUCH_DEFAULT);
            await this._writeReg(_REG_E0RTH + 2 * n, _RELEASE_DEFAULT);
        }
        await this._writeReg(_REG_ECR, _ECR_DEFAULT);
    }

    /**
     * Enter Stop Mode (ECR=0x00).
     * @returns {Promise<void>}
     */
    async stop() {
        await this._writeReg(_REG_ECR, 0x00);
    }

    /**
     * Enter Run Mode with the given electrode configuration.
     * @param {number} [nElectrodes=12] Number of electrodes 1-12.
     * @param {number} [cl=2] Calibration lock / baseline init 0-3.
     * @param {number} [eleproxEn=0] Proximity enable 0-3.
     * @returns {Promise<void>}
     */
    async start(nElectrodes = 12, cl = 2, eleproxEn = 0) {
        if (nElectrodes < 1 || nElectrodes > 12) throw new Error('n_electrodes must be in 1..12');
        if (cl < 0 || cl > 3) throw new Error('cl must be in 0..3');
        if (eleproxEn < 0 || eleproxEn > 3) throw new Error('eleprox_en must be in 0..3');
        const ecr = ((cl & 0x03) << 6) | ((eleproxEn & 0x03) << 4) | (nElectrodes & 0x0F);
        await this._writeReg(_REG_ECR, ecr);
    }

    /**
     * Set touch and release thresholds for a single electrode.
     * @param {number} electrode Electrode index 0-11.
     * @param {number} touch Touch threshold 0-255.
     * @param {number} release Release threshold 0-255.
     * @returns {Promise<void>}
     */
    async configureThresholds(electrode, touch, release) {
        if (electrode < 0 || electrode > 11) throw new Error('electrode must be in 0..11');
        await this._writeReg(_REG_E0TTH + 2 * electrode, touch & 0xFF);
        await this._writeReg(_REG_E0RTH + 2 * electrode, release & 0xFF);
    }

    /**
     * Apply the same touch and release thresholds to all 12 electrodes.
     * @param {number} touch Touch threshold 0-255.
     * @param {number} release Release threshold 0-255.
     * @returns {Promise<void>}
     */
    async configureAllThresholds(touch, release) {
        for (let n = 0; n < 12; n++) {
            await this.configureThresholds(n, touch, release);
        }
    }

    /**
     * Set ELEPROX touch and release thresholds.
     * @param {number} touch ELEPROX touch threshold 0-255.
     * @param {number} release ELEPROX release threshold 0-255.
     * @returns {Promise<void>}
     */
    async configureProximityThresholds(touch, release) {
        await this._writeReg(_REG_EPROXTTH, touch & 0xFF);
        await this._writeReg(_REG_EPROXRTH, release & 0xFF);
    }

    /**
     * Read the 10-bit filtered capacitance data for an electrode.
     * @param {number} electrode 0-11 for ELE0-ELE11, 12 for ELEPROX.
     * @returns {Promise<number>} 10-bit value.
     */
    async filtered(electrode) {
        if (electrode < 0 || electrode > 12) throw new Error('electrode must be in 0..12');
        const addr = (electrode === 12) ? 0x1C : (0x04 + 2 * electrode);
        return this._readReg16(addr);
    }

    /**
     * Read the 10-bit baseline for an electrode.
     * @param {number} electrode 0-11 for ELE0-ELE11, 12 for ELEPROX.
     * @returns {Promise<number>} 10-bit baseline value.
     */
    async baseline(electrode) {
        if (electrode < 0 || electrode > 12) throw new Error('electrode must be in 0..12');
        const addr = (electrode === 12) ? 0x2A : (0x1E + electrode);
        return (await this._readReg(addr)) << 2;
    }

    /**
     * Write a baseline value (Stop Mode only).
     * @param {number} electrode 0-11 for ELE0-ELE11, 12 for ELEPROX.
     * @param {number} value 10-bit value 0-1023.
     * @returns {Promise<void>}
     */
    async setBaseline(electrode, value) {
        if (electrode < 0 || electrode > 12) throw new Error('electrode must be in 0..12');
        const addr = (electrode === 12) ? 0x2A : (0x1E + electrode);
        await this._writeReg(addr, (value >> 2) & 0xFF);
    }

    /**
     * Read the 13-bit out-of-range bitmask.
     * @returns {Promise<number>} bits 0-11 = ELE0-ELE11 OOR, bit 12 = ELEPROX OOR.
     */
    async oorStatus() {
        const buf = await this._conn.writeRead(Buffer.from([_REG_ELE0_7_OOR]), 2);
        return buf[0] | ((buf[1] & 0x1F) << 8);
    }

    /**
     * Set the global baseline filter parameters (Stop Mode).
     * @returns {Promise<void>}
     */
    async configureBaselineFilter(mhdr, nhdr, nclr, fdlr, mhdf, nhdf, nclf, fdlf, nhdt, nclt, fdlt) {
        await this._writeReg(_REG_MHDR, mhdr & 0x3F);
        await this._writeReg(_REG_NHDR, nhdr & 0x3F);
        await this._writeReg(0x2D, nclr & 0xFF);
        await this._writeReg(0x2E, fdlr & 0xFF);
        await this._writeReg(_REG_MHDF, mhdf & 0x3F);
        await this._writeReg(_REG_NHDF, nhdf & 0x3F);
        await this._writeReg(0x31, nclf & 0xFF);
        await this._writeReg(0x32, fdlf & 0xFF);
        await this._writeReg(0x33, nhdt & 0x3F);
        await this._writeReg(0x34, nclt & 0xFF);
        await this._writeReg(0x35, fdlt & 0xFF);
    }

    /**
     * Set global AFE (sampling) configuration (Stop Mode).
     * @returns {Promise<void>}
     */
    async configureSampling(cdc, cdt, ffi, sfi, esi) {
        const cdcCfg = ((ffi & 0x03) << 6) | (cdc & 0x3F);
        const cdtCfg = ((cdt & 0x07) << 5) | ((sfi & 0x03) << 2) | (esi & 0x07);
        await this._writeReg(_REG_CDC_CONFIG, cdcCfg);
        await this._writeReg(_REG_CDT_CONFIG, cdtCfg);
    }

    /**
     * Set debounce counts (Stop Mode).
     * @param {number} touch Debounce count for touch 0-7.
     * @param {number} release Debounce count for release 0-7.
     * @returns {Promise<void>}
     */
    async configureDebounce(touch, release) {
        const deb = ((release & 0x07) << 4) | (touch & 0x07);
        await this._writeReg(_REG_DEBOUNCE, deb);
    }

    /**
     * Compute USL/TL/LSL from VDD and write autoconfig registers (Stop Mode).
     * @param {number} [vddMv=3300] VDD supply in millivolts.
     * @param {number} [retry=0] Auto-retry on autoconfig failure 0-3.
     * @param {boolean} [scts=false] Skip Charge Time Search.
     * @param {boolean} [are=true] Auto-Reconfiguration Enable.
     * @param {boolean} [ace=true] Auto-Configuration Enable.
     * @returns {Promise<void>}
     */
    async configureAutoconfig(vddMv = 3300, retry = 0, scts = false, are = true, ace = true) {
        const usl = Math.floor(((vddMv - 700) * 256) / vddMv);
        const tl  = Math.floor(usl * 0.9);
        const lsl = Math.floor(usl * 0.65);
        await this._writeReg(_REG_USL, usl & 0xFF);
        await this._writeReg(_REG_TL,  tl & 0xFF);
        await this._writeReg(_REG_LSL, lsl & 0xFF);
        const ffi = (await this._readReg(_REG_CDC_CONFIG) >> 6) & 0x03;
        const autoconfig0 = ((ffi & 0x03) << 6) | ((retry & 0x03) << 4) | (are ? 0x08 : 0) | (ace ? 0x01 : 0);
        await this._writeReg(_REG_AUTOCONFIG0, autoconfig0);
        const autoconfig1 = scts ? 0x80 : 0x00;
        await this._writeReg(_REG_AUTOCONFIG1, autoconfig1);
    }

    /**
     * Return True if the ELEPROX virtual electrode is touched.
     * @returns {Promise<boolean>}
     */
    async proximityTouched() {
        return ((await this._readReg(_REG_ELE8_PROX_TCH)) & 0x10) !== 0;
    }

    /**
     * Clear the OVCF bit in register 0x01.
     * @returns {Promise<void>}
     */
    async clearOvercurrent() {
        const raw = await this._readReg(_REG_ELE8_PROX_TCH);
        await this._writeReg(_REG_ELE8_PROX_TCH, raw & 0x7F);
    }

    /**
     * Enable one of the AUTOCONFIG1-based interrupt sources.
     * @param {number} source One of MPR121Full.SOURCE_OOR, SOURCE_ARF, SOURCE_ACF.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        const cur = (await this._readReg(_REG_AUTOCONFIG1)) & 0x00;
        await this._writeReg(_REG_AUTOCONFIG1, cur | (source & 0x07));
    }

    /**
     * Disable one of the AUTOCONFIG1-based interrupt sources.
     * @param {number} source One of MPR121Full.SOURCE_OOR, SOURCE_ARF, SOURCE_ACF.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        const cur = await this._readReg(_REG_AUTOCONFIG1);
        await this._writeReg(_REG_AUTOCONFIG1, cur & ~(source & 0x07));
    }
}

module.exports = { MPR121Minimal, MPR121Full };
