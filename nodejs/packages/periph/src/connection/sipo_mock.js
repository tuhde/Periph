'use strict';

/**
 * In-memory fake SiPo connection for unit tests — no hardware, no bus.
 *
 * Models the write-only shift-register protocol chip drivers in this repo
 * use (TPIC6B595, SN74HC595, ...): write() shifts + latches, clear() pulses
 * SRCLR, setOutputEnable() drives G. SRCLR/G availability is configurable
 * at construction, matching the real connection's thrown Error when the
 * corresponding GPIO line wasn't wired.
 */
class SiPoConnectionMock {
    /**
     * @param {object} [options]
     * @param {boolean} [options.hasSrclr=true] - true if SRCLR is "wired".
     * @param {boolean} [options.hasG=true] - true if G is "wired".
     */
    constructor({ hasSrclr = true, hasG = true } = {}) {
        this.writes = [];
        this.clearCount = 0;
        this.outputEnableCalls = [];
        this._hasSrclr = hasSrclr;
        this._hasG = hasG;
        this._enabled = true;
    }

    enable() { this._enabled = true; }
    disable() { this._enabled = false; }
    isEnabled() { return this._enabled; }

    write(data) {
        if (!this._enabled) return;
        this.writes.push(Buffer.from(data));
    }

    clear() {
        if (!this._hasSrclr) throw new Error('SRCLR not configured');
        this.clearCount++;
    }

    setOutputEnable(enabled) {
        if (!this._hasG) throw new Error('G not configured');
        this.outputEnableCalls.push(enabled);
    }

    close() {}
}

module.exports = { SiPoConnectionMock };
