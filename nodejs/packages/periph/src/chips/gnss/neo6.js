'use strict';

const SENTENCE_START = 0x24; // '$'
const CR = 0x0D;
const LF = 0x0A;
const MAX_SENTENCE = 96;

const UBX_SYNC1 = 0xB5;
const UBX_SYNC2 = 0x62;
const CLASS_ACK = 0x05;
const ID_ACK_NAK = 0x00;

/**
 * Validate the *XX checksum of a $...*XX\r\n NMEA sentence.
 * @param {Buffer} sentence - Full sentence including '$' and trailing CRLF.
 * @returns {boolean} True if the checksum matches.
 */
function nmeaChecksumOk(sentence) {
    const star = sentence.indexOf(0x2A); // '*'
    if (star < 1 || star + 4 > sentence.length) return false;
    let checksum = 0;
    for (let i = 1; i < star; i++) checksum ^= sentence[i];
    const expected = parseInt(sentence.toString('ascii', star + 1, star + 3), 16);
    if (Number.isNaN(expected)) return false;
    return checksum === expected;
}

/**
 * Convert NMEA ddmm.mmmm / dddmm.mmmm to signed decimal degrees.
 * @param {string} raw - Raw NMEA coordinate field.
 * @param {string} hemisphere - 'N', 'S', 'E', or 'W'.
 * @returns {number} Decimal degrees, negative for S/W.
 */
function nmeaToDegrees(raw, hemisphere) {
    const value = parseFloat(raw);
    const deg = Math.trunc(value / 100);
    const minutes = value - deg * 100;
    let decimal = deg + minutes / 60.0;
    if (hemisphere === 'S' || hemisphere === 'W') decimal = -decimal;
    return decimal;
}

/**
 * 8-bit Fletcher checksum over class, id, length, and payload bytes.
 * @param {Buffer} data - Bytes to checksum (class, id, length LE, payload).
 * @returns {[number, number]} [ckA, ckB].
 */
function ubxChecksum(data) {
    let ckA = 0, ckB = 0;
    for (let i = 0; i < data.length; i++) {
        ckA = (ckA + data[i]) & 0xFF;
        ckB = (ckB + ckA) & 0xFF;
    }
    return [ckA, ckB];
}

/**
 * u-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status.
 *
 * Reads bytes from the transport and assembles complete NMEA sentences
 * terminated by CR/LF. Works out of the box with the module's factory
 * defaults (NMEA output at 9600 baud, 1 Hz, all standard sentences
 * enabled) -- no chip-side configuration is sent.
 *
 * The driver is transport-agnostic; pass busType to match the transport
 * given at construction:
 *
 * - 'uart' (default): a UART transport. read() rejects on timeout; a
 *   rejection is treated as "no new byte this call", not an error.
 * - 'i2c': an I2C (DDC) transport. Each byte is fetched with a
 *   random-read to register 0xFF, per the DDC protocol.
 * - 'spi': an SPI transport. Each byte is fetched with a full-duplex
 *   transfer; writeRead() is called with an empty command so the
 *   module's real output byte is never discarded mid-transfer.
 *
 * A stray idle-filler byte (0xFF on I2C/SPI when the module has nothing
 * queued) can never start a sentence (NMEA sentences start with '$'); if
 * one lands mid-sentence during a buffer underrun, the resulting sentence
 * simply fails its checksum and is discarded, same as any other corrupted
 * sentence.
 */
class NEO6Minimal {
    /**
     * @param {object} transport - UART, I2C, or SPI transport.
     * @param {string} [busType='uart'] - 'uart', 'i2c', or 'spi'.
     */
    constructor(transport, busType = 'uart') {
        this._transport = transport;
        this._busType = busType;
        this._buf = [];
        this._inSentence = false;
        this._lat = null;
        this._lon = null;
        this._alt = null;
        this._fix = 0;
        this._satellites = 0;
    }

