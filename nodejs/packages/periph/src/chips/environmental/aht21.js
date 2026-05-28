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
 * the transport. Handles power-on initialization, calibration check, and
 * measurement triggering automatically.
 *
 * Default configuration (baked in at construction):
 * - Measurement triggered on every read() call (no continuous mode)
 * - 80 ms fixed wait after trigger (no busy-polling)
 * - No CRC verification (reduces complexity; CRC check is Full-only)
 */
class AHT21Minimal {
    /**
     * @param {object} transport - Configured I²C transport pointing at the device (address 0x38).
     */
    constructor(transport) {
        this._transport = transport;
        _sleep(100);
        let status = this._readStatus();
        if ((status & 0x18) !== 0x18) {
            this._transport.write(_CMD_SOFT_RESET);
            _sleep(20);
            status = this._readStatus();
            if ((status & 0x18) !== 0x18) {
                this._transport.write(_CMD_CAL_INIT_1);
                _sleep(10);
                this._transport.write(_CMD_CAL_INIT_2);
                _sleep(10);
                this._transport.write(_CMD_CAL_INIT_3);
                _sleep(10);
            }
        }
    }

    _readStatus() {
        return this._transport.read(1)[0];
    }

    /**
     * Trigger a measurement and return temperature and humidity.
     *
     * Sends the trigger command, waits 80 ms, reads 6 bytes, and decodes
     * the raw 20-bit values into physical units.
     *
     * @returns {{ temperature_c: number, humidity_pct: number }}
     *   temperature_c: Temperature in degrees Celsius (-50 to 150 °C).
     *   humidity_pct: Relative humidity in percent (0 to 100 %RH).
     */
    read() {
        this._transport.write(_CMD_TRIGGER);
        _sleep(80);
        const data = this._transport.read(6);
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
     * @param {object} transport - Configured I²C transport pointing at the device (address 0x38).
     */
    constructor(transport) {
        super(transport);
    }

    /**
     * Trigger a measurement and return temperature only.
     * @returns {number} Temperature in degrees Celsius (-50 to 150 °C).
     */
    readTemperature() {
        return this.read().temperature_c;
    }

    /**
     * Trigger a measurement and return humidity only.
     * @returns {number} Relative humidity in percent (0 to 100 %RH).
     */
    readHumidity() {
        return this.read().humidity_pct;
    }

    /**
     * Trigger a measurement, read 7 bytes, and verify CRC-8.
     *
     * Uses polynomial x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF
     * to verify the CRC byte against bytes 0–5 of the response.
     *
     * @returns {{ temperature_c: number, humidity_pct: number, crc_ok: boolean }}
     *   temperature_c: Temperature in degrees Celsius.
     *   humidity_pct: Relative humidity in percent.
     *   crc_ok: True if CRC-8 verification passed.
     */
    readWithCrc() {
        this._transport.write(_CMD_TRIGGER);
        _sleep(80);
        const data = this._transport.read(7);
        const rawRh = (data[1] << 12) | (data[2] << 4) | (data[3] >> 4);
        const rawT  = ((data[3] & 0x0F) << 16) | (data[4] << 8) | data[5];
        const humidityPct = (rawRh / 1048576.0) * 100.0;
        const temperatureC = (rawT / 1048576.0) * 200.0 - 50.0;
        const crcOk = this._crc8(data.slice(0, 6)) === data[6];
        return { temperature_c: temperatureC, humidity_pct: humidityPct, crc_ok: crcOk };
    }

    /**
     * Send the soft reset command and wait 20 ms for recovery.
     */
    softReset() {
        this._transport.write(_CMD_SOFT_RESET);
        _sleep(20);
    }

    /**
     * Check if the calibration bit is set in the status byte.
     * @returns {boolean} True if the sensor reports calibration enabled.
     */
    isCalibrated() {
        return !!(this._readStatus() & _STATUS_CAL);
    }

    /**
     * Check if the busy bit is set in the status byte.
     * @returns {boolean} True if a measurement is in progress.
     */
    isBusy() {
        return !!(this._readStatus() & _STATUS_BUSY);
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
