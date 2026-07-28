'use strict';

const _CMD_IDLE            = 0x00;
const _CMD_MEM             = 0x01;
const _CMD_RANDOM_ID       = 0x02;
const _CMD_CALC_CRC        = 0x03;
const _CMD_TRANSMIT        = 0x04;
const _CMD_NO_CMD_CHANGE   = 0x07;
const _CMD_RECEIVE         = 0x08;
const _CMD_TRANSCEIVE      = 0x0C;
const _CMD_MFAUTHENT       = 0x0E;
const _CMD_SOFT_RESET      = 0x0F;

const _REG_COMMAND         = 0x01;
const _REG_COM_IRQ         = 0x04;
const _REG_DIV_IRQ         = 0x05;
const _REG_ERROR           = 0x06;
const _REG_STATUS_2        = 0x08;
const _REG_FIFO_DATA       = 0x09;
const _REG_FIFO_LEVEL      = 0x0A;
const _REG_BIT_FRAMING     = 0x0D;
const _REG_TX_MODE         = 0x12;
const _REG_RX_MODE         = 0x13;
const _REG_TX_CONTROL      = 0x14;
const _REG_TX_ASK          = 0x15;
const _REG_MODE            = 0x11;
const _REG_CRC_RESULT_H    = 0x21;
const _REG_CRC_RESULT_L    = 0x22;
const _REG_RF_CFG          = 0x26;
const _REG_T_MODE          = 0x2A;
const _REG_T_PRESCALER     = 0x2B;
const _REG_T_RELOAD_H      = 0x2C;
const _REG_T_RELOAD_L      = 0x2D;
const _REG_AUTO_TEST       = 0x36;
const _REG_VERSION         = 0x37;

const _IRQ_RX              = 0x30;
const _IRQ_IDLE            = 0x10;
const _IRQ_TIMER           = 0x01;
const _IRQ_ALL             = 0x7F;

const _STATUS_2_CRYPTO1ON  = 0x08;
const _FIFO_FLUSH          = 0x80;

const _PICC_REQA           = 0x26;
const _PICC_WUPA           = 0x52;
const _PICC_HLTA           = 0x50;
const _PICC_CT             = 0x88;
const _PICC_SEL_BIT        = 0x70;
const _PICC_SAK_NOT_COMPLETE = 0x04;

function _delay(ms) {
    const start = Date.now();
    while (Date.now() - start < ms) { /* spin */ }
}

/**
 * MFRC522 13.56 MHz contactless reader/writer — minimal interface.
 *
 * Provides a 13.56 MHz RFID/NFC reader/writer frontend that detects an
 * ISO/IEC 14443 Type A card in the field and reads its UID. No
 * configuration beyond the connection is required.
 *
 * Supports three host connections — I²C, SPI, and UART — all of which
 * expose the same 64-register internal bank; the address-byte framing
 * differs per connection. The driver selects the correct framing from a
 * busType parameter.
 *
 * Default configuration (baked in at construction):
 *     - 25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)
 *     - Force100ASK modulation
 *     - ISO/IEC 14443-3 CRC_A preset (0x6363)
 *     - Antenna enabled
 *     - 106 kBd, 33 dB RX gain (reset default)
 *
 * Constructor caveat: JS constructors cannot be async. The SoftReset +
 * initialization sequence is fired off unawaited, matching the
 * fire-and-forget convention used throughout this port — in practice it
 * settles before any subsequent `await` in caller code is scheduled, since
 * the underlying I2C/SPI/UART connection is synchronous or near-instant
 * under the hood, but this is not a guaranteed blocking wait the way the
 * pre-async version was. Call `await reader.isCardPresent()` (or any other
 * async method) once before relying on the chip being initialized if this
 * matters for your use case.
 *
 * @param {import('../../connection/connection').Connection} connection - Configured I²C, SPI, or UART connection.
 * @param {string} [busType='spi'] - Bus type: 'spi', 'i2c', or 'uart'.
 */
