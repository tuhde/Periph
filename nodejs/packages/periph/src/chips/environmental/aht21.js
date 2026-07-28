'use strict';

const _CMD_TRIGGER    = Buffer.from([0xAC, 0x33, 0x00]);
const _CMD_SOFT_RESET = Buffer.from([0xBA]);
const _CMD_CAL_INIT_1 = Buffer.from([0x1B, 0x00, 0x00]);
const _CMD_CAL_INIT_2 = Buffer.from([0x1C, 0x00, 0x00]);
const _CMD_CAL_INIT_3 = Buffer.from([0x1E, 0x00, 0x00]);

const _STATUS_BUSY = 0x80;
const _STATUS_CAL  = 0x08;

function _sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

/**
 * AHT21 temperature and humidity sensor — minimal interface.
 *
 * Provides temperature and humidity readings with no configuration beyond
 * the connection. Handles power-on initialization, calibration check, and
 * measurement triggering automatically.
 *
 * Default configuration (baked in at construction):
 * - Measurement triggered on every read() call (no continuous mode)
 * - 80 ms fixed wait after trigger (no busy-polling)
 * - No CRC verification (reduces complexity; CRC check is Full-only)
 *
 * Constructor caveat: the calibration-check sequence below is conditional on
 * read results (a genuine async dependency chain), but JS constructors
 * cannot be async. It is fired off unawaited, matching the fire-and-forget
 * convention used for every other constructor-time connection call in this
 * port — in practice it settles before any subsequent await in caller code
 * gets scheduled, since the underlying I2C connection is synchronous under
 * the hood, but this is not a guaranteed blocking wait the way the
 * pre-async version was. Call `await sensor.read()` once before relying on
 * calibration state if this matters for your use case.
 */
class AHT21Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x38).
     */
    constructor(connection) {
        this._conn = connection;
        this._init();
    }

    async _init() {
        _sleep(100);
        let status = await this._readStatus();
        if ((status & 0x18) !== 0x18) {
            await this._conn.write(_CMD_SOFT_RESET);
            _sleep(20);
            status = await this._readStatus();
            if ((status & 0x18) !== 0x18) {
                await this._conn.write(_CMD_CAL_INIT_1);
                _sleep(10);
                await this._conn.write(_CMD_CAL_INIT_2);
                _sleep(10);
                await this._conn.write(_CMD_CAL_INIT_3);
                _sleep(10);
            }
        }
    }

    async _readStatus() {
        return (await this._conn.read(1))[0];
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * Sends the trigger command, waits 80 ms, reads 6 bytes, and decodes
     * the raw 20-bit values into physical units.
     *
     * @returns {Promise<{ temperature_c: number, humidity_pct: number }>}
     *   temperature_c: Temperature in degrees Celsius (-50 to 150 °C).
     *   humidity_pct: Relative humidity in percent (0 to 100 %RH).
     */
    async read() {
        await this._conn.write(_CMD_TRIGGER);
        _sleep(80);
        const data = await this._conn.read(6);
        const rawRh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4);
        const rawT  = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5];
        const humidityPct = (rawRh / 1048576.0) * 100.0;
        const temperatureC = (rawT / 1048576.0) * 200.0 - 50.0;
        return { temperature_c: temperatureC, humidity_pct: humidityPct };
    }
}

/**
 * AHT21 full interface — extends AHT21Minimal with CRC and status support.
 *
 * Adds CRC-8 verification, explicit soft reset, calibration status inspection,
 * and individual temperature/humidity readings.
 */
class AHT21Full extends AHT21Minimal {
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
     * Trigger a measurement, read 7 bytes, and verify CRC-8.
     *
     * Uses polynomial x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF
     * to verify the CRC byte against bytes 0–5 of the response.
     *
     * @returns {Promise<{ temperature_c: number, humidity_pct: number, crc_ok: boolean }>}
     *   temperature_c: Temperature in degrees Celsius.
     *   humidity_pct: Relative humidity in percent.
     *   crc_ok: True if CRC-8 verification passed.
     */
    async readWithCrc() {
        await this._conn.write(_CMD_TRIGGER);
        _sleep(80);
        const data = await this._conn.read(7);
        const rawRh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4);
        const rawT  = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5];
        const humidityPct = (rawRh / 1048576.0) * 100.0;
        const temperatureC = (rawT / 1048576.0) * 200.0 - 50.0;
        const crcOk = this._crc8(data.slice(0, 6)) === data[6];
        return { temperature_c: temperatureC, humidity_pct: humidityPct, crc_ok: crcOk };
    }

    /**
     * Send the soft reset command and wait 20 ms for recovery.
     * @returns {Promise<void>}
     */
    async softReset() {
        await this._conn.write(_CMD_SOFT_RESET);
        _sleep(20);
    }

    /**
     * Check if the calibration bit is set in the status byte.
     * @returns {Promise<boolean>} True if the sensor reports calibration enabled.
     */
    async isCalibrated() {
        return !!((await this._readStatus()) & _STATUS_CAL);
    }

    /**
     * Check if the busy bit is set in the status byte.
     * @returns {Promise<boolean>} True if a measurement is in progress.
     */
    async isBusy() {
        return !!((await this._readStatus()) & _STATUS_BUSY);
    }

    _crc8(data) {
        let crc = 0xFF;
        for (let i = 0; i < data.length; i++) {
            crc ^= data[i];
            for (let j = 0; j < 8; j++) {
                if (crc & 0x80)
                    crc = ((crc << 1) ^ 0x31) & 0xFF;
                else
                    crc = (crc << 1) & 0xFF;
            }
        }
        return crc;
    }
}

module.exports = { AHT21Minimal, AHT21Full };
