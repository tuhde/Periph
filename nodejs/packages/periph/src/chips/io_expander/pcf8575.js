'use strict';

const { EventEmitter } = require('node:events');

/**
 * PCF8575 16-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all 16 pins (P00–P07, P10–P17) as GPIO objects via the pin() factory.
 * Direction is implicit: value=true puts a pin in input mode (weak pull-up);
 * value=false drives it low. Two shadow registers track the output latches.
 *
 * Initialises all pins to input mode (shadow = [0xFF, 0xFF]) at construction.
 *
 * @param {object} transport - Configured I²C transport (write, read, writeRead).
 * @param {number} [addr=0x20] - 7-bit I²C device address.
 */
class Pcf8575Minimal {
    /**
     * @param {object} transport - Configured I²C transport.
     * @param {number} [addr=0x20] - 7-bit I²C device address.
     */
    constructor(transport, addr = 0x20) {
        this._transport = transport;
        this._addr = addr;
        this._shadow = [0xFF, 0xFF];
        this._writeBoth();
    }

    _writeBoth() {
        this._transport.write(Buffer.from([this._shadow[0], this._shadow[1]]));
    }

    _readPort() {
        return this._transport.read(2);
    }

    _setPin(n, value) {
        const portIdx = Math.floor(n / 8);
        const bit = n % 8;
        if (value) this._shadow[portIdx] |=  (1 << bit);
        else       this._shadow[portIdx] &= ~(1 << bit);
        this._writeBoth();
    }

    /**
     * Return a Pin proxy object for pin n (0–15).
     * @param {number} n - Pin index (0 = P00, 15 = P17).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy implementing the opengpio Input/Output shape.
     */
    pin(n, direction = 'in') {
        const p = new _Pin(this, n, direction);
        if (direction === 'out') this._setPin(n, 0);
        else                     this._setPin(n, 1);
        return p;
    }

    /**
     * Read all 8 pins of the given port as a bitmask.
     * @param {number} [port=0] - Port index (0 = P00–P07, 1 = P10–P17).
     * @returns {number} 8-bit bitmask of actual pin logic levels.
     */
    readPort(port = 0) {
        return this._readPort()[port];
    }

    /**
     * Write all 8 pins of the given port at once and update the shadow register.
     * @param {number} [port=0] - Port index (0 or 1).
     * @param {number} mask - 8-bit output mask; 1 = input mode, 0 = drive low.
     */
    writePort(port = 0, mask = 0xFF) {
        this._shadow[port] = mask & 0xFF;
        this._writeBoth();
    }
}

/**
 * GPIO proxy for a single PCF8575 pin — opengpio Input/Output shape.
 *
 * Obtain via Pcf8575Minimal.pin(n). Do not instantiate directly.
 *
 * @param {Pcf8575Minimal} chip - Parent driver instance.
 * @param {number} n - Pin index (0–15).
 * @param {string} direction - 'in' or 'out'; fixed for the pin's lifetime.
 */
class _Pin {
    constructor(chip, n, direction) {
        this._chip = chip;
        this._n = n;
        this._direction = direction;
    }

    /** @type {string} Pin direction ('in' or 'out'). */
    get direction() { return this._direction; }

    /**
     * Current pin state.
     * @returns {boolean} true (released to quasi-high input) or false (driven low).
     */
    get value() {
        const port = Math.floor(this._n / 8);
        const bit = this._n % 8;
        return ((this._chip._readPort()[port] >> bit) & 1) === 1;
    }

    /**
     * Drive the pin. Only valid on pins created with direction 'out'.
     * @param {boolean} value - true releases to quasi-high input, false drives low.
     * @throws {Error} If this pin is direction 'in'.
     */
    set value(value) {
        if (this._direction !== 'out') throw new Error('cannot set value on an input pin');
        this._chip._setPin(this._n, value ? 1 : 0);
    }

    /** Release the pin (no-op; shadow state preserved). */
    stop() {}
}

/**
 * PCF8575 full interface — extends Pcf8575Minimal with interrupt support.
 *
 * Adds configureInterrupt() to attach a callback to the chip's INT line
 * and clearInterrupt() to return the 16-bit changed-pin bitmask.
 * Pin objects gain watch() returning a per-pin interrupt EventEmitter.
 *
 * @param {object} transport - Configured I²C transport.
 * @param {number} [addr=0x20] - 7-bit I²C device address.
 */
