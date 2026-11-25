'use strict';

const { WireMode } = require('./display');

/**
 * In-memory fake display connection for unit tests — no hardware, no bus.
 *
 * Has the public interface of the real connection (sendCommand, sendData,
 * readData, reset, waitBusy, busyLevel, enable/disable) and records every call
 * in order in `events`: {type:'reset'}, {type:'command', cmd, data},
 * {type:'data', data}, {type:'read', length}, {type:'waitBusy',
 * busyActiveHigh, timeoutMs}, {type:'delay', ms}, {type:'delayUs', us}.
 */
class DisplayConnectionMock {
    /**
     * @param {object} [options]
     * @param {number} [options.wireMode=WireMode.SIMPLEX] - readData() throws in SIMPLEX.
     */
    constructor({ wireMode = WireMode.SIMPLEX } = {}) {
        this.wireMode = wireMode;
        this.events = [];
        this.ready = true;        // waitBusy() result; set false to simulate a timeout
        this.busyHigh = false;    // raw level returned by busyLevel()
        this._readQueue = [];
        this._enabled = true;
    }

    /** Queue bytes that later readData() calls return, in order. @param {Buffer|number[]} data */
    queueRead(data) { this._readQueue.push(...data); }

    /** @returns {Array<{cmd:number,data:Buffer}>} sendCommand() calls so far. */
    get commands() {
        return this.events.filter((e) => e.type === 'command').map((e) => ({ cmd: e.cmd, data: e.data }));
    }

    /** @returns {number} Number of reset() calls so far. */
    get resetCount() { return this.events.filter((e) => e.type === 'reset').length; }

    async enable() { this._enabled = true; }
    async disable() { this._enabled = false; }
    isEnabled() { return this._enabled; }

    async reset() { if (this._enabled) this.events.push({ type: 'reset' }); }
    async delayMs(ms) { this.events.push({ type: 'delay', ms }); }
    async delayUs(us) { this.events.push({ type: 'delayUs', us }); }
    busyLevel() { return this.busyHigh; }

    async waitBusy(busyActiveHigh, timeoutMs = null) {
        this.events.push({ type: 'waitBusy', busyActiveHigh, timeoutMs });
        return this.ready;
    }

    async sendCommand(cmd, data = []) {
        if (!this._enabled) return;
        this.events.push({ type: 'command', cmd, data: Buffer.from(data) });
    }

    async sendData(data) {
        if (!this._enabled) return;
        this.events.push({ type: 'data', data: Buffer.from(data) });
    }

    async readData(length) {
        if (this.wireMode === WireMode.SIMPLEX) {
            throw new Error('readData() is not available in SIMPLEX wire mode');
        }
        if (!this._enabled) return Buffer.alloc(length);
        this.events.push({ type: 'read', length });
        const out = Buffer.alloc(length);
        for (let i = 0; i < length; i++) out[i] = this._readQueue.length ? this._readQueue.shift() : 0;
        return out;
    }

    close() {}
}

module.exports = { DisplayConnectionMock };
