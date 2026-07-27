'use strict';

/**
 * PCF8574 8-bit quasi-bidirectional I/O port expander — minimal interface.
 *
 * Exposes all eight pins (P0–P7) as GPIO objects via the pin() factory.
 * Pin objects expose async read()/write() (not onoff's synchronous
 * readSync()/writeSync() — Connection is async everywhere, so a
 * synchronous pin proxy can no longer correctly return a value).
 *
 * Direction is implicit: write(1) puts a pin in input mode (weak pull-up);
 * write(0) drives it low. A shadow register tracks the output latch.
 *
 * Initialises all pins to input mode (shadow = 0xFF) at construction.
 */
class Pcf8574Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     * @param {number} [addr=0x20] - 7-bit I²C device address.
     *   PCF8574 default 0x20; PCF8574A default 0x38.
     */
    constructor(connection, addr = 0x20) {
        this._conn   = connection;
        this._addr   = addr;
        this._shadow = 0xFF;
        this._writePort(0xFF); // fire-and-forget: underlying I2C write is synchronous
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    async _writePort(mask) {
        await this._conn.write(Buffer.from([mask & 0xFF]));
    }

    async _readPort() {
        const buf = await this._conn.read(1);
        return buf[0];
    }

    async _setPin(n, value) {
        if (value) this._shadow |=  (1 << n);
        else       this._shadow &= ~(1 << n);
        this._shadow &= 0xFF;
        await this._writePort(this._shadow);
    }

    // ------------------------------------------------------------------
    // Public driver API
    // ------------------------------------------------------------------

    /**
     * Return a Pin proxy object for pin n (0–7).
     * @param {number} n - Pin index (0 = P0, 7 = P7).
     * @param {string} [direction='in'] - Initial direction: 'in' or 'out'.
     * @returns {_Pin} Pin proxy.
     */
    pin(n, direction = 'in') {
        const p = new _Pin(this, n, direction);
        this._setPin(n, direction === 'out' ? 0 : 1); // fire-and-forget
        return p;
    }

    /**
     * Read all 8 pins as a bitmask.
     * @param {number} [port=0] - Port index (ignored; PCF8574 has one port).
     * @returns {Promise<number>} 8-bit bitmask of actual pin logic levels (bit 0 = P0).
     */
    async readPort(port = 0) { return this._readPort(); }

    /**
     * Write all 8 pins at once and update the shadow register.
     * @param {number} [port=0] - Port index (ignored).
     * @param {number} mask - 8-bit output mask; 1 = input mode, 0 = drive low.
     * @returns {Promise<void>}
     */
    async writePort(port = 0, mask = 0xFF) {
        this._shadow = mask & 0xFF;
        await this._writePort(this._shadow);
    }
}

/**
 * GPIO proxy for a single PCF8574 pin.
 *
 * Obtain via Pcf8574Minimal.pin(n). Do not instantiate directly.
 */
class _Pin {
    /**
     * @param {Pcf8574Minimal} chip - Parent driver instance.
     * @param {number} n - Pin index (0–7).
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
     * Read pin.
     * @returns {Promise<number>} 0 or 1.
     */
    async read() {
        return (await this._chip._readPort() >> this._n) & 1;
    }

    /**
     * Write pin.
     * @param {number} value - 0 (drive low) or 1 (release to quasi-high input).
     * @returns {Promise<void>}
     */
    async write(value) {
        await this._chip._setPin(this._n, value ? 1 : 0);
    }

    /**
     * Set pin direction.
     * @param {string} direction - 'in' or 'out'.
     * @returns {Promise<void>}
     */
    async setDirection(direction) {
        this._direction = direction;
        await this._chip._setPin(this._n, direction === 'in' ? 1 : 0);
    }

    /** Release the pin (no-op; shadow state preserved). */
    unexport() {}
}

/**
 * PCF8574 full interface — extends Pcf8574Minimal with interrupt support.
 *
 * Adds onInterrupt()/offInterrupt() to subscribe to the chip's INT line and
 * pollInterrupt() to read and clear the changed-pin bitmask. Pin objects
 * gain watch()/unwatch() for per-pin interrupt handlers.
 */
