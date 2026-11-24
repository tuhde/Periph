'use strict';

const _CMD_RESET = 0x1E;
const _CMD_CONVERT_D1 = 0x40;
const _CMD_CONVERT_D2 = 0x50;
const _CMD_ADC_READ = 0x00;
const _CMD_PROM_READ = 0xA0;
const _OSR_VALUES = [256, 512, 1024, 2048, 4096];
// maximum conversion time per OSR (s), datasheet
const _T_CONV = [0.00060, 0.00117, 0.00228, 0.00454, 0.00904];
const _MARGIN_MS = 1;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-4 of the MS5607 PROM (application note AN520): polynomial x^4 + x^3 + 1 over the
 * 16 bytes of the eight PROM words, MSB first, with the low byte of word 7 treated as zero.
 * @param {number[]} words - The eight 16-bit PROM words.
 * @returns {number} The 4-bit CRC.
 */
function crc4(words) {
    const prom = words.slice();
    prom[7] &= 0xFF00;
    let rem = 0;
    for (let cnt = 0; cnt < 16; cnt++) {
        rem ^= (cnt & 1) ? (prom[cnt >> 1] & 0x00FF) : (prom[cnt >> 1] >> 8);
        for (let b = 0; b < 8; b++) rem = (rem & 0x8000) ? ((rem << 1) ^ 0x3000) & 0xFFFF : (rem << 1) & 0xFFFF;
    }
    return (rem >> 12) & 0x0F;
}

// Integer division truncating toward zero (BigInt division already does).
const _big = BigInt;

/**
 * MS5607 barometric pressure sensor — minimal interface.
 *
 * `init()` resets the chip, reads the eight PROM words and verifies the CRC-4. Each `read()`
 * converts the temperature (D2) and then the pressure (D1) at oversampling ratio 4096 (waiting
 * 10 ms each) and applies the first- and second-order compensation. Pressure is in pascals
 * (1 Pa = 0.01 mbar, the chip's native unit).
 *
 * The device is command driven (no register pointer), so a plain I²C connection is used.
 * The constructor does not touch the bus.
 */
class MS5607Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x77, or 0x76 with CSB high).
     */
    constructor(connection) {
        this._conn = connection;
        this._osrIndex = 4;
        this._prom = [0, 0, 0, 0, 0, 0, 0, 0];
    }

    async _readPromWords() {
        const words = [];
        for (let n = 0; n < 8; n++) {
            await this._conn.write(Buffer.from([_CMD_PROM_READ + 2 * n]));
            const data = Buffer.from(await this._conn.read(2));
            words.push((data[0] << 8) | data[1]);
        }
        return words;
    }

    async _loadProm() {
        const words = await this._readPromWords();
        if (crc4(words) !== (words[7] & 0x0F)) throw new Error('MS5607 PROM CRC mismatch');
        this._prom = words;
    }

    async _readAdc() {
        await this._conn.write(Buffer.from([_CMD_ADC_READ]));
        const data = Buffer.from(await this._conn.read(3));
        const value = (data[0] << 16) | (data[1] << 8) | data[2];
        if (value === 0) throw new Error('MS5607 ADC returned 0 (conversion not finished or not started)');
        return value;
    }

    async _convert(kind) {
        await this._conn.write(Buffer.from([(kind ? _CMD_CONVERT_D2 : _CMD_CONVERT_D1) | (this._osrIndex << 1)]));
        await _sleep(Math.ceil(_T_CONV[this._osrIndex] * 1000) + _MARGIN_MS);
        return this._readAdc();
    }

    _temperatureTerms(d2) {
        const dt = _big(d2) - _big(this._prom[5]) * 256n;
        const temp = 2000n + (dt * _big(this._prom[6])) / 8388608n;
        return [dt, temp];
    }

    _secondOrder(dt, temp) {
        let t2 = 0n, off2 = 0n, sens2 = 0n;
        if (temp < 2000n) {
            const d = temp - 2000n;
            t2 = (dt * dt) / 2147483648n;
            off2 = (61n * d * d) / 16n;
            sens2 = 2n * d * d;
            if (temp < -1500n) {
                const e = temp + 1500n;
                off2 += 15n * e * e;
                sens2 += 8n * e * e;
            }
        }
        return [t2, off2, sens2];
    }

    _compensate(d1, d2, secondOrder = true) {
        const c = this._prom;
        const [dt, t0] = this._temperatureTerms(d2);
        let temp = t0;
        let off = _big(c[2]) * 131072n + (_big(c[4]) * dt) / 64n;
        let sens = _big(c[1]) * 65536n + (_big(c[3]) * dt) / 128n;
        if (secondOrder) {
            const [t2, off2, sens2] = this._secondOrder(dt, temp);
            temp -= t2;
            off -= off2;
            sens -= sens2;
        }
        const p = ((_big(d1) * sens) / 2097152n - off) / 32768n;
        return { temperature_c: Number(temp) / 100.0, pressure_pa: Number(p) };
    }

    /**
     * Reset (0x1E), wait 3 ms, read the PROM and verify its CRC-4.
     * @throws {Error} If the PROM CRC does not match.
     */
    async init() {
        await this._conn.write(Buffer.from([_CMD_RESET]));
        await _sleep(3);
        await this._loadProm();
    }

    /**
     * Measure temperature and pressure (OSR 4096, second-order compensation).
     * @returns {Promise<{temperature_c: number, pressure_pa: number}>} °C and Pa
     */
    async read() {
        const d2 = await this._convert(1);
        const d1 = await this._convert(0);
        return this._compensate(d1, d2);
    }
}

