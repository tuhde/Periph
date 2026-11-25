'use strict';

const SIZE = 0x0100;
const PAGE = 8;
const WRITE_TIMEOUT_MS = 10;   // tWR = 5 ms max, plus margin

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function _checkAddress(address) {
    if (!Number.isInteger(address) || address < 0 || address >= SIZE) {
        throw new RangeError('address out of range 0-255');
    }
}

function _checkRange(address, length) {
    if (!Number.isInteger(address) || !Number.isInteger(length) ||
        length < 0 || address < 0 || address + length > SIZE) {
        throw new RangeError('range outside 0-255');
    }
}

/**
 * AT24C02N 2 Kbit (256 B) I2C EEPROM — minimal interface.
 *
 * Self-timed writes (5 ms max) are completed by ACK polling: after every
 * write the driver polls the device address until the chip answers again, so
 * a resolved write is always committed. The chip has no configuration
 * registers; it is ready after power-up.
 *
 * Memory layout: 0x00-0x00FF, 8-bit address, big-endian on the wire,
 * 8 pages of 8 bytes.
 *
 * Writes are ignored by the chip while the WP pin is high (hardware only;
 * the driver cannot detect it — verify with a read-back if WP is wired).
 */
class AT24C02NMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection with regBytes=1, pointing at the device (address 0x50-0x57).
     */
    constructor(connection) {
        this._conn = connection;
    }

    /** ACK-poll: an address-only write is acknowledged once the write cycle has ended. */
    async _waitReady(timeoutMs) {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
            try {
                await this._conn.writeReg(0, Buffer.alloc(0));
                return true;
            } catch (e) {
                // NACK while the write cycle is running
            }
            if (Date.now() >= deadline) return false;
            await _sleep(1);
        }
    }

    async _commit() {
        if (!(await this._waitReady(WRITE_TIMEOUT_MS))) {
            throw new Error('AT24C02N write cycle did not complete');
        }
    }

    /**
     * Read a single byte (random read).
     * @param {number} address - Memory address 0-255.
     * @returns {Promise<number>} Byte value 0-255.
     * @throws {RangeError} If the address is outside 0-255.
     */
    async readByte(address) {
        _checkAddress(address);
        return (await this._conn.readReg(address, 1))[0];
    }

    /**
     * Write a single byte, then ACK-poll until the write cycle completes.
     * @param {number} address - Memory address 0-255.
     * @param {number} value - Byte value 0-255.
     * @returns {Promise<void>}
     * @throws {RangeError} If the address is outside 0-255.
     * @throws {Error} If the chip does not answer again within the write-cycle timeout.
     */
    async writeByte(address, value) {
        _checkAddress(address);
        await this._conn.writeReg(address, value & 0xFF);
        await this._commit();
    }
}

/**
 * AT24C02N full interface — extends minimal with block access and page writes.
 *
 * Writes are split at 8-byte page boundaries (a write crossing a boundary
 * would wrap inside the page and corrupt data); ranges that would run past
 * 0x00FF are rejected instead of wrapping to 0x0000 as the hardware would.
 *
 */
class AT24C02NFull extends AT24C02NMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection with regBytes=1 (address 0x50-0x57).
     */
    constructor(connection) {
        super(connection);
        this._maxChunk = 30;
    }

    /**
     * Maximum data bytes per bus transaction (default 30; Arduino Wire limits transfers to 32 bytes including the address byte).
     * @type {number}
     */
    get maxChunk() { return this._maxChunk; }

    set maxChunk(value) {
        if (!Number.isInteger(value) || value < 1) throw new RangeError('maxChunk must be >= 1');
        this._maxChunk = value;
    }

    /**
     * Array size in bytes.
     * @returns {number} 256.
     */
    size() { return SIZE; }

    /**
     * Write page size in bytes.
     * @returns {number} 8.
     */
    pageSize() { return PAGE; }

    /**
     * ACK-poll until the chip answers its device address again.
     * @param {number} timeoutMs - Maximum time to poll in milliseconds.
     * @returns {Promise<boolean>} True if the chip responded within the timeout.
     */
    async waitReady(timeoutMs) {
        return this._waitReady(timeoutMs);
    }

    /**
     * Sequential read, split into transactions of at most maxChunk bytes.
     * @param {number} address - Starting address 0-255.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} `length` bytes.
     * @throws {RangeError} If address..address+length is not inside 0x00-0x00FF.
     */
    async read(address, length) {
        _checkRange(address, length);
        const parts = [];
        while (length > 0) {
            const n = Math.min(length, this._maxChunk);
            parts.push(await this._conn.readReg(address, n));
            address += n;
            length -= n;
        }
        return Buffer.concat(parts);
    }

    /**
     * Write one page (or part of it) in a single transaction, then ACK-poll.
     * @param {number} address - Starting address 0-255.
     * @param {Buffer|number[]} data - 1-8 bytes, all inside the same 8-byte page.
     * @returns {Promise<void>}
     * @throws {RangeError} If the data crosses a page boundary, is longer than a page, or runs outside 0x00-0x00FF.
     */
    async writePage(address, data) {
        const buf = Buffer.from(data);
        _checkRange(address, buf.length);
        if (buf.length > PAGE || (address % PAGE) + buf.length > PAGE) {
            throw new RangeError('write crosses a page boundary');
        }
        if (buf.length === 0) return;
        await this._conn.writeReg(address, buf);
        await this._commit();
    }

    /**
     * Write `data` starting at `address`, split at page boundaries. Each chunk is
     * at most maxChunk bytes and never crosses a 8-byte page boundary; the write
     * cycle is ACK-polled after every chunk.
     * @param {number} address - Starting address 0-255.
     * @param {Buffer|number[]} data - Bytes to write.
     * @returns {Promise<void>}
     * @throws {RangeError} If address..address+data.length is not inside 0x00-0x00FF.
     */
    async write(address, data) {
        const buf = Buffer.from(data);
        _checkRange(address, buf.length);
        let off = 0;
        while (off < buf.length) {
            const room = PAGE - ((address + off) % PAGE);
            const n = Math.min(room, this._maxChunk, buf.length - off);
            await this._conn.writeReg(address + off, buf.subarray(off, off + n));
            await this._commit();
            off += n;
        }
    }

    /**
     * Write `value` over a range, page by page.
     * @param {number} address - Starting address 0-255.
     * @param {number} length - Number of bytes to fill.
     * @param {number} [value=0] - Byte value 0-255.
     * @returns {Promise<void>}
     * @throws {RangeError} If address..address+length is not inside 0x00-0x00FF.
     */
    async fill(address, length, value = 0) {
        _checkRange(address, length);
        let done = 0;
        while (done < length) {
            const room = PAGE - ((address + done) % PAGE);
            const n = Math.min(room, this._maxChunk, length - done);
            await this._conn.writeReg(address + done, Buffer.alloc(n, value & 0xFF));
            await this._commit();
            done += n;
        }
    }
}

module.exports = { AT24C02NMinimal, AT24C02NFull };
