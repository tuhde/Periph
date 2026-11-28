'use strict';

/**
 * ST25DV04K / ST25DV16K / ST25DV64K dynamic NFC/RFID tag driver.
 *
 * The chip answers on two I²C addresses, both with 16-bit register addresses:
 * 0x53 (user memory, dynamic registers, fast-transfer-mode mailbox) and 0x57
 * (system configuration: static registers, UID, IC_REF, MEM_SIZE, I²C password).
 * The driver therefore takes two register connections on the same bus, both
 * created with a 2-byte register address width.
 *
 * EEPROM writes are self-timed (5 ms per 4-byte internal page); the chip does
 * not acknowledge its address during the cycle, so every write is completed by
 * ACK polling. While an RF reader accesses the EEPROM the chip NACKs I²C, so
 * reads are retried a few times before rejecting.
 * @module st25dv
 */

const _SYS_MEM_SIZE = 0x0014;
const _CHUNK = 16;
const _WRITE_TIMEOUT_MS = 50;
const _READ_RETRIES = 3;

// System area (E2 = 1)
const _SYS_GPO = 0x0000;
const _SYS_IT_TIME = 0x0001;
const _SYS_EH_MODE = 0x0002;
const _SYS_RFA1SS = 0x0004;
const _SYS_ENDA1 = 0x0005;
const _SYS_I2CSS = 0x000B;
const _SYS_LOCK_CCFILE = 0x000C;
const _SYS_MB_MODE = 0x000D;
const _SYS_MB_WDG = 0x000E;
const _SYS_UID = 0x0018;
const _SYS_IC_REV = 0x0020;
const _SYS_I2C_PWD = 0x0900;

// Dynamic registers (E2 = 0)
const _DYN_GPO_CTRL = 0x2000;
const _DYN_EH_CTRL = 0x2002;
const _DYN_RF_MNGT = 0x2003;
const _DYN_I2C_SSO = 0x2004;
const _DYN_IT_STS = 0x2005;
const _DYN_MB_CTRL = 0x2006;
const _DYN_MB_LEN = 0x2007;
const _MAILBOX = 0x2008;
const _MAILBOX_SIZE = 256;

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Interrupt sources: bit layout of IT_STS_Dyn (0x2005). */
const ST25DVSource = Object.freeze({
    RF_USER: 0x01,
    RF_ACTIVITY: 0x02,
    RF_INTERRUPT: 0x04,
    FIELD_FALLING: 0x08,
    FIELD_RISING: 0x10,
    RF_PUT_MSG: 0x20,
    RF_GET_MSG: 0x40,
    RF_WRITE: 0x80,
});

/**
 * ST25DV dynamic NFC tag — minimal interface: user memory as a byte store.
 *
 * Call `init()` once: it reads MEM_SIZE, BLK_SIZE and IC_REF from the system
 * area and derives the user memory size. No configuration is written; the chip
 * keeps its delivery state (area 1 = the whole memory, no protection,
 * GPO_EN = 1, field-change interrupt only). Reads and writes are split into
 * transfers of at most 16 bytes aligned to 16-byte boundaries, so a transfer
 * never crosses a 256-byte page or an area border; every write is ACK-polled.
 */
class ST25DVMinimal {
    /**
     * @param {object} user - RegisterConnection at 0x53 (2-byte register addresses).
     * @param {object} system - RegisterConnection at 0x57 (2-byte register addresses).
     * @param {boolean} [gpoActiveHigh=false] - true for -JF variants (CMOS GPO, rising edge),
     *     false for -IE variants (open drain, falling edge).
     */
    constructor(user, system, gpoActiveHigh = false) {
        this._user = user;
        this._system = system;
        this._gpoActiveHigh = gpoActiveHigh;
        this._size = 0;
        this._icRef = 0;
    }

    /**
     * Read MEM_SIZE, BLK_SIZE and IC_REF and derive the memory size.
     * @returns {Promise<void>}
     */
    async init() {
        const id = await this._read(this._system, _SYS_MEM_SIZE, 4);
        const mem = id[0] | (id[1] << 8);
        this._size = (mem + 1) * (id[2] + 1);
        this._icRef = id[3];
    }

