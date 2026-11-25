'use strict';

const _CMD_SLEEP = 0xB098;
const _CMD_WAKE = 0x3517;
const _CMD_RESET = 0x805D;
const _CMD_READ_ID = 0xEFC8;
// measurement commands [lowPower][clockStretching], temperature first
const _CMD_MEASURE = [[0x7866, 0x7CA2], [0x609C, 0x6458]];
const _ID_MASK = 0x083F;
const _ID_VALUE = 0x0807;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * CRC-8 used by the SHTC3: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0xFF, no final XOR.
 * @param {Buffer|number[]} data
 * @returns {number}
 */
function crc8(data) {
    let crc = 0xFF;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

function _toHumidity(code) {
    return Math.min(100.0, Math.max(0.0, 100.0 * code / 65536.0));
}

function _toTemperature(code) {
    return -45.0 + 175.0 * code / 65536.0;
}

function _word(data, offset) {
    const chunk = data.subarray(offset, offset + 3);
    if (crc8(chunk.subarray(0, 2)) !== chunk[2]) throw new Error('SHTC3 CRC mismatch');
    return (chunk[0] << 8) | chunk[1];
}

/**
 * SHTC3 humidity and temperature sensor — minimal interface.
 *
 * Each `read()` runs a full measurement cycle: wake-up (0x3517), a normal-mode
 * temperature-first measurement without clock stretching (0x7866), a 13 ms wait,
 * a 6-byte read with both CRC-8 bytes verified, and the sleep command (0xB098).
 * A bad CRC is retried once before an error is thrown.
 *
 * The device is command driven (no register pointer), so a plain I²C connection is used.
 * The constructor does not touch the bus; `init()` performs the soft reset and the ID check.
 */
class SHTC3Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x70).
     */
    constructor(connection) {
        this._conn = connection;
        this._lowPower = false;
        this._stretching = false;
        this._sleepBetween = true;
        this._asleep = false;
    }

    async _command(code) {
        await this._conn.write(Buffer.from([(code >> 8) & 0xFF, code & 0xFF]));
    }

    async _wake() {
        if (this._asleep) {
            await this._command(_CMD_WAKE);
            await _sleep(1);
            this._asleep = false;
        }
    }

    async _sleepCmd() {
        await this._command(_CMD_SLEEP);
        this._asleep = true;
    }

    async _measure() {
        await this._wake();
        try {
            for (let attempt = 0; ; attempt++) {
                await this._command(_CMD_MEASURE[this._lowPower ? 1 : 0][this._stretching ? 1 : 0]);
                if (!this._stretching) await _sleep(this._lowPower ? 1 : 13);
                const data = Buffer.from(await this._conn.read(6));
                try {
                    return [_word(data, 0), _word(data, 3)];
                } catch (e) {
                    if (attempt) throw e;
                }
            }
        } finally {
            if (this._sleepBetween) await this._sleepCmd();
        }
    }

    /**
     * Wake, soft reset (0x805D), check the product ID, then sleep.
     * @throws {Error} If the ID word fails its CRC or is not an SHTC3.
     */
    async init() {
        this._asleep = true;
        await this._wake();
        await this._command(_CMD_RESET);
        await _sleep(1);
        await this._command(_CMD_READ_ID);
        const data = Buffer.from(await this._conn.read(3));
        let id;
        try {
            id = _word(data, 0);
        } finally {
            await this._sleepCmd();
        }
        if ((id & _ID_MASK) !== _ID_VALUE) throw new Error('not an SHTC3 (ID 0x' + id.toString(16) + ')');
    }

    /**
     * Measure temperature and humidity.
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>} °C and %RH (0-100)
     */
    async read() {
        const [t, rh] = await this._measure();
        return { temperature_c: _toTemperature(t), humidity_pct: _toHumidity(rh) };
    }
}

/**
 * SHTC3 full interface — single values, raw codes, low-power mode, clock stretching, sleep control, ID, dew point.
 *
 * Extends SHTC3Minimal with single values, raw codes, the low-power measurement mode,
 * the clock-stretching command set, sleep control, soft reset, the product ID and the dew point.
 */
class SHTC3Full extends SHTC3Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x70).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Run one measurement cycle and return the temperature.
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        return _toTemperature((await this._measure())[0]);
    }

    /**
     * Run one measurement cycle and return the humidity.
     * @returns {Promise<number>} Relative humidity in %RH, clamped to 0-100.
     */
    async readHumidity() {
        return _toHumidity((await this._measure())[1]);
    }

    /**
     * Run one measurement cycle and return the raw codes.
     * @returns {Promise<[number, number]>} [raw_temperature, raw_humidity], both 16-bit unsigned.
     */
    async readRaw() {
        return this._measure();
    }

    /**
     * Use the low-power (0.7 ms) or the normal (10.8 ms) measurement commands.
     * @param {boolean} on - True for low-power mode (waits 1 ms), false for normal mode (13 ms).
     */
    setLowPower(on) {
        this._lowPower = Boolean(on);
    }

    /**
     * Use the clock-stretching command set; the fixed wait is skipped.
     * Requires an I²C controller that tolerates a slave holding SCL low.
     * @param {boolean} on
     */
    setClockStretching(on) {
        this._stretching = Boolean(on);
    }

    /**
     * Choose whether a read ends with the sleep command (default on).
     * @param {boolean} on
     */
    setSleepBetween(on) {
        this._sleepBetween = Boolean(on);
    }

    /** Send the wake-up command (0x3517) and wait 1 ms. */
    async wake() {
        await this._command(_CMD_WAKE);
        await _sleep(1);
        this._asleep = false;
    }

    /** Send the sleep command (0xB098). */
    async sleep() {
        await this._sleepCmd();
    }

    /** Soft reset (0x805D), 1 ms wait; leaves the sensor idle. */
    async softReset() {
        await this._wake();
        await this._command(_CMD_RESET);
        await _sleep(1);
    }

    /**
     * Read the 16-bit ID word (command 0xEFC8, CRC verified). A sleeping sensor is woken
     * for the read and put back to sleep.
     * @returns {Promise<number>} ID word; (id & 0x083F) === 0x0807 for the SHTC3.
     */
    async readId() {
        const wasAsleep = this._asleep;
        await this._wake();
        await this._command(_CMD_READ_ID);
        const data = Buffer.from(await this._conn.read(3));
        if (wasAsleep) await this._sleepCmd();
        return _word(data, 0);
    }

    /**
     * Run one measurement and compute the dew point (Magnus formula over water).
     * @returns {Promise<number>} Dew point in °C.
     */
    async dewPoint() {
        const m = await this.read();
        const t = m.temperature_c;
        const rh = m.humidity_pct <= 0.0 ? 0.01 : m.humidity_pct;
        const g = Math.log(rh / 100.0) + 17.62 * t / (243.12 + t);
        return 243.12 * g / (17.62 - g);
    }
}

module.exports = { SHTC3Minimal, SHTC3Full, crc8 };
