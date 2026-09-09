'use strict';

/**
 * In-memory fake DHTxx connection for unit tests — no hardware, no GPIO.
 *
 * DHTxx has no register map and no framing to fake at this level: the chip
 * driver only ever calls `read()` and gets back a raw 5-byte frame (or an
 * exception propagating straight through, e.g. a real connection's
 * timeout/framing `DHTxxError`). Preload frames with `queueRead`; preload an
 * error to throw instead with `queueError`. Each `read()` call pops the next
 * queued item (falling back to a 5 zero-byte buffer if the queue is empty,
 * matching the real connections' disabled-state return value).
 */
class DHTxxConnectionMock {
    constructor() {
        this._queue = [];
    }

    /** Queue a raw 5-byte frame to be returned by the next read(). */
    queueRead(frame) {
        this._queue.push({ frame: Buffer.from(frame) });
    }

    /** Queue an Error instance to be thrown by the next read(). */
    queueError(err) {
        this._queue.push({ err });
    }

    read() {
        if (this._queue.length === 0) return Buffer.alloc(5);
        const item = this._queue.shift();
        if (item.err) throw item.err;
        return item.frame;
    }

    close() {}
}

module.exports = { DHTxxConnectionMock };