    async _read(conn, reg, length) {
        for (let attempt = 1; ; attempt++) {
            try {
                return Buffer.from(await conn.readReg(reg, length));
            } catch (e) {
                if (attempt >= _READ_RETRIES) throw e;
                await _sleep(5);
            }
        }
    }

    async _ackPoll(conn, timeoutMs) {
        const deadline = Date.now() + timeoutMs;
        for (;;) {
            try {
                await conn.writeReg(0, Buffer.alloc(0));
                return true;
            } catch (e) {
                // NACK while the write cycle is running
            }
            if (Date.now() >= deadline) return false;
            await _sleep(1);
        }
    }

    async _commit(conn) {
        if (!(await this._ackPoll(conn, _WRITE_TIMEOUT_MS))) {
            throw new Error('ST25DV write cycle did not complete');
        }
    }

    _checkRange(address, length) {
        if (!Number.isInteger(address) || !Number.isInteger(length) ||
            address < 0 || length < 0 || address + length > this._size) {
            throw new RangeError(`range outside the user memory 0-${this._size - 1}`);
        }
    }

    /**
     * Read user memory.
     * @param {number} address - Start byte address.
     * @param {number} length - Number of bytes.
     * @returns {Promise<Buffer>} `length` bytes.
     * @throws {RangeError} If the range lies outside the user memory.
     */
    async read(address, length) {
        this._checkRange(address, length);
        const parts = [];
        while (length > 0) {
            const n = Math.min(_CHUNK - (address % _CHUNK), length);
            parts.push(await this._read(this._user, address, n));
            address += n;
            length -= n;
        }
        return Buffer.concat(parts);
    }

    /**
     * Write user memory in 16-byte-aligned pieces, ACK-polling after each.
     * @param {number} address - Start byte address.
     * @param {Buffer|number[]} data - Bytes to write.
     * @returns {Promise<void>}
     * @throws {RangeError} If the range lies outside the user memory.
     * @throws {Error} If a write is NACKed or a write cycle does not complete.
     */
    async write(address, data) {
        const buf = Buffer.from(data);
        this._checkRange(address, buf.length);
        let offset = 0;
        while (offset < buf.length) {
            const a = address + offset;
            const n = Math.min(_CHUNK - (a % _CHUNK), buf.length - offset);
            await this._user.writeReg(a, buf.subarray(offset, offset + n));
            await this._commit(this._user);
            offset += n;
        }
    }
}

/**
 * ST25DV full interface — identification, system area, password session,
 * memory areas, GPO interrupts, energy harvesting, RF state, mailbox and a
 * minimal NDEF writer.
 *
 * System-area writes need an open I²C security session (`presentPassword`).
 * Bits 0-6 of GPO_CTRL_Dyn are read-only over I²C, so interrupt sources are
 * configured in the static GPO register (session required); only GPO_EN can be
 * switched at runtime without a session.
 */
class ST25DVFull extends ST25DVMinimal {
    /**
     * @param {object} user - RegisterConnection at 0x53.
     * @param {object} system - RegisterConnection at 0x57.
     * @param {boolean} [gpoActiveHigh=false] - true for -JF variants.
     */
    constructor(user, system, gpoActiveHigh = false) {
        super(user, system, gpoActiveHigh);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._edgeHandler = () => { this._service(); };
    }

    /** @returns {number} User memory size in bytes ((MEM_SIZE + 1) × (BLK_SIZE + 1)). */
    memorySize() { return this._size; }

    /** @returns {number} IC_REF: 0x24 (ST25DV04K) or 0x26 (ST25DV16K/64K). */
    icReference() { return this._icRef; }

    /**
     * Read the 64-bit unique ID.
     * @returns {Promise<Buffer>} 8 bytes, most significant first (E0 02 …).
     */
    async uid() {
        const raw = await this._read(this._system, _SYS_UID, 8);
        return Buffer.from(raw).reverse();
    }

    /** @returns {Promise<number>} IC_REV. */
    async icRevision() {
        return (await this._read(this._system, _SYS_IC_REV, 1))[0];
    }

    /**
     * Raw read of the system configuration area (device 0x57).
     * @param {number} address - System register address.
     * @param {number} length - Number of bytes.
     * @returns {Promise<Buffer>}
     */
    async readSystem(address, length) {
        return this._read(this._system, address, length);
    }

