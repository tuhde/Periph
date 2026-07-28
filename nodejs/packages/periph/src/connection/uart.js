'use strict';

const { SerialPort } = require('serialport');
const { Connection } = require('./connection');

/**
 * UART connection for Node.js (wraps the `serialport` npm package).
 *
 * Opens the serial port asynchronously at construction and accumulates
 * incoming bytes in an internal buffer. All operations return Promises.
 * Call close() when done to release the port.
 *
 * For RS-485 DE toggling, pass de_gpio ({chip, line}) and install the
 * `opengpio` package. The GPIO is asserted high before each write and
 * deasserted after drain. This is separate from the standard EN pin
 * (hardware enable/power) — DE controls RS-485 transceiver direction,
 * not device power.
 */
class UARTConnection extends Connection {
    /**
     * @param {string}  path      - Serial device path (e.g. '/dev/ttyS0').
     * @param {object}  [options]
     * @param {number}  [options.baudRate=9600]    - Baud rate.
     * @param {number}  [options.timeoutMs=1000]   - Read timeout in milliseconds.
     * @param {{chip: number, line: number}|null} [options.de_gpio=null] - gpiod chip+line for RS-485 DE; null disables.
     * @param {import('./input_pin').InputPin|null} [options.intPin=null] - Optional INT-line InputPin.
     * @param {import('./output_pin').OutputPin|null} [options.enPin=null] - Optional EN-pin OutputPin.
     */
    constructor(path, options = {}) {
        super(options.intPin ?? null, options.enPin ?? null);
        this._baudRate  = options.baudRate  ?? 9600;
        this._timeoutMs = options.timeoutMs ?? 1000;
        this._de_gpio   = options.de_gpio ?? null;
        this._rxBuf     = Buffer.alloc(0);
        this._rxWaiters = [];
        this._de        = null;

        this._port = new SerialPort({ path, baudRate: this._baudRate, autoOpen: false });
        this._openPromise = new Promise((resolve, reject) => {
            this._port.open(err => { if (err) reject(err); else resolve(); });
        });

        this._port.on('data', chunk => {
            this._rxBuf = Buffer.concat([this._rxBuf, chunk]);
            this._drainWaiters();
        });

        if (this._de_gpio !== null) {
            try {
                const { Default } = require('opengpio');
                this._de = Default.output(this._de_gpio);
                this._de.value = false;
            } catch (_) {
                // opengpio unavailable — RS-485 DE toggling disabled.
            }
        }
    }

    _drainWaiters() {
        while (this._rxWaiters.length > 0) {
            const [n, resolve] = this._rxWaiters[0];
            if (this._rxBuf.length < n) break;
            this._rxWaiters.shift();
            const chunk = this._rxBuf.subarray(0, n);
            this._rxBuf = this._rxBuf.subarray(n);
            resolve(Buffer.from(chunk));
        }
    }

    _waitForBytes(n) {
        return new Promise((resolve, reject) => {
            if (this._rxBuf.length >= n) {
                const chunk = this._rxBuf.subarray(0, n);
                this._rxBuf = this._rxBuf.subarray(n);
                resolve(Buffer.from(chunk));
                return;
            }
            const timer = setTimeout(() => {
                this._rxWaiters = this._rxWaiters.filter(w => w[1] !== resolve);
                reject(new Error('UART read timeout'));
            }, this._timeoutMs);
            this._rxWaiters.push([n, (buf) => { clearTimeout(timer); resolve(buf); }]);
        });
    }

    /**
     * Wait for the port to finish opening. Must be awaited before first use.
     * @returns {Promise<void>}
     */
    async open() {
        return this._openPromise;
    }

    /**
     * Send bytes to the device; in RS-485 mode asserts DE, drains the OS
     * TX buffer, then deasserts DE.
     * @param {Buffer|Uint8Array} data - Bytes to send.
     * @returns {Promise<void>}
     */
    async _write(data) {
        await this._openPromise;
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (this._de) this._de.value = true;
        await new Promise((resolve, reject) => {
            this._port.write(buf, err => { if (err) reject(err); else resolve(); });
        });
        await new Promise((resolve, reject) => {
            this._port.drain(err => { if (err) reject(err); else resolve(); });
        });
        if (this._de) this._de.value = false;
    }

    /**
     * Receive n bytes; resolves when n bytes have accumulated in the buffer
     * or rejects on timeout.
     * @param {number} n - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device.
     */
    async _read(n) {
        await this._openPromise;
        return this._waitForBytes(n);
    }

    /**
     * Send bytes then receive n bytes. In RS-485 mode DE is asserted only
     * during the write phase.
     * @param {Buffer|Uint8Array} data - Bytes to send.
     * @param {number}            n    - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device.
     */
    async _writeRead(data, n) {
        await this._write(data);
        return this._read(n);
    }

    /**
     * Close the serial port and release the DE GPIO if applicable.
     * @returns {Promise<void>}
     */
    async close() {
        await new Promise((resolve, reject) => {
            this._port.close(err => { if (err) reject(err); else resolve(); });
        });
        if (this._de) {
            this._de.value = false;
            this._de.stop();
            this._de = null;
        }
    }
}

module.exports = { UARTConnection };
