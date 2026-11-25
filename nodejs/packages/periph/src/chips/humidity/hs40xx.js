'use strict';

const _CMD_T_NOHOLD = 0xF3;
const _CMD_HT_HOLD = 0xE5;
const _CMD_HT_NOHOLD = 0xF5;
const _CMD_READ_REG = 0xA7;
const _CMD_WRITE_REG = 0xA6;
const _CMD_STOP_PERIODIC = 0x30;
const _CMD_READ_ID = 0xD7;
const _CMD_SOFT_RESET = 0xFE;

const _REG_RESOLUTION = 0x00;
const _REG_PERIODIC = 0x02;
const _REG_ALERT_EN = 0x03;

// [low-byte register, high-byte register] of each 14-bit threshold
const _THRESHOLDS = {
    rhHigh: [0x0F, 0x10], rhHighReset: [0x0D, 0x0E],
    rhLow: [0x11, 0x12], rhLowReset: [0x13, 0x14],
    tempHigh: [0x07, 0x08], tempHighReset: [0x05, 0x06],
    tempLow: [0x09, 0x0A], tempLowReset: [0x0B, 0x0C],
};

const _BITS = [8, 10, 12, 14];
const _FULL_SCALE = 16383.0;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _crc8(data) {
    let crc = 0xFF;
    for (const byte of data) {
        crc ^= byte;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x1D) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

function _checkCrc(frame) {
    // Temperature-only frames (3 bytes) take the humidity bytes as zero.
    const data = frame.length === 3 ? Buffer.from([0, 0, frame[0], frame[1]]) : frame.subarray(0, 4);
    return _crc8(data) === frame[frame.length - 1];
}

const _rawHumidity = (f) => ((f[0] & 0x3F) << 8) | f[1];
const _rawTemperature = (f, o = 2) => ((f[o] & 0x3F) << 8) | f[o + 1];
const _toHumidity = (raw) => raw / _FULL_SCALE * 100.0;
const _toTemperature = (raw) => raw / _FULL_SCALE * 165.0 - 40.0;

/**
 * HS40xx humidity and temperature sensor — minimal interface.
 *
 * Covers the Renesas HS4001 and HS4003, which share one protocol (HS4002 and the HS401x
 * variants differ only physically and get no separate class). Each `read()` sends the no-hold
 * humidity + temperature command (0xF5), waits 3 ms, reads five bytes (retrying while the
 * sensor NACKs the read header), verifies the CRC-8 and converts. A CRC mismatch is retried once.
 *
 * The constructor performs no bus access.
 *
 * Default configuration (power-on): 14-bit resolution, one-shot no-hold measurements, CRC
 * checked, periodic mode and alerts off.
 */
class HS40xxMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x54).
     */
    constructor(connection) {
        this._conn = connection;
    }

    async _readFrame(length, timeoutMs = 20) {
        // The sensor NACKs the read header while converting; retry until it answers.
        const deadline = Date.now() + timeoutMs;
        for (;;) {
            try {
                return Buffer.from(await this._conn.read(length));
            } catch (e) {
                if (Date.now() >= deadline) throw e;
                await _sleep(1);
            }
        }
    }

    async _measure(cmd, length, hold = false) {
        for (let attempt = 0; attempt < 2; attempt++) {
            await this._conn.write(Buffer.from([cmd]));
            if (!hold) await _sleep(3);
            const frame = await this._readFrame(length);
            if (_checkCrc(frame)) return frame;
        }
        throw new Error('HS40xx CRC mismatch');
    }

    /**
     * Measure temperature and humidity (no-hold 0xF5, 5 bytes, CRC verified).
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>} Temperature in °C (-40 to 125) and relative humidity in %RH (0 to 100).
     * @throws {Error} If the CRC is wrong after one retry, or the sensor keeps NACKing for more than 20 ms.
     */
    async read() {
        const f = await this._measure(_CMD_HT_NOHOLD, 5);
        return { temperature_c: _toTemperature(_rawTemperature(f)), humidity_pct: _toHumidity(_rawHumidity(f)) };
    }
}

