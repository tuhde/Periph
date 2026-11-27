'use strict';

/*
 * PN532 NFC/RFID controller (NXP), I²C frame protocol.
 *
 * The host exchanges frames with the PN532 firmware: a command frame
 * `00 00 FF LEN LCS D4 code data DCS 00`, the chip's ACK frame `00 00 FF 00 FF 00`
 * and a response frame (TFI 0xD5, code + 1). Every I²C read starts with a status
 * byte (bit 0 = frame ready); a 1-byte read does not consume the frame. The driver
 * reads the 6-byte response header, sends a NACK so the chip resends the response,
 * and then reads exactly 1 + LEN + 7 bytes.
 */

const _ACK = Buffer.from([0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00]);
const _NACK = Buffer.from([0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00]);

const _TFI_HOST = 0xD4;
const _TFI_CHIP = 0xD5;
const _TFI_ERROR = 0x7F;

const _CMD_DIAGNOSE = 0x00;
const _CMD_GET_FIRMWARE_VERSION = 0x02;
const _CMD_GET_GENERAL_STATUS = 0x04;
const _CMD_READ_REGISTER = 0x06;
const _CMD_WRITE_REGISTER = 0x08;
const _CMD_SAM_CONFIGURATION = 0x14;
const _CMD_POWER_DOWN = 0x16;
const _CMD_RF_CONFIGURATION = 0x32;
const _CMD_IN_DATA_EXCHANGE = 0x40;
const _CMD_IN_COMMUNICATE_THRU = 0x42;
const _CMD_IN_DESELECT = 0x44;
const _CMD_IN_LIST_PASSIVE_TARGET = 0x4A;
const _CMD_IN_RELEASE = 0x52;
const _CMD_IN_SELECT = 0x54;

const _IC = 0x32;
const _WRITE_RETRIES = 10;
const _ACK_TIMEOUT = 0.015;
const _ACK_ATTEMPTS = 3;
const _PATTERN = Buffer.from('PERIPH01', 'ascii');

const _sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * PN532 protocol or status error.
 * @property {number|null} code - PN532 status error code (`Status & 0x3F`), or null for
 *   framing / timeout errors.
 */
class PN532Error extends Error {
    constructor(message, code = null) {
        super(message);
        this.name = 'PN532Error';
        this.code = code;
    }
}

class _Timeout extends PN532Error {}

/**
 * Build a host command frame.
 * @param {number} command - Command code.
 * @param {Buffer|number[]} data - Parameters.
 * @returns {Buffer} The frame.
 */
function _frame(command, data) {
    const body = Buffer.concat([Buffer.from([_TFI_HOST, command]), Buffer.from(data)]);
    let sum = 0;
    for (const b of body) sum = (sum + b) & 0xFF;
    return Buffer.concat([
        Buffer.from([0x00, 0x00, 0xFF, body.length, (0x100 - body.length) & 0xFF]),
        body,
        Buffer.from([(0x100 - sum) & 0xFF, 0x00]),
    ]);
}

/**
 * PN532 NFC/RFID reader — minimal interface. Detects one ISO/IEC 14443 A card or tag
 * at 106 kbps and returns its UID.
 *
 * `init()` checks GetFirmwareVersion (IC 0x32), sets SAMConfiguration normal mode with the
 * IRQ pin enabled and MxRtyPassiveActivation = 2 so a read without a card returns. Writes are
 * retried up to 10 times (2 ms apart) to wake the chip; the ACK is awaited 15 ms and the frame
 * resent up to 3 times. The chip must be wired for I²C (I0 low, I1 high); address 0x24.
 *
 * @param {object} connection - Plain I²C connection bound to address 0x24.
 */
class PN532Minimal {
    constructor(connection) {
        this._conn = connection;
    }

    /**
     * Wake the chip, check the firmware and configure SAM and retries.
     * @returns {Promise<void>}
     * @throws {PN532Error} If the chip does not answer or IC is not 0x32.
     */
    async init() {
        const d = await this._command(_CMD_GET_FIRMWARE_VERSION);
        if (d.length < 4 || d[0] !== _IC) {
            throw new PN532Error(`Unexpected PN532 IC 0x${(d[0] || 0).toString(16)}, expected 0x32`);
        }
        await this._command(_CMD_SAM_CONFIGURATION, [0x01, 0x14, 0x01]);
        await this._command(_CMD_RF_CONFIGURATION, [0x05, 0xFF, 0x01, 0x02]);
    }