class MFRC522Minimal {
    constructor(connection, busType = 'spi') {
        this._conn = connection;
        this._busType = busType;
        this._initChip();
    }

    _addrFor(reg, read) {
        if (this._busType === 'spi') {
            return ((reg << 1) & 0x7E) | (read ? 0x80 : 0x00);
        }
        if (this._busType === 'uart') {
            return (reg & 0x3F) | (read ? 0x80 : 0x00);
        }
        return reg & 0x3F;
    }

    async _writeReg(reg, value) {
        const addr = this._addrFor(reg, false);
        await this._conn.write(Buffer.from([addr, value & 0xFF]));
    }

    async _readReg(reg) {
        const addr = this._addrFor(reg, true);
        return (await this._conn.writeRead(Buffer.from([addr]), 1))[0];
    }

    async _setBits(reg, mask) {
        await this._writeReg(reg, (await this._readReg(reg)) | mask);
    }

    async _clearBits(reg, mask) {
        await this._writeReg(reg, (await this._readReg(reg)) & ~mask);
    }

    async _initChip() {
        await this._writeReg(_REG_COMMAND, _CMD_SOFT_RESET);
        for (let i = 0; i < 50; i++) {
            if (((await this._readReg(_REG_COMMAND)) & 0x10) === 0) break;
            _delay(1);
        }
        _delay(50);
        await this._writeReg(_REG_T_MODE,      0x80);
        await this._writeReg(_REG_T_PRESCALER, 0xA9);
        await this._writeReg(_REG_T_RELOAD_H,  0x03);
        await this._writeReg(_REG_T_RELOAD_L,  0xE8);
        await this._writeReg(_REG_TX_ASK, 0x40);
        await this._writeReg(_REG_MODE, 0x3D);
        await this._setBits(_REG_TX_CONTROL, 0x03);
    }