/**
 * HS40xx full interface — single values, hold mode, resolution, periodic mode, alerts, ID.
 *
 * Extends `HS40xxMinimal`. Registers are volatile: they return to their defaults whenever the
 * supply is cut or after `softReset()`.
 */
class HS40xxFull extends HS40xxMinimal {
    /** @param {import('../../connection/connection').Connection} connection - Configured I²C connection (address 0x54). */
    constructor(connection) {
        super(connection);
        this._alertsEnabled = false;
        this._periodS = 1;
        this._activeHigh = true;
        this._callback = null;
        this._edgeHandler = null;
        this._intPin = null;
        this._pollTimer = null;
    }

    /**
     * Measure temperature only (no-hold 0xF3, 3 bytes, CRC verified).
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        const f = await this._measure(_CMD_T_NOHOLD, 3);
        return _toTemperature(_rawTemperature(f, 0));
    }

    /**
     * Measure humidity (uses the 0xF5 humidity + temperature command).
     * @returns {Promise<number>} Relative humidity in %RH.
     */
    async readHumidity() {
        return (await this.read()).humidity_pct;
    }

    /**
     * Measure with the hold command 0xE5 (the sensor stretches SCL until done; needs a stretching-capable controller and SCL >= 200 kHz).
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>}
     */
    async readHold() {
        const f = await this._measure(_CMD_HT_HOLD, 5, true);
        return { temperature_c: _toTemperature(_rawTemperature(f)), humidity_pct: _toHumidity(_rawHumidity(f)) };
    }

    /**
     * Measure and return the raw 14-bit values (CRC verified).
     * @returns {Promise<{raw_humidity: number, raw_temperature: number}>}
     */
    async readRaw() {
        const f = await this._measure(_CMD_HT_NOHOLD, 5);
        return { raw_humidity: _rawHumidity(f), raw_temperature: _rawTemperature(f) };
    }

    /**
     * Read one configuration register (command 0xA7 + address, then a 1-byte read).
     * @param {number} reg - Register address (0x00 to 0x14).
     * @returns {Promise<number>} Register value (0 to 255).
     */
    async readRegister(reg) {
        await this._conn.write(Buffer.from([_CMD_READ_REG, reg]));
        return (await this._readFrame(1))[0];
    }

    /**
     * Write one configuration register (command 0xA6 + address + data).
     * @param {number} reg - Register address (0x00 to 0x14).
     * @param {number} value - Byte to write (0 to 255).
     */
    async writeRegister(reg, value) {
        await this._conn.write(Buffer.from([_CMD_WRITE_REG, reg, value & 0xFF]));
    }

    async _modify(reg, mask, bits) {
        const value = ((await this.readRegister(reg)) & ~mask & 0xFF) | (bits & mask);
        await this.writeRegister(reg, value);
    }

    /**
     * Set the measurement resolutions (register 0x00, read-modify-write).
     * @param {number|null} humidityBits - 8, 10, 12 or 14, or null to leave unchanged.
     * @param {number|null} temperatureBits - 8, 10, 12 or 14, or null to leave unchanged.
     * @throws {RangeError} If a value is not 8, 10, 12 or 14.
     */
    async setResolution(humidityBits = null, temperatureBits = null) {
        for (const bits of [humidityBits, temperatureBits]) {
            if (bits !== null && bits !== undefined && !_BITS.includes(bits)) {
                throw new RangeError('resolution must be 8, 10, 12 or 14 bits');
            }
        }
        let mask = 0;
        let value = 0;
        if (humidityBits !== null && humidityBits !== undefined) { mask |= 0x0C; value |= _BITS.indexOf(humidityBits) << 2; }
        if (temperatureBits !== null && temperatureBits !== undefined) { mask |= 0x03; value |= _BITS.indexOf(temperatureBits); }
        if (mask) await this._modify(_REG_RESOLUTION, mask, value);
    }

