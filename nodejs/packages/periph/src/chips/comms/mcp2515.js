'use strict';

/**
 * MCP2515 stand-alone CAN 2.0B controller driver (SPI).
 *
 * Supports standard (11-bit) and extended (29-bit) identifiers, data frames
 * of 0–8 bytes, and CAN bus speeds up to 1 Mbit/s. Three TX buffers, two RX
 * buffers, six acceptance filters, two acceptance masks.
 *
 * The driver is built around an internal `_MCP2515Base` that owns the
 * register-level logic and the SPI instruction helpers. `MCP2515Minimal`
 * adds the primary send/recv API; `MCP2515Full` extends Minimal with mode
 * control, error counters, acceptance filters/masks, abort and one-shot
 * support, and explicit per-buffer TX selection.
 *
 * Default configuration baked into Minimal:
 *     - Standard 11-bit or extended 29-bit ID accepted (set per-call)
 *     - All three TX buffers enabled; TXB0 used by default with priority 3
 *     - All acceptance filters and masks pass every ID (RXM[1:0]=11 in
 *       RXB0CTRL and RXB1CTRL; RXM0SIDH..RXM1EID0 = 0x00)
 *     - BUKT=1 in RXB0CTRL (RXB0 → RXB1 overflow rollover)
 *     - CANINTE=0x00 (polled operation; INT pin not used)
 *     - OSM=0 (retransmit on error or loss of arbitration)
 *     - Loopback mode not enabled — Normal mode at init
 *     - Bit timing: 125 kbit/s with 8 MHz oscillator (CNF1=0x01, CNF2=0xBA,
 *       CNF3=0x03); overridable via `init()` arguments.
 *
 * All SPI transactions follow the chip's instruction set:
 *     RESET              0xC0
 *     READ               0x03 + addr + n bytes out
 *     READ RX BUFFER     0x90|n + bytes out
 *     WRITE              0x02 + addr + n bytes in
 *     LOAD TX BUFFER     0x40|n + bytes in
 *     RTS                0x80|mask
 *     READ STATUS        0xA0 + 1 byte out
 *     RX STATUS          0xB0 + 1 byte out
 *     BIT MODIFY         0x05 + addr + mask + data
 */

// SPI instruction set
const INSTR_RESET       = 0xC0;
const INSTR_READ        = 0x03;
const INSTR_READ_RX_BUF = 0x90;
const INSTR_WRITE       = 0x02;
const INSTR_LOAD_TX_BUF = 0x40;
const INSTR_RTS         = 0x80;
const INSTR_READ_STATUS = 0xA0;
const INSTR_RX_STATUS   = 0xB0;
const INSTR_BIT_MODIFY  = 0x05;

// Register addresses
const REG_CANSTAT  = 0x0E;
const REG_CANCTRL  = 0x0F;
const REG_CNF3     = 0x28;
const REG_CNF2     = 0x29;
const REG_CNF1     = 0x2A;
const REG_CANINTE  = 0x2B;
const REG_CANINTF  = 0x2C;
const REG_EFLG     = 0x2D;

const REG_RXB0CTRL = 0x60;
const REG_RXB1CTRL = 0x70;

const REG_TXB0CTRL = 0x30;
const REG_TXB1CTRL = 0x40;
const REG_TXB2CTRL = 0x50;

const REG_RXM0SIDH = 0x20;
const REG_RXM1SIDH = 0x24;

const REG_TEC = 0x1C;
const REG_REC = 0x1D;

const CANSTAT_OPMOD_MASK = 0xE0;
const CANSTAT_OPMOD_NORMAL      = 0x00;
const CANSTAT_OPMOD_SLEEP       = 0x20;
const CANSTAT_OPMOD_LOOPBACK    = 0x40;
const CANSTAT_OPMOD_LISTEN_ONLY = 0x60;
const CANSTAT_OPMOD_CONFIG      = 0x80;