    /**
     * Detect one ISO/IEC 14443 A card and return its UID; the target is released.
     * @param {number} [timeout=1.0] - Response timeout in s; a timeout aborts the command.
     * @returns {Promise<Buffer|null>} NFCID1 (4, 7 or 10 bytes), or null when no card answered.
     */
    async readUid(timeout = 1.0) {
        const d = await this._list(1, 0x00, [], timeout);
        if (d === null || d.length < 6 || d[0] === 0) return null;
        const uid = Buffer.from(d.subarray(6, 6 + d[5]));
        await this._command(_CMD_IN_RELEASE, [d[1]]);
        return uid;
    }

    async _list(maxTargets, baud, initiatorData, timeout) {
        await this._start(_CMD_IN_LIST_PASSIVE_TARGET, Buffer.concat([Buffer.from([maxTargets, baud]), Buffer.from(initiatorData)]));
        try {
            return await this._response(_CMD_IN_LIST_PASSIVE_TARGET, timeout);
        } catch (e) {
            if (e instanceof _Timeout) return null;
            throw e;
        }
    }

    async _write(data) {
        for (let attempt = 0; ; attempt++) {
            try {
                await this._conn.write(data);
                return;
            } catch (e) {
                if (attempt >= _WRITE_RETRIES - 1) throw e;
                await _sleep(2);
            }
        }
    }

    async _ready() {
        const s = await this._conn.read(1);
        return (s[0] & 0x01) !== 0;
    }

    async _wait(timeout) {
        const polls = Math.floor(timeout * 1000) + 1;
        for (let i = 0; i < polls; i++) {
            if (await this._ready()) return true;
            await _sleep(1);
        }
        return false;
    }

    async _start(command, data = []) {
        const frame = _frame(command, data);
        for (let attempt = 0; attempt < _ACK_ATTEMPTS; attempt++) {
            await this._write(frame);
            if (await this._wait(_ACK_TIMEOUT)) {
                const ack = await this._conn.read(7);
                if (ack.subarray(1, 7).equals(_ACK)) return;
            }
        }
        throw new PN532Error(`PN532 did not ACK command 0x${command.toString(16)}`);
    }

    async _response(command, timeout) {
        if (!(await this._wait(timeout))) {
            await this._write(_ACK); // abort the running command
            throw new _Timeout(`PN532 response timeout (command 0x${command.toString(16)})`);
        }
        const header = await this._conn.read(6);
        if (header[1] !== 0x00 || header[2] !== 0x00 || header[3] !== 0xFF) throw new PN532Error('PN532 bad frame preamble');
        const length = header[4];
        if (((length + header[5]) & 0xFF) !== 0) throw new PN532Error('PN532 bad length checksum');
        await this._write(_NACK); // have the chip resend the whole frame
        if (!(await this._wait(timeout))) throw new _Timeout(`PN532 response timeout (command 0x${command.toString(16)})`);
        const frame = await this._conn.read(1 + length + 7);
        const body = frame.subarray(6, 6 + length);
        if (length === 1 && body[0] === _TFI_ERROR) throw new PN532Error(`PN532 error frame (command 0x${command.toString(16)})`);
        if (length < 2 || body[0] !== _TFI_CHIP || body[1] !== command + 1) {
            throw new PN532Error(`PN532 unexpected response to command 0x${command.toString(16)}`);
        }
        let sum = frame[6 + length];
        for (const b of body) sum = (sum + b) & 0xFF;
        if (sum !== 0) throw new PN532Error('PN532 bad data checksum');
        return Buffer.from(body.subarray(2));
    }

    async _command(command, data = [], timeout = 1.0) {
        await this._start(command, data);
        return this._response(command, timeout);
    }
}

/**
 * PN532 NFC/RFID controller — full interface. Adds target listing at every baud rate, data
 * exchange, MIFARE Classic and NTAG helpers, RF and retry configuration, register access,
 * power-down, the self test, raw commands and the IRQ interrupt API.
 *
 * @param {object} connection - Plain I²C connection bound to address 0x24.
 */
class PN532Full extends PN532Minimal {
    constructor(connection) {
        super(connection);
        this._callback = null;
        this._intPinUsed = null;
        this._pollTimer = null;
        this._polling = false;
        this._wasReady = false;
        this._edgeHandler = () => { if (this._callback) this._callback(); };
    }