    /**
     * Read the measurement resolutions.
     * @returns {Promise<{humidity_bits: number, temperature_bits: number}>} Each 8, 10, 12 or 14.
     */
    async getResolution() {
        const reg = await this.readRegister(_REG_RESOLUTION);
        return { humidity_bits: _BITS[(reg >> 2) & 3], temperature_bits: _BITS[reg & 3] };
    }

    /**
     * Start periodic measurement mode (register 0x02 bit 7 plus the period).
     * @param {number} [periodS=1] - Measurement period in seconds: 0.5, 1 or 2.5.
     * @throws {RangeError} If the period is not supported.
     */
    async startPeriodic(periodS = 1) {
        const code = { 0.5: 0, 1: 1, 2.5: 2 }[periodS];
        if (code === undefined) throw new RangeError('periodS must be 0.5, 1 or 2.5');
        this._alertsEnabled = ((await this.readRegister(_REG_ALERT_EN)) & 0x0F) !== 0;
        this._periodS = periodS;
        await this._modify(_REG_PERIODIC, 0xB0, 0x80 | (code << 4));
    }

    /** Leave periodic mode (command 0x30); the sensor then sleeps. */
    async stopPeriodic() {
        await this._conn.write(Buffer.from([_CMD_STOP_PERIODIC]));
    }

    /**
     * Fetch the latest periodic result (a plain read; CRC verified). The frame has a leading
     * status byte when any alert is enabled.
     * @returns {Promise<{temperature_c: number, humidity_pct: number, status: number}>} Status is the SOURCE_* bitmask of the active alerts (0 when alerts are off).
     * @throws {Error} If the CRC does not match.
     */
    async readPeriodic() {
        let frame = await this._readFrame(this._alertsEnabled ? 6 : 5);
        let status = 0;
        if (this._alertsEnabled) {
            status = frame[0] & 0x0F;
            frame = frame.subarray(1);
        }
        if (!_checkCrc(frame)) throw new Error('HS40xx CRC mismatch');
        return { temperature_c: _toTemperature(_rawTemperature(frame)), humidity_pct: _toHumidity(_rawHumidity(frame)), status };
    }

    /**
     * Program alert trigger / reset thresholds; only the given ones are written.
     * @param {{rhHigh?: number, rhHighReset?: number, rhLow?: number, rhLowReset?: number, tempHigh?: number, tempHighReset?: number, tempLow?: number, tempLowReset?: number}} thresholds - Humidity in %RH, temperature in °C.
     */
    async setAlertThresholds(thresholds = {}) {
        for (const name of Object.keys(_THRESHOLDS)) {
            const v = thresholds[name];
            if (v === undefined || v === null) continue;
            const raw = name.startsWith('rh') ? v / 100.0 * _FULL_SCALE : (v + 40.0) / 165.0 * _FULL_SCALE;
            const word = Math.max(0, Math.min(16383, Math.round(raw)));
            const [lowReg, highReg] = _THRESHOLDS[name];
            await this.writeRegister(lowReg, word & 0xFF);
            await this.writeRegister(highReg, word >> 8);
        }
    }

    /**
     * Set the ALERT pin polarity (register 0x02 bit 0).
     * @param {boolean} activeHigh - true for active high when triggered (default), false for active low.
     */
    async setAlertPolarity(activeHigh) {
        this._activeHigh = !!activeHigh;
        await this._modify(_REG_PERIODIC, 0x01, activeHigh ? 1 : 0);
    }

    /**
     * Enable alert source(s) in register 0x03.
     * @param {number} source - Bitmask of `HS40xxFull.SOURCE_*`.
     */
    async enableInterrupt(source) {
        const value = (await this.readRegister(_REG_ALERT_EN)) | (source & 0x0F);
        await this.writeRegister(_REG_ALERT_EN, value);
        this._alertsEnabled = (value & 0x0F) !== 0;
    }