class Pcf8574Full extends Pcf8574Minimal {
    /**
     * @param {import('../../connection/connection').Connection} connection - Configured I²C connection.
     * @param {number} [addr=0x20] - 7-bit I²C device address.
     */
    constructor(connection, addr = 0x20) {
        super(connection, addr);
        this._prev     = 0xFF;
        this._readPort().then(v => { this._prev = v; }); // fire-and-forget refresh
        this._callback  = null;
        this._watchers  = {}; // { [pin]: { handler, trigger } }
        this._intPin    = null;
        this._pollTimer = null;
        this._edgeHandler = () => { this._handleEdge(); };
    }

    /**
     * Return a Full pin proxy for pin n (0–7).
     * @param {number} n - Pin index.
     * @param {string} [direction='in'] - Initial direction.
     * @returns {_FullPin} Full pin proxy with watch/unwatch support.
     */
    pin(n, direction = 'in') {
        const p = new _FullPin(this, n, direction);
        this._setPin(n, direction === 'out' ? 0 : 1); // fire-and-forget
        return p;
    }

    /**
     * Subscribe to INT assertions.
     *
     * Wires options.intPin (or connection.intPin if omitted) to deliver
     * edges. If neither is available, falls back to a 5 ms polling loop
     * reading the port directly (no InputPin involved).
     *
     * @param {function} callback - Called with the changed-pin bitmask on any input change.
     * @param {object} [options]
     * @param {import('../../connection/input_pin').InputPin|null} [options.intPin] - Overrides connection.intPin.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, options = {}) {
        this._callback = callback;
        const pin = options.intPin ?? this._conn.intPin ?? null;
        if (pin) {
            this._intPin = pin;
            await pin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._startPolling();
        }
    }

    /**
     * Unsubscribe and stop delivery.
     * @returns {Promise<void>}
     */
    async offInterrupt() {
        this._callback = null;
        if (this._intPin) {
            await this._intPin.offEdge(this._edgeHandler);
            this._intPin = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
    }

    _startPolling() {
        if (this._pollTimer) return;
        this._pollTimer = setInterval(() => { this._handleEdge(); }, 5);
    }

    async _handleEdge() {
        const changed = await this.pollInterrupt();
        if (!changed) return;
        if (this._callback) this._callback(changed);
        for (const [n, w] of Object.entries(this._watchers)) {
            if (!((changed >> n) & 1)) continue;
            const current = (await this._readPort() >> n) & 1;
            const rising = current === 1 && w.lastState === 0;
            const falling = current === 0 && w.lastState === 1;
            w.lastState = current;
            if (w.trigger === 'change' || (w.trigger === 'rising' && rising) || (w.trigger === 'falling' && falling)) {
                w.handler(this.pin(Number(n)));
            }
        }
    }

    /**
     * Read current pin states and return bitmask of pins that changed.
     * Also clears the chip's INT line.
     * @returns {Promise<number>} 8-bit changed-pin bitmask.
     */
    async pollInterrupt() {
        const current = await this._readPort();
        const changed = (current ^ this._prev) & 0xFF;
        this._prev = current;
        return changed;
    }
}

/**
 * Full GPIO proxy — adds watch/unwatch for interrupt-driven input.
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
     * Subscribe to this pin's edge events.
     *
     * At most one handler per pin at a time; a second watch() call replaces
     * the first. Call the chip's onInterrupt() first to arm delivery.
     *
     * @param {function} handler - Called with this pin when its state matches trigger.
     * @param {string} [trigger='change'] - 'rising', 'falling', or 'change'.
     */
    watch(handler, trigger = 'change') {
        this._chip._watchers[this._n] = { handler, trigger, lastState: 1 };
    }

    /** Unsubscribe this pin's handler. No-op if not registered. */
    unwatch() {
        delete this._chip._watchers[this._n];
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
