'use strict';

/**
 * In-memory fake connection for NEO-6 unit tests — no hardware, no bus.
 *
 * NEO-6's driver treats UART, I2C (DDC), and SPI as the same underlying
 * NMEA/UBX byte stream (see specs/gnss/neo-6.md's "Connection abstraction"
 * note): `read(1)` for UART, `writeRead(Buffer.from([0xFF]), 1)` for I2C,
 * `writeRead(Buffer.alloc(0), 1)` for SPI. This mock is transport-shape
 * agnostic: preload the stream with `queueBytes(data)`; `read()` and
 * `writeRead()` both just pop the next byte(s) off the front of one shared
 * FIFO, regardless of the prefix passed to `writeRead` — matching the real
 * module, where all three transports deliver the same bytes and only the
 * framing differs. `write()` calls (e.g. sendUbx) are logged to `writes`
 * for assertions.
 */
class Neo6ConnectionMock {
    constructor() {
        this._stream = Buffer.alloc(0);
        this.writes = [];
    }

    /** Append bytes to the end of the shared read stream. */
    queueBytes(data) {
        this._stream = Buffer.concat([this._stream, Buffer.from(data)]);
    }

    async read(n) {
        const out = this._stream.subarray(0, n);
        this._stream = this._stream.subarray(out.length);
        return Buffer.from(out);
    }

    async writeRead(data, n) {
        return this.read(n);
    }

    async write(data) {
        this.writes.push(Buffer.from(data));
    }

    async close() {}
}

module.exports = { Neo6ConnectionMock };