    async _readFifo(n) {
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) {
            out[i] = await this._readReg(_REG_FIFO_DATA);
        }
        return out;
    }

    async _writeFifo(data) {
        for (const b of data) {
            await this._writeReg(_REG_FIFO_DATA, b & 0xFF);
        }
    }

    async _flushFifo() {
        await this._writeReg(_REG_FIFO_LEVEL, _FIFO_FLUSH);
    }

    async _cardCommand(command, waitIrq, sendData) {
        await this._writeReg(_REG_COMMAND, _CMD_IDLE);
        await this._writeReg(_REG_COM_IRQ, 0x7F);
        await this._flushFifo();
        if (sendData && sendData.length) {
            await this._writeFifo(sendData);
        }
        await this._writeReg(_REG_COMMAND, command);
        if (command === _CMD_TRANSCEIVE) {
            await this._setBits(_REG_BIT_FRAMING, 0x80);
        }
        for (let i = 0; i < 200; i++) {
            const n = await this._readReg(_REG_COM_IRQ);
            if (n & waitIrq) return true;
            if (n & 0x01) return false;
        }
        return false;
    }

    async _transceive(send) {
        const ok = await this._cardCommand(_CMD_TRANSCEIVE, _IRQ_RX | _IRQ_IDLE, send);
        if (!ok) return null;
        const err = await this._readReg(_REG_ERROR);
        if (err & 0x13) return null;
        const fifoLevel = await this._readReg(_REG_FIFO_LEVEL);
        if (fifoLevel === 0) return null;
        return this._readFifo(fifoLevel);
    }

    async _calcCrc(data) {
        await this._writeReg(_REG_COMMAND, _CMD_IDLE);
        await this._writeReg(_REG_DIV_IRQ, 0x04);
        await this._flushFifo();
        await this._writeFifo(data);
        await this._writeReg(_REG_COMMAND, _CMD_CALC_CRC);
        for (let i = 0; i < 100; i++) {
            if ((await this._readReg(_REG_DIV_IRQ)) & 0x04) break;
            _delay(1);
        }
        await this._writeReg(_REG_COMMAND, _CMD_IDLE);
        return Buffer.from([
            await this._readReg(_REG_CRC_RESULT_H),
            await this._readReg(_REG_CRC_RESULT_L),
        ]);
    }

    /**
     * Detect a card in the RF field.
     *
     * Sends a REQA short frame. Returns true if a card answered with
     * a valid 2-byte ATQA.
     *
     * @returns {Promise<boolean>} true if a card is in the field.
     */
    async isCardPresent() {
        await this._writeReg(_REG_BIT_FRAMING, 0x07);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        const back = await this._transceive(Buffer.from([_PICC_REQA]));
        return back !== null && back.length === 2;
    }

    async _anticollision(cmd) {
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        await this._writeReg(_REG_BIT_FRAMING, 0x00);
        const send = Buffer.alloc(7);
        send[0] = cmd;
        send[1] = 0x20;
        _delay(1);
        const back = await this._transceive(send);
        if (!back || back.length !== 5) return null;
        let bcc = 0;
        for (let i = 0; i < 4; i++) bcc ^= back[i];
        if (bcc !== back[4]) return null;
        return back.subarray(0, 4);
    }

    async _select(cmd, uidPart) {
        const buf = Buffer.alloc(9);
        buf[0] = cmd;
        buf[1] = _PICC_SEL_BIT;
        uidPart.copy(buf, 2, 0, 4);
        let bcc = 0;
        for (let i = 0; i < 4; i++) bcc ^= uidPart[i];
        buf[6] = bcc;
        const crc = await this._calcCrc(buf.subarray(0, 7));
        buf[7] = crc[0];
        buf[8] = crc[1];
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        _delay(1);
        const back = await this._transceive(buf);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length < 1) return null;
        return back[0];
    }

    async _selectCard() {
        const cascade = [
            [0x93, 0x93],
            [0x95, 0x95],
            [0x97, 0x97],
        ];
        const uid = [];
        for (const [anti, sel] of cascade) {
            const part = await this._anticollision(anti);
            if (!part) return null;
            const sak = await this._select(sel, part);
            if (sak === null) return null;
            if (!(sak & _PICC_SAK_NOT_COMPLETE)) {
                if (part[0] === _PICC_CT) {
                    uid.push(part[1], part[2], part[3]);
                } else {
                    uid.push(part[0], part[1], part[2], part[3]);
                }
                return Buffer.from(uid);
            } else {
                uid.push(part[1], part[2], part[3]);
            }
        }
        return null;
    }

    async _haltCard() {
        const buf = Buffer.alloc(4);
        buf[0] = _PICC_HLTA;
        buf[1] = 0x00;
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(buf.subarray(0, 2));
        buf[2] = crc[0];
        buf[3] = crc[1];
        _delay(1);
        await this._cardCommand(_CMD_TRANSCEIVE, _IRQ_RX | _IRQ_IDLE, buf);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
    }

    /**
     * Detect a card, run anticollision/Select (all cascade levels), and HLTA.
     *
     * Returns the reassembled UID (4, 7, or 10 bytes). A card read this way
     * is immediately halted, so the next call re-detects it from scratch.
     *
     * @returns {Promise<Buffer|null>} UID bytes, or null if no card answered.
     */
    async readUid() {
        if (!(await this.isCardPresent())) return null;
        const uid = await this._selectCard();
        await this._haltCard();
        return uid;
    }
}

/**
 * MFRC522 full interface — extends minimal with configuration, antenna
 * control, self test, MIFARE Classic authenticated operations, and MIFARE
 * Ultralight / NTAG page read/write.
 */
class MFRC522Full extends MFRC522Minimal {
    static KEY_A = 0x60;
    static KEY_B = 0x61;

    static RX_GAIN_18_DB = 0x00;
    static RX_GAIN_23_DB = 0x10;
    static RX_GAIN_33_DB = 0x40;
    static RX_GAIN_38_DB = 0x50;
    static RX_GAIN_43_DB = 0x60;
    static RX_GAIN_48_DB = 0x70;

