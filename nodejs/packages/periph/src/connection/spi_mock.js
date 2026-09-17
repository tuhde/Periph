'use strict';

/**
 * In-memory fake SPI connection for unit tests — no hardware, no bus.
 *
 * Models the same primitive that `SPIConnection` exposes to chip drivers:
 *
 * - `write(data)`: appends `data` to `writes` for assertions. If `data` is
 *   a command-address-plus-data packet (i.e. one of the MCP2515-style
 *   multi-byte commands — WRITE `0x02 addr byte...`, BIT MODIFY
 *   `0x05 addr mask data`, LOAD TX BUFFER `0x40|n payload...`), the
 *   trailing bytes are also written into the byte-addressable `registers`
 *   map starting at the address byte. Writes whose first byte is a
 *   standalone instruction (RESET `0xC0`, RTS `0x80|mask`) are recorded
 *   but do not touch `registers`.
 * - `read(n)`: returns a `n`-byte FIFO-buffered payload, popped from
 *   `queueRead`. Falls back to `n` zero bytes if the queue is empty.
 * - `writeRead(data, n)`: writes are first applied to `registers` when
 *   `data[0]` is a register-addressed instruction (READ `0x03 addr`,
 *   READ RX BUFFER `0x90|offset`, READ STATUS `0xA0`, RX STATUS `0xB0`).
 *   The returned `n` bytes are read from `registers` starting at
 *   `data[1]` (when the instruction is READ/READ RX BUFFER) or from
 *   address `0` (READ STATUS / RX STATUS, which return a single
 *   status byte regardless of address).
 *
 * Useful commands and addresses recognised by `setRegister` and the
 * auto-into-registers `write` update:
 *
 * - RESET  `0xC0`                        (no register update)
 * - READ   `0x03 addr`                   (command with 1 address byte)
 * - WRITE  `0x02 addr data...`           (command + address + data)
 * - LOAD TX BUFFER `0x40|offset payload` (command + 13 payload bytes;
 *          TX buffers map to addresses 0x31..0x3D / 0x41..0x4D /
 *          0x51..0x5D — `0x40|0` → 0x31, `0x40|2` → 0x32…)
 * - RTS    `0x80|mask`                   (no register update)
 * - READ STATUS  `0xA0`                  (returns `registers.get(0)`
 *          or 0 if unset — works around the chip's "address ignored"
 *          behaviour by stashing the queued byte at address 0)
 * - RX STATUS    `0xB0`                  (likewise)
 * - BIT MODIFY   `0x05 addr mask data`   (command + addr + 2 data bytes)
 */
class SPIConnectionMock {
    constructor() {
        this.registers = new Map();
        this.writes = [];
        this._readQueue = [];
    }

    /**
     * Preload consecutive register bytes starting at `reg`.
     * @param {number} reg - First register address.
     * @param {number[]|Buffer|Uint8Array} values - Bytes to set.
     */
    setRegister(reg, values) {
        for (let i = 0; i < values.length; i++) {
            this.registers.set(reg + i, values[i]);
        }
    }

    /**
     * Queue bytes to be returned by the next plain `read(n)` call.
     * @param {number[]|Buffer|Uint8Array} data - Bytes to queue.
     */
    queueRead(data) {
        this._readQueue.push(Buffer.from(data));
    }

    /**
     * Apply a write command to the in-memory register map. Returns true if
     * the command affected registers, false if it was a standalone
     * instruction (RESET / RTS) or an unrecognised first byte.
     * @param {Buffer} buf
     * @returns {boolean}
     */
    _applyWrite(buf) {
        const cmd = buf[0];
        if (cmd === 0xC0 || (cmd & 0x80) === 0x80) {
            return false;
        }
        if (cmd === 0x02 && buf.length >= 3) {
            const reg = buf[1];
            for (let i = 2; i < buf.length; i++) {
                this.registers.set(reg + i - 2, buf[i]);
            }
            return true;
        }
        if (cmd === 0x05 && buf.length >= 4) {
            const reg = buf[1];
            const mask = buf[2];
            const data = buf[3];
            const cur = this.registers.get(reg) || 0;
            this.registers.set(reg, (cur & ~mask) | (data & mask));
            return true;
        }
        if ((cmd & 0xE0) === 0x40) {
            return false;
        }
        return false;
    }

    /** @param {Buffer|Uint8Array} data */
    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        this._applyWrite(buf);
    }

    /** @param {number} n */
    async read(n) {
        if (this._readQueue.length > 0) {
            const front = this._readQueue.shift();
            const out = Buffer.alloc(n);
            front.copy(out, 0, 0, Math.min(n, front.length));
            return out;
        }
        return Buffer.alloc(n);
    }

    /** @param {Buffer|Uint8Array} data @param {number} n */
    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        const cmd = buf[0];
        const out = Buffer.alloc(n);
        let startReg = null;
        if (cmd === 0x03 && buf.length >= 2) {
            startReg = buf[1];
        } else if ((cmd & 0xF0) === 0x90) {
            const offset = cmd & 0x07;
            // Per the spec: offset 0 → RXB0SIDH (0x61), offset 2 → RXB0D0
            // (0x66); offset 4 → RXB1SIDH (0x71), offset 6 → RXB1D0 (0x76).
            // The first byte of the returned burst is therefore the SIDH of
            // the chosen RX buffer — never the control register.
            if (offset < 4) startReg = 0x61 + (offset & 0x03);
            else             startReg = 0x71 + (offset & 0x03);
        } else if (cmd === 0xA0 || cmd === 0xB0) {
            startReg = 0;
        }
        if (startReg !== null) {
            for (let i = 0; i < n; i++) {
                out[i] = this.registers.get(startReg + i) || 0;
            }
        }
        return out;
    }

    /** No-op. */
    async close() {}
}

module.exports = { SPIConnectionMock };