    /**
     * Raw write to the system area, then ACK poll (session required).
     * @param {number} address - System register address.
     * @param {Buffer|number[]|number} data - Bytes, or one byte value.
     * @returns {Promise<void>}
     */
    async writeSystem(address, data) {
        const buf = typeof data === 'number' ? Buffer.from([data & 0xFF]) : Buffer.from(data);
        await this._system.writeReg(address, buf);
        await this._commit(this._system);
    }

    async _sysByte(address) { return (await this._read(this._system, address, 1))[0]; }

    async _dynByte(address) { return (await this._read(this._user, address, 1))[0]; }

    async _writeDyn(address, value) { await this._user.writeReg(address, Buffer.from([value & 0xFF])); }

    static _passwordFrame(password, code) {
        const pw = Buffer.from(password);
        if (pw.length !== 8) throw new RangeError('password must be 8 bytes');
        return Buffer.concat([pw, Buffer.from([code]), pw]);
    }

    /**
     * Present the I²C password (opens the security session).
     * @param {Buffer|number[]} password - 8 bytes, most significant first (factory 00 × 8).
     * @returns {Promise<boolean>} true if the session is open afterwards.
     */
    async presentPassword(password) {
        await this._system.writeReg(_SYS_I2C_PWD, ST25DVFull._passwordFrame(password, 0x09));
        await this._ackPoll(this._system, _WRITE_TIMEOUT_MS);
        return this.sessionOpen();
    }

    /**
     * Change the I²C password (session must be open).
     * @param {Buffer|number[]} password - New 8-byte password, most significant first.
     * @returns {Promise<void>}
     * @throws {Error} If no security session is open or the write fails.
     */
    async writePassword(password) {
        const frame = ST25DVFull._passwordFrame(password, 0x07);
        if (!(await this.sessionOpen())) throw new Error('ST25DV security session is closed');
        await this._system.writeReg(_SYS_I2C_PWD, frame);
        await this._commit(this._system);
    }

    /** @returns {Promise<boolean>} I2C_SSO_Dyn bit 0: the I²C security session is open. */
    async sessionOpen() {
        return ((await this._dynByte(_DYN_I2C_SSO)) & 0x01) !== 0;
    }

    /**
     * Set ENDA1..3 (session required); area n ends at byte endBlock × 32 + 31.
     * @param {number} area - 1-3.
     * @param {number} endBlock - ENDAn value 0-255.
     * @returns {Promise<void>}
     */
    async setAreaEnd(area, endBlock) {
        if (!(area >= 1 && area <= 3)) throw new RangeError('area must be 1-3');
        if (!(endBlock >= 0 && endBlock <= 0xFF)) throw new RangeError('endBlock must be 0-255');
        await this.writeSystem(_SYS_ENDA1 + 2 * (area - 1), endBlock);
    }

    /**
     * Set the RF and I²C protection of an area (session required).
     * @param {number} area - 1-4.
     * @param {number} rf - RFAnSS 0-15 (bits 3:2 RW protection, bits 1:0 RF password).
     * @param {number} i2c - I2CSS level 0-3 (bit 0 write, bit 1 read protection).
     * @returns {Promise<void>}
     */
    async setAreaProtection(area, rf, i2c) {
        if (!(area >= 1 && area <= 4)) throw new RangeError('area must be 1-4');
        if (!(rf >= 0 && rf <= 0x0F && i2c >= 0 && i2c <= 3)) throw new RangeError('rf must be 0-15 and i2c 0-3');
        await this.writeSystem(_SYS_RFA1SS + 2 * (area - 1), rf);
        const shift = 2 * (area - 1);
        let i2css = await this._sysByte(_SYS_I2CSS);
        i2css = (i2css & ~(0x03 << shift) & 0xFF) | (i2c << shift);
        await this.writeSystem(_SYS_I2CSS, i2css);
    }

    /**
     * Read the protection of an area.
     * @param {number} area - 1-4.
     * @returns {Promise<{rf: number, i2c: number}>}
     */
    async getAreaProtection(area) {
        if (!(area >= 1 && area <= 4)) throw new RangeError('area must be 1-4');
        const rf = (await this._sysByte(_SYS_RFA1SS + 2 * (area - 1))) & 0x0F;
        const i2c = ((await this._sysByte(_SYS_I2CSS)) >> (2 * (area - 1))) & 0x03;
        return { rf, i2c };
    }

