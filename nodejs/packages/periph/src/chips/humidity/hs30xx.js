'use strict';

const _CMD_ENTER_PROG = Buffer.from([0xA0, 0x00, 0x00]);
const _CMD_EXIT_PROG = Buffer.from([0x80, 0x00, 0x00]);

const _REG_RH_READ = 0x06;
const _REG_RH_WRITE = 0x46;
const _REG_T_READ = 0x11;
const _REG_T_WRITE = 0x51;
const _REG_ID_HIGH = 0x1E;
const _REG_ID_LOW = 0x1F;

const _NVM_OK = 0x81;
const _RES_MASK = 0x0C00;
const _BITS = [8, 10, 12, 14];
const _FULL_SCALE = 16383.0;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _rawHumidity(data) {
    return ((data[0] & 0x3F) << 8) | data[1];
}

function _rawTemperature(data) {
    return (data[2] << 6) | (data[3] >> 2);
}

function _toHumidity(raw) {
    return raw / _FULL_SCALE * 100.0;
}

function _toTemperature(raw) {
    return raw / _FULL_SCALE * 165.0 - 40.0;
}

/**
 * HS30xx humidity and temperature sensor — minimal interface.
 *
 * Covers the whole Renesas HS3xxx family (HS3001, HS3002, HS3003 — they differ only in
 * accuracy class and housing, not in protocol). Each `read()` sends a measurement request
 * (MR, an address-only write), waits 40 ms, fetches four bytes (data fetch, DF) and converts
 * them. If the status bits report stale data it waits 10 ms and fetches once more.
 *
 * The constructor performs no bus access: bytes written within 10 ms of power-up could be
 * taken as NVM programming commands.
 *
 * Default configuration (factory): 14-bit humidity and temperature resolution, fixed 40 ms
 * wait after the request, no NVM access.
 */
class HS30xxMinimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection pointing at the device (address 0x44).
     */
    constructor(connection) {
        this._conn = connection;
        this._wait = 40;
    }

    async _fetch(length) {
        // MR is an address-only write (no data byte); the DF read follows after the conversion.
        await this._conn.write(Buffer.alloc(0));
        await _sleep(this._wait);
        let data = await this._conn.read(length);
        if ((data[0] >> 6) !== 0) {
            await _sleep(10);
            data = await this._conn.read(length);
            if ((data[0] >> 6) !== 0) throw new Error('HS30xx returned stale data');
        }
        return data;
    }

    /**
     * Measure temperature and humidity (request, 40 ms wait, 4-byte fetch; the output is always scaled to 14 bits).
     * @returns {Promise<{temperature_c: number, humidity_pct: number}>} Temperature in °C (-40 to 125) and relative humidity in %RH (0 to 100).
     * @throws {Error} If the data are still flagged stale after one retry.
     */
    async read() {
        const data = await this._fetch(4);
        return {
            temperature_c: _toTemperature(_rawTemperature(data)),
            humidity_pct: _toHumidity(_rawHumidity(data)),
        };
    }
}

/**
 * HS30xx full interface — raw values, wait override, NVM resolution and ID, dew point.
 *
 * Extends `HS30xxMinimal`. The NVM methods (resolution, ID) need the sensor's programming
 * mode, which can only be entered within 10 ms after power-up. They therefore take a
 * `powerCycle` function that cuts and restores the sensor supply (e.g. via a MOSFET or a
 * GPIO powering the sensor); without it they throw.
 */
class HS30xxFull extends HS30xxMinimal {
    /** @param {import('../../connection/connection').Connection} connection - Configured I²C connection (address 0x44). */
    constructor(connection) {
        super(connection);
    }

    /**
     * Measure temperature only (4-byte data fetch, humidity discarded).
     * @returns {Promise<number>} Temperature in °C.
     */
    async readTemperature() {
        return _toTemperature(_rawTemperature(await this._fetch(4)));
    }

    /**
     * Measure humidity only (2-byte data fetch).
     * @returns {Promise<number>} Relative humidity in %RH.
     */
    async readHumidity() {
        return _toHumidity(_rawHumidity(await this._fetch(2)));
    }

    /**
     * Measure and return the raw 14-bit values and the status field, without a stale check.
     * @returns {Promise<{raw_humidity: number, raw_temperature: number, status: number}>} Status: 0 = valid, 1 = stale.
     */
    async readRaw() {
        await this._conn.write(Buffer.alloc(0));
        await _sleep(this._wait);
        const data = await this._conn.read(4);
        return { raw_humidity: _rawHumidity(data), raw_temperature: _rawTemperature(data), status: data[0] >> 6 };
    }

    /**
     * Override the wait between measurement request and data fetch (default 40 ms).
     * Lower resolutions convert faster: 0.1 ms + RH_time/2 + T_time/2 (datasheet).
     * @param {number} ms - Wait in milliseconds, greater than 0.
     */
    setMeasurementWait(ms) {
        if (!(ms > 0)) throw new RangeError('ms must be > 0');
        this._wait = ms;
    }