const CANCTRL_REQOP_NORMAL      = 0x00;
const CANCTRL_REQOP_SLEEP       = 0x20;
const CANCTRL_REQOP_LOOPBACK    = 0x40;
const CANCTRL_REQOP_LISTEN_ONLY = 0x60;
const CANCTRL_REQOP_CONFIG      = 0x80;

const TXBnCTRL_TXREQ  = 0x08;
const TXBnCTRL_TXP_MASK = 0x03;

const RTS_MASK_TXB0 = 0x01;
const RTS_MASK_TXB1 = 0x02;
const RTS_MASK_TXB2 = 0x04;

const RXBnCTRL_RXM_MASK = 0x60;
const RXBnCTRL_RXM_ANY  = 0x60;
const RXBnCTRL_BUKT     = 0x04;

const CANINTF_RX0IF = 0x01;
const CANINTF_RX1IF = 0x02;

const EFLG_RX0OVR = 0x40;
const EFLG_RX1OVR = 0x80;

const CNF_PRESCALER_8MHZ = {
    125:  [0x01, 0xBA, 0x03],
    250:  [0x00, 0xBA, 0x03],
    500:  [0x00, 0x91, 0x01],
    1000: [0x00, 0x80, 0x00],
};
const CNF_PRESCALER_16MHZ = {
    125:  [0x03, 0xBA, 0x03],
    250:  [0x01, 0xBA, 0x03],
    500:  [0x00, 0xBA, 0x03],
    1000: [0x00, 0x91, 0x01],
};

/**
 * A CAN 2.0B data or remote frame.
 */
class CanFrame {
    /**
     * @param {number} id - 11-bit (standard) or 29-bit (extended) CAN identifier.
     * @param {Buffer|Uint8Array} [data=Buffer.alloc(0)] - Payload bytes, 0–8 bytes.
     * @param {boolean} [extended=false] - True if this is a 29-bit extended-ID frame.
     * @param {boolean} [rtr=false] - True if this is a remote transmission request frame.
     */
    constructor(id, data = Buffer.alloc(0), extended = false, rtr = false) {
        if (extended) {
            if (!Number.isInteger(id) || id < 0 || id > 0x1FFFFFFF) {
                throw new RangeError(`extended id 0x${id.toString(16)} out of range [0, 0x1FFFFFFF]`);
            }
        } else {
            if (!Number.isInteger(id) || id < 0 || id > 0x7FF) {
                throw new RangeError(`standard id 0x${id.toString(16)} out of range [0, 0x7FF]`);
            }
        }
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        if (buf.length > 8) {
            throw new RangeError(`CAN data length ${buf.length} exceeds 8 bytes`);
        }
        this.id = id;
        this.data = buf;
        this.extended = !!extended;
        this.rtr = !!rtr;
    }
}

/**
 * Base class for the MCP2515 CAN controller — register logic and SPI helpers.
 *
 * Owns the connection and exposes register read/write helpers plus the SPI
 * instruction set. The Minimal-stage public API (`init`, `send`, `recv`)
 * lives in `MCP2515Minimal`; Full-only functionality is implemented as
 * underscore-prefixed private helpers here and re-exposed via
 * `MCP2515FullMixin`.
 *
 * Not exported — construct one of the `MCP2515Minimal` /
 * `MCP2515Full` classes.
 */
class _MCP2515Base {
    /**
     * @param {object} connection - Configured SPI connection (write, read, writeRead).
     * @param {number} [bitrateKbps=125] - Bus bitrate in kbit/s (125, 250, 500, or 1000).
     * @param {number} [oscMhz=8] - Oscillator frequency in MHz (8 or 16).
     */
    constructor(connection, bitrateKbps = 125, oscMhz = 8) {
        this._connection = connection;
        if (bitrateKbps !== 125 && bitrateKbps !== 250 && bitrateKbps !== 500 && bitrateKbps !== 1000) {
            throw new RangeError('bitrateKbps must be one of 125, 250, 500, 1000');
        }
        if (oscMhz !== 8 && oscMhz !== 16) {
            throw new RangeError('oscMhz must be 8 or 16');
        }
        this._bitrateKbps = bitrateKbps;
        this._oscMhz = oscMhz;

        this._runInit(bitrateKbps, oscMhz);
    }

