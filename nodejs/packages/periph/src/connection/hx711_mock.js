'use strict';

/**
 * In-memory fake HX711 connection for unit tests — no hardware, no GPIO.
 *
 * HX711 has no register map; each conversion is instead selected by the
 * pulse count (25/26/27) sent to `readRaw`. Preload signed 24-bit conversion
 * results with `queueRead`; each `readRaw` call pops the next one (0 if the
 * queue is empty) and, matching every real connection's contract, validates
 * `numPulses` is one of {25, 26, 27}, throwing an `Error` otherwise. Every
 * call's pulse count is appended to `reads` so tests can assert which
 * channel/gain a driver call actually requested (e.g. that `setGain` drives
 * the right pulse count). `powerDown`/`powerUp` calls are logged to
 * `powerCalls`.
 */
class HX711ConnectionMock {
    constructor() {
        this._queue = [];
        this.reads = [];
        this.powerCalls = [];
        this.ready = true;
    }

    /** Queue a signed 24-bit value to be returned by the next readRaw() call. */
    queueRead(value) {
        this._queue.push(value);
    }

    isReady() {
        return this.ready;
    }

    readRaw(numPulses) {
        if (numPulses !== 25 && numPulses !== 26 && numPulses !== 27)
            throw new Error('numPulses must be 25, 26, or 27');
        this.reads.push(numPulses);
        if (this._queue.length > 0) return this._queue.shift();
        return 0;
    }

    powerDown() {
        this.powerCalls.push('down');
    }

    powerUp() {
        this.powerCalls.push('up');
    }

    close() {}
}

module.exports = { HX711ConnectionMock };
