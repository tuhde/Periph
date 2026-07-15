'use strict';

const ADDR_UID_BASE   = 0xFC;
const ADDR_MFR_CODE   = 0xFA;
const ADDR_DEV_CODE   = 0xFB;
const WRITE_CYCLE_MS  = 5;
const ACK_POLL_MAX    = 20;
const PAGE_SIZE       = 8;

function _sleep(ms) {
    const end = Date.now() + ms;
    while (Date.now() < end) {}
}

/**
 * 24AA02UID 2K I2C EEPROM with 32-bit unique serial number — minimal interface.
 *
 * Provides read access to the chip's unique 32-bit serial number and basic
 * single-byte read/write access to the user EEPROM. The chip has no
 * configuration registers; it is ready for use immediately after power-on.
 *
 * Memory layout:
 * - 0x00-0x7F — 128 bytes general-purpose user EEPROM (R/W)
 * - 0x80-0xF9 — reserved, read-only
 * - 0xFA      — manufacturer code (always 0x29, Microchip)
 * - 0xFB      — device code (always 0x41)
 * - 0xFC-0xFF — 32-bit unique serial number, MSB first
 *
 * Default configuration (no registers to configure):
 * - User EEPROM is read/written as raw bytes (no interpretation)
 * - writeByte() waits for the internal write cycle to finish via
 *   ACK polling before returning (max 5 ms)
 * - All addresses 0x80-0xFF are write-protected; writes are silently
 *   ignored by the chip. Drivers accept the call but the data is not
 *   retained.
 */
class EEPROM24AA02UIDMinimal {
    /**
     * @param {object} transport - Configured I2C transport pointing at the device (address 0x50).
     */
    constructor(transport) {
        this._transport = transport;
    }

    /**
     * Read the chip's factory-programmed 32-bit unique serial number.
     * @returns {Buffer} 4-byte UID, MSB first (0xFC, 0xFD, 0xFE, 0xFF).
     */
    readUid() {
        return this._transport.writeRead(Buffer.from([ADDR_UID_BASE]), 4);
    }

    /**
     * Read a single byte from user EEPROM at 0x00-0x7F.
     * @param {number} address - Memory address 0-127.
     * @returns {number} Byte value 0-255.
     */
    readByte(address) {
        return this._transport.writeRead(Buffer.from([address & 0xFF]), 1)[0];
    }

    /**
     * Write a single byte to user EEPROM at 0x00-0x7F and wait for completion.
     *
     * Sends the byte, then ACK-polls the chip until the internal write
     * cycle completes (max 5 ms). Writes to 0x80-0xFF are accepted by the
     * device but silently ignored (write-protected region).
     *
     * @param {number} address - Memory address 0-255.
     * @param {number} value   - Byte value 0-255.
     */
    writeByte(address, value) {
        this._transport.write(Buffer.from([address & 0xFF, value & 0xFF]));
        this._ackPoll();
    }

    _ackPoll() {
        for (let i = 0; i < ACK_POLL_MAX; i++) {
            try {
                this._transport.writeRead(Buffer.from([0x00]), 1);
                return;
            } catch (e) {
                _sleep(1);
            }
        }
    }
}

/**
 * 24AA02UID full interface — extends minimal with multi-byte read/write.
 *
 * Adds sequential read, raw page write (8-byte page), arbitrary-length
 * write that automatically crosses page boundaries, and accessors for
 * the manufacturer and device codes in the upper (read-only) block.
 */
class EEPROM24AA02UIDFull extends EEPROM24AA02UIDMinimal {
    /**
     * @param {object} transport - Configured I2C transport pointing at the device (address 0x50).
     */
    constructor(transport) {
        super(transport);
    }

    /**
     * Sequential read of `length` bytes starting at `address`.
     *
     * The internal address pointer auto-increments; reads may cross any
     * boundary and wrap at the end of the 256-byte address space.
     *
     * @param {number} address - Starting address 0-255.
     * @param {number} length  - Number of bytes to read.
     * @returns {Buffer} `length` bytes from the device.
     */
    read(address, length) {
        return this._transport.writeRead(Buffer.from([address & 0xFF]), length);
    }

    /**
     * Write up to 8 bytes within a single 8-byte page.
     *
     * The caller is responsible for ensuring all bytes lie within the
     * same page. Bytes that would overflow the page boundary wrap to the
     * start of the same page (FIFO overwrite) — use write() to handle
     * boundaries automatically.
     *
     * @param {number} address - Start address within an 8-byte page (0, 8, 16, …).
     * @param {Buffer|Uint8Array} data - Bytes to write (1 to 8 bytes).
     */
    writePage(address, data) {
        if (data.length === 0) return;
        const buf = Buffer.alloc(1 + data.length);
        buf[0] = address & 0xFF;
        for (let i = 0; i < data.length; i++) buf[1 + i] = data[i];
        this._transport.write(buf);
        this._ackPoll();
    }

    /**
     * Write an arbitrary-length buffer, splitting at 8-byte page boundaries.
     *
     * Automatically splits the write into page-aligned chunks and waits
     * for the write cycle of each chunk before continuing.
     *
     * @param {number} address - Starting address 0-255.
     * @param {Buffer|Uint8Array} data - Bytes to write.
     */
    write(address, data) {
        let offset = 0;
        let remaining = data.length;
        let current = address;
        while (remaining > 0) {
            const pageOffset = current & (PAGE_SIZE - 1);
            let chunk = PAGE_SIZE - pageOffset;
            if (chunk > remaining) chunk = remaining;
            this.writePage(current, data.slice(offset, offset + chunk));
            offset += chunk;
            current += chunk;
            remaining -= chunk;
        }
    }

    /**
     * Read the manufacturer code at 0xFA.
     * @returns {number} Manufacturer code; expect 0x29 (Microchip).
     */
    readManufacturerCode() {
        return this.readByte(ADDR_MFR_CODE);
    }

    /**
     * Read the device code at 0xFB.
     * @returns {number} Device code; expect 0x41 (I2C 2-Kbit EEPROM).
     */
    readDeviceCode() {
        return this.readByte(ADDR_DEV_CODE);
    }
}

module.exports = { EEPROM24AA02UIDMinimal, EEPROM24AA02UIDFull };
