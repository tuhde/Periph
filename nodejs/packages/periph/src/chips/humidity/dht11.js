'use strict';

/**
 * DHT11 temperature and humidity sensor (ASAIR) — minimal interface.
 *
 * Performs a full DHT11 protocol transaction (host start signal, sensor
 * response, 40-bit data frame, checksum verification) and returns temperature
 * and humidity. Single read attempt; throws on sensor timeout or checksum
 * mismatch.
 *
 * Callers must respect the 2-second minimum sampling interval between reads;
 * the driver does not enforce this automatically.
 *
 * The driver accepts a platform pin adapter exposing a uniform interface:
 *   - setOutput() / setInput() — reconfigure the line direction
 *   - drive(high: boolean)     — drive the line HIGH or LOW (output mode)
 *   - read()                   — read the current logic level (input mode)
 *
 * See {@link module:periph/transport/dht11} for the onoff-based Node.js
 * adapter. On Linux the bit-bang may sporadically fail under load; use
 * DHT11Full#readRetry() to recover from transient checksum errors.
 */
class DHT11Minimal {
    /**
     * @param {object} pin - Platform pin adapter (DHT11Pin on Node.js / Linux).
     */
    constructor(pin) {
        this._pin = pin;
    }

    _micros() {
        return Number(process.hrtime.bigint() / 1000n);
    }

    _waitLow(timeoutUs) {
        const start = this._micros();
        while (this._pin.read() === true) {
            if (this._micros() - start > timeoutUs) return false;
        }
        return true;
    }

    _waitHigh(timeoutUs) {
        const start = this._micros();
        while (this._pin.read() === false) {
            if (this._micros() - start > timeoutUs) return false;
        }
        return true;
    }

    _measureHigh() {
        const start = this._micros();
        while (this._pin.read() === true) {
            if (this._micros() - start > 100) break;
        }
        return this._micros() - start;
    }

    _readFrame() {
        this._pin.setOutput();
        this._pin.drive(false);
        const end = Date.now() + 20;
        while (Date.now() < end) {}
        this._pin.setInput();
        const release = process.hrtime.bigint() + 30n * 1000n;
        while (process.hrtime.bigint() < release) {}
        if (!this._waitLow(200))  throw new Error('DHT11 sensor did not respond (no LOW)');
        if (!this._waitHigh(200)) throw new Error('DHT11 sensor did not release response LOW');
        if (!this._waitLow(200))  throw new Error('DHT11 sensor did not start data phase');
        let bits = 0n;
        for (let i = 0; i < 40; i++) {
            if (!this._waitHigh(200)) throw new Error('DHT11 bit LOW phase missing');
            const highUs = this._measureHigh();
            bits = (bits << 1n) | (highUs > 40 ? 1n : 0n);
        }
        const bytes = [0, 0, 0, 0, 0];
        for (let i = 0; i < 5; i++) {
            bytes[i] = Number((bits >> BigInt(8 * (4 - i))) & 0xffn);
        }
        const checksum = (bytes[0] + bytes[1] + bytes[2] + bytes[3]) & 0xff;
        if (checksum !== bytes[4]) throw new Error('DHT11 checksum mismatch');
        return bytes;
    }

    /**
     * Perform a full protocol read and return temperature and humidity.
     *
     * @returns {{temperature_c: number, humidity_rh: number}} Reading.
     * @throws {Error} On sensor timeout or checksum mismatch.
     */
    read() {
        const raw = this._readFrame();
        const sign     = (raw[3] & 0x80) ? -1 : 1;
        const tempDec  = raw[3] & 0x7f;
        const temperature_c = sign * (raw[2] + tempDec / 10.0);
        const humidity_rh   = raw[0] + raw[1] / 10.0;
        return { temperature_c, humidity_rh };
    }
}

/**
 * DHT11 full interface — extends DHT11Minimal with retry and raw access.
 *
 * Adds separate temperature/humidity accessors, automatic retry on checksum
 * failure, and access to the raw 5-byte frame.
 */
class DHT11Full extends DHT11Minimal {
    /**
     * Read temperature in °C.
     * @returns {number} Temperature; on failure, returns the last successful value (0 if none).
     */
    readTemperature() { return this._t; }

    /**
     * Read humidity in %RH.
     * @returns {number} Humidity; on failure, returns the last successful value (0 if none).
     */
    readHumidity() { return this._h; }

    /**
     * Read with automatic retry on checksum/timeout failure.
     *
     * @param {number} [maxRetries=3] - Maximum number of attempts.
     * @returns {{temperature_c: number, humidity_rh: number}} Reading on success.
     * @throws {Error} After all retries have been exhausted.
     */
    readRetry(maxRetries = 3) {
        let lastErr;
        for (let i = 0; i < maxRetries; i++) {
            try {
                const r = this.read();
                this._t = r.temperature_c;
                this._h = r.humidity_rh;
                return r;
            } catch (e) {
                lastErr = e;
            }
        }
        throw lastErr;
    }

    /**
     * Read the raw 5-byte frame without interpretation.
     *
     * @returns {number[]} 5-byte array ``[hum_int, hum_dec, temp_int, temp_dec, checksum]``.
     * @throws {Error} On sensor timeout or checksum mismatch.
     */
    readRaw() {
        return this._readFrame();
    }
}

DHT11Full.prototype._t = 0.0;
DHT11Full.prototype._h = 0.0;

module.exports = { DHT11Minimal, DHT11Full };