    /** Send the enter-programming-mode command (0xA0 0x00 0x00) and wait 120 µs; only valid within 10 ms after power-up. */
    async enterProgrammingMode() {
        await this._conn.write(_CMD_ENTER_PROG);
        await _sleep(1);
    }

    /** Send the leave-programming-mode command (0x80 0x00 0x00) and return to measurement operation. */
    async exitProgrammingMode() {
        await this._conn.write(_CMD_EXIT_PROG);
    }

    /**
     * Read one 16-bit NVM register (programming mode only).
     * @param {number} reg - NVM read address (0x06, 0x11, 0x1E or 0x1F).
     * @returns {Promise<number>} Register value (0 to 65535).
     * @throws {Error} If the response status byte is not 0x81.
     */
    async readNvm(reg) {
        await this._conn.write(Buffer.from([reg, 0x00, 0x00]));
        await _sleep(1);
        const data = await this._conn.read(3);
        if (data[0] !== _NVM_OK) throw new Error('HS30xx NVM read failed (status 0x' + data[0].toString(16) + ')');
        return (data[1] << 8) | data[2];
    }

    async _writeNvm(reg, value) {
        await this._conn.write(Buffer.from([reg, (value >> 8) & 0xFF, value & 0xFF]));
        await _sleep(14);
    }

    async _program(powerCycle) {
        if (typeof powerCycle !== 'function') throw new Error('HS30xx NVM access needs a powerCycle function');
        await powerCycle();
        await this.enterProgrammingMode();
    }

    /**
     * Read the NVM resolution settings (power-cycles the sensor).
     * @param {function(): (void|Promise<void>)} powerCycle - Cuts and restores the sensor supply; the programming-mode command follows immediately.
     * @returns {Promise<{humidity_bits: number, temperature_bits: number}>} Each 8, 10, 12 or 14.
     * @throws {Error} If powerCycle is missing or the NVM read fails.
     */
    async getResolution(powerCycle) {
        await this._program(powerCycle);
        try {
            const rh = await this.readNvm(_REG_RH_READ);
            const t = await this.readNvm(_REG_T_READ);
            return { humidity_bits: _BITS[(rh >> 10) & 3], temperature_bits: _BITS[(t >> 10) & 3] };
        } finally {
            await this.exitProgrammingMode();
        }
    }

    /**
     * Write the NVM resolution settings (power-cycles the sensor; bits 11:10 only).
     * Each register is read, bits 11:10 replaced and the whole word written back, then the driver waits 14 ms for the NVM commit.
     * @param {number|null} humidityBits - 8, 10, 12 or 14, or null to leave unchanged.
     * @param {number|null} temperatureBits - 8, 10, 12 or 14, or null to leave unchanged.
     * @param {function(): (void|Promise<void>)} powerCycle - Cuts and restores the sensor supply.
     * @throws {RangeError} If a value is not 8, 10, 12 or 14.
     * @throws {Error} If powerCycle is missing or an NVM read fails.
     */
    async setResolution(humidityBits, temperatureBits, powerCycle) {
        for (const bits of [humidityBits, temperatureBits]) {
            if (bits !== null && bits !== undefined && !_BITS.includes(bits)) {
                throw new RangeError('resolution must be 8, 10, 12 or 14 bits');
            }
        }
        await this._program(powerCycle);
        try {
            for (const [bits, rreg, wreg] of [[humidityBits, _REG_RH_READ, _REG_RH_WRITE],
                                              [temperatureBits, _REG_T_READ, _REG_T_WRITE]]) {
                if (bits !== null && bits !== undefined) {
                    const value = await this.readNvm(rreg);
                    await this._writeNvm(wreg, (value & ~_RES_MASK & 0xFFFF) | (_BITS.indexOf(bits) << 10));
                }
            }
        } finally {
            await this.exitProgrammingMode();
        }
    }

    /**
     * Read the 32-bit sensor ID from NVM (power-cycles the sensor).
     * @param {function(): (void|Promise<void>)} powerCycle - Cuts and restores the sensor supply.
     * @returns {Promise<number>} 32-bit ID.
     * @throws {Error} If powerCycle is missing or the NVM read fails.
     */
    async readId(powerCycle) {
        await this._program(powerCycle);
        try {
            const high = await this.readNvm(_REG_ID_HIGH);
            const low = await this.readNvm(_REG_ID_LOW);
            return ((high << 16) | low) >>> 0;
        } finally {
            await this.exitProgrammingMode();
        }
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

// The HS3001, HS3002 and HS3003 share one protocol and differ only in accuracy class and
// housing, so they are plain aliases of the HS30xx drivers.
module.exports = {
    HS30xxMinimal, HS30xxFull,
    HS3001Minimal: HS30xxMinimal, HS3002Minimal: HS30xxMinimal, HS3003Minimal: HS30xxMinimal,
    HS3001Full: HS30xxFull, HS3002Full: HS30xxFull, HS3003Full: HS30xxFull,
};