    /**
     * Disable alert source(s) in register 0x03.
     * @param {number} source - Bitmask of `HS40xxFull.SOURCE_*`.
     */
    async disableInterrupt(source) {
        const value = (await this.readRegister(_REG_ALERT_EN)) & ~(source & 0x0F) & 0xFF;
        await this.writeRegister(_REG_ALERT_EN, value);
        this._alertsEnabled = (value & 0x0F) !== 0;
    }

    /**
     * Fetch the latest periodic result and return the alert status; needs periodic mode with an alert source enabled.
     * @returns {Promise<number>} Bitmask of `HS40xxFull.SOURCE_*` for the alerts currently active.
     * @throws {Error} If no alert source is enabled or the CRC does not match.
     */
    async pollInterrupt() {
        if (!this._alertsEnabled) throw new Error('HS40xx alerts are not enabled');
        return (await this.readPeriodic()).status;
    }

    async _dispatch() {
        if (!this._callback) return;
        try {
            this._callback(await this.pollInterrupt());
        } catch (e) {
            // ignore a failed poll
        }
    }

    /**
     * Subscribe to the ALERT pin; falls back to polling at the periodic interval.
     * @param {function(number): void} callback - Called with the status bitmask.
     * @param {object} [intPin] - Optional InputPin; defaults to `connection.intPin`.
     */
    async onInterrupt(callback, intPin = null) {
        this._callback = callback;
        const pin = intPin || this._conn.intPin;
        if (pin) {
            this._intPin = pin;
            this._edgeHandler = () => this._dispatch();
            await pin.onEdge(this._edgeHandler, this._activeHigh ? 'rising' : 'falling');
        } else {
            let busy = false;
            this._pollTimer = setInterval(async () => {
                if (busy) return;
                busy = true;
                try { await this._dispatch(); } finally { busy = false; }
            }, this._periodS * 1000);
        }
    }

    /** Unsubscribe from the ALERT pin (or stop the polling task). */
    async offInterrupt() {
        this._callback = null;
        if (this._intPin && this._edgeHandler) await this._intPin.offEdge(this._edgeHandler);
        this._intPin = null;
        this._edgeHandler = null;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
    }

    /**
     * Read the 32-bit sensor ID (command 0xD7, 4 bytes MSB first).
     * @returns {Promise<number>} Unique 32-bit device ID.
     */
    async readId() {
        await this._conn.write(Buffer.from([_CMD_READ_ID]));
        const d = await this._readFrame(4);
        return ((d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3]) >>> 0;
    }

    /** Stop periodic mode, send the soft reset (0xFE) and wait 500 ms. */
    async softReset() {
        await this.stopPeriodic();
        await this._conn.write(Buffer.from([_CMD_SOFT_RESET]));
        await _sleep(500);
        this._alertsEnabled = false;
        this._activeHigh = true;
    }

    /**
     * Run one measurement and return the dew point (Magnus formula).
     * @returns {Promise<number>} Dew point in °C; -273.15 if humidity is 0 %RH.
     */
    async dewPoint() {
        const m = await this.read();
        if (m.humidity_pct <= 0.0) return -273.15;
        const gamma = Math.log(m.humidity_pct / 100.0) + 17.62 * m.temperature_c / (243.12 + m.temperature_c);
        return 243.12 * gamma / (17.62 - gamma);
    }
}

HS40xxFull.SOURCE_RH_LOW = 0x01;
HS40xxFull.SOURCE_RH_HIGH = 0x02;
HS40xxFull.SOURCE_TEMP_LOW = 0x04;
HS40xxFull.SOURCE_TEMP_HIGH = 0x08;

// HS4001 and HS4003 share one protocol, so they are plain aliases of the HS40xx drivers
// (HS4002 and the HS401x variants are only physically different).
module.exports = {
    HS40xxMinimal, HS40xxFull,
    HS4001Minimal: HS40xxMinimal, HS4003Minimal: HS40xxMinimal,
    HS4001Full: HS40xxFull, HS4003Full: HS40xxFull,
};
