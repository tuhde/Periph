'use strict';

const spi = require('spi-device');
const { RegisterConnection } = require('./register_connection');

/**
 * SPI connection for Node.js (wraps spi-device).
 *
 * Opens the spidev device synchronously at construction. CS is managed by
 * the kernel spidev driver. Call close() when done.
 */
class SPIConnection extends RegisterConnection {
    /**
     * @param {number} busNumber    - SPI bus number.
     * @param {number} deviceNumber - Chip-select line on the bus.
     * @param {object} [options]
     * @param {number} [options.mode=spi.MODE0]       - SPI mode (0–3).
     * @param {number} [options.maxSpeedHz=1_000_000] - Clock frequency in Hz.
     * @param {number} [options.readBit=0x80]         - Bit ORed into the command byte
     *   for a read; 0 if the chip has no such bit.
     * @param {number|null} [options.multiByteBit=null] - Bit ORed in for multi-byte
     *   (burst) transfers when length > 1; null if the chip has no such bit and
     *   always auto-increments.
     * @param {import('./input_pin').InputPin|null} [options.intPin=null] - Optional INT-line InputPin.
     * @param {import('./output_pin').OutputPin|null} [options.enPin=null] - Optional EN-pin OutputPin.
     * @param {boolean} [options.enActiveHigh=true] - True if the EN pin is active-high (default); false for active-low.
     * @param {import('./output_pin').OutputPin|null} [options.csPin=null] - OutputPin that drives chip select
     *   itself. When given, the kernel's CS is disabled (`noChipSelect`) and the optional extensions
     *   `idleClocks` / `select` / `deselect` work (needed by SD cards); without it they reject.
     */
    constructor(busNumber, deviceNumber, options = {}) {
        super(options.intPin ?? null, options.enPin ?? null, options.enActiveHigh ?? true);
        this._device = spi.openSync(busNumber, deviceNumber, {
            mode: options.mode ?? spi.MODE0,
            maxSpeedHz: options.maxSpeedHz ?? 1_000_000,
            ...(options.csPin ? { noChipSelect: true } : {}),
        });
        this._csPin = options.csPin ?? null;
        this._held = false;
        if (this._csPin) this._csPin.set(true);
        this._readBit = options.readBit ?? 0x80;
        this._multiByteBit = options.multiByteBit ?? null;
    }

    /**
     * Read `length` bytes starting at register `reg`, building the SPI command byte.
     * @param {number} reg    - Register address.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device.
     */
    async readReg(reg, length) {
        let cmd = reg | this._readBit;
        if (length > 1 && this._multiByteBit) cmd |= this._multiByteBit;
        return this.writeRead(Buffer.from([cmd]), length);
    }

    /**
     * Write `data` to register `reg`, building the SPI command byte.
     * @param {number} reg - Register address.
     * @param {Buffer|Uint8Array|number} data - Bytes to write, or a single int for a 1-byte register.
     * @returns {Promise<void>}
     */
    async writeReg(reg, data) {
        const payload = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'number' ? [data] : data);
        const cmd = (reg & ~this._readBit) | ((payload.length > 1 && this._multiByteBit) ? this._multiByteBit : 0);
        return this.write(Buffer.concat([Buffer.from([cmd]), payload]));
    }

    /**
     * Send bytes to the device.
     * @param {Buffer|Uint8Array} data - Bytes to send.
     */
    /**
     * Clock `n` bits of 0xFF with CS deasserted (rounded up to whole bytes).
     * @param {number} n
     * @returns {Promise<void>}
     */
    async idleClocks(n) {
        if (!this._csPin) throw new Error('idleClocks needs options.csPin (noChipSelect + GPIO chip select)');
        await this._csPin.set(true);
        const sendBuffer = Buffer.alloc(Math.ceil(n / 8), 0xFF);
        this._device.transferSync([{ sendBuffer, byteLength: sendBuffer.length }]);
        if (this._held) await this._csPin.set(false);
    }

    /**
     * Change the SCK frequency.
     * @param {number} hz
     * @returns {Promise<boolean>} true
     */
    async setSpeed(hz) {
        this._device.setOptionsSync({ maxSpeedHz: hz });
        return true;
    }

    /**
     * Assert CS and hold it low across following operations until deselect().
     * @returns {Promise<void>}
     */
    async select() {
        if (!this._csPin) throw new Error('select needs options.csPin (noChipSelect + GPIO chip select)');
        await this._csPin.set(false);
        this._held = true;
    }

    /**
     * Release CS asserted by select().
     * @returns {Promise<void>}
     */
    async deselect() {
        this._held = false;
        if (this._csPin) await this._csPin.set(true);
    }

    /**
     * Fill `buf` with `buf.length` bytes read from the device (MOSI idles high).
     * @param {Buffer} buf
     * @returns {Promise<void>}
     */
    async readInto(buf) {
        await this._csLow();
        const sendBuffer = Buffer.alloc(buf.length, 0xFF);
        this._device.transferSync([{ sendBuffer, receiveBuffer: buf, byteLength: buf.length }]);
        await this._csHigh();
    }

    async _csLow() {
        if (this._csPin && !this._held) await this._csPin.set(false);
    }

    async _csHigh() {
        if (this._csPin && !this._held) await this._csPin.set(true);
    }

    async _write(data) {
        const sendBuffer = Buffer.isBuffer(data) ? data : Buffer.from(data);
        await this._csLow();
        this._device.transferSync([{ sendBuffer, byteLength: sendBuffer.length }]);
        await this._csHigh();
    }

    async _read(n) {
        const receiveBuffer = Buffer.alloc(n);
        await this.readInto(receiveBuffer);
        return receiveBuffer;
    }

    async _writeRead(data, n) {
        const prefix = Buffer.isBuffer(data) ? data : Buffer.from(data);
        const sendBuffer = Buffer.concat([prefix, Buffer.alloc(n, 0xFF)]);
        const receiveBuffer = Buffer.alloc(sendBuffer.length);
        await this._csLow();
        this._device.transferSync([{ sendBuffer, receiveBuffer, byteLength: sendBuffer.length }]);
        await this._csHigh();
        return receiveBuffer.subarray(prefix.length);
    }

    async close() {
        this._device.closeSync();
    }
}

module.exports = { SPIConnection };