/**
 * MS5607 full interface — single values, raw codes, oversampling, non-blocking conversion, PROM access, altitude.
 *
 * Extends MS5607Minimal with single values, raw codes, the oversampling ratio, explicit
 * conversion start / ADC read, pure compensation, reset, PROM and coefficient access and the
 * barometric altitude.
 */
class MS5607Full extends MS5607Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x77, or 0x76 with CSB high).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Convert D2 and D1 at the current OSR and return the raw ADC codes.
     * @returns {Promise<number[]>} [d1, d2], both 24-bit
     */
    async readRaw() {
        const d2 = await this._convert(1);
        const d1 = await this._convert(0);
        return [d1, d2];
    }

    /**
     * Measure and return the compensated pressure.
     * @returns {Promise<number>} Pressure in pascals
     */
    async readPressure() {
        return (await this.read()).pressure_pa;
    }

    /**
     * Convert D2 only and return the temperature (with the low-temperature correction).
     * @returns {Promise<number>} Temperature in degrees Celsius
     */
    async readTemperature() {
        const [dt, temp] = this._temperatureTerms(await this._convert(1));
        return Number(temp - this._secondOrder(dt, temp)[0]) / 100.0;
    }

    /**
     * Set the oversampling ratio used for both conversions.
     * @param {number} osr - 256, 512, 1024, 2048 or 4096.
     * @throws {Error} For any other value.
     */
    setOversampling(osr) {
        const i = _OSR_VALUES.indexOf(osr);
        if (i < 0) throw new Error('OSR must be 256, 512, 1024, 2048 or 4096');
        this._osrIndex = i;
    }

    /** @returns {number} The current oversampling ratio (256 ... 4096). */
    getOversampling() {
        return _OSR_VALUES[this._osrIndex];
    }

    /**
     * Maximum conversion time for an oversampling ratio.
     * @param {number} [osr] - 256 ... 4096; the current ratio if omitted.
     * @returns {number} Seconds (0.60 / 1.17 / 2.28 / 4.54 / 9.04 ms)
     * @throws {Error} For an invalid ratio.
     */
    conversionTime(osr) {
        if (osr === undefined || osr === null) return _T_CONV[this._osrIndex];
        const i = _OSR_VALUES.indexOf(osr);
        if (i < 0) throw new Error('OSR must be 256, 512, 1024, 2048 or 4096');
        return _T_CONV[i];
    }

    /**
     * Write a conversion command without waiting (non-blocking use); wait at least
     * `conversionTime()` before `readAdc()`.
     * @param {number} kind - MS5607Full.PRESSURE (D1) or MS5607Full.TEMPERATURE (D2).
     * @param {number} [osr] - Oversampling ratio; the current one if omitted.
     */
    async startConversion(kind, osr) {
        let i = this._osrIndex;
        if (osr !== undefined && osr !== null) {
            i = _OSR_VALUES.indexOf(osr);
            if (i < 0) throw new Error('OSR must be 256, 512, 1024, 2048 or 4096');
        }
        await this._conn.write(Buffer.from([(kind === MS5607Full.TEMPERATURE ? _CMD_CONVERT_D2 : _CMD_CONVERT_D1) | (i << 1)]));
    }

    /**
     * ADC read command plus 3 bytes: the result of the last conversion. Only valid after the
     * conversion time; a read during the conversion or a repeated read returns 0.
     * @returns {Promise<number>} 24-bit result
     * @throws {Error} If the chip returns 0.
     */
    async readAdc() {
        return this._readAdc();
    }

    /**
     * Pure computation with the stored coefficients (no bus access).
     * @param {number} d1 - 24-bit raw pressure.
     * @param {number} d2 - 24-bit raw temperature.
     * @param {boolean} [secondOrder=true] - false for the first-order result only.
     * @returns {{temperature_c: number, pressure_pa: number}}
     */
    compensate(d1, d2, secondOrder = true) {
        return this._compensate(d1, d2, secondOrder);
    }

    /** Reset (0x1E), wait 3 ms and reload and verify the PROM. */
    async reset() {
        await this.init();
    }

    /** @returns {Promise<number[]>} The eight PROM words read from the chip. */
    async readProm() {
        return this._readPromWords();
    }

    /** @returns {number[]} The calibration coefficients [c1, ..., c6]. */
    coefficients() {
        return this._prom.slice(1, 7);
    }

    /** @returns {boolean} True if the CRC-4 of the PROM words read at init matches (no bus access). */
    promCrcOk() {
        return crc4(this._prom) === (this._prom[7] & 0x0F);
    }

    /** @returns {number} The serial code (PROM word 7, bits 15:4). */
    serialCode() {
        return (this._prom[7] >> 4) & 0x0FFF;
    }

    /**
     * Altitude from one pressure reading (international barometric formula).
     * @param {number} [seaLevelPa=101325] - Reference sea-level pressure in pascals.
     * @returns {Promise<number>} Altitude in metres
     */
    async altitude(seaLevelPa = 101325.0) {
        return 44330.0 * (1.0 - Math.pow((await this.readPressure()) / seaLevelPa, 1.0 / 5.255));
    }
}

MS5607Full.PRESSURE = 0;
MS5607Full.TEMPERATURE = 1;

module.exports = { MS5607Minimal, MS5607Full, crc4 };