    /**
     * GetFirmwareVersion.
     * @returns {Promise<{ic: number, version: number, revision: number, support: number,
     *   iso14443a: boolean, iso14443b: boolean, iso18092: boolean}>}
     */
    async firmwareVersion() {
        const d = await this._command(_CMD_GET_FIRMWARE_VERSION);
        return {
            ic: d[0], version: d[1], revision: d[2], support: d[3],
            iso14443a: (d[3] & 0x01) !== 0,
            iso14443b: (d[3] & 0x02) !== 0,
            iso18092: (d[3] & 0x04) !== 0,
        };
    }

    /**
     * GetGeneralStatus (per-target entries are skipped).
     * @returns {Promise<{error: number, field: boolean, targets: number, samStatus: number}>}
     */
    async generalStatus() {
        const d = await this._command(_CMD_GET_GENERAL_STATUS);
        const n = d[2];
        const samIndex = 3 + 4 * n;
        return { error: d[0], field: d[1] !== 0, targets: n, samStatus: samIndex < d.length ? d[samIndex] : 0 };
    }

    /**
     * InListPassiveTarget.
     * @param {object} [options]
     * @param {number} [options.baud=PN532Full.BAUD_106_A] - BAUD_106_A, BAUD_212_F, BAUD_424_F or BAUD_106_B.
     * @param {number} [options.maxTargets=1] - 1 or 2.
     * @param {Buffer|number[]} [options.initiatorData=[]] - Optional InitiatorData.
     * @param {number} [options.timeout=1.0] - Response timeout in s; a timeout aborts the command.
     * @returns {Promise<Array<{tg: number, sensRes: number, selRes: number, uid: Buffer, ats: Buffer}>>}
     *   Targets found; empty when none answered.
     */
    async listPassiveTargets({ baud = 0x00, maxTargets = 1, initiatorData = [], timeout = 1.0 } = {}) {
        if (maxTargets !== 1 && maxTargets !== 2) throw new RangeError('maxTargets must be 1 or 2');
        if (baud < 0 || baud > 4) throw new RangeError('baud must be 0..4');
        const d = await this._list(maxTargets, baud, initiatorData, timeout);
        if (d === null || d.length === 0) return [];
        const targets = [];
        let i = 1;
        for (let k = 0; k < d[0] && i < d.length; k++) {
            const tg = d[i];
            if (baud === PN532Full.BAUD_106_A) {
                const sensRes = (d[i + 1] << 8) | d[i + 2];
                const selRes = d[i + 3];
                const n = d[i + 4];
                const uid = Buffer.from(d.subarray(i + 5, i + 5 + n));
                i += 5 + n;
                let ats = Buffer.alloc(0);
                if ((selRes & 0x20) && i < d.length) {
                    const tl = d[i];
                    ats = Buffer.from(d.subarray(i, i + tl));
                    i += tl;
                }
                targets.push({ tg, sensRes, selRes, uid, ats });
            } else if (baud === PN532Full.BAUD_212_F || baud === PN532Full.BAUD_424_F) {
                const len = d[i + 1];
                const pol = d.subarray(i + 2, i + 1 + len);
                targets.push({ tg, sensRes: 0, selRes: 0, uid: Buffer.from(pol.subarray(1, 9)), ats: Buffer.from(pol.subarray(9)) });
                i += 1 + len;
            } else {
                const atqb = d.subarray(i + 1, i + 13);
                const len = d[i + 13];
                const attrib = Buffer.from(d.subarray(i + 14, i + 14 + len));
                targets.push({ tg, sensRes: 0, selRes: 0, uid: Buffer.from(atqb.subarray(1, 5)), ats: attrib });
                i += 14 + len;
            }
        }
        return targets;
    }

    async _statusCommand(command, data) {
        const d = await this._command(command, data);
        if (d.length === 0) throw new PN532Error('PN532 empty status response');
        const code = d[0] & 0x3F;
        if (code) throw new PN532Error(`PN532 status error 0x${code.toString(16)} (command 0x${command.toString(16)})`, code);
        return Buffer.from(d.subarray(1));
    }

    /**
     * InDataExchange with an activated target.
     * @param {number} target - Logical target number.
     * @param {Buffer|number[]} data - Data to send.
     * @returns {Promise<Buffer>} Data received.
     * @throws {PN532Error} On a non-zero status (`code` holds it).
     */
    async dataExchange(target, data) {
        return this._statusCommand(_CMD_IN_DATA_EXCHANGE, Buffer.concat([Buffer.from([target]), Buffer.from(data)]));
    }