    async _runInit(bitrateKbps, oscMhz) {
        await this._reset();
        await this._sleep(5);
        await this._waitOpMode(CANSTAT_OPMOD_CONFIG);

        const cnf = this._cnfFor(bitrateKbps, oscMhz);
        await this._writeReg(REG_CNF1, cnf[0]);
        await this._writeReg(REG_CNF2, cnf[1]);
        await this._writeReg(REG_CNF3, cnf[2]);

        await this._writeReg(REG_RXB0CTRL, RXBnCTRL_RXM_ANY | RXBnCTRL_BUKT);
        await this._writeReg(REG_RXB1CTRL, RXBnCTRL_RXM_ANY);

        await this._writeReg(REG_CANINTE, 0x00);

        await this._writeReg(REG_TXB0CTRL, TXBnCTRL_TXP_MASK);
        await this._writeReg(REG_TXB1CTRL, TXBnCTRL_TXP_MASK);
        await this._writeReg(REG_TXB2CTRL, TXBnCTRL_TXP_MASK);

        await this._writeReg(REG_CANCTRL, CANCTRL_REQOP_NORMAL);
        await this._waitOpMode(CANSTAT_OPMOD_NORMAL);
    }

    _cnfFor(bitrateKbps, oscMhz) {
        const table = (oscMhz === 8) ? CNF_PRESCALER_8MHZ : CNF_PRESCALER_16MHZ;
        return table[bitrateKbps];
    }

    _sleep(ms) {
        return new Promise(r => setTimeout(r, ms));
    }

    async _reset() {
        await this._connection.write(Buffer.from([INSTR_RESET]));
    }

    async _writeReg(reg, value) {
        await this._connection.write(Buffer.from([INSTR_WRITE, reg & 0xFF, value & 0xFF]));
    }

    async _readReg(reg) {
        const out = await this._connection.writeRead(Buffer.from([INSTR_READ, reg & 0xFF]), 1);
        return out[0];
    }

    async _modifyReg(reg, mask, value) {
        await this._connection.write(Buffer.from([
            INSTR_BIT_MODIFY, reg & 0xFF, mask & 0xFF, value & 0xFF,
        ]));
    }

    async _readStatus() {
        const out = await this._connection.writeRead(Buffer.from([INSTR_READ_STATUS]), 1);
        return out[0];
    }

    async _rts(mask) {
        await this._connection.write(Buffer.from([INSTR_RTS | (mask & 0x07)]));
    }