    /**
     * Write the static GPO register (session required); the chip copies it into GPO_CTRL_Dyn.
     * @param {number} sources - Mask of GPO_* enable bits 0-6.
     * @param {boolean} [enable=true] - Sets GPO_EN.
     * @returns {Promise<void>}
     */
    async setGpo(sources, enable = true) {
        await this.writeSystem(_SYS_GPO, (sources & 0x7F) | (enable ? ST25DVFull.GPO_EN : 0));
    }

    /** @returns {Promise<number>} Static GPO register (GPO_* mask). */
    async getGpo() {
        return this._sysByte(_SYS_GPO);
    }

    /**
     * Switch the GPO output at runtime (GPO_CTRL_Dyn.GPO_EN, no session; bits 0-6 are read-only).
     * @param {boolean} enable - true enables the output.
     * @returns {Promise<void>}
     */
    async setGpoRuntime(enable) {
        await this._writeDyn(_DYN_GPO_CTRL, enable ? ST25DVFull.GPO_EN : 0);
    }

    /**
     * Set IT_TIME (session required); pulse = 301 µs − value × 37.65 µs.
     * @param {number} value - 0-7.
     * @returns {Promise<void>}
     */
    async setInterruptPulse(value) {
        if (!(value >= 0 && value <= 7)) throw new RangeError('value must be 0-7');
        await this.writeSystem(_SYS_IT_TIME, value);
    }

    /** @returns {Promise<boolean>} EH_CTRL_Dyn.FIELD_ON: an RF field is present. */
    async fieldPresent() {
        return ((await this._dynByte(_DYN_EH_CTRL)) & 0x04) !== 0;
    }

    /**
     * Set EH_CTRL_Dyn.EH_EN; write EH_MODE (session required) only when `forced` changes it.
     * @param {boolean} enabled - Energy harvesting on now.
     * @param {boolean} [forced=false] - Forced after power-up instead of on demand.
     * @returns {Promise<void>}
     */
    async setEnergyHarvesting(enabled, forced = false) {
        const mode = forced ? 0 : 1;
        if (((await this._sysByte(_SYS_EH_MODE)) & 0x01) !== mode) {
            await this.writeSystem(_SYS_EH_MODE, mode);
        }
        await this._writeDyn(_DYN_EH_CTRL, enabled ? 0x01 : 0x00);
    }

    /**
     * Set the runtime RF state RF_MNGT_Dyn (the static RF_MNGT is unchanged).
     * @param {boolean} [disable=false] - RF_DISABLE.
     * @param {boolean} [sleep=false] - RF_SLEEP.
     * @returns {Promise<void>}
     */
    async setRfState(disable = false, sleep = false) {
        await this._writeDyn(_DYN_RF_MNGT, (disable ? 0x01 : 0) | (sleep ? 0x02 : 0));
    }

    /**
     * Set MB_MODE if needed (session required), then MB_CTRL_Dyn.MB_EN.
     * @returns {Promise<void>}
     */
    async enableMailbox() {
        if (!((await this._sysByte(_SYS_MB_MODE)) & 0x01)) {
            await this.writeSystem(_SYS_MB_MODE, 0x01);
        }
        await this._writeDyn(_DYN_MB_CTRL, 0x01);
    }

    /** Clear MB_CTRL_Dyn.MB_EN (MB_MODE stays). @returns {Promise<void>} */
    async disableMailbox() {
        await this._writeDyn(_DYN_MB_CTRL, 0x00);
    }

    /**
     * Decode MB_CTRL_Dyn.
     * @returns {Promise<{enabled: boolean, hostPut: boolean, rfPut: boolean, hostMiss: boolean,
     *     rfMiss: boolean, hostCurrent: boolean, rfCurrent: boolean}>}
     */
    async mailboxStatus() {
        const v = await this._dynByte(_DYN_MB_CTRL);
        return {
            enabled: (v & 0x01) !== 0,
            hostPut: (v & 0x02) !== 0,
            rfPut: (v & 0x04) !== 0,
            hostMiss: (v & 0x10) !== 0,
            rfMiss: (v & 0x20) !== 0,
            hostCurrent: (v & 0x40) !== 0,
            rfCurrent: (v & 0x80) !== 0,
        };
    }

