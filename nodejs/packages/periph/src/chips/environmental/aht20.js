'use strict';

const _CMD_STATUS     = Buffer.from([0x71]);
const _CMD_TRIGGER    = Buffer.from([0xAC, 0x33, 0x00]);
const _CMD_SOFT_RESET = Buffer.from([0xBA]);
const _CMD_CAL_INIT   = [
    Buffer.from([0x1B, 0x00, 0x00]),
    Buffer.from([0x1C, 0x00, 0x00]),
    Buffer.from([0x1E, 0x00, 0x00]),
];

const _STATUS_BUSY = 0x80;
const _STATUS_CAL  = 0x08;
const _STATUS_READY = 0x18;

function _sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

function _crc8(data) {
    let crc = 0xFF;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) {
            crc = (crc & 0x80) ? ((crc << 1) ^ 0x31) & 0xFF : (crc << 1) & 0xFF;
        }
    }
    return crc;
}

function _decodeRaw(data) {
    return {
        rawRh: (data[1] << 12) | (data[2] << 4) | (data[3] >> 4),
        rawT:  ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5],
    };
}

/**
 * AHT20 temperature and humidity sensor — minimal interface.
 *
 * Provides temperature and humidity readings with no configuration beyond
 * the connection. Handles power-on wait, calibration check (soft reset and
 * calibration register init if needed) and measurement triggering automatically.
 *
 * Default configuration (baked in at construction):
 * - One-shot measurement triggered on every read() call
 * - 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
 * - No CRC verification (CRC check is Full-only)
 *
 * Constructor caveat: JS constructors cannot be async, so the init sequence
 * runs unawaited. Every measurement awaits it first, so errors (e.g. sensor
 * not calibrated after init) surface as a rejected promise from the
 * first read()/isCalibrated() call.
 */
class AHT20Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x38).
     */
    constructor(connection) {
        this._conn = connection;
        this._ready = this._init();
        this._ready.catch(() => {});
    }

    async _isReady() {
        return ((await this._readStatus()) & _STATUS_READY) === _STATUS_READY;
    }

    // The datasheet tests the status word against 0x18 once after power-on.
    async _calibrate() {
        if (await this._isReady()) return;
        await this._conn.write(_CMD_SOFT_RESET);
        _sleep(20);
        if (await this._isReady()) return;
        for (const cmd of _CMD_CAL_INIT) {
            await this._conn.write(cmd);
            _sleep(10);
        }
        if (!(await this._isReady())) {
            throw new Error('AHT20 not calibrated after init');
        }
    }

    async _init() {
        _sleep(100);
        await this._calibrate();
        _sleep(10);
    }

    async _readStatus() {
        await this._conn.write(_CMD_STATUS);
        return (await this._conn.read(1))[0];
    }

    async _measure(length = 6) {
        await this._ready;
        await this._conn.write(_CMD_TRIGGER);
        _sleep(80); // conversion takes > 80 ms
        for (let waited = 0; ; waited += 10) {
            const data = await this._conn.read(length);
            if (!(data[0] & _STATUS_BUSY)) return data;
            if (waited >= 200) throw new Error('AHT20 measurement timeout');
            _sleep(10);
        }
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * Sends the trigger command, waits 80 ms, polls BUSY, reads 6 bytes and
     * decodes the raw 20-bit values into physical units.
     *
     * @returns {Promise<{ temperature_c: number, humidity_pct: number }>}
     *   temperature_c: Temperature in degrees Celsius (-50 to 150 °C).
     *   humidity_pct: Relative humidity in percent (0 to 100 %RH).
     */
    async read() {
        const { rawRh, rawT } = _decodeRaw(await this._measure());
        return {
            temperature_c: (rawT / 1048576.0) * 200.0 - 50.0,
            humidity_pct: Math.min(Math.max((rawRh / 1048576.0) * 100.0, 0.0), 100.0),
        };
    }
}

/**
 * AHT20 full interface — extends AHT20Minimal with CRC, raw values, status and dew point.
 */
class AHT20Full extends AHT20Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x38).
     */
    constructor(connection) {
        super(connection);
    }

    /**
     * Trigger a measurement and return temperature only.
     * @returns {Promise<number>} Temperature in degrees Celsius (-50 to 150 °C).
     */
    async readTemperature() {
        return (await this.read()).temperature_c;
    }

    /**
     * Trigger a measurement and return humidity only.
     * @returns {Promise<number>} Relative humidity in percent (0 to 100 %RH).
     */
    async readHumidity() {
        return (await this.read()).humidity_pct;
    }

    /**
     * Trigger a measurement and return the raw 20-bit values.
     * @returns {Promise<{ raw_humidity: number, raw_temperature: number }>} Each 0 to 1048575.
     */
    async readRaw() {
        const { rawRh, rawT } = _decodeRaw(await this._measure());
        return { raw_humidity: rawRh, raw_temperature: rawT };
    }

    /**
     * Trigger a measurement, read 7 bytes and verify the CRC-8
     * (polynomial 0x31, initial value 0xFF, over bytes 0 to 5, compared with byte 6).
     * @returns {Promise<{ temperature_c: number, humidity_pct: number, crc_ok: boolean }>}
     */
    async readWithCrc() {
        const data = await this._measure(7);
        const { rawRh, rawT } = _decodeRaw(data);
        return {
            temperature_c: (rawT / 1048576.0) * 200.0 - 50.0,
            humidity_pct: Math.min(Math.max((rawRh / 1048576.0) * 100.0, 0.0), 100.0),
            crc_ok: _crc8(data.slice(0, 6)) === data[6],
        };
    }

    /**
     * Read the status byte.
     * @returns {Promise<number>} Status byte (bit 7 BUSY, bit 3 CAL).
     */
    async readStatus() {
        await this._ready;
        return this._readStatus();
    }

    /**
     * Check if the calibration bit is set in the status byte.
     * @returns {Promise<boolean>} True if the sensor reports calibration enabled.
     */
    async isCalibrated() {
        return !!((await this.readStatus()) & _STATUS_CAL);
    }

    /**
     * Check if the busy bit is set in the status byte.
     * @returns {Promise<boolean>} True if a measurement is in progress.
     */
    async isBusy() {
        return !!((await this.readStatus()) & _STATUS_BUSY);
    }

    /**
     * Send the soft reset command, wait 20 ms, and re-run the calibration check.
     * @returns {Promise<void>} Rejects if the sensor does not report calibrated afterwards.
     */
    async softReset() {
        await this._ready;
        await this._conn.write(_CMD_SOFT_RESET);
        _sleep(20);
        await this._calibrate();
    }

    /**
     * Trigger a measurement and return the dew point (Magnus formula).
     * @returns {Promise<number>} Dew point in degrees Celsius; -50 if humidity is 0 %RH.
     */
    async dewPoint() {
        const { temperature_c: t, humidity_pct: rh } = await this.read();
        if (rh <= 0) return -50.0;
        const gamma = Math.log(rh / 100.0) + (17.62 * t) / (243.12 + t);
        return (243.12 * gamma) / (17.62 - gamma);
    }
}

module.exports = { AHT20Minimal, AHT20Full };
