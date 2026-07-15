'use strict';

const Gpio = require('onoff').Gpio;

/**
 * DHT11 GPIO pin adapter for Node.js (wraps onoff Gpio).
 *
 * The DHT11's bidirectional DATA line requires switching between output
 * (host driving) and input (host listening) within microseconds of each
 * other. onoff's Gpio constructor locks the direction at creation time, so
 * this adapter tears down and re-creates the underlying Gpio on every
 * direction change.
 *
 * @param {number} pin - BCM GPIO number for the DATA line.
 */
class DHT11Pin {
    /**
     * @param {number} pin - BCM GPIO number.
     */
    constructor(pin) {
        this._pin = pin;
        this._gpio = new Gpio(pin, 'in');
        this._dir = 'in';
    }

    setOutput() {
        if (this._dir === 'out') return;
        try { this._gpio.unexport(); } catch (_) {}
        this._gpio = new Gpio(this._pin, 'out');
        this._gpio.writeSync(0);
        this._dir = 'out';
    }

    setInput() {
        if (this._dir === 'in') return;
        try { this._gpio.unexport(); } catch (_) {}
        this._gpio = new Gpio(this._pin, 'in');
        this._dir = 'in';
    }

    drive(high) {
        if (this._dir !== 'out') this.setOutput();
        this._gpio.writeSync(high ? 1 : 0);
    }

    read() {
        if (this._dir !== 'in') this.setInput();
        return this._gpio.readSync() === 1;
    }

    close() {
        try { this._gpio.unexport(); } catch (_) {}
    }
}

module.exports = { DHT11Pin };
