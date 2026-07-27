'use strict';

/**
 * Abstract connection interface shared by all chip drivers.
 *
 * A connection instance represents one device on a bus, plus its optional
 * INT pin (edge notifications from the chip's INT line), EN pin (hardware
 * enable/power control), and a software enable gate. Subclasses wrap a
 * platform-specific bus (i2c-bus, spi-device, serialport, etc.) and a device
 * address or CS line, implementing the protected _read/_write/_writeRead
 * hooks; this base class gates every call through the enabled flag so chip
 * drivers never need to check it themselves.
 *
 * All methods are async — required so UART (inherently Promise-based via
 * `serialport`) shares one contract with I2C/SPI/SMBus (synchronous
 * underlying calls, wrapped in async methods for a uniform interface).
 */
class Connection {
    /**
     * @param {InputPin|null} [intPin=null] - Optional InputPin for INT-line delivery.
     * @param {OutputPin|null} [enPin=null] - Optional OutputPin for hardware enable/power control.
     */
    constructor(intPin = null, enPin = null) {
        this._intPin = intPin;
        this._enPin = enPin;
        this._enabled = true;
    }

    /** Resume bus access; drives the hardware EN pin high if wired.
     * @returns {Promise<void>}
     */
    async enable() {
        this._enabled = true;
        if (this._enPin) await this._enPin.set(true);
    }

    /**
     * Gate all subsequent bus reads and writes; drives EN pin low if wired.
     *
     * Reads silently return zero-filled buffers; writes silently become no-ops.
     * @returns {Promise<void>}
     */
    async disable() {
        this._enabled = false;
        if (this._enPin) await this._enPin.set(false);
    }

    /** @returns {boolean} The current software-gate state. */
    isEnabled() { return this._enabled; }

    /** @returns {InputPin|null} The INT-line InputPin, or null if none is wired. */
    get intPin() { return this._intPin; }

    /** @returns {OutputPin|null} The EN-pin OutputPin, or null if none is wired. */
    get enPin() { return this._enPin; }

    /**
     * Send bytes to the device, unless disabled.
     * @param {Buffer|Uint8Array} data - Bytes to send.
     * @returns {Promise<void>}
     */
    async write(data) {
        if (!this._enabled) return;
        return this._write(data);
    }

    /**
     * Read bytes from the device, unless disabled.
     * @param {number} length - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device, or a zero-filled buffer if disabled.
     */
    async read(length) {
        if (!this._enabled) return Buffer.alloc(length);
        return this._read(length);
    }

    /**
     * Write then read without releasing the bus between phases, unless disabled.
     * @param {Buffer|Uint8Array} data - Command/address bytes to send.
     * @param {number} n - Number of bytes to read back.
     * @returns {Promise<Buffer>} Data received from the device, or a zero-filled buffer if disabled.
     */
    async writeRead(data, n) {
        if (!this._enabled) return Buffer.alloc(n);
        return this._writeRead(data, n);
    }

    /** Subclass hook: send bytes to the device.
     * @abstract
     */
    async _write(_data) { throw new Error('abstract'); }

    /** Subclass hook: read bytes from the device.
     * @abstract
     */
    async _read(_length) { throw new Error('abstract'); }

    /** Subclass hook: write then read without releasing the bus.
     * @abstract
     */
    async _writeRead(_data, _n) { throw new Error('abstract'); }

    /**
     * Close the underlying bus/device. Default no-op; subclasses that hold
     * an OS resource (file descriptor, serial port) override this.
     * @returns {Promise<void>}
     */
    async close() {}
}

module.exports = { Connection };
