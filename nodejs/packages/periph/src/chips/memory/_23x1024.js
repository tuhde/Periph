'use strict';

const CMD_READ  = 0x03;
const CMD_WRITE = 0x02;
const CMD_RDMR  = 0x05;
const CMD_WRMR  = 0x01;
const CMD_RSTIO = 0xFF;
const MODE_MASK = 0xC0;

const SIZE = 131072;
const PAGE_SIZE = 32;
const MAX_CHUNK_LIMIT = 256;

/**
 * 23LC1024 / 23A1024 1-Mbit SPI serial SRAM — minimal interface.
 *
 * Byte read/write access to the 131,072-byte array over plain SPI (Mode 0).
 * The chip is volatile but has unlimited cycles and zero write time: a write
 * is complete when CS is released, with no polling.
 *
 * Default configuration:
 * - Sequential mode (MODE register = 0x40), written and read back by
 *   init(); a mismatch (floating/missing MISO, wrong SPI mode, chip stuck
 *   in SDI/SQI mode) rejects with an Error.
 * - Full 128 KiB addressable; SDI/SQI bus modes are never used.
 *
 * Every transaction is one instruction byte, a 24-bit big-endian address and
 * data, with CS held low for the whole frame, so the driver takes the bare
 * Connection and uses write()/writeRead() directly (the instruction is not a
 * register address, so RegisterConnection's read-bit logic does not apply).
 *
 * Call `await chip.init()` before use (the constructor cannot await bus I/O).
 */
class SRAM23X1024Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - SPI connection (Mode 0, MSB first, CS managed by the connection).
     */
    constructor(connection) {
        this._conn = connection;
        this._mode = 0x40;
    }

    /**
     * Select Sequential mode and verify it with a MODE register round-trip.
     * @returns {Promise<void>}
     */
    async init() {
        await this._writeMode(0x40);
        if ((await this._readMode()) !== 0x40) {
            throw new Error('23X1024 MODE register mismatch: check wiring and SPI Mode 0');
        }
        this._mode = 0x40;
    }

    async _readMode() {
        const r = await this._conn.writeRead(Buffer.from([CMD_RDMR]), 1);
        return r[0];
    }

    async _writeMode(mode) {
        await this._conn.write(Buffer.from([CMD_WRMR, mode]));
    }

    static _header(command, address) {
        return Buffer.from([command, (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF]);
    }

    async _readRaw(address, length) {
        const r = await this._conn.writeRead(SRAM23X1024Minimal._header(CMD_READ, address), length);
        return Buffer.from(r);
    }

    async _writeRaw(address, data) {
        await this._conn.write(Buffer.concat([SRAM23X1024Minimal._header(CMD_WRITE, address), Buffer.from(data)]));
    }

    /**
     * Read one byte (READ, 0x03).
     * @param {number} address - 0–131071.
     * @returns {Promise<number>} Byte value 0–255.
     */
    async readByte(address) {
        if (!Number.isInteger(address) || address < 0 || address >= SIZE) throw new RangeError('address out of range');
        return (await this._readRaw(address, 1))[0];
    }

    /**
     * Write one byte (WRITE, 0x02); complete when CS is released.
     * @param {number} address - 0–131071.
     * @param {number} value   - Byte value 0–255.
     * @returns {Promise<void>}
     */
    async writeByte(address, value) {
        if (!Number.isInteger(address) || address < 0 || address >= SIZE) throw new RangeError('address out of range');
        await this._writeRaw(address, Buffer.from([value & 0xFF]));
    }
}

/**
 * 23X1024 full interface — extends minimal with block access, operating modes and I/O reset.
 *
 * read()/write()/fill() have linear-address semantics in every mode: the
 * driver splits transfers into transactions of at most maxChunk data bytes
 * and, in Page/Byte mode, at the 32-byte page / single-byte boundaries the
 * hardware would otherwise wrap or truncate at. Ranges that would leave the
 * array reject with a RangeError instead of silently wrapping around.
 */
class SRAM23X1024Full extends SRAM23X1024Minimal {
    constructor(connection) {
        super(connection);
        this._maxChunk = 256;
    }

    /** @returns {number} Maximum data bytes per bus transaction (1–256, default 256). */
    get maxChunk() { return this._maxChunk; }

