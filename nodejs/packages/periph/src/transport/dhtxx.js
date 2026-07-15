'use strict';

const Gpio = require('onoff').Gpio;

/**
 * Error thrown when the DHTxx transport cannot complete a read.
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
 * DHTxx single-wire transport for Node.js (wraps onoff Gpio).
 *
 * Implements the host side of the DHT11 / DHT22 single-wire protocol: a
 * bidirectional DATA line, externally pulled up to VCC via a 4.7 kΩ resistor.
 * The transport switches the pin's direction as needed via the onoff
 * `reconfigureDirection: true` option. Timing uses `process.hrtime.bigint()`
 * with busy-wait loops; V8's non-deterministic GC pauses make this the least
 * timing-reliable of the Linux targets.
 *
 * The open-drain two-pin variant is not available on Node.js — `onoff` does
 * not expose open-drain drive modes.
 */
class DHTxxTransport {
    /**
     * @param {number} dataPin - GPIO pin number for the DATA line.
     */
    constructor(dataPin) {
        this._pin = new Gpio(dataPin, 'in', 'both', { reconfigureDirection: true });
    }

    /**
     * Execute the full DHTxx transaction and return the raw 5-byte frame.
     *
     * @returns {Buffer} 5 bytes — [hum_int, hum_dec, temp_int, temp_dec, checksum].
     * @throws {DHTxxError} On timeout or framing error.
     */
    read() {
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

module.exports = { DHTxxTransport, DHTxxError };