    /**
     * Fetch one byte if available; returns null if none is ready yet.
     * @returns {Promise<number|null>} The byte (0-255), or null.
     */
    async _readByte() {
        let b;
        if (this._busType === 'uart') {
            try {
                b = await this._transport.read(1);
            } catch (_) {
                return null;
            }
        } else if (this._busType === 'i2c') {
            // DDC random-read: set the register pointer to 0xFF, then read
            // one stream byte. The pointer saturates at 0xFF once set, so
            // re-sending it on every byte is redundant but harmless.
            b = await this._transport.writeRead(Buffer.from([0xFF]), 1);
        } else {
            // SPI has no register-address concept, so the write phase must
            // stay empty: writeRead(prefix, n) clocks prefix's response
            // bytes and discards them, and any non-empty prefix here would
            // throw away a real byte of the module's output stream. An
            // empty prefix makes the whole call one true 1:1 full-duplex
            // transfer, so no incoming byte is ever discarded.
            b = await this._transport.writeRead(Buffer.alloc(0), 1);
        }
        return (b && b.length) ? b[0] : null;
    }

    /**
     * Read available bytes and parse at most one complete NMEA sentence.
     * @returns {Promise<boolean>} True if a GGA sentence with a valid fix
     *     (fix status > 0) was parsed during this call.
     */
    async update() {
        const byte = await this._readByte();
        if (byte === null) return false;
        if (byte === SENTENCE_START) {
            this._buf = [byte];
            this._inSentence = true;
            return false;
        }
        if (!this._inSentence) return false;
        this._buf.push(byte);
        if (this._buf.length > MAX_SENTENCE) {
            this._buf = [];
            this._inSentence = false;
            return false;
        }
        if (byte === LF && this._buf.length >= 2 && this._buf[this._buf.length - 2] === CR) {
            const sentence = Buffer.from(this._buf);
            this._buf = [];
            this._inSentence = false;
            return this._onSentence(sentence);
        }
        return false;
    }

    _onSentence(sentence) {
        if (!nmeaChecksumOk(sentence)) return false;
        const star = sentence.indexOf(0x2A);
        if (star < 0) return false;
        const body = sentence.toString('ascii', 1, star);
        const fields = body.split(',');
        if (fields[0].length < 5) return false;
        const sentenceId = fields[0].substring(2, 5);
        let result = false;
        if (sentenceId === 'GGA') result = this._parseGga(fields);
        this._handleExtra(sentenceId, fields);
        return result;
    }

    _parseGga(fields) {
        if (fields.length < 15) return false;
        const fix = fields[6] ? parseInt(fields[6], 10) : 0;
        this._fix = Number.isNaN(fix) ? 0 : fix;
        const sats = fields[7] ? parseInt(fields[7], 10) : 0;
        this._satellites = Number.isNaN(sats) ? 0 : sats;
        if (this._fix > 0 && fields[2] && fields[3] && fields[4] && fields[5]) {
            this._lat = nmeaToDegrees(fields[2], fields[3]);
            this._lon = nmeaToDegrees(fields[4], fields[5]);
            this._alt = fields[9] ? parseFloat(fields[9]) : null;
        }
        return this._fix > 0;
    }

    /**
     * Hook for Full to parse additional sentence types. No-op here.
     * @param {string} sentenceId - Three-letter NMEA sentence ID (e.g. 'RMC').
     * @param {string[]} fields - Comma-split fields, including the talker+ID at index 0.
     */
    _handleExtra(sentenceId, fields) {
        // no-op in Minimal
    }

    /**
     * Latitude of the last valid fix.
     * @returns {number|null} Decimal degrees, positive north; null until the first valid GGA fix.
     */
    latitude() { return this._lat; }

    /**
     * Longitude of the last valid fix.
     * @returns {number|null} Decimal degrees, positive east; null until the first valid GGA fix.
     */
    longitude() { return this._lon; }

    /**
     * Height above mean sea level of the last valid fix.
     * @returns {number|null} Meters; null until the first valid GGA fix.
     */
    altitude() { return this._alt; }

    /**
     * GGA fix quality of the last parsed GGA sentence.
     * @returns {number} 0 = no fix, 1 = GPS, 2 = DGPS.
     */
    fix() { return this._fix; }

