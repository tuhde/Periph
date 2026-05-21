'use strict';

const { SerialPort } = require('serialport');

/**
 * UART transport for Node.js (wraps the `serialport` npm package).
 *
 * Opens the serial port asynchronously at construction and accumulates
 * incoming bytes in an internal buffer. All operations return Promises.
 * Call close() when done to release the port.
 *
 * For RS-485 DE toggling, pass de_pin_num and install the `onoff` package.
 * The GPIO is asserted high before each write and deasserted after drain.
 */
class UARTTransport {
    /**
     * @param {string}  path      - Serial device path (e.g. '/dev/ttyS0').
     * @param {object}  [options]
     * @param {number}  [options.baudRate=9600]    - Baud rate.
     * @param {number}  [options.timeoutMs=1000]   - Read timeout in milliseconds.
     * @param {number|null} [options.de_pin_num=null] - GPIO line for RS-485 DE; null disables.
     */
    constructor(path, options = {}) {
        this._baudRate   = options.baudRate  ?? 9600;
        this._timeoutMs  = options.timeoutMs ?? 1000;
        this._de_pin_num = options.de_pin_num ?? null;
        this._rxBuf      = Buffer.alloc(0);
        this._rxWaiters  = [];
        this._de         = null;

        this._port = new SerialPort({ path, baudRate: this._baudRate, autoOpen: false });
        this._openPromise = new Promise((resolve, reject) => {
            this._port.open(err => { if (err) reject(err); else resolve(); });
        });

        this._port.on('data', chunk => {
            this._rxBuf = Buffer.concat([this._rxBuf, chunk]);
            this._drainWaiters();
        });

        if (this._de_pin_num !== null) {
            try {
                const { Gpio } = require('onoff');
                this._de = new Gpio(this._de_pin_num, 'out');
                this._de.writeSync(0);
            } catch (_) {
                // onoff unavailable — RS-485 DE toggling disabled.
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
    async write(data) {
        await this._openPromise;
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (this._de) this._de.writeSync(1);
        await new Promise((resolve, reject) => {
            this._port.write(buf, err => { if (err) reject(err); else resolve(); });
        });
        await new Promise((resolve, reject) => {
            this._port.drain(err => { if (err) reject(err); else resolve(); });
        });
        if (this._de) this._de.writeSync(0);
    }

    /**
     * Receive n bytes; resolves when n bytes have accumulated in the buffer
     * or rejects on timeout.
     * @param {number} n - Number of bytes to read.
     * @returns {Promise<Buffer>} Data received from the device.
     */
    async read(n) {
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
    async writeRead(data, n) {
        await this.write(data);
        return this.read(n);
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
            this._de.writeSync(0);
            this._de.unexport();
            this._de = null;
        }
    }
}

module.exports = { UARTTransport };