    /**
     * Put a message into the mailbox (one write starting at 0x2008).
     * @param {Buffer|number[]} data - 1-256 bytes.
     * @returns {Promise<void>}
     * @throws {RangeError} If the length is not 1-256.
     * @throws {Error} If the mailbox is disabled or still holds a message.
     */
    async mailboxWrite(data) {
        const buf = Buffer.from(data);
        if (buf.length < 1 || buf.length > _MAILBOX_SIZE) throw new RangeError('mailbox message must be 1-256 bytes');
        const ctrl = await this._dynByte(_DYN_MB_CTRL);
        if (!(ctrl & 0x01)) throw new Error('ST25DV mailbox is disabled');
        if (ctrl & 0x06) throw new Error('ST25DV mailbox is busy');
        await this._user.writeReg(_MAILBOX, buf);
    }

    /**
     * Read the mailbox message.
     * @returns {Promise<Buffer>} MB_LEN_Dyn + 1 bytes, or empty when no message is present.
     */
    async mailboxRead() {
        const ctrl = await this._dynByte(_DYN_MB_CTRL);
        if (!(ctrl & 0x06)) return Buffer.alloc(0);
        const length = (await this._dynByte(_DYN_MB_LEN)) + 1;
        const parts = [];
        for (let offset = 0; offset < length; offset += _CHUNK) {
            parts.push(await this._read(this._user, _MAILBOX + offset, Math.min(_CHUNK, length - offset)));
        }
        return Buffer.concat(parts);
    }

    /**
     * Set MB_WDG (session required); watchdog = 2^(value − 1) × 30 ms, 0 disables it.
     * @param {number} value - 0-7.
     * @returns {Promise<void>}
     */
    async setMailboxWatchdog(value) {
        if (!(value >= 0 && value <= 7)) throw new RangeError('value must be 0-7');
        await this.writeSystem(_SYS_MB_WDG, value);
    }

    /**
     * Write LOCK_CCFILE (session required): RF write protection of blocks 0 and 1.
     * @param {boolean} [lock=true] - true locks both blocks.
     * @returns {Promise<void>}
     */
    async lockCcfile(lock = true) {
        await this.writeSystem(_SYS_LOCK_CCFILE, lock ? 0x03 : 0x00);
    }

    _capabilityContainer() {
        if (this._size <= 2048) {
            return Buffer.from([0xE1, 0x40, Math.min(this._size >> 3, 0xFF), 0x01]);
        }
        const mlen = (this._size >> 3) - 1;
        return Buffer.from([0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, (mlen >> 8) & 0xFF, mlen & 0xFF]);
    }

    /**
     * Write a Type 5 capability container and one NDEF TLV (03 len … FE; 03 FF hi lo … for ≥ 255 bytes).
     * @param {Buffer|number[]} message - Encoded NDEF message.
     * @returns {Promise<void>}
     * @throws {RangeError} If the message does not fit.
     */
    async writeNdef(message) {
        const msg = Buffer.from(message);
        const tlv = msg.length < 0xFF
            ? Buffer.from([0x03, msg.length])
            : Buffer.from([0x03, 0xFF, (msg.length >> 8) & 0xFF, msg.length & 0xFF]);
        const image = Buffer.concat([this._capabilityContainer(), tlv, msg, Buffer.from([0xFE])]);
        if (image.length > this._size) throw new RangeError('NDEF message too large');
        await this.write(0, image);
    }