    /**
     * InCommunicateThru: raw RF exchange with the current target.
     * @param {Buffer|number[]} data - Raw bytes to transmit.
     * @returns {Promise<Buffer>} Raw bytes received.
     */
    async communicateThru(data) {
        return this._statusCommand(_CMD_IN_COMMUNICATE_THRU, data);
    }

    /** InRelease. @param {number} [target=0] - 0 = all. @returns {Promise<void>} */
    async release(target = 0) { await this._statusCommand(_CMD_IN_RELEASE, [target]); }

    /** InSelect a listed target. @param {number} [target=0] @returns {Promise<void>} */
    async select(target = 0) { await this._statusCommand(_CMD_IN_SELECT, [target]); }

    /** InDeselect (keeps the target's information). @param {number} [target=0] - 0 = all. @returns {Promise<void>} */
    async deselect(target = 0) { await this._statusCommand(_CMD_IN_DESELECT, [target]); }

    /**
     * Authenticate a MIFARE Classic sector (0x60 key A / 0x61 key B).
     * @param {number} target - Logical target number.
     * @param {number} block - Any block of the sector.
     * @param {Buffer|number[]} key - 6-byte key.
     * @param {Buffer|number[]} uid - Card UID; its last 4 bytes are used.
     * @param {boolean} [keyB=false] - Use key B.
     * @returns {Promise<void>}
     */
    async mifareAuthenticate(target, block, key, uid, keyB = false) {
        const k = Buffer.from(key);
        const u = Buffer.from(uid);
        if (k.length !== 6) throw new RangeError('key must be 6 bytes');
        if (u.length < 4) throw new RangeError('uid must have at least 4 bytes');
        await this.dataExchange(target, Buffer.concat([Buffer.from([keyB ? 0x61 : 0x60, block & 0xFF]), k, u.subarray(u.length - 4)]));
    }

    /** Read a 16-byte MIFARE Classic block (0x30). @param {number} target @param {number} block @returns {Promise<Buffer>} 16 bytes. */
    async mifareReadBlock(target, block) {
        return (await this.dataExchange(target, [0x30, block & 0xFF])).subarray(0, 16);
    }

    /**
     * Write a 16-byte MIFARE Classic block (0xA0).
     * @param {number} target @param {number} block @param {Buffer|number[]} data - 16 bytes.
     * @returns {Promise<void>}
     */
    async mifareWriteBlock(target, block, data) {
        const b = Buffer.from(data);
        if (b.length !== 16) throw new RangeError('data must be 16 bytes');
        await this.dataExchange(target, Buffer.concat([Buffer.from([0xA0, block & 0xFF]), b]));
    }

    /** Read four NTAG / Ultralight pages from `page` (0x30). @param {number} target @param {number} page @returns {Promise<Buffer>} 16 bytes. */
    async ntagReadPage(target, page) {
        return (await this.dataExchange(target, [0x30, page & 0xFF])).subarray(0, 16);
    }

    /**
     * Write one 4-byte NTAG / Ultralight page (0xA2).
     * @param {number} target @param {number} page @param {Buffer|number[]} data - 4 bytes.
     * @returns {Promise<void>}
     */
    async ntagWritePage(target, page, data) {
        const b = Buffer.from(data);
        if (b.length !== 4) throw new RangeError('data must be 4 bytes');
        await this.dataExchange(target, Buffer.concat([Buffer.from([0xA2, page & 0xFF]), b]));
    }

    /** RFConfiguration item 0x01. @param {boolean} on @param {boolean} [autoRfca=false] @returns {Promise<void>} */
    async setRfField(on, autoRfca = false) {
        await this._command(_CMD_RF_CONFIGURATION, [0x01, (on ? 0x02 : 0x00) | (autoRfca ? 0x01 : 0x00)]);
    }

    /**
     * RFConfiguration item 0x05: retry counts, 0xFF = infinite.
     * @param {object} [options]
     * @param {number} [options.passiveActivation=2] - MxRtyPassiveActivation.
     * @param {number} [options.atr=0xFF] - MxRtyATR.
     * @param {number} [options.psl=1] - MxRtyPSL.
     * @returns {Promise<void>}
     */
    async setMaxRetries({ passiveActivation = 2, atr = 0xFF, psl = 1 } = {}) {
        await this._command(_CMD_RF_CONFIGURATION, [0x05, atr & 0xFF, psl & 0xFF, passiveActivation & 0xFF]);
    }

