'use strict';

/**
 * In-memory fake I2C connection for unit tests — no hardware, no bus.
 *
 * Supports the two access patterns chip drivers in this repo use:
 *
 * - Register-addressed reads (`writeRead(Buffer.from([reg]), n)`): backed by
 *   a byte-addressable `registers` map. Preload it with `setRegister` before
 *   constructing the chip.
 * - Plain streamed reads (`read(n)`, no register address): backed by a FIFO
 *   queue. Preload responses with `queueRead`; each `read(n)` call pops the
 *   next one. Falls back to `n` zero bytes if the queue is empty.
 *
 * Every `write()` call (register writes and plain command writes alike) is
 * appended to `writes` for assertions, and 2+ byte writes are also applied
 * to `registers` so a later `writeRead` sees them.
 */
class I2CConnectionMock {
    constructor() {
        this.registers = new Map();
        this.writes = [];
        this._readQueue = [];
    }

    /** Preload consecutive register bytes starting at `reg`. */
    setRegister(reg, values) {
        for (let i = 0; i < values.length; i++) {
            this.registers.set(reg + i, values[i]);
        }
    }

    /** Queue bytes to be returned by the next plain `read(n)` call. */
    queueRead(data) {
        this._readQueue.push(Buffer.from(data));
    }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        if (buf.length >= 2) {
            const reg = buf[0];
            for (let i = 1; i < buf.length; i++) {
                this.registers.set(reg + i - 1, buf[i]);
            }
        }
    }

    async read(n) {
        if (this._readQueue.length > 0) {
            const front = this._readQueue.shift();
            const out = Buffer.alloc(n);
            front.copy(out, 0, 0, Math.min(n, front.length));
            return out;
        }
        return Buffer.alloc(n);
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        const reg = buf[0];
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) {
            out[i] = this.registers.get(reg + i) || 0;
        }
        return out;
    }

    async close() {}
}

module.exports = { I2CConnectionMock };
