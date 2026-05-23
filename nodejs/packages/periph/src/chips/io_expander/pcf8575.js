'use strict';

/**
 * PCF8575 16-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all 16 pins (P00–P07, P10–P17) as GPIO objects via the pin() factory.
 * Direction is implicit: writeSync(1) puts a pin in input mode (weak pull-up);
 * writeSync(0) drives it low. Two shadow registers track the output latches.
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
     * @returns {_Pin} Pin proxy implementing the onoff Gpio subset.
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
 * GPIO proxy for a single PCF8575 pin — onoff Gpio interface subset.
 *
 * Obtain via Pcf8575Minimal.pin(n). Do not instantiate directly.
 *
 * @param {Pcf8575Minimal} chip - Parent driver instance.
 * @param {number} n - Pin index (0–15).
 * @param {string} direction - 'in' or 'out'.
 */
class _Pin {
    constructor(chip, n, direction) {
        this._chip = chip;
        this._n = n;
        this._direction = direction;
    }

    /** @type {string} Current pin direction ('in' or 'out'). */
    get direction() { return this._direction; }

    /**
     * Read pin synchronously.
     * @returns {number} 0 or 1.
     */
    readSync() {
        const port = Math.floor(this._n / 8);
        const bit = this._n % 8;
        return (this._chip._readPort()[port] >> bit) & 1;
    }

    /**
     * Write pin synchronously.
     * @param {number} value - 0 (drive low) or 1 (release to quasi-high input).
     */
    writeSync(value) {
        this._chip._setPin(this._n, value ? 1 : 0);
    }

    /**
     * Read pin asynchronously.
     * @param {function} callback - Node-style callback(err, value).
     */
    read(callback) {
        try { callback(null, this.readSync()); } catch (e) { callback(e); }
    }

    /**
     * Write pin asynchronously.
     * @param {number} value - 0 or 1.
     * @param {function} callback - Node-style callback(err).
     */
    write(value, callback) {
        try { this.writeSync(value); callback(null); } catch (e) { callback(e); }
    }

    /**
     * Set pin direction.
     * @param {string} direction - 'in' or 'out'.
     * @param {function} callback - Node-style callback(err).
     */
    setDirection(direction, callback) {
        try {
            this._direction = direction;
            this._chip._setPin(this._n, direction === 'in' ? 1 : 0);
            if (callback) callback(null);
        } catch (e) { if (callback) callback(e); }
    }

    /** Release the pin (no-op; shadow state preserved). */
    unexport() {}
}

/**
 * PCF8575 full interface — extends Pcf8575Minimal with interrupt support.
 *
 * Adds configureInterrupt() to attach a callback to the chip's INT line
 * and clearInterrupt() to return the 16-bit changed-pin bitmask.
 * Pin objects gain watch() / unwatch() for per-pin interrupt handlers.
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
     * @returns {_FullPin} Full pin proxy with watch/unwatch support.
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
        for (const [n, handlers] of Object.entries(this._watchers)) {
            if ((changed >> n) & 1) {
                handlers.forEach(h => h(null, this.readSync ? undefined : 0));
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
 * Full GPIO proxy — adds watch/unwatch for interrupt-driven input.
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
     * Register a handler for pin state changes.
     * Requires configureInterrupt() to have been called on the driver.
     * @param {function} handler - Called with (err, value) when pin changes.
     */
    watch(handler) {
        const n = this._n;
        if (!this._chip._watchers[n]) this._chip._watchers[n] = [];
        this._chip._watchers[n].push((err) => {
            handler(err, this.readSync());
        });
    }

    /**
     * Remove a previously registered handler.
     * @param {function} handler - The handler to remove.
     */
    unwatch(handler) {
        const n = this._n;
        if (this._chip._watchers[n]) {
            this._chip._watchers[n] = this._chip._watchers[n].filter(h => h !== handler);
        }
    }

    /** Remove all handlers for this pin. */
    unwatchAll() {
        this._chip._watchers[this._n] = [];
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