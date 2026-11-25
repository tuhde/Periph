'use strict';

const { crc8 } = require('../chips/motor/tmc2208');

/**
 * Scripted TMC2208-style UART chip simulator for unit tests — no hardware.
 *
 * Behaves like a Trinamic single-wire UART slave: `write()` accepts 8-byte
 * write datagrams (stores the register, bumps IFCNT) and 4-byte read requests
 * (queues the reply from `regs`). With `echo` every transmitted byte is also
 * queued back, as on the shared PDN_UART wire. All datagrams are kept in
 * `datagrams`; register writes in `writes`.
 */
class UARTConnectionMock {
    constructor(echo = true) {
        this.echo = echo;
        this.regs = new Map([[0x06, 0x21000100], [0x02, 0]]);
        this.datagrams = [];
        this.writes = [];
        this.corruptNextReply = false;
        this._rx = Buffer.alloc(0);
    }

    async write(data) {
        const d = Buffer.from(data);
        this.datagrams.push(d);
        if (this.echo) this._rx = Buffer.concat([this._rx, d]);
        if (d.length === 8 && (d[2] & 0x80)) {
            const reg = d[2] & 0x7F;
            const value = d.readUInt32BE(3);
            this.regs.set(reg, value);
            this.writes.push([reg, value]);
            this.regs.set(0x02, (this.regs.get(0x02) + 1) & 0xFF);
        } else if (d.length === 4) {
            const reg = d[2] & 0x7F;
            const body = Buffer.alloc(7);
            body[0] = 0x05; body[1] = 0xFF; body[2] = reg;
            body.writeUInt32BE((this.regs.get(reg) ?? 0) >>> 0, 3);
            let crc = crc8(body);
            if (this.corruptNextReply) { crc ^= 0xFF; this.corruptNextReply = false; }
            this._rx = Buffer.concat([this._rx, body, Buffer.from([crc])]);
        }
    }

    async read(n) {
        const out = Buffer.from(this._rx.subarray(0, n));
        this._rx = this._rx.subarray(out.length);
        return out;
    }

    async writeRead(data, n) {
        await this.write(data);
        return this.read(n);
    }

    async flush() { this._rx = Buffer.alloc(0); }

    /** Last value written to a register, or undefined. */
    lastWrite(reg) {
        const w = this.writes.filter((x) => x[0] === reg);
        return w.length ? w[w.length - 1][1] : undefined;
    }
}

module.exports = { UARTConnectionMock };