    constructor(connection, busType = 'spi') {
        super(connection, busType);
    }

    /**
     * Re-run SoftReset and the full initialization sequence.
     * @returns {Promise<void>}
     */
    async reset() { await this._initChip(); }

    /** Enable the antenna driver (TX1 + TX2).
     * @returns {Promise<void>} */
    async antennaOn() { await this._setBits(_REG_TX_CONTROL, 0x03); }

    /** Disable the antenna driver (TX1 + TX2).
     * @returns {Promise<void>} */
    async antennaOff() { await this._clearBits(_REG_TX_CONTROL, 0x03); }

    /**
     * Set the receiver gain.
     * @param {number} dB - One of 18, 23, 33, 38, 43, or 48 dB.
     * @returns {Promise<void>}
     */
    async setAntennaGain(dB) {
        const map = { 18: 0x00, 23: 0x10, 33: 0x40, 38: 0x50, 43: 0x60, 48: 0x70 };
        const gain = map[dB];
        if (gain === undefined) return;
        const cur = (await this._readReg(_REG_RF_CFG)) & 0x8F;
        await this._writeReg(_REG_RF_CFG, cur | gain);
    }

    /**
     * Read the currently configured receiver gain.
     * @returns {Promise<number>} Gain in dB (one of 18, 23, 33, 38, 43, 48).
     */
    async antennaGain() {
        const cur = (await this._readReg(_REG_RF_CFG)) & 0x70;
        const map = { 0x00: 18, 0x10: 23, 0x40: 33, 0x50: 38, 0x60: 43, 0x70: 48 };
        return map[cur] || 0;
    }

    /**
     * Read the version register and decode it.
     * @returns {Promise<{chipType: number, version: number}>} chipType=0x09 for MFRC522.
     */
    async version() {
        const raw = await this._readReg(_REG_VERSION);
        return { chipType: (raw >> 4) & 0x0F, version: raw & 0x0F };
    }

    /**
     * Run the datasheet-defined digital self test.
     * @returns {Promise<boolean>} true if all 64 FIFO bytes match the version-specific reference.
     */
    async selfTest() {
        const refV10 = [
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
        ];
        const refV20 = [
            0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
            0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
            0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
            0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
            0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
            0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
            0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
            0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F,
        ];
        const v = (await this.version()).version;
        const ref = v === 1 ? refV10 : refV20;
        await this._writeReg(_REG_AUTO_TEST, 0x09);
        await this._writeReg(_REG_FIFO_LEVEL, _FIFO_FLUSH);
        await this._writeReg(_REG_COMMAND, _CMD_IDLE);
        for (let i = 0; i < 255; i++) {
            if ((await this._readReg(_REG_FIFO_LEVEL)) >= 64) break;
            await this._writeReg(_REG_COMMAND, _CMD_CALC_CRC);
            _delay(1);
        }
        await this._writeReg(_REG_AUTO_TEST, 0x00);
        await this._writeReg(_REG_COMMAND, _CMD_SOFT_RESET);
        _delay(50);
        await this._initChip();
        const got = await this._readFifo(64);
        for (let i = 0; i < 64; i++) {
            if (got[i] !== ref[i]) return false;
        }
        return true;
    }

    /** WUPA — wake a HALTed card. Same as isCardPresent but with WUPA.
     * @returns {Promise<boolean>} */
    async wakeupCard() {
        await this._writeReg(_REG_BIT_FRAMING, 0x07);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        const back = await this._transceive(Buffer.from([_PICC_WUPA]));
        return back !== null && back.length === 2;
    }

    /**
     * Run anticollision / Select only — leaves the card active for further ops.
     * @returns {Promise<Buffer|null>} UID bytes, or null if no card answered.
     */
    async selectCard() {
        if (!(await this.wakeupCard())) return null;
        return this._selectCard();
    }

    /** Send HLTA — put the currently selected card into HALT state.
     * @returns {Promise<void>} */
    async haltCard() { await this._haltCard(); }