    /** @param {number} n - Maximum data bytes per bus transaction (1–256). */
    set maxChunk(n) {
        if (!Number.isInteger(n) || n < 1 || n > MAX_CHUNK_LIMIT) throw new RangeError('maxChunk must be 1-256');
        this._maxChunk = n;
    }

    _chunkLen(address, remaining) {
        let n = Math.min(remaining, this._maxChunk);
        if (this._mode === SRAM23X1024Full.MODE_BYTE) return 1;
        if (this._mode === SRAM23X1024Full.MODE_PAGE) n = Math.min(n, PAGE_SIZE - (address % PAGE_SIZE));
        return n;
    }

    static _checkRange(address, length) {
        if (!Number.isInteger(address) || !Number.isInteger(length) || address < 0 || length < 0 || address + length > SIZE) {
            throw new RangeError('range leaves the 128 KiB array');
        }
    }

    /**
     * Block read of `length` bytes starting at `address`.
     * @param {number} address - Start address; address + length must be <= 131072.
     * @param {number} length  - Number of bytes.
     * @returns {Promise<Buffer>}
     */
    async read(address, length) {
        SRAM23X1024Full._checkRange(address, length);
        const parts = [];
        while (length > 0) {
            const n = this._chunkLen(address, length);
            parts.push(await this._readRaw(address, n));
            address += n; length -= n;
        }
        return Buffer.concat(parts);
    }

    /**
     * Block write of `data` starting at `address` (same range rule as read()).
     * @param {number} address - Start address.
     * @param {Buffer|number[]} data - Bytes to write.
     * @returns {Promise<void>}
     */
    async write(address, data) {
        const buf = Buffer.from(data);
        SRAM23X1024Full._checkRange(address, buf.length);
        let pos = 0;
        while (pos < buf.length) {
            const n = this._chunkLen(address, buf.length - pos);
            await this._writeRaw(address, buf.subarray(pos, pos + n));
            address += n; pos += n;
        }
    }

    /**
     * Write `value` over `length` bytes starting at `address` (chunked).
     * @param {number} address - Start address.
     * @param {number} length  - Number of bytes.
     * @param {number} [value=0] - Byte value.
     * @returns {Promise<void>}
     */
    async fill(address, length, value = 0) {
        SRAM23X1024Full._checkRange(address, length);
        const block = Buffer.alloc(PAGE_SIZE, value & 0xFF);
        while (length > 0) {
            const n = Math.min(this._chunkLen(address, length), block.length);
            await this._writeRaw(address, block.subarray(0, n));
            address += n; length -= n;
        }
    }

    /** @returns {number} Array size in bytes (131072). */
    size() { return SIZE; }

    /**
     * Select the operating mode (WRMR) and verify it with RDMR.
     * @param {number} mode - SRAM23X1024Full.MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL; any other value rejects with a RangeError.
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        if (mode !== SRAM23X1024Full.MODE_BYTE && mode !== SRAM23X1024Full.MODE_PAGE && mode !== SRAM23X1024Full.MODE_SEQUENTIAL) {
            throw new RangeError('mode must be MODE_BYTE, MODE_PAGE or MODE_SEQUENTIAL');
        }
        await this._writeMode(mode);
        if ((await this._readMode()) !== mode) throw new Error('23X1024 MODE register read-back mismatch');
        this._mode = mode;
    }

    /**
     * Read the operating mode (RDMR bits 7:6).
     * @returns {Promise<number>} One of the MODE_* constants (0xC0 if reserved).
     */
    async getMode() {
        return (await this._readMode()) & MODE_MASK;
    }

    /**
     * Send RSTIO (0xFF) to return the chip to SPI mode after a stray EDIO/EQIO.
     * Resynchronises the cached mode with the chip (re-reads RDMR).
     * @returns {Promise<void>}
     */
    async resetIo() {
        await this._conn.write(Buffer.from([CMD_RSTIO]));
        this._mode = await this.getMode();
    }
}

SRAM23X1024Full.MODE_BYTE = 0x00;
SRAM23X1024Full.MODE_PAGE = 0x80;
SRAM23X1024Full.MODE_SEQUENTIAL = 0x40;
SRAM23X1024Minimal.SIZE = SIZE;

module.exports = { SRAM23X1024Minimal, SRAM23X1024Full };