    /** ReadRegister (16-bit address). @param {number} address @returns {Promise<number>} */
    async readRegister(address) {
        const d = await this._command(_CMD_READ_REGISTER, [(address >> 8) & 0xFF, address & 0xFF]);
        return d[0];
    }

    /** WriteRegister (16-bit address). @param {number} address @param {number} value @returns {Promise<void>} */
    async writeRegister(address, value) {
        await this._command(_CMD_WRITE_REGISTER, [(address >> 8) & 0xFF, address & 0xFF, value & 0xFF]);
    }

    /** PowerDown. @param {number} [wakeupSources=0x20] - WakeUpEnable mask (0x20 = I²C). @returns {Promise<void>} */
    async powerDown(wakeupSources = 0x20) {
        await this._statusCommand(_CMD_POWER_DOWN, [wakeupSources & 0xFF]);
    }

    /**
     * Diagnose self test.
     * @param {number} [test=0x00] - 0x00 communication line (echo), 0x01 ROM, 0x02 RAM, ...
     * @returns {Promise<boolean>} True when the test passed.
     */
    async selfTest(test = 0x00) {
        if (test === 0x00) {
            const req = Buffer.concat([Buffer.from([0x00]), _PATTERN]);
            return (await this._command(_CMD_DIAGNOSE, req)).equals(req);
        }
        const d = await this._command(_CMD_DIAGNOSE, [test & 0xFF]);
        return d.length > 0 && d[0] === 0x00;
    }

    /**
     * Send a raw command and read its response.
     * @param {number} command - Command code.
     * @param {Buffer|number[]} [data=[]] - Parameters.
     * @param {number} [timeout=1.0] - Response timeout in s.
     * @returns {Promise<Buffer>} Response data after the response code.
     */
    async sendCommand(command, data = [], timeout = 1.0) {
        return this._command(command, data, timeout);
    }

    /** Write a command frame and read its ACK, without waiting for the response. @param {number} command @param {Buffer|number[]} [data=[]] @returns {Promise<void>} */
    async startCommand(command, data = []) {
        await this._start(command, data);
    }

    /**
     * Read the response to a command started with `startCommand`.
     * @param {number} command - The started command code.
     * @param {number} [timeout=1.0] - Response timeout in s; a timeout aborts the command.
     * @returns {Promise<Buffer>} Response data after the response code.
     */
    async readResponse(command, timeout = 1.0) {
        return this._response(command, timeout);
    }

    /** Poll the status byte every 1 ms until a frame is ready. @param {number} timeout - s. @returns {Promise<boolean>} */
    async waitReady(timeout) {
        return this._wait(timeout);
    }

    /** True when the status byte reports a ready ACK or response frame. @returns {Promise<boolean>} */
    async pollInterrupt() {
        return this._ready();
    }

    /**
     * Call `callback()` when the chip has a frame ready. Uses `intPin` (or the connection's
     * `intPin`) on its falling edge, otherwise a 10 ms status-byte poll that fires on each
     * not-ready → ready transition. Read the frame with `readResponse`.
     * @param {function(): void} callback - Called with no arguments.
     * @param {object|null} [intPin=null] - Optional InputPin wired to IRQ.
     * @returns {Promise<void>}
     */
    async onInterrupt(callback, intPin = null) {
        await this.offInterrupt();
        this._callback = callback;
        const ipin = intPin ?? this._conn.intPin ?? null;
        if (ipin) {
            this._intPinUsed = ipin;
            await ipin.onEdge(this._edgeHandler, 'falling');
        } else {
            this._wasReady = false;
            this._pollTimer = setInterval(() => { this._service(); }, 10);
            if (this._pollTimer.unref) this._pollTimer.unref();
        }
    }

    /** Unsubscribe. @returns {Promise<void>} */
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
            const now = await this._ready();
            if (now && !this._wasReady && this._callback) this._callback();
            this._wasReady = now;
        } catch (e) {
            /* a failed poll is retried on the next tick */
        } finally {
            this._polling = false;
        }
    }
}

/** 106 kbps ISO/IEC 14443 A. */
PN532Full.BAUD_106_A = 0x00;
/** 212 kbps FeliCa. */
PN532Full.BAUD_212_F = 0x01;
/** 424 kbps FeliCa. */
PN532Full.BAUD_424_F = 0x02;
/** 106 kbps ISO/IEC 14443 B. */
PN532Full.BAUD_106_B = 0x03;

module.exports = { PN532Minimal, PN532Full, PN532Error, _frame };
