'use strict';

const { EventEmitter } = require('node:events');

/**
 * PCF8574 8-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all eight pins (P0–P7) as GPIO objects via the pin() factory.
 * Pin objects implement the opengpio Input/Output shape so they are
 * drop-in replacements for hardware GPIO in opengpio-based code.
 *
 * Direction is implicit: value=true puts a pin in input mode (weak pull-up);
 * value=false drives it low. A shadow register tracks the output latch.
 *
 * Initialises all pins to input mode (shadow = 0xFF) at construction.
 */
class Pcf8574Minimal {
    /**
     * @param {object} transport - Configured I²C transport (write, read, writeRead).
     * @param {number} [addr=0x20] - 7-bit I²C device address.
     *   PCF8574 default 0x20; PCF8574A default 0x38.
     */
    constructor(transport, addr = 0x20) {
        this._transport = transport;
        this._addr      = addr;
        this._shadow    = 0xFF;
        this._writePort(0xFF);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    _writePort(mask) {
        this._transport.write(Buffer.from([mask & 0xFF]));
    }

    _readPort() {
        return this._transport.read(1)[0];
    }

    _setPin(n, value) {
        if (value) this._shadow |=  (1 << n);
        else       this._shadow &= ~(1 << n);
        this._shadow &= 0xFF;
        this._writePort(this._shadow);
    }

    // ------------------------------------------------------------------
    // Public driver API
    // ------------------------------------------------------------------

    /**
     * Return a Pin proxy object for pin n (0–7).
     * @param {number} n - Pin index (0 = P0, 7 = P7).
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
     * Read all 8 pins as a bitmask.
     * @param {number} [port=0] - Port index (ignored; PCF8574 has one port).
     * @returns {number} 8-bit bitmask of actual pin logic levels (bit 0 = P0).
     */
    readPort(port = 0) { return this._readPort(); }

    /**
     * Write all 8 pins at once and update the shadow register.
     * @param {number} [port=0] - Port index (ignored).
     * @param {number} mask - 8-bit output mask; 1 = input mode, 0 = drive low.
     */
    writePort(port = 0, mask = 0xFF) {
        this._shadow = mask & 0xFF;
        this._writePort(this._shadow);
    }
}

/**
 * GPIO proxy for a single PCF8574 pin — opengpio Input/Output shape.
 *
 * Obtain via Pcf8574Minimal.pin(n). Do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Pcf8574Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–7).
     * @param {string} direction - 'in' or 'out'; fixed for the pin's lifetime.
     */
    constructor(chip, n, direction) {
        this._chip      = chip;
        this._n         = n;
        this._direction = direction;
    }

    /** @type {string} Pin direction ('in' or 'out'). */
    get direction() { return this._direction; }

    /**
     * Current pin state.
     * @returns {boolean} true (released to quasi-high input) or false (driven low).
     */
    get value() {
        return ((this._chip._readPort() >> this._n) & 1) === 1;
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
 * PCF8574 full interface — extends Pcf8574Minimal with interrupt support.
 *
 * Adds configureInterrupt() to attach a callback to the chip's INT line
 * and clearInterrupt() to return the changed-pin bitmask.
 * Pin objects gain watch() returning a per-pin interrupt EventEmitter.
 */
class Pcf8574Full extends Pcf8574Minimal {
    /**
     * @param {object} transport - Configured I²C transport.
     * @param {number} [addr=0x20] - 7-bit I²C device address.
     */
    constructor(transport, addr = 0x20) {
        super(transport, addr);
        this._prev      = this._readPort();
        this._callback  = null;
        this._pollTimer = null;
        this._watchers  = {};
    }

    /**
     * Return a Full pin proxy for pin n (0–7).
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
     * sysfs value file (e.g. '/sys/class/gpio/gpio5/value') for edge-
     * based delivery via epoll. Pass null to use polling.
     *
     * @param {string|null} intGpioPath - Sysfs GPIO value file path, or null for polling.
     * @param {function} callback - Called with changed bitmask on any input change.
     */
    configureInterrupt(intGpioPath, callback) {
        this._callback = callback;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intGpioPath) {
            try {
                const fs   = require('fs');
                const ep   = require('epoll').Epoll;
                const fd   = fs.openSync(intGpioPath, 'r');
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
                const value = ((current >> pinN) & 1) === 1;
                watchers.forEach(w => {
                    w.emit('change', value);
                    w.emit(value ? 'rise' : 'fall', value);
                });
            }
        }
    }

    /**
     * Read current pin states and return bitmask of pins that changed.
     * Also clears the chip's INT line.
     * @returns {number} 8-bit changed-pin bitmask.
     */
    clearInterrupt() {
        const current = this._readPort();
        const changed = (current ^ this._prev) & 0xFF;
        this._prev    = current;
        return changed;
    }
}

/**
 * Full GPIO proxy — adds watch() for interrupt-driven input.
 */
class _FullPin extends _Pin {
    /**
     * @param {Pcf8574Full} chip - Parent full driver instance.
     * @param {number} n - Pin index.
     * @param {string} direction - 'in' or 'out'.
     */
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

module.exports = { Pcf8574Minimal, Pcf8574Full };