    /**
     * Number of satellites used in the last GGA fix.
     * @returns {number} Satellite count (GGA field 7).
     */
    satellites() { return this._satellites; }
}

/**
 * NEO-6 with UBX binary messaging, rate/platform configuration, and richer
 * NMEA fields (speed, course, UTC time/date, HDOP).
 *
 * Extends NEO6Minimal; all Minimal methods are inherited unchanged.
 */
class NEO6Full extends NEO6Minimal {
    /**
     * @param {object} transport - UART, I2C, or SPI transport.
     * @param {string} [busType='uart'] - 'uart', 'i2c', or 'spi'.
     */
    constructor(transport, busType = 'uart') {
        super(transport, busType);
        this._speed = null;
        this._course = null;
        this._utcTime = null;
        this._utcDate = null;
        this._hdop = null;
    }

    _handleExtra(sentenceId, fields) {
        if (sentenceId === 'GGA') {
            if (fields.length > 1 && fields[1]) this._utcTime = fields[1];
            if (fields.length > 8 && fields[8]) {
                const hdop = parseFloat(fields[8]);
                if (!Number.isNaN(hdop)) this._hdop = hdop;
            }
        } else if (sentenceId === 'RMC') {
            this._parseRmc(fields);
        } else if (sentenceId === 'VTG') {
            this._parseVtg(fields);
        }
    }

    _parseRmc(fields) {
        if (fields.length < 10) return;
        if (fields[1]) this._utcTime = fields[1];
        if (fields[7]) {
            const knots = parseFloat(fields[7]);
            if (!Number.isNaN(knots)) this._speed = knots * 0.514444;
        }
        if (fields[8]) {
            const course = parseFloat(fields[8]);
            if (!Number.isNaN(course)) this._course = course;
        }
        if (fields[9]) this._utcDate = fields[9];
    }

    _parseVtg(fields) {
        if (fields.length > 1 && fields[1]) {
            const course = parseFloat(fields[1]);
            if (!Number.isNaN(course)) this._course = course;
        }
        if (fields.length > 7 && fields[7]) {
            const kmh = parseFloat(fields[7]);
            if (!Number.isNaN(kmh)) this._speed = kmh / 3.6;
        }
    }

    /**
     * Speed over ground.
     * @returns {number|null} Meters per second, converted from RMC/VTG; null until the first speed field is parsed.
     */
    speed() { return this._speed; }

    /**
     * Course over ground.
     * @returns {number|null} Degrees, 0-360, from RMC/VTG; null until the first course field is parsed.
     */
    course() { return this._course; }

    /**
     * UTC time of the last GGA or RMC sentence.
     * @returns {string|null} 'hhmmss.ss'; null until the first sentence with a time field is parsed.
     */
    utcTime() { return this._utcTime; }

    /**
     * UTC date of the last RMC sentence.
     * @returns {string|null} 'ddmmyy'; null until the first RMC sentence is parsed.
     */
    utcDate() { return this._utcDate; }

    /**
     * Horizontal dilution of precision from the last GGA sentence.
     * @returns {number|null} Unitless HDOP; null until the first GGA sentence with a populated HDOP field is parsed.
     */
    hdop() { return this._hdop; }

    /**
     * Frame and write a UBX message (adds sync bytes, length, checksum).
     * @param {number} msgClass - UBX message class (e.g. 0x06 for CFG).
     * @param {number} msgId - UBX message ID within the class.
     * @param {Buffer} [payload] - Message payload bytes; default empty (a poll request).
     * @returns {Promise<void>}
     */
    async sendUbx(msgClass, msgId, payload = Buffer.alloc(0)) {
        const length = payload.length;
        const body = Buffer.concat([Buffer.from([msgClass, msgId, length & 0xFF, (length >> 8) & 0xFF]), payload]);
        const [ckA, ckB] = ubxChecksum(body);
        const frame = Buffer.concat([Buffer.from([UBX_SYNC1, UBX_SYNC2]), body, Buffer.from([ckA, ckB])]);
        await this._transport.write(frame);
    }

