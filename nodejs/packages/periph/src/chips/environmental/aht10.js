'use strict';

const _CMD_INIT       = Buffer.from([0xE1, 0x08, 0x00]);
const _CMD_TRIGGER    = Buffer.from([0xAC, 0x33, 0x00]);
const _CMD_SOFT_RESET = Buffer.from([0xBA]);

const _STATUS_BUSY = 0x80;
const _STATUS_CAL  = 0x08;

function _sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

function _decodeRaw(data) {
    return {
        rawRh: (data[1] << 12) | (data[2] << 4) | (data[3] >> 4),
        rawT:  ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5],
    };
}

/**
 * AHT10 temperature and humidity sensor — minimal interface.
 *
 * Provides temperature and humidity readings with no configuration beyond
 * the connection. Handles power-on wait, initialization (0xE1 calibration
 * enable), calibration check and measurement triggering automatically.
 *
 * Default configuration (baked in at construction):
 * - One-shot measurement triggered on every read() call
 * - 80 ms wait after trigger, then BUSY polling (10 ms steps, 200 ms guard)
 * - No CRC (the AHT10 does not send one)
 *
 * Constructor caveat: JS constructors cannot be async, so the init sequence
 * runs unawaited. Every measurement awaits it first, so errors (e.g. sensor
 * not calibrated after one retry) surface as a rejected promise from the
 * first read()/isCalibrated() call.
 */
class AHT10Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x38).
     */
    constructor(connection) {
        this._conn = connection;
        this._ready = this._init();
        this._ready.catch(() => {});
    }

    async _initialize() {
        await this._conn.write(_CMD_INIT);
        _sleep(10);
    }

    async _init() {
        _sleep(40);
        await this._initialize();
        if (!((await this._readStatus()) & _STATUS_CAL)) {
            await this._conn.write(_CMD_SOFT_RESET);
            _sleep(20);
            await this._initialize();
            if (!((await this._readStatus()) & _STATUS_CAL)) {
                throw new Error('AHT10 not calibrated after init');
            }
        }
    }

    async _readStatus() {
        return (await this._conn.read(1))[0];
    }

    async _measure() {
        await this._ready;
        await this._conn.write(_CMD_TRIGGER);
        _sleep(80); // conversion takes > 75 ms
        for (let waited = 0; ; waited += 10) {
            const data = await this._conn.read(6);
            if (!(data[0] & _STATUS_BUSY)) return data;
            if (waited >= 200) throw new Error('AHT10 measurement timeout');
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
 * AHT10 full interface — extends AHT10Minimal with raw values, status and dew point.
 */
class AHT10Full extends AHT10Minimal {
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
     * Read the status byte.
     * @returns {Promise<number>} Status byte (bit 7 BUSY, bits 6:5 MODE, bit 3 CAL).
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
     * Send the soft reset command, wait 20 ms, and re-run the init command.
     * @returns {Promise<void>}
     */
    async softReset() {
        await this._ready;
        await this._conn.write(_CMD_SOFT_RESET);
        _sleep(20);
        await this._initialize();
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

module.exports = { AHT10Minimal, AHT10Full };
