'use strict';

const REG_IODIRA  = 0x00;
const REG_IODIRB  = 0x01;
const REG_IPOLA   = 0x02;
const REG_IPOLB   = 0x03;
const REG_GPINTENA = 0x04;
const REG_GPINTENB = 0x05;
const REG_DEFVALA  = 0x06;
const REG_DEFVALB  = 0x07;
const REG_INTCONA  = 0x08;
const REG_INTCONB  = 0x09;
const REG_IOCON    = 0x0A;
const REG_GPPUA    = 0x0C;
const REG_GPPUB    = 0x0D;
const REG_INTFA    = 0x0E;
const REG_INTFB    = 0x0F;
const REG_INTCAPA  = 0x10;
const REG_INTCAPB  = 0x11;
const REG_GPIOA    = 0x12;
const REG_GPIOB    = 0x13;
const REG_OLATA    = 0x14;
const REG_OLATB    = 0x15;

/**
 * MCP23017 16-bit bidirectional I/O port expander — minimal interface.
 *
 * Provides access to 16 GPIO pins (GPA0–GPA7 and GPB0–GPB7) via a pin()
 * factory. Each returned pin implements the onoff Gpio interface subset.
 *
 * At construction, all pins initialise as inputs except GPA7 and GPB7
 * which are output-only on the hardware and are forced to output mode.
 * A shadow register tracks the output latch (OLATA/OLATB) to enable
 * bit-level read-modify-write without an I²C read.
 *
 * IOCON.BANK is left at 0 (power-on default) throughout; do not alter it.
 */
class Mcp23017Minimal {
    /**
     * @param {object} transport - I²C transport with write, read, writeRead methods.
     * @param {number} [addr=0x20] - 7-bit I²C address (default 0x20, range 0x20–0x27).
     */
    constructor(transport, addr = 0x20) {
        this._transport = transport;
        this._addr      = addr;
        this._shadow    = [0, 0];

        this._writeReg(REG_OLATA,  0x00);
        this._writeReg(REG_OLATB,  0x00);
        this._writeReg(REG_IODIRA, 0x7F);
        this._writeReg(REG_IODIRB, 0x7F);
        this._writeReg(REG_IPOLA,  0x00);
        this._writeReg(REG_IPOLB,  0x00);
        this._writeReg(REG_GPPUA,  0x00);
        this._writeReg(REG_GPPUB,  0x00);
    }

    _writeReg(reg, value) {
        this._transport.write(Buffer.from([reg, value & 0xFF]));
    }

    _readReg(reg) {
        return this._transport.writeRead(Buffer.from([reg]), 1)[0];
    }

    _writePort(port, mask) {
        this._writeReg(REG_OLATA + port, mask & 0xFF);
    }

    _readPort(port) {
        return this._readReg(REG_GPIOA + port);
    }

    _setPin(n, value) {
        const port = n >> 3;
        const bit  = n & 7;
        if (value) this._shadow[port] |=  (1 << bit);
        else       this._shadow[port] &= ~(1 << bit);
        this._writePort(port, this._shadow[port]);
    }

    /**
     * Return a Pin proxy for pin n (0–15).
     *
     * Pins 0–7 map to PORTA (GPA0–GPA7); pins 8–15 map to PORTB (GPB0–GPB7).
     * Pins 7 and 15 (GPA7/GPB7) are output-only; attempting to set them
     * as input via direction='in' will still allow reading the output latch
     * value, but the hardware forces them as outputs.
     *
     * @param {number} n - Pin index (0–15).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy implementing the onoff Gpio subset.
     */
    pin(n, direction = 'in') {
        return new _Pin(this, n, direction);
    }

    /**
     * Read all 8 pins of a port as a bitmask.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {number} 8-bit bitmask (bit 0 = pin 0 of the port).
     */
    readPort(port = 0) {
        return this._readPort(port);
    }

    /**
     * Write all 8 output pins of a port via the output latch.
     * Updates the internal shadow register.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit output mask.
     */
    writePort(port = 0, mask = 0x00) {
        this._shadow[port] = mask & 0xFF;
        this._writePort(port, this._shadow[port]);
    }

    /**
     * Configure the direction (IODIR) of a full port.
     * @param {number} port - 0 = PORTA (IODIRA), 1 = PORTB (IODIRB).
     * @param {number} mask - 8-bit mask; bit = 1 → input, 0 → output.
     */
    configureDirection(port, mask) {
        this._writeReg(REG_IODIRA + (port & 1), mask & 0xFF);
    }
}

/**
 * GPIO proxy for a single MCP23017 pin — onoff Gpio interface subset.
 *
 * Obtain via Mcp23017Minimal.pin(n). Do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Mcp23017Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–15).
     * @param {string} direction - 'in' or 'out'.
     */
    constructor(chip, n, direction) {
        this._chip      = chip;
        this._n         = n;
        this._direction = direction;
    }

    /** @type {string} Current pin direction ('in' or 'out'). */
    get direction() { return this._direction; }

    /**
     * Read pin synchronously.
     * @returns {number} 0 or 1.
     */
    readSync() {
        return (this._chip._readPort(this._n >> 3) >> (this._n & 7)) & 1;
    }

    /**
     * Write pin synchronously.
     * @param {number} value - 0 or 1.
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
            if (callback) callback(null);
        } catch (e) { if (callback) callback(e); }
    }

    /** Release the pin (no-op; shadow state preserved). */
    unexport() {}
}