    /**
     * Send a poll request and return the response payload.
     * @param {number} msgClass - UBX message class to poll.
     * @param {number} msgId - UBX message ID to poll.
     * @returns {Promise<Buffer>} The response message's payload.
     * @throws {Error} If the module answers with ACK-NAK, or no matching
     *     response arrives before the internal idle budget is spent.
     */
    async pollUbx(msgClass, msgId) {
        await this.sendUbx(msgClass, msgId);
        return this._readUbxResponse(msgClass, msgId);
    }

    async _readUbxResponse(wantClass, wantId, maxFrames = 400, maxIdle = 4000) {
        let idle = 0;
        let frames = 0;
        while (frames < maxFrames) {
            const byte = await this._readByte();
            if (byte === null) {
                idle++;
                if (idle > maxIdle) throw new Error('UBX response timeout');
                continue;
            }
            idle = 0;
            if (byte !== UBX_SYNC1) continue;
            if ((await this._readByte()) !== UBX_SYNC2) continue;
            const header = [];
            let headerOk = true;
            for (let i = 0; i < 4; i++) {
                const b = await this._readByte();
                if (b === null) { headerOk = false; break; }
                header.push(b);
            }
            if (!headerOk) continue;
            const [cls, mid, lenLo, lenHi] = header;
            const length = lenLo | (lenHi << 8);
            const payload = [];
            for (let i = 0; i < length; i++) {
                const b = await this._readByte();
                if (b === null) break;
                payload.push(b);
            }
            if (payload.length !== length) { frames++; continue; }
            const ckA = await this._readByte();
            const ckB = await this._readByte();
            const [expA, expB] = ubxChecksum(Buffer.from([cls, mid, lenLo, lenHi, ...payload]));
            frames++;
            if (ckA !== expA || ckB !== expB) continue;
            if (cls === CLASS_ACK && mid === ID_ACK_NAK) {
                throw new Error(`UBX NAK for class 0x${wantClass.toString(16)} id 0x${wantId.toString(16)}`);
            }
            if (cls === wantClass && mid === wantId) return Buffer.from(payload);
        }
        throw new Error('UBX response timeout');
    }

    /**
     * Set the navigation update rate via CFG-RATE.
     * @param {number} hz - Update rate in Hz (1-5 Hz for standard NEO-6 models).
     * @returns {Promise<void>}
     */
    async setRate(hz) {
        const measRateMs = Math.trunc(1000 / hz);
        const payload = Buffer.alloc(6);
        payload.writeUInt16LE(measRateMs, 0);
        payload.writeUInt16LE(1, 2);
        payload.writeUInt16LE(0, 4);
        await this.sendUbx(0x06, 0x08, payload);
    }

    /**
     * Set the dynamic platform model via CFG-NAV5.
     * @param {number} model - Platform model code -- 0=portable, 2=stationary,
     *     3=pedestrian, 4=automotive, 5=sea, 6=airborne<1g, 7=airborne<2g, 8=airborne<4g.
     * @returns {Promise<void>}
     */
    async setPlatform(model) {
        const payload = Buffer.alloc(36);
        payload.writeUInt16LE(0x0001, 0); // mask: apply dynModel only
        payload[2] = model & 0xFF;
        await this.sendUbx(0x06, 0x24, payload);
    }

    /**
     * Force a cold start via CFG-RST (clears almanac, ephemeris, and last known position).
     * @returns {Promise<void>}
     */
    async coldStart() {
        const payload = Buffer.alloc(4);
        payload.writeUInt16LE(0xFFFF, 0);
        payload[2] = 0x02;
        payload[3] = 0x00;
        await this.sendUbx(0x06, 0x04, payload);
    }

    /**
     * Persist the current configuration via CFG-CFG (saves to battery-backed RAM and flash, where available).
     * @returns {Promise<void>}
     */
    async saveConfig() {
        const payload = Buffer.alloc(13);
        payload.writeUInt32LE(0x00000000, 0);
        payload.writeUInt32LE(0xFFFFFFFF, 4);
        payload.writeUInt32LE(0x00000000, 8);
        payload[12] = 0x07;
        await this.sendUbx(0x06, 0x09, payload);
    }
}

module.exports = { NEO6Minimal, NEO6Full };