    async _waitOpMode(target, timeoutMs = 100) {
        const targetMasked = target & CANSTAT_OPMOD_MASK;
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const cur = await this._readReg(REG_CANSTAT);
            if ((cur & CANSTAT_OPMOD_MASK) === targetMasked) return;
            await this._sleep(5);
        }
        throw new Error(`timed out waiting for CANSTAT.OPMOD = 0x${targetMasked.toString(16)}`);
    }

    _packId(canId, extended) {
        if (extended) {
            return [
                (canId >> 21) & 0xFF,
                (((canId >> 18) & 0x07) << 5) | 0x08 | ((canId >> 16) & 0x03),
                (canId >> 8) & 0xFF,
                canId & 0xFF,
            ];
        }
        return [
            (canId >> 3) & 0xFF,
            (canId & 0x07) << 5,
            0,
            0,
        ];
    }

    _unpackId(sidh, sidl, eid8, eid0, ide) {
        if (ide) {
            return (sidh << 21) | ((sidl >> 5) << 18) | ((sidl & 0x03) << 16) | (eid8 << 8) | eid0;
        }
        return (sidh << 3) | (sidl >> 5);
    }

    _txBufOffset(bufIndex) {
        if (bufIndex === 0) return 0;
        if (bufIndex === 1) return 2;
        if (bufIndex === 2) return 4;
        throw new RangeError('bufIndex must be 0, 1, or 2');
    }

    _txRegBase(bufIndex) {
        return [REG_TXB0CTRL, REG_TXB1CTRL, REG_TXB2CTRL][bufIndex];
    }

    async _txFreeBuf(timeoutMs = 10) {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const status = await this._readStatus();
            if (!(status & 0x04)) return 0;
            if (!(status & 0x10)) return 1;
            if (!(status & 0x40)) return 2;
            await this._sleep(1);
        }
        return null;
    }

    async _loadTxBuffer(bufIndex, canId, data, extended, rtr) {
        const [sidh, sidl, eid8, eid0] = this._packId(canId, extended);
        const dlc = (data.length & 0x0F) | (rtr ? 0x40 : 0x00);
        const base = this._txBufOffset(bufIndex);
        const payload = Buffer.alloc(13);
        payload[0] = sidh;
        payload[1] = sidl;
        payload[2] = eid8;
        payload[3] = eid0;
        payload[4] = dlc;
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        for (let i = 0; i < buf.length; i++) payload[5 + i] = buf[i];
        const cmd = Buffer.from([INSTR_LOAD_TX_BUF | base]);
        await this._connection.write(Buffer.concat([cmd, payload]));
    }

    async _send(canId, data, extended, rtr, bufIndex) {
        let freeBuf = bufIndex;
        if (bufIndex === null || bufIndex === undefined) {
            freeBuf = await this._txFreeBuf();
            if (freeBuf === null) throw new Error('all TX buffers busy');
        }
        await this._loadTxBuffer(freeBuf, canId, data, extended, rtr);
        await this._rts(1 << freeBuf);
        const base = this._txRegBase(freeBuf);
        const start = Date.now();
        while (Date.now() - start < 1000) {
            const ctrl = await this._readReg(base);
            if (!(ctrl & TXBnCTRL_TXREQ)) return;
            await this._sleep(1);
        }
        throw new Error(`TX buffer ${freeBuf} did not clear TXREQ within 1 s`);
    }

    async _readRxBuffer(rxIndex) {
        const offset = (rxIndex === 0) ? 0 : 4;
        const data = await this._connection.writeRead(
            Buffer.from([INSTR_READ_RX_BUF | offset]),
            13,
        );
        const sidh = data[0], sidl = data[1], eid8 = data[2], eid0 = data[3], dlc = data[4];
        const ide = (sidl & 0x08) !== 0;
        const rtr = ide ? !!(dlc & 0x40) : !!(sidl & 0x10);
        const canId = this._unpackId(sidh, sidl, eid8, eid0, ide);
        const length = dlc & 0x0F;
        return new CanFrame(canId, data.subarray(5, 5 + length), ide, rtr);
    }

    async _pollRx(timeoutMs) {
        if (timeoutMs <= 0) {
            const status = await this._readStatus();
            if (status & CANINTF_RX0IF) return await this._readRxBuffer(0);
            if (status & CANINTF_RX1IF) return await this._readRxBuffer(1);
            return null;
        }
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const status = await this._readStatus();
            if (status & CANINTF_RX0IF) return await this._readRxBuffer(0);
            if (status & CANINTF_RX1IF) return await this._readRxBuffer(1);
            await this._sleep(1);
        }
        return null;
    }

    async _setMode(mode) {
        let reqop;
        switch (mode) {
            case 'normal':      reqop = CANCTRL_REQOP_NORMAL; break;
            case 'loopback':    reqop = CANCTRL_REQOP_LOOPBACK; break;
            case 'listen_only': reqop = CANCTRL_REQOP_LISTEN_ONLY; break;
            case 'sleep':       reqop = CANCTRL_REQOP_SLEEP; break;
            case 'config':      reqop = CANCTRL_REQOP_CONFIG; break;
            default: throw new RangeError(`mode must be 'normal', 'loopback', 'listen_only', 'sleep', or 'config'`);
        }
        await this._modifyReg(REG_CANCTRL, 0xE0, reqop);
        await this._waitOpMode(reqop);
    }

    async _getMode() {
        const opmod = (await this._readReg(REG_CANSTAT)) & CANSTAT_OPMOD_MASK;
        if (opmod === CANSTAT_OPMOD_NORMAL) return 'normal';
        if (opmod === CANSTAT_OPMOD_SLEEP) return 'sleep';
        if (opmod === CANSTAT_OPMOD_LOOPBACK) return 'loopback';
        if (opmod === CANSTAT_OPMOD_LISTEN_ONLY) return 'listen_only';
        if (opmod === CANSTAT_OPMOD_CONFIG) return 'config';
        return 'unknown';
    }

    async _setFilter(n, canId, extended) {
        if (n < 0 || n > 5) throw new RangeError('filterNum must be 0–5');
        const base = n * 4;
        const [sidh, sidl, eid8, eid0] = this._packId(canId, extended);
        await this._writeReg(base,     sidh);
        await this._writeReg(base + 1, sidl);
        await this._writeReg(base + 2, eid8);
        await this._writeReg(base + 3, eid0);
    }

    async _setMask(n, mask, extended) {
        if (n < 0 || n > 1) throw new RangeError('maskNum must be 0 or 1');
        const base = (n === 0) ? REG_RXM0SIDH : REG_RXM1SIDH;
        const [sidh, sidl, eid8, eid0] = this._packId(mask, extended);
        await this._writeReg(base,     sidh);
        await this._writeReg(base + 1, sidl);
        await this._writeReg(base + 2, eid8);
        await this._writeReg(base + 3, eid0);
    }

    async _setRxMode(buf, mode) {
        if (buf !== 0 && buf !== 1) throw new RangeError('buf must be 0 or 1');
        if (mode !== 0 && mode !== 1 && mode !== 3) {
            throw new RangeError('mode must be 0 (std filter), 1 (ext filter), or 3 (accept all)');
        }
        const reg = (buf === 0) ? REG_RXB0CTRL : REG_RXB1CTRL;
        await this._modifyReg(reg, RXBnCTRL_RXM_MASK, (mode & 0x03) << 5);
    }

    async _readErrors() {
        const tec = await this._readReg(REG_TEC);
        const rec = await this._readReg(REG_REC);
        const eflg = await this._readReg(REG_EFLG);
        return { tec, rec, eflg };
    }

    async _clearOverflow(buf) {
        if (buf === 0)      await this._modifyReg(REG_EFLG, EFLG_RX0OVR, 0x00);
        else if (buf === 1) await this._modifyReg(REG_EFLG, EFLG_RX1OVR, 0x00);
        else throw new RangeError('buf must be 0 or 1');
    }

    async _abortTx() {
        await this._modifyReg(REG_CANCTRL, 0x10, 0x10);
        const start = Date.now();
        while (Date.now() - start < 500) {
            const ctrl = await this._readReg(REG_CANCTRL);
            if (!(ctrl & 0x10)) return;
            await this._sleep(5);
        }
        throw new Error('ABAT did not clear within 500 ms');
    }

    async _setOneShot(enable) {
        await this._modifyReg(REG_CANCTRL, 0x08, enable ? 0x08 : 0x00);
    }
}

