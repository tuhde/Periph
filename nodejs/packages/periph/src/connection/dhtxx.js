'use strict';

const Gpio = require('onoff').Gpio;

/**
 * Error thrown when the DHTxx connection cannot complete a read.
 *
 * `kind` is one of "timeout" (sensor did not respond) or "framing"
 * (fewer than 40 bit pulses received).
 */
class DHTxxError extends Error {
    constructor(kind, detail) {
        super(detail ? `${kind}: ${detail}` : kind);
        this.kind = kind;
    }
}

/**
 * DHTxx single-wire connection for Node.js (wraps onoff Gpio).
 *
 * Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 * bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 * The connection switches the pin's direction as needed via the onoff
 * `reconfigureDirection: true` option. Timing uses `process.hrtime.bigint()`
 * with busy-wait loops; V8's non-deterministic GC pauses make this the least
 * timing-reliable of the Linux targets.
 *
 * The open-drain two-pin variant is not available on Node.js — `onoff` does
 * not expose open-drain drive modes.
 *
 * This is a custom protocol with no generic byte read/write, so it does not
 * extend the shared Connection base — it carries its own enabled flag and
 * enPin instead.
 */
class DHTxxConnection {
    /**
     * @param {number} dataPin - GPIO pin number for the DATA line.
     * @param {import('./output_pin').OutputPin|null} [enPin=null] - Optional EN-pin OutputPin.
     */
    constructor(dataPin, enPin = null) {
        this._pin = new Gpio(dataPin, 'in', 'both', { reconfigureDirection: true });
        this.enPin = enPin;
        this._enabled = true;
    }

    /** Resume reads; drives the hardware EN pin high if wired.
     * @returns {Promise<void>}
     */
    async enable() {
        this._enabled = true;
        if (this.enPin) await this.enPin.set(true);
    }

    /** Gate read(); drives the hardware EN pin low if wired.
     * @returns {Promise<void>}
     */
    async disable() {
        this._enabled = false;
        if (this.enPin) await this.enPin.set(false);
    }

    /** @returns {boolean} The current software-gate state. */
    isEnabled() { return this._enabled; }

    /**
     * Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     * Returns a zero-filled buffer without touching the bus if this
     * connection is disabled.
     *
     * @returns {Buffer} 5 bytes — [hum_int, hum_dec, temp_int, temp_dec, checksum].
     * @throws {DHTxxError} On timeout or framing error.
     */
    read() {
        if (!this._enabled) return Buffer.alloc(5);

        const startLowMs = 20;
        const responseTimeoutUs = 200;
        const bitTimeoutUs = 200;
        const bitThresholdUs = 40;

        this._driveLow();
        const until = Date.now() + startLowMs;
        while (Date.now() < until) { /* busy-wait */ }
        this._releaseBus();

        let elapsed = this._measurePulse(0, responseTimeoutUs);
        if (elapsed < 0) throw new DHTxxError('timeout', `sensor did not pull DATA low within ${responseTimeoutUs} us`);
        elapsed = this._measurePulse(1, responseTimeoutUs);
        if (elapsed < 0) throw new DHTxxError('timeout', 'sensor did not release after response low');

        const frame = Buffer.alloc(5);
        for (let byteIdx = 0; byteIdx < 5; byteIdx++) {
            let byte = 0;
            for (let bitIdx = 0; bitIdx < 8; bitIdx++) {
                elapsed = this._measurePulse(0, bitTimeoutUs);
                if (elapsed < 0) throw new DHTxxError('framing', `bit ${byteIdx * 8 + bitIdx} start-low missing`);
                elapsed = this._measurePulse(1, bitTimeoutUs);
                if (elapsed < 0) throw new DHTxxError('framing', `bit ${byteIdx * 8 + bitIdx} high-pulse missing`);
                byte = (byte << 1) | (elapsed > bitThresholdUs ? 1 : 0);
            }
            frame[byteIdx] = byte;
        }
        return frame;
    }

    _driveLow() {
        this._pin.unexport();
        this._pin = new Gpio(this._pin.gpio, 'out');
        this._pin.writeSync(0);
    }

    _releaseBus() {
        this._pin.unexport();
        this._pin = new Gpio(this._pin.gpio, 'in', 'both', { reconfigureDirection: true });
    }

    _measurePulse(level, timeoutUs) {
        const deadline = process.hrtime.bigint() + BigInt(timeoutUs) * 1000n;
        while (this._pin.readSync() !== level) {
            if (process.hrtime.bigint() >= deadline) return -1;
        }
        const pulseStart = process.hrtime.bigint();
        while (this._pin.readSync() === level) {
            if (process.hrtime.bigint() >= deadline) return -1;
        }
        return Number((process.hrtime.bigint() - pulseStart) / 1000n);
    }

    /**
     * Release the GPIO pin.
     */
    close() {
        try { this._pin.unexport(); } catch (_) { /* already closed */ }
    }
}

module.exports = { DHTxxConnection, DHTxxError };