/**
 * MCP23017 full interface — extends Minimal with interrupt support,
 * per-pin pull-ups, and interrupt-on-change configuration.
 *
 * configureInterrupt() attaches a callback to the chip's INT line;
 * clearInterrupt() returns the changed-pin bitmask for a port and
 * clears the hardware interrupt. Per-pin watch() / unwatch() handlers
 * are also available.
 */
class Mcp23017Full extends Mcp23017Minimal {
    /**
     * @param {object} transport - I²C transport with write, read, writeRead methods.
     * @param {number} [addr=0x20] - 7-bit I²C address.
     */
    constructor(transport, addr = 0x20) {
        super(transport, addr);
        this._prev      = [0, 0];
        this._callback  = null;
        this._pollTimer = null;
        this._watchers  = {};
    }

    /**
     * Return a Full Pin proxy for pin n (0–15).
     * @param {number} n - Pin index.
     * @param {string} [direction='in'] - Initial direction.
     * @returns {_FullPin} Full pin proxy with watch/unwatch support.
     */
    pin(n, direction = 'in') {
        return new _FullPin(this, n, direction);
    }

    /**
     * Enable/disable per-pin pull-ups on a port.
     *
     * Pull-ups are only electrically active on pins configured as inputs.
     * Enabling them on output pins has no hardware effect but is allowed
     * in Full (caller responsibility).
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit mask: 1 = enable 100 kΩ pull-up.
     */
    configurePullup(port = 0, mask = 0x00) {
        this._writeReg(REG_GPPUA + port, mask & 0xFF);
    }

    /**
     * Configure input polarity inversion on a port.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @param {number} mask - 8-bit mask: 1 = invert GPIO read.
     */
    configurePolarity(port = 0, mask = 0x00) {
        this._writeReg(REG_IPOLA + port, mask & 0xFF);
    }

    /**
     * Attach an interrupt callback to a port's INT line.
     *
     * When intGpioPath is provided (sysfs GPIO value file path), edge
     * detection via epoll is used. Otherwise a 5 ms polling loop drives
     * delivery. The callback receives the changed-pin bitmask for the port.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA (INTA), 1 = PORTB (INTB).
     * @param {string|null} intGpioPath - Sysfs GPIO value file for edge delivery, or null for polling.
     * @param {function} callback - Called with (changedMask) on any input change.
     */
    configureInterrupt(port = 0, intGpioPath = null, callback) {
        this._callback = callback;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }

        this._writeReg(REG_GPINTENA + port, 0xFF);
        this._writeReg(REG_INTCONA  + port, 0x00);

        if (intGpioPath) {
            try {
                const fs   = require('fs');
                const ep   = require('epoll').Epoll;
                const fd   = fs.openSync(intGpioPath, 'r');
                const poll = new ep((err, fd2) => {
                    fs.readSync(fd2, Buffer.alloc(1), 0, 1, 0);
                    const changed = this.clearInterrupt(port);
                    if (changed) this._dispatch(port, changed);
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
            const changed = this.clearInterrupt(0) | (this.clearInterrupt(1) << 8);
            if (changed) this._dispatch(0, changed & 0xFF);
            if (changed >> 8) this._dispatch(1, (changed >> 8) & 0xFF);
        }, 5);
    }

    _dispatch(port, changed) {
        if (this._callback) this._callback(port, changed);
        for (const [n, handlers] of Object.entries(this._watchers)) {
            const pinPort = Number(n) >> 3;
            if (pinPort === port && ((changed >> (Number(n) & 7)) & 1)) {
                handlers.forEach(h => h(null, this._chip ? this._chip._readPort(port) : 0));
            }
        }
    }

    /**
     * Read and clear the interrupt for a port, returning the changed-pin bitmask.
     *
     * Also updates the per-port previous-state tracker used by the polling loop.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {number} 8-bit changed-pin bitmask for the port.
     */
    clearInterrupt(port = 0) {
        const captured = this._readReg(REG_INTCAPA + port);
        const current  = this._readReg(REG_GPIOA   + port);
        const changed  = (current ^ this._prev[port]) & 0xFF;
        this._prev[port] = current;
        return changed;
    }

    /**
     * Read interrupt flags without clearing the interrupt.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     * @returns {number} 8-bit interrupt-flag bitmask.
     */
    readInterruptFlags(port = 0) {
        return this._readReg(REG_INTFA + port);
    }

    /**
     * Disable interrupt generation for a port.
     *
     * @param {number} [port=0] - Port index: 0 = PORTA, 1 = PORTB.
     */
    stopInterrupt(port = 0) {
        this._writeReg(REG_GPINTENA + port, 0x00);
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
    }
}

/**
 * Full GPIO proxy — adds watch/unwatch for interrupt-driven input.
 */
class _FullPin extends _Pin {
    /**
     * @param {Mcp23017Full} chip - Parent full driver instance.
     * @param {number} n - Pin index (0–15).
     * @param {string} direction - 'in' or 'out'.
     */
    constructor(chip, n, direction) {
        super(chip, n, direction);
    }

    /**
     * Register a handler for pin state changes.
     * Requires configureInterrupt() to have been called on the driver.
     * @param {function} handler - Called on pin change.
     */
    watch(handler) {
        const n = this._n;
        if (!this._chip._watchers[n]) this._chip._watchers[n] = [];
        this._chip._watchers[n].push(handler);
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

module.exports = { Mcp23017Minimal, Mcp23017Full };