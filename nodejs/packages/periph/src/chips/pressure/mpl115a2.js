'use strict';

const _REG_PADC = 0x00;
const _REG_COEF = 0x04;
const _REG_CONVERT = 0x12;

const _CONVERSION_MS = 5;
const _TEMP_COUNTS_PER_C = -5.35;
const _TEMP_REF_COUNTS = 498;
const _TEMP_REF_C = 25.0;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * MPL115A2 miniature I²C digital barometer (50-115 kPa) — minimal interface.
 *
 * Reads the compensated absolute pressure in pascals and an approximate temperature in degrees
 * Celsius. The constructor starts reading the four per-device compensation coefficients
 * (a0, b1, b2, c12); every bus method waits for that and `ready` exposes it. Each reading writes
 * CONVERT, waits 5 ms and reads the ADC registers. Fixed I²C address 0x60; no configuration
 * registers. Bus calls are serialised internally.
 */
class MPL115A2Minimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C register connection (address 0x60).
     */
    constructor(connection) {
        this._conn = connection;
        this._chain = Promise.resolve();
        this._ready = this._exec(() => this._loadCoefficients());
    }

    /** Wait for the coefficient read started by the constructor. @returns {Promise<void>} */
    get ready() { return this._ready; }

    _exec(fn) {
        const p = this._chain.then(fn);
        this._chain = p.catch(() => {});
        return p;
    }

    async _loadCoefficients() {
        const d = await this._conn.readReg(_REG_COEF, 8);
        this._a0 = d.readInt16BE(0) / 8;
        this._b1 = d.readInt16BE(2) / 8192;
        this._b2 = d.readInt16BE(4) / 16384;
        this._c12 = d.readInt16BE(6) / 16777216;
    }

    async _convert() {
        await this._conn.writeReg(_REG_CONVERT, Buffer.from([0x00]));
        await _sleep(_CONVERSION_MS);
        const d = await this._conn.readReg(_REG_PADC, 4);
        return { padc: d.readUInt16BE(0) >> 6, tadc: d.readUInt16BE(2) >> 6 };
    }

    _compensate(padc, tadc) {
        const pcomp = this._a0 + (this._b1 + this._c12 * tadc) * padc + this._b2 * tadc;
        return (pcomp * (115 - 50) / 1023 + 50) * 1000;
    }

    static _temperatureC(tadc) {
        return _TEMP_REF_C + (tadc - _TEMP_REF_COUNTS) / _TEMP_COUNTS_PER_C;
    }

    /**
     * Re-read the eight coefficient bytes and cache a0, b1, b2, c12 (e.g. after a power cycle).
     * @returns {Promise<void>}
     */
    reloadCoefficients() {
        return this._exec(() => this._loadCoefficients());
    }

    /**
     * Run one conversion and return the compensated pressure.
     * @returns {Promise<number>} Pressure in pascals.
     */
    readPressure() {
        return this._exec(async () => {
            await this._ready;
            const { padc, tadc } = await this._convert();
            return this._compensate(padc, tadc);
        });
    }

    /**
     * Run one conversion and return an approximate temperature (25 + (Tadc - 498) / -5.35;
     * the datasheet gives no formula, accurate to a few degrees — use for trends only).
     * @returns {Promise<number>} Approximate temperature in degrees Celsius.
     */
    readTemperature() {
        return this._exec(async () => {
            await this._ready;
            const { tadc } = await this._convert();
            return MPL115A2Minimal._temperatureC(tadc);
        });
    }
}

/**
 * MPL115A2 full interface — extends MPL115A2Minimal with a combined reading, raw ADC counts,
 * coefficient access, the pure compensation function, hPa and altitude helpers.
 */
class MPL115A2Full extends MPL115A2Minimal {
    /**
     * Run one conversion and return both values.
     * @returns {Promise<{pressure_pa: number, temperature_c: number}>}
     */
    read() {
        return this._exec(async () => {
            await this._ready;
            const { padc, tadc } = await this._convert();
            return { pressure_pa: this._compensate(padc, tadc), temperature_c: MPL115A2Minimal._temperatureC(tadc) };
        });
    }

    /**
     * Run one conversion and return the raw 10-bit ADC counts.
     * @returns {Promise<{padc: number, tadc: number}>} Counts 0-1023.
     */
    readRaw() {
        return this._exec(async () => {
            await this._ready;
            return this._convert();
        });
    }

    /**
     * Return the cached compensation coefficients.
     * @returns {Promise<{a0: number, b1: number, b2: number, c12: number}>}
     */
    coefficients() {
        return this._exec(async () => {
            await this._ready;
            return { a0: this._a0, b1: this._b1, b2: this._b2, c12: this._c12 };
        });
    }

    /**
     * Compute pressure from raw counts and the cached coefficients (no conversion).
     * @param {number} padc - 10-bit pressure ADC count.
     * @param {number} tadc - 10-bit temperature ADC count.
     * @returns {Promise<number>} Pressure in pascals.
     */
    compensate(padc, tadc) {
        return this._exec(async () => {
            await this._ready;
            return this._compensate(padc, tadc);
        });
    }

    /**
     * Run one conversion and return the pressure in hectopascals.
     * @returns {Promise<number>} Pressure in hPa.
     */
    async pressureHpa() {
        return (await this.readPressure()) / 100;
    }

    /**
     * Compute the altitude from a fresh pressure reading (barometric formula, informative).
     * @param {number} [seaLevelPa=101325] - Reference sea-level pressure in Pa.
     * @returns {Promise<number>} Altitude in metres.
     */
    async altitude(seaLevelPa = 101325.0) {
        const p = await this.readPressure();
        return 44330 * (1 - Math.pow(p / seaLevelPa, 1 / 5.255));
    }
}

module.exports = { MPL115A2Minimal, MPL115A2Full };