    /**
     * Read the message of the first NDEF TLV.
     * @returns {Promise<Buffer>} The message, or empty when no CC or NDEF TLV is found.
     */
    async readNdef() {
        const cc = await this.read(0, 4);
        let pos;
        if (cc[0] === 0xE1) pos = 4;
        else if (cc[0] === 0xE2) pos = 8;
        else return Buffer.alloc(0);
        while (pos < this._size) {
            const t = (await this.read(pos, 1))[0];
            if (t === 0x00) { pos++; continue; }
            if (t === 0xFE || pos + 2 > this._size) return Buffer.alloc(0);
            let length = (await this.read(pos + 1, 1))[0];
            pos += 2;
            if (length === 0xFF) {
                if (pos + 2 > this._size) return Buffer.alloc(0);
                const lb = await this.read(pos, 2);
                length = (lb[0] << 8) | lb[1];
                pos += 2;
            }
            if (t === 0x03) {
                if (pos + length > this._size) return Buffer.alloc(0);
                return this.read(pos, length);
            }
            pos += length;
        }
        return Buffer.alloc(0);
    }

    /**
     * ACK poll on 0x53 until the write cycle has finished.
     * @returns {Promise<void>}
     * @throws {Error} On timeout.
     */
    async waitWriteComplete() {
        await this._commit(this._user);
    }

    static _sourceToGpo(source) {
        let gpo = source & 0x07;
        if (source & (ST25DVSource.FIELD_RISING | ST25DVSource.FIELD_FALLING)) gpo |= 0x08;
        gpo |= (source >> 1) & 0x70;
        return gpo;
    }

    /**
     * Enable GPO sources in the static GPO register plus GPO_EN (session required).
     * FIELD_RISING and FIELD_FALLING share FIELD_CHANGE_EN.
     * @param {number} source - ST25DVSource mask.
     * @returns {Promise<void>}
     */
    async enableInterrupt(source) {
        const gpo = (await this.getGpo()) | ST25DVFull._sourceToGpo(source) | ST25DVFull.GPO_EN;
        await this.writeSystem(_SYS_GPO, gpo);
        await this.setGpoRuntime(true);
    }

    /**
     * Clear GPO sources in the static GPO register (session required).
     * @param {number} source - ST25DVSource mask.
     * @returns {Promise<void>}
     */
    async disableInterrupt(source) {
        const gpo = (await this.getGpo()) & ~ST25DVFull._sourceToGpo(source) & 0xFF;
        await this.writeSystem(_SYS_GPO, gpo);
    }

    /**
     * Read IT_STS_Dyn (cleared on read).
     * @returns {Promise<number>} ST25DVSource mask, 0 = none.
     */
    async pollInterrupt() {
        return (await this._dynByte(_DYN_IT_STS)) & 0xFF;
    }

    /**
     * Subscribe to GPO events: the falling (-IE) or rising (-JF) edge via `intPin` (or the user
     * connection's pin), otherwise a 10 ms polling timer on IT_STS_Dyn.
     * @param {function(number): void} callback - Called with the ST25DVSource mask.
     * @param {object|null} [intPin=null] - Optional InputPin wired to GPO.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin = null) {
        await this.offInterrupt();
        this._callback = callback;
        const ipin = intPin ?? this._user.intPin ?? null;
        this._intPinUsed = ipin;
        if (ipin) {
            await ipin.onEdge(this._edgeHandler, this._gpoActiveHigh ? 'rising' : 'falling');
        } else {
            this._pollTimer = setInterval(() => { this._service(); }, 10);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /** Unsubscribe and stop delivery. @returns {Promise<void>} */
    async offInterrupt() {
        if (this._intPinUsed) {
            await this._intPinUsed.offEdge(this._edgeHandler);
            this._intPinUsed = null;
        }
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        this._callback = null;
    }

    async _service() {
        if (this._polling) return;
        this._polling = true;
        try {
            const status = await this.pollInterrupt();
            if (status && this._callback) this._callback(status);
        } catch (e) {
            /* a failed poll is retried on the next edge / tick */
        } finally {
            this._polling = false;
        }
    }
}

ST25DVFull.GPO_RF_USER = 0x01;
ST25DVFull.GPO_RF_ACTIVITY = 0x02;
ST25DVFull.GPO_RF_INTERRUPT = 0x04;
ST25DVFull.GPO_FIELD_CHANGE = 0x08;
ST25DVFull.GPO_RF_PUT_MSG = 0x10;
ST25DVFull.GPO_RF_GET_MSG = 0x20;
ST25DVFull.GPO_RF_WRITE = 0x40;
ST25DVFull.GPO_EN = 0x80;

module.exports = { ST25DVMinimal, ST25DVFull, ST25DVSource };