/**
 * MCP2515 minimal driver — send/recv with default configuration.
 *
 * Runs the chip's full init sequence at construction: software reset,
 * Configuration mode, accept-all filters, RXB0→RXB1 rollover, polled
 * operation (no interrupts), TXB0/1/2 priority 3, Normal mode.
 */
class MCP2515Minimal extends _MCP2515Base {
    /**
     * @param {object} connection - Configured SPI connection bound to the device.
     * @param {number} [bitrateKbps=125] - Bus bitrate in kbit/s (125, 250, 500, or 1000).
     * @param {number} [oscMhz=8] - Oscillator frequency in MHz (8 or 16).
     */
    constructor(connection, bitrateKbps = 125, oscMhz = 8) {
        super(connection, bitrateKbps, oscMhz);
    }

    /**
     * Re-run the full init sequence with new bitrate/oscillator values.
     * @param {number} [bitrateKbps=125] - Bus bitrate in kbit/s.
     * @param {number} [oscMhz=8] - Oscillator frequency in MHz.
     * @returns {Promise<void>}
     */
    async init(bitrateKbps = 125, oscMhz = 8) {
        if (bitrateKbps !== 125 && bitrateKbps !== 250 && bitrateKbps !== 500 && bitrateKbps !== 1000) {
            throw new RangeError('bitrateKbps must be one of 125, 250, 500, 1000');
        }
        if (oscMhz !== 8 && oscMhz !== 16) {
            throw new RangeError('oscMhz must be 8 or 16');
        }
        this._bitrateKbps = bitrateKbps;
        this._oscMhz = oscMhz;
        await this._runInit(bitrateKbps, oscMhz);
    }

