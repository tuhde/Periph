'use strict';

/**
 * Raised on checksum error when reading a DHT11 frame.
 */
class DHT11Error extends Error {
    constructor(detail) {
        super(detail);
    }
}

/**
 * DHT11 combined temperature and humidity sensor — minimal interface.
 *
 * The DHT11 returns a 40-bit reading (humidity integer + decimal,
 * temperature integer + decimal, checksum) over a single bidirectional
 * data line. The driver accepts a `DHTxxTransport` instance that handles
 * the underlying single-wire protocol; this class is responsible only
 * for validating the frame and converting it to engineering units.
 *
 * Default configuration (baked in at construction):
 *  - Single read attempt; throws on checksum mismatch
 *  - Caller responsible for respecting the ≥ 2 s sampling interval
 */
class DHT11Minimal {
    /**
     * @param {object} transport - DHTxx transport instance.
     */
    constructor(transport) {
        this._transport = transport;
    }

    /**
     * Read both temperature and humidity in a single transaction.
     *
     * @returns {{temperature: number, humidity: number}} Temperature in °C
     *          and humidity in %RH.
     * @throws {DHT11Error} If the frame's checksum is invalid.
     */
    read() {
        const frame = this._transport.read();
        return DHT11Minimal._decode(frame);
    }

    static _decode(frame) {
        if (frame.length !== 5) {
            throw new DHT11Error(`frame must be 5 bytes, got ${frame.length}`);
        }
        const humInt  = frame[0];
        const humDec  = frame[1];
        const tempInt = frame[2];
        const tempDec = frame[3];
        const checksum = frame[4];
        const expected = (humInt + humDec + tempInt + tempDec) & 0xFF;
        if (expected !== checksum) {
            throw new DHT11Error(`checksum mismatch: expected 0x${expected.toString(16).padStart(2,'0').toUpperCase()}, got 0x${checksum.toString(16).padStart(2,'0').toUpperCase()}`);
        }
        const humidity = humInt + humDec / 10.0;
        const sign = (tempDec & 0x80) ? -1 : 1;
        const tempDecValue = tempDec & 0x7F;
        const temperature = sign * (tempInt + tempDecValue / 10.0);
        return { temperature, humidity };
    }
}

/**
 * DHT11 full interface — extends DHT11Minimal with retry, raw access, and
 * convenience methods.
 */
class DHT11Full extends DHT11Minimal {
    /**
     * @param {object} transport - DHTxx transport instance.
     * @param {number} [maxRetries=3] - Default retry count for `readRetry`.
     */
    constructor(transport, maxRetries = 3) {
        super(transport);
        this._maxRetries = maxRetries;
    }

    /**
     * Read temperature in a single transaction.
     * @returns {number} Temperature in degrees Celsius.
     */
    readTemperature() {
        return this.read().temperature;
    }

    /**
     * Read humidity in a single transaction.
     * @returns {number} Humidity in %RH.
     */
    readHumidity() {
        return this.read().humidity;
    }

    /**
     * Read both values, retrying on checksum error.
     *
     * @param {number} [maxRetries] - Override retry count (default: constructor value).
     * @returns {{temperature: number, humidity: number}}
     * @throws {DHT11Error} If all attempts fail with a checksum error.
     */
    readRetry(maxRetries) {
        const n = maxRetries || this._maxRetries;
        let lastErr = null;
        for (let i = 0; i < n; i++) {
            try {
                return this.read();
            } catch (e) {
                lastErr = e;
            }
        }
        throw new DHT11Error(`readRetry exhausted after ${n} attempts: ${lastErr && lastErr.message}`);
    }

    /**
     * Read the raw 5-byte frame (after validating checksum).
     *
     * @returns {Buffer} 5-byte frame.
     * @throws {DHT11Error} If the frame's checksum is invalid.
     */
    readRaw() {
        const frame = this._transport.read();
        if (frame.length !== 5) {
            throw new DHT11Error(`frame must be 5 bytes, got ${frame.length}`);
        }
        const expected = (frame[0] + frame[1] + frame[2] + frame[3]) & 0xFF;
        if (expected !== frame[4]) {
            throw new DHT11Error(`checksum mismatch: expected 0x${expected.toString(16).padStart(2,'0').toUpperCase()}, got 0x${frame[4].toString(16).padStart(2,'0').toUpperCase()}`);
        }
        return frame;
    }
}

module.exports = { DHT11Minimal, DHT11Full, DHT11Error };