class Pcf8575Full extends Pcf8575Minimal {
    /**
     * @param {object} transport - Configured I²C transport.
     * @param {number} [addr=0x20] - 7-bit I²C device address.
     */
    constructor(transport, addr = 0x20) {
        super(transport, addr);
        const raw = this._readPort();
        this._prev = [raw[0], raw[1]];
        this._callback = null;
        this._pollTimer = null;
        this._watchers = {};
    }

    /**
     * Return a Full pin proxy for pin n (0–15).
     * @param {number} n - Pin index.
     * @param {string} [direction='in'] - Initial direction.
     * @returns {_FullPin} Full pin proxy with watch() support.
     */
    pin(n, direction = 'in') {
        const p = new _FullPin(this, n, direction);
        if (direction === 'out') this._setPin(n, 0);
        else                     this._setPin(n, 1);
        return p;
    }

    /**
     * Attach a callback to the chip's INT output.
     *
     * On Linux, uses a 5 ms polling interval; pass intGpioPath to the
     * sysfs value file for edge-based delivery via epoll. Pass null to use polling.
     *
     * @param {string|null} intGpioPath - Sysfs GPIO value file path, or null for polling.
     * @param {function} callback - Called with 16-bit changed bitmask on any input change.
     */
    configureInterrupt(intGpioPath, callback) {
        this._callback = callback;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intGpioPath) {
            try {
                const fs = require('fs');
                const ep = require('epoll').Epoll;
                const fd = fs.openSync(intGpioPath, 'r');
                const poll = new ep((err, fd2) => {
                    fs.readSync(fd2, Buffer.alloc(1), 0, 1, 0);
                    const changed = this.clearInterrupt();
                    if (changed) this._dispatch(changed);
                });
                poll.add(fd, ep.EPOLLPRI);
            } catch (_) {
                this._startPolling();
            }
        } else {
            this._startPolling();
        }
    }

    _startPolling() {
        this._pollTimer = setInterval(() => {
            const changed = this.clearInterrupt();
            if (changed) this._dispatch(changed);
        }, 5);
    }

    _dispatch(changed) {
        if (this._callback) this._callback(changed);
        const current = this._readPort();
        for (const [n, watchers] of Object.entries(this._watchers)) {
            const pinN = Number(n);
            if ((changed >> pinN) & 1) {
                const port = pinN >> 3;
                const bit = pinN & 7;
                const value = ((current[port] >> bit) & 1) === 1;
                watchers.forEach(w => {
                    w.emit('change', value);
                    w.emit(value ? 'rise' : 'fall', value);
                });
            }
        }
    }

    /**
     * Read current pin states and return 16-bit bitmask of pins that changed.
     * Also clears the chip's INT line.
     * @returns {number} 16-bit changed-pin bitmask (bits 0–7 = Port 0, bits 8–15 = Port 1).
     */
    clearInterrupt() {
        const current = this._readPort();
        const changed0 = current[0] ^ this._prev[0];
        const changed1 = current[1] ^ this._prev[1];
        this._prev = [current[0], current[1]];
        return changed0 | (changed1 << 8);
    }
}

/**
 * Full GPIO proxy — adds watch() for interrupt-driven input.
 *
 * @param {Pcf8575Full} chip - Parent full driver instance.
 * @param {number} n - Pin index.
 * @param {string} direction - 'in' or 'out'.
 */
class _FullPin extends _Pin {
    constructor(chip, n, direction) {
        super(chip, n, direction);
    }

    /**
     * Start watching this pin for state changes.
     *
     * Requires configureInterrupt() to have been called on the driver.
     * Matches opengpio's Watch class: an EventEmitter emitting 'rise' /
     * 'fall' / 'change', plus a `value` getter and `stop()`.
     *
     * @returns {EventEmitter} Watcher emitting 'rise', 'fall', 'change'.
     */
    watch() {
        const n = this._n;
        const watcher = new EventEmitter();
        Object.defineProperty(watcher, 'value', { get: () => this.value });
        watcher.stop = () => {
            const list = this._chip._watchers[n];
            if (list) this._chip._watchers[n] = list.filter(w => w !== watcher);
        };
        if (!this._chip._watchers[n]) this._chip._watchers[n] = [];
        this._chip._watchers[n].push(watcher);
        return watcher;
    }

    /**
     * Invert active-low polarity for this pin.
     * @param {boolean} invert - true to invert read/write values.
     */
    setActiveLow(invert) {
        this._activeLow = !!invert;
    }
}

module.exports = { Pcf8575Minimal, Pcf8575Full };