    /**
     * Run MIFARE Classic Crypto1 authentication.
     * @param {number}   blockAddress - Block number to authenticate against.
     * @param {number}   keyType      - MFRC522Full.KEY_A (0x60) or MFRC522Full.KEY_B (0x61).
     * @param {Buffer}   key          - 6-byte key.
     * @param {Buffer}   uid          - 4-byte UID of the card.
     * @returns {Promise<boolean>} true on success.
     */
    async authenticate(blockAddress, keyType, key, uid) {
        if (key.length !== 6 || uid.length !== 4) return false;
        const buf = Buffer.alloc(12);
        buf[0] = keyType;
        buf[1] = blockAddress & 0xFF;
        key.copy(buf, 2, 0, 6);
        uid.copy(buf, 8, 0, 4);
        await this._writeReg(_REG_COM_IRQ, _IRQ_ALL);
        await this._writeReg(_REG_STATUS_2, 0x00);
        await this._flushFifo();
        await this._writeFifo(buf);
        await this._writeReg(_REG_COMMAND, _CMD_MFAUTHENT);
        for (let i = 0; i < 200; i++) {
            const n = await this._readReg(_REG_STATUS_2);
            if (n & _STATUS_2_CRYPTO1ON) return true;
            _delay(1);
        }
        return false;
    }

    /** Clear Status2Reg.MFCrypto1On.
     * @returns {Promise<void>} */
    async stopCrypto() { await this._clearBits(_REG_STATUS_2, _STATUS_2_CRYPTO1ON); }