    /**
     * Send a CAN frame.
     *
     * Loads the next free TX buffer (TXB0 first, then TXB1, TXB2), issues
     * RTS, and waits up to 1 s for TXREQ to clear.
     *
     * @param {number} id - 11-bit (standard) or 29-bit (extended) identifier.
     * @param {Buffer|Uint8Array} [data=Buffer.alloc(0)] - Payload bytes, 0–8 bytes.
     * @param {boolean} [extended=false] - True for 29-bit extended-ID frame.
     * @returns {Promise<void>}
     */
    async send(id, data = Buffer.alloc(0), extended = false) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        await this._send(id, buf, extended, false, null);
    }

    /**
     * Receive a single CAN frame.
     *
     * Polls READ STATUS until a frame is available in RXB0 or RXB1, or the
     * timeout elapses.
     *
     * @param {number} [timeoutMs=0] - Receive timeout in ms; 0 means non-blocking poll.
     * @returns {Promise<CanFrame|null>} Received frame, or null on timeout.
     */
    async recv(timeoutMs = 0) {
        return await this._pollRx(timeoutMs);
    }
}

/**
 * Mixin that adds Full-stage functionality on top of a `MCP2515Minimal` subclass.
 *
 * `_MCP2515Base` structurally lacks these methods (they are not defined
 * anywhere on it), so applying this mixin is the only way to obtain them.
 *
 * Usage: `class MCP2515Full extends _MCP2515FullMixin(MCP2515Minimal) {}`.
 *
 * @param {typeof _MCP2515Base} Base - A `MCP2515Minimal` class (itself extending `_MCP2515Base`).
 * @returns {typeof _MCP2515Base} A subclass of `Base` with the Full API added.
 */
