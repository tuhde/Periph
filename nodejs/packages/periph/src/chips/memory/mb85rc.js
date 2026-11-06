'use strict';

const SIZE = 0x8000;
const DEVICE_ID_BASE = 0xA0;

function _checkAddress(address) {
    if (!Number.isInteger(address) || address < 0 || address >= SIZE) {
        throw new RangeError('address out of range 0-32767');
    }
}

function _checkRange(address, length) {
    if (!Number.isInteger(address) || !Number.isInteger(length) ||
        length < 0 || address < 0 || address + length > SIZE) {
        throw new RangeError('range outside 0-32767');
    }
}

/**
 * MB85RC256V 256 Kbit (32 KiB) I2C FRAM — minimal interface.
 *
 * Ferroelectric RAM: non-volatile like EEPROM, but every write completes at
 * bus speed — no write cycle, no page buffer, no ACK polling, and at least
 * 10^12 write cycles per byte. The chip has no configuration registers.
 *
 * Memory layout: 0x0000-0x7FFF, 15-bit address, big-endian on the wire.
 *
 * Writes are ignored by the chip while the WP pin is high (hardware only;
 * the driver cannot detect it).
 */
class MB85RCMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection with regBytes=2, pointing at the device (address 0x50-0x57).
     */
    constructor(connection) {
        this._conn = connection;
    }

    /**
     * Read a single byte (random read).
     * @param {number} address - Memory address 0-32767.
     * @returns {Promise<number>} Byte value 0-255.
     * @throws {RangeError} If the address is outside 0-32767.
     */
    async readByte(address) {
        _checkAddress(address);
        return (await this._conn.readReg(address, 1))[0];
    }

    /**
     * Write a single byte (byte write); resolves when the transfer completes.
     * @param {number} address - Memory address 0-32767.
     * @param {number} value - Byte value 0-255.
     * @returns {Promise<void>}
     * @throws {RangeError} If the address is outside 0-32767.
     */
    async writeByte(address, value) {
        _checkAddress(address);
        await this._conn.writeReg(address, value & 0xFF);
    }
}

/**
 * MB85RC256V full interface — extends minimal with block access and Device ID.
 *
 * Ranges that would run past 0x7FFF are rejected instead of silently
 * wrapping to 0x0000 as the hardware would do.
 */
class MB85RCFull extends MB85RCMinimal {
    /**
     * @param {import('../../connection/register_connection').RegisterConnection} connection - I²C or SMBus register connection with regBytes=2 (address 0x50-0x57).
     */
    constructor(connection) {
        super(connection);
        this._maxChunk = 30;
    }

    /**
     * Maximum data bytes per bus transaction (default 30; Arduino Wire limits transfers to 32 bytes including the 2 address bytes).
     * @type {number}
     */
    get maxChunk() { return this._maxChunk; }

    set maxChunk(value) {
        if (!Number.isInteger(value) || value < 1) throw new RangeError('maxChunk must be >= 1');
        this._maxChunk = value;
    }

    /**
     * Array size in bytes.
     * @returns {number} 32768.
     */
    size() { return SIZE; }

    /**
     * Sequential read, split into transactions of at most maxChunk bytes.
     * @param {number} address - Starting address 0-32767.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} `length` bytes.
     * @throws {RangeError} If address..address+length is not inside 0x0000-0x7FFF.
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
     * Sequential write, split into transactions of at most maxChunk bytes; no delay between chunks.
     * @param {number} address - Starting address 0-32767.
     * @param {Buffer|number[]} data - Bytes to write.
     * @returns {Promise<void>}
     * @throws {RangeError} If address..address+data.length is not inside 0x0000-0x7FFF.
     */
    async write(address, data) {
        const buf = Buffer.from(data);
        _checkRange(address, buf.length);
        for (let off = 0; off < buf.length; off += this._maxChunk) {
            const chunk = buf.subarray(off, off + this._maxChunk);
            await this._conn.writeReg(address + off, chunk);
        }
    }

    /**
     * Write `value` over a range, in maxChunk-sized transactions.
     * @param {number} address - Starting address 0-32767.
     * @param {number} length - Number of bytes to fill.
     * @param {number} [value=0] - Byte value 0-255.
     * @returns {Promise<void>}
     * @throws {RangeError} If address..address+length is not inside 0x0000-0x7FFF.
     */
    async fill(address, length, value = 0) {
        _checkRange(address, length);
        while (length > 0) {
            const n = Math.min(length, this._maxChunk);
            await this._conn.writeReg(address, Buffer.alloc(n, value & 0xFF));
            address += n;
            length -= n;
        }
    }

    /**
     * Read the 3-byte Device ID via the reserved slave address 0x7C.
     * Linux i2c-dev may refuse addresses 0x78-0x7F, in which case this rejects.
     * @param {import('../../connection/register_connection').RegisterConnection} idConnection - regBytes=1 connection bound to I2C address 0x7C.
     * @param {number} [address=0x50] - 7-bit address of the FRAM (0x50-0x57), used to build the device address word.
     * @returns {Promise<{manufacturer: number, density: number, product: number}>} {0x00A, 0x5, 0x10} for the MB85RC256V.
     */
    async readDeviceId(idConnection, address = 0x50) {
        const word = DEVICE_ID_BASE + ((address & 0x07) << 1);
        const raw = await idConnection.readReg(word, 3);
        return {
            manufacturer: (raw[0] << 4) | (raw[1] >> 4),
            density: raw[1] & 0x0F,
            product: raw[2],
        };
    }
}

module.exports = { MB85RCMinimal, MB85RCFull };