    /**
     * Read a 16-byte MIFARE Classic block.
     * @param {number} blockAddress - Block number.
     * @returns {Promise<Buffer|null>} 16 data bytes, or null on failure.
     */
    async readBlock(blockAddress) {
        const cmd = Buffer.from([0x30, blockAddress & 0xFF]);
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(cmd);
        const full = Buffer.concat([cmd, crc]);
        const back = await this._transceive(full);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length !== 16) return null;
        return back;
    }

    /**
     * Write a 16-byte MIFARE Classic block.
     * @param {number} blockAddress - Block number.
     * @param {Buffer} data         - 16 bytes to write.
     * @returns {Promise<boolean>} true on success.
     */
    async writeBlock(blockAddress, data) {
        if (data.length !== 16) return false;
        // Phase 1: 0xA0 + block_address, expect 4-bit ACK
        const c = Buffer.from([0xA0, blockAddress & 0xFF]);
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(c);
        const full = Buffer.concat([c, crc]);
        const back = await this._transceive(full);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) {
            await this._writeReg(_REG_TX_MODE, 0x00);
            await this._writeReg(_REG_RX_MODE, 0x00);
            return false;
        }
        // Phase 2: 16 data bytes, expect 4-bit ACK
        const crc2 = await this._calcCrc(data);
        const buf = Buffer.concat([data, crc2]);
        const back2 = await this._transceive(buf);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        return back2 !== null && back2.length >= 1 && (back2[0] & 0x0F) === 0x0A;
    }

    /**
     * Increment the value block at blockAddress by delta and transfer it back.
     * @param {number} blockAddress - Source value block.
     * @param {number} delta        - Unsigned 32-bit increment.
     * @returns {Promise<boolean>} true on success.
     */
    async incrementValue(blockAddress, delta) {
        if (!(await this._valueOp(0xC1, blockAddress, delta, false))) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Decrement the value block at blockAddress by delta and transfer it back.
     * @param {number} blockAddress - Source value block.
     * @param {number} delta        - Unsigned 32-bit decrement.
     * @returns {Promise<boolean>} true on success.
     */
    async decrementValue(blockAddress, delta) {
        if (!(await this._valueOp(0xC0, blockAddress, delta, false))) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Restore (re-read) the value block at blockAddress into the internal data register.
     * @param {number} blockAddress - Value block to restore.
     * @returns {Promise<boolean>} true on success.
     */
    async restoreValue(blockAddress) {
        if (!(await this._valueOp(0xC2, blockAddress, 0, true))) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Commit the internal data register to destinationBlock.
     * @param {number} destinationBlock - Block to write the data register to.
     * @returns {Promise<boolean>} true on success.
     */
    async transferValue(destinationBlock) { return this._transfer(destinationBlock); }

    async _valueOp(cmd, blockAddress, delta, dummy) {
        const c = Buffer.from([cmd, blockAddress & 0xFF]);
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(c);
        const full = Buffer.concat([c, crc]);
        const back = await this._transceive(full);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) {
            await this._writeReg(_REG_TX_MODE, 0x00);
            await this._writeReg(_REG_RX_MODE, 0x00);
            return false;
        }
        const data = Buffer.alloc(6);
        if (dummy) {
            data[0] = data[1] = data[2] = data[3] = 0;
        } else {
            data[0] = delta & 0xFF;
            data[1] = (delta >> 8) & 0xFF;
            data[2] = (delta >> 16) & 0xFF;
            data[3] = (delta >> 24) & 0xFF;
        }
        const crc2 = await this._calcCrc(data.subarray(0, 4));
        data[4] = crc2[0];
        data[5] = crc2[1];
        const back2 = await this._transceive(data);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        if (!back2 || back2.length < 1 || (back2[0] & 0x0F) !== 0x0A) return false;
        return true;
    }

    async _transfer(blockAddress) {
        const c = Buffer.from([0xB0, blockAddress & 0xFF]);
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(c);
        const full = Buffer.concat([c, crc]);
        const back = await this._transceive(full);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) return false;
        return true;
    }

    /**
     * Read 4 consecutive pages (16 bytes) starting at pageAddress.
     * @param {number} pageAddress - Page number (0-based).
     * @returns {Promise<Buffer|null>} 16 data bytes, or null on failure.
     */
    async readUltralightPage(pageAddress) {
        const cmd = Buffer.from([0x30, pageAddress & 0xFF]);
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(cmd);
        const full = Buffer.concat([cmd, crc]);
        const back = await this._transceive(full);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length !== 16) return null;
        return back;
    }

    /**
     * Write a 4-byte page (MIFARE Ultralight / NTAG).
     * @param {number} pageAddress - Page number.
     * @param {Buffer} data        - 4 bytes to write.
     * @returns {Promise<boolean>} true on success.
     */
    async writeUltralightPage(pageAddress, data) {
        if (data.length !== 4) return false;
        const buf = Buffer.alloc(8);
        buf[0] = 0xA2;
        buf[1] = pageAddress & 0xFF;
        data.copy(buf, 2, 0, 4);
        await this._writeReg(_REG_TX_MODE, 0x80);
        await this._writeReg(_REG_RX_MODE, 0x80);
        const crc = await this._calcCrc(buf.subarray(0, 6));
        buf[6] = crc[0];
        buf[7] = crc[1];
        const back = await this._transceive(buf);
        await this._writeReg(_REG_TX_MODE, 0x00);
        await this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) return false;
        return true;
    }

    /**
     * Run the Generate RandomID command and return the 10-byte ID.
     * @returns {Promise<Buffer>} 10-byte random ID.
     */
    async generateRandomId() {
        await this._writeReg(_REG_COMMAND, _CMD_IDLE);
        await this._writeReg(_REG_COM_IRQ, _IRQ_ALL);
        await this._writeReg(_REG_DIV_IRQ, 0x14);
        await this._writeReg(_REG_COMMAND, _CMD_RANDOM_ID);
        for (let i = 0; i < 50; i++) {
            if ((await this._readReg(_REG_COM_IRQ)) & 0x10) break;
            _delay(1);
        }
        await this._writeReg(_REG_COMMAND, _CMD_IDLE);
        return this._readFifo(10);
    }
}

module.exports = { MFRC522Minimal, MFRC522Full };