const _MCP2515FullMixin = (Base) => class extends Base {
    /**
     * Send a CAN frame using a specific TX buffer.
     * @param {number} id - 11-bit (standard) or 29-bit (extended) identifier.
     * @param {Buffer|Uint8Array} [data=Buffer.alloc(0)] - Payload bytes, 0–8 bytes.
     * @param {boolean} [extended=false] - True for 29-bit extended-ID frame.
     * @param {number} [buf=0] - TX buffer index 0, 1, or 2.
     * @returns {Promise<void>}
     */
    async sendBuffered(id, data = Buffer.alloc(0), extended = false, buf = 0) {
        const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data);
        await this._send(id, bytes, extended, false, buf);
    }

    /**
     * Configure an acceptance filter.
     * @param {number} filterNum - Filter index 0–5.
     * @param {number} id - Identifier value to match.
     * @param {boolean} [extended=false] - True for 29-bit extended-ID filter.
     * @returns {Promise<void>}
     */
    async setFilter(filterNum, id, extended = false) {
        const prevMode = await this._getMode();
        if (prevMode !== 'config') await this._setMode('config');
        try {
            await this._setFilter(filterNum, id, extended);
        } finally {
            if (prevMode !== 'config') await this._setMode(prevMode);
        }
    }

    /**
     * Configure an acceptance mask.
     *
     * Mask bit = 1: filter bit must match. Mask bit = 0: don't care.
     *
     * @param {number} maskNum - 0 (RXB0; filters 0–1) or 1 (RXB1; filters 2–5).
     * @param {number} mask - Mask value.
     * @param {boolean} [extended=false] - True for 29-bit extended-ID mask.
     * @returns {Promise<void>}
     */
    async setMask(maskNum, mask, extended = false) {
        const prevMode = await this._getMode();
        if (prevMode !== 'config') await this._setMode('config');
        try {
            await this._setMask(maskNum, mask, extended);
        } finally {
            if (prevMode !== 'config') await this._setMode(prevMode);
        }
    }

    /**
     * Set RXM[1:0] for the given RX buffer.
     * @param {number} buf - RX buffer index 0 or 1.
     * @param {number} mode - 0=standard filter, 1=extended filter, 3=accept all.
     * @returns {Promise<void>}
     */
    async setRxMode(buf, mode) {
        await this._setRxMode(buf, mode);
    }

    /**
     * Switch operating mode and wait until CANSTAT confirms.
     * @param {string} mode - 'normal', 'loopback', 'listen_only', 'sleep', or 'config'.
     * @returns {Promise<void>}
     */
    async setMode(mode) {
        await this._setMode(mode);
    }

    /**
     * Return the current operating mode from CANSTAT.OPMOD.
     * @returns {Promise<string>} 'normal', 'sleep', 'loopback', 'listen_only', 'config', or 'unknown'.
     */
    async getMode() {
        return await this._getMode();
    }

    /**
     * Issue a SPI RESET command; chip returns to Configuration mode.
     *
     * After a reset, all configuration is back to POR defaults. Re-run
     * `init()` to set the configuration again.
     *
     * @returns {Promise<void>}
     */
    async reset() {
        await this._reset();
        await this._sleep(5);
    }

    /**
     * Return TEC, REC, and EFLG register values.
     * @returns {Promise<{tec:number, rec:number, eflg:number}>}
     */
    async readErrors() {
        return await this._readErrors();
    }

    /**
     * Clear the RX0OVR or RX1OVR flag in EFLG.
     * @param {number} buf - RX buffer 0 or 1.
     * @returns {Promise<void>}
     */
    async clearOverflow(buf) {
        await this._clearOverflow(buf);
    }

    /**
     * Set ABAT in CANCTRL; poll until cleared by hardware.
     * @returns {Promise<void>}
     */
    async abortTx() {
        await this._abortTx();
    }

    /**
     * Enable or disable one-shot mode (OSM in CANCTRL).
     *
     * In one-shot mode the chip does not retransmit on error or loss of
     * arbitration.
     *
     * @param {boolean} enable - True to enable, false to disable.
     * @returns {Promise<void>}
     */
    async setOneShot(enable) {
        await this._setOneShot(enable);
    }
};

/**
 * MCP2515 full driver — adds mode/filter/error-counter methods.
 *
 * Inherits send/recv/init from `MCP2515Minimal`; re-exposes the
 * Full-only methods from `_MCP2515Base` via `_MCP2515FullMixin`.
 */
class MCP2515Full extends _MCP2515FullMixin(MCP2515Minimal) {}

module.exports = { CanFrame, MCP2515Minimal, MCP2515Full };
