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
 * configuration beyond the transport is required.
 *
 * Supports three host transports — I²C, SPI, and UART — all of which
 * expose the same 64-register internal bank; the address-byte framing
 * differs per transport. The driver selects the correct framing from a
 * busType parameter.
 *
 * Default configuration (baked in at construction):
 *     - 25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)
 *     - Force100ASK modulation
 *     - ISO/IEC 14443-3 CRC_A preset (0x6363)
 *     - Antenna enabled
 *     - 106 kBd, 33 dB RX gain (reset default)
 *
 * @param {object} transport - Configured I²C, SPI, or UART transport.
 * @param {string} [busType='spi'] - Bus type: 'spi', 'i2c', or 'uart'.
 */
class MFRC522Minimal {
    constructor(transport, busType = 'spi') {
        this._transport = transport;
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

    _writeReg(reg, value) {
        const addr = this._addrFor(reg, false);
        this._transport.write(Buffer.from([addr, value & 0xFF]));
    }

    _readReg(reg) {
        const addr = this._addrFor(reg, true);
        return this._transport.writeRead(Buffer.from([addr]), 1)[0];
    }

    _setBits(reg, mask) {
        this._writeReg(reg, this._readReg(reg) | mask);
    }

    _clearBits(reg, mask) {
        this._writeReg(reg, this._readReg(reg) & ~mask);
    }

    _initChip() {
        this._writeReg(_REG_COMMAND, _CMD_SOFT_RESET);
        for (let i = 0; i < 50; i++) {
            if ((this._readReg(_REG_COMMAND) & 0x10) === 0) break;
            _delay(1);
        }
        _delay(50);
        this._writeReg(_REG_T_MODE,      0x80);
        this._writeReg(_REG_T_PRESCALER, 0xA9);
        this._writeReg(_REG_T_RELOAD_H,  0x03);
        this._writeReg(_REG_T_RELOAD_L,  0xE8);
        this._writeReg(_REG_TX_ASK, 0x40);
        this._writeReg(_REG_MODE, 0x3D);
        this._setBits(_REG_TX_CONTROL, 0x03);
    }

    _readFifo(n) {
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) {
            out[i] = this._readReg(_REG_FIFO_DATA);
        }
        return out;
    }

    _writeFifo(data) {
        for (const b of data) {
            this._writeReg(_REG_FIFO_DATA, b & 0xFF);
        }
    }

    _flushFifo() {
        this._writeReg(_REG_FIFO_LEVEL, _FIFO_FLUSH);
    }

    _cardCommand(command, waitIrq, sendData) {
        this._writeReg(_REG_COMMAND, _CMD_IDLE);
        this._writeReg(_REG_COM_IRQ, 0x7F);
        this._flushFifo();
        if (sendData && sendData.length) {
            this._writeFifo(sendData);
        }
        this._writeReg(_REG_COMMAND, command);
        if (command === _CMD_TRANSCEIVE) {
            this._setBits(_REG_BIT_FRAMING, 0x80);
        }
        for (let i = 0; i < 200; i++) {
            const n = this._readReg(_REG_COM_IRQ);
            if (n & waitIrq) return true;
            if (n & 0x01) return false;
        }
        return false;
    }

    _transceive(send) {
        const ok = this._cardCommand(_CMD_TRANSCEIVE, _IRQ_RX | _IRQ_IDLE, send);
        if (!ok) return null;
        const err = this._readReg(_REG_ERROR);
        if (err & 0x13) return null;
        const fifoLevel = this._readReg(_REG_FIFO_LEVEL);
        if (fifoLevel === 0) return null;
        return this._readFifo(fifoLevel);
    }

    _calcCrc(data) {
        this._writeReg(_REG_COMMAND, _CMD_IDLE);
        this._writeReg(_REG_DIV_IRQ, 0x04);
        this._flushFifo();
        this._writeFifo(data);
        this._writeReg(_REG_COMMAND, _CMD_CALC_CRC);
        for (let i = 0; i < 100; i++) {
            if (this._readReg(_REG_DIV_IRQ) & 0x04) break;
            _delay(1);
        }
        this._writeReg(_REG_COMMAND, _CMD_IDLE);
        return Buffer.from([
            this._readReg(_REG_CRC_RESULT_H),
            this._readReg(_REG_CRC_RESULT_L),
        ]);
    }

    /**
     * Detect a card in the RF field.
     *
     * Sends a REQA short frame. Returns true if a card answered with
     * a valid 2-byte ATQA.
     *
     * @returns {boolean} true if a card is in the field.
     */
    isCardPresent() {
        this._writeReg(_REG_BIT_FRAMING, 0x07);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        const back = this._transceive(Buffer.from([_PICC_REQA]));
        return back !== null && back.length === 2;
    }

    _anticollision(cmd) {
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        this._writeReg(_REG_BIT_FRAMING, 0x00);
        const send = Buffer.alloc(7);
        send[0] = cmd;
        send[1] = 0x20;
        _delay(1);
        const back = this._transceive(send);
        if (!back || back.length !== 5) return null;
        let bcc = 0;
        for (let i = 0; i < 4; i++) bcc ^= back[i];
        if (bcc !== back[4]) return null;
        return back.subarray(0, 4);
    }

    _select(cmd, uidPart) {
        const buf = Buffer.alloc(9);
        buf[0] = cmd;
        buf[1] = _PICC_SEL_BIT;
        uidPart.copy(buf, 2, 0, 4);
        let bcc = 0;
        for (let i = 0; i < 4; i++) bcc ^= uidPart[i];
        buf[6] = bcc;
        const crc = this._calcCrc(buf.subarray(0, 7));
        buf[7] = crc[0];
        buf[8] = crc[1];
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        _delay(1);
        const back = this._transceive(buf);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length < 1) return null;
        return back[0];
    }

    _selectCard() {
        const cascade = [
            [0x93, 0x93],
            [0x95, 0x95],
            [0x97, 0x97],
        ];
        const uid = [];
        for (const [anti, sel] of cascade) {
            const part = this._anticollision(anti);
            if (!part) return null;
            const sak = this._select(sel, part);
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

    _haltCard() {
        const buf = Buffer.alloc(4);
        buf[0] = _PICC_HLTA;
        buf[1] = 0x00;
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        const crc = this._calcCrc(buf.subarray(0, 2));
        buf[2] = crc[0];
        buf[3] = crc[1];
        _delay(1);
        this._cardCommand(_CMD_TRANSCEIVE, _IRQ_RX | _IRQ_IDLE, buf);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
    }

    /**
     * Detect a card, run anticollision/Select (all cascade levels), and HLTA.
     *
     * Returns the reassembled UID (4, 7, or 10 bytes). A card read this way
     * is immediately halted, so the next call re-detects it from scratch.
     *
     * @returns {Buffer|null} UID bytes, or null if no card answered.
     */
    readUid() {
        if (!this.isCardPresent()) return null;
        const uid = this._selectCard();
        this._haltCard();
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

    constructor(transport, busType = 'spi') {
        super(transport, busType);
    }

    /**
     * Re-run SoftReset and the full initialization sequence.
     */
    reset() { this._initChip(); }

    /** Enable the antenna driver (TX1 + TX2). */
    antennaOn() { this._setBits(_REG_TX_CONTROL, 0x03); }

    /** Disable the antenna driver (TX1 + TX2). */
    antennaOff() { this._clearBits(_REG_TX_CONTROL, 0x03); }

    /**
     * Set the receiver gain.
     * @param {number} dB - One of 18, 23, 33, 38, 43, or 48 dB.
     */
    setAntennaGain(dB) {
        const map = { 18: 0x00, 23: 0x10, 33: 0x40, 38: 0x50, 43: 0x60, 48: 0x70 };
        const gain = map[dB];
        if (gain === undefined) return;
        const cur = this._readReg(_REG_RF_CFG) & 0x8F;
        this._writeReg(_REG_RF_CFG, cur | gain);
    }

    /**
     * Read the currently configured receiver gain.
     * @returns {number} Gain in dB (one of 18, 23, 33, 38, 43, 48).
     */
    antennaGain() {
        const cur = this._readReg(_REG_RF_CFG) & 0x70;
        const map = { 0x00: 18, 0x10: 23, 0x40: 33, 0x50: 38, 0x60: 43, 0x70: 48 };
        return map[cur] || 0;
    }

    /**
     * Read the version register and decode it.
     * @returns {{chipType: number, version: number}} chipType=0x09 for MFRC522.
     */
    version() {
        const raw = this._readReg(_REG_VERSION);
        return { chipType: (raw >> 4) & 0x0F, version: raw & 0x0F };
    }

    /**
     * Run the datasheet-defined digital self test.
     * @returns {boolean} true if all 64 FIFO bytes match the version-specific reference.
     */
    selfTest() {
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
        const v = this.version().version;
        const ref = v === 1 ? refV10 : refV20;
        this._writeReg(_REG_AUTO_TEST, 0x09);
        this._writeReg(_REG_FIFO_LEVEL, _FIFO_FLUSH);
        this._writeReg(_REG_COMMAND, _CMD_IDLE);
        for (let i = 0; i < 255; i++) {
            if (this._readReg(_REG_FIFO_LEVEL) >= 64) break;
            this._writeReg(_REG_COMMAND, _CMD_CALC_CRC);
            _delay(1);
        }
        this._writeReg(_REG_AUTO_TEST, 0x00);
        this._writeReg(_REG_COMMAND, _CMD_SOFT_RESET);
        _delay(50);
        this._initChip();
        const got = this._readFifo(64);
        for (let i = 0; i < 64; i++) {
            if (got[i] !== ref[i]) return false;
        }
        return true;
    }

    /** WUPA — wake a HALTed card. Same as isCardPresent but with WUPA. */
    wakeupCard() {
        this._writeReg(_REG_BIT_FRAMING, 0x07);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        const back = this._transceive(Buffer.from([_PICC_WUPA]));
        return back !== null && back.length === 2;
    }

    /**
     * Run anticollision / Select only — leaves the card active for further ops.
     * @returns {Buffer|null} UID bytes, or null if no card answered.
     */
    selectCard() {
        if (!this.wakeupCard()) return null;
        return this._selectCard();
    }

    /** Send HLTA — put the currently selected card into HALT state. */
    haltCard() { this._haltCard(); }

    /**
     * Run MIFARE Classic Crypto1 authentication.
     * @param {number}   blockAddress - Block number to authenticate against.
     * @param {number}   keyType      - MFRC522Full.KEY_A (0x60) or MFRC522Full.KEY_B (0x61).
     * @param {Buffer}   key          - 6-byte key.
     * @param {Buffer}   uid          - 4-byte UID of the card.
     * @returns {boolean} true on success.
     */
    authenticate(blockAddress, keyType, key, uid) {
        if (key.length !== 6 || uid.length !== 4) return false;
        const buf = Buffer.alloc(12);
        buf[0] = keyType;
        buf[1] = blockAddress & 0xFF;
        key.copy(buf, 2, 0, 6);
        uid.copy(buf, 8, 0, 4);
        this._writeReg(_REG_COM_IRQ, _IRQ_ALL);
        this._writeReg(_REG_STATUS_2, 0x00);
        this._flushFifo();
        this._writeFifo(buf);
        this._writeReg(_REG_COMMAND, _CMD_MFAUTHENT);
        for (let i = 0; i < 200; i++) {
            const n = this._readReg(_REG_STATUS_2);
            if (n & _STATUS_2_CRYPTO1ON) return true;
            _delay(1);
        }
        return false;
    }

    /** Clear Status2Reg.MFCrypto1On. */
    stopCrypto() { this._clearBits(_REG_STATUS_2, _STATUS_2_CRYPTO1ON); }

    /**
     * Read a 16-byte MIFARE Classic block.
     * @param {number} blockAddress - Block number.
     * @returns {Buffer|null} 16 data bytes, or null on failure.
     */
    readBlock(blockAddress) {
        const cmd = Buffer.from([0x30, blockAddress & 0xFF]);
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        const crc = this._calcCrc(cmd);
        const full = Buffer.concat([cmd, crc]);
        const back = this._transceive(full);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length !== 16) return null;
        return back;
    }

    /**
     * Write a 16-byte MIFARE Classic block.
     * @param {number} blockAddress - Block number.
     * @param {Buffer} data         - 16 bytes to write.
     * @returns {boolean} true on success.
     */
    writeBlock(blockAddress, data) {
        if (data.length !== 16) return false;
        if (!this._valueOp(0xA0, blockAddress, 0, false)) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Increment the value block at blockAddress by delta and transfer it back.
     * @param {number} blockAddress - Source value block.
     * @param {number} delta        - Unsigned 32-bit increment.
     * @returns {boolean} true on success.
     */
    incrementValue(blockAddress, delta) {
        if (!this._valueOp(0xC1, blockAddress, delta, false)) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Decrement the value block at blockAddress by delta and transfer it back.
     * @param {number} blockAddress - Source value block.
     * @param {number} delta        - Unsigned 32-bit decrement.
     * @returns {boolean} true on success.
     */
    decrementValue(blockAddress, delta) {
        if (!this._valueOp(0xC0, blockAddress, delta, false)) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Restore (re-read) the value block at blockAddress into the internal data register.
     * @param {number} blockAddress - Value block to restore.
     * @returns {boolean} true on success.
     */
    restoreValue(blockAddress) {
        if (!this._valueOp(0xC2, blockAddress, 0, true)) return false;
        return this._transfer(blockAddress);
    }

    /**
     * Commit the internal data register to destinationBlock.
     * @param {number} destinationBlock - Block to write the data register to.
     * @returns {boolean} true on success.
     */
    transferValue(destinationBlock) { return this._transfer(destinationBlock); }

    _valueOp(cmd, blockAddress, delta, dummy) {
        const c = Buffer.from([cmd, blockAddress & 0xFF]);
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        const crc = this._calcCrc(c);
        const full = Buffer.concat([c, crc]);
        const back = this._transceive(full);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) {
            this._writeReg(_REG_TX_MODE, 0x00);
            this._writeReg(_REG_RX_MODE, 0x00);
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
        const crc2 = this._calcCrc(data.subarray(0, 4));
        data[4] = crc2[0];
        data[5] = crc2[1];
        const back2 = this._transceive(data);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        if (!back2 || back2.length < 1 || (back2[0] & 0x0F) !== 0x0A) return false;
        return true;
    }

    _transfer(blockAddress) {
        const c = Buffer.from([0xB0, blockAddress & 0xFF]);
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        const crc = this._calcCrc(c);
        const full = Buffer.concat([c, crc]);
        const back = this._transceive(full);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) return false;
        return true;
    }

    /**
     * Read 4 consecutive pages (16 bytes) starting at pageAddress.
     * @param {number} pageAddress - Page number (0-based).
     * @returns {Buffer|null} 16 data bytes, or null on failure.
     */
    readUltralightPage(pageAddress) {
        const cmd = Buffer.from([0x30, pageAddress & 0xFF]);
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        const crc = this._calcCrc(cmd);
        const full = Buffer.concat([cmd, crc]);
        const back = this._transceive(full);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length !== 16) return null;
        return back;
    }

    /**
     * Write a 4-byte page (MIFARE Ultralight / NTAG).
     * @param {number} pageAddress - Page number.
     * @param {Buffer} data        - 4 bytes to write.
     * @returns {boolean} true on success.
     */
    writeUltralightPage(pageAddress, data) {
        if (data.length !== 4) return false;
        const buf = Buffer.alloc(8);
        buf[0] = 0xA2;
        buf[1] = pageAddress & 0xFF;
        data.copy(buf, 2, 0, 4);
        this._writeReg(_REG_TX_MODE, 0x80);
        this._writeReg(_REG_RX_MODE, 0x80);
        const crc = this._calcCrc(buf.subarray(0, 6));
        buf[6] = crc[0];
        buf[7] = crc[1];
        const back = this._transceive(buf);
        this._writeReg(_REG_TX_MODE, 0x00);
        this._writeReg(_REG_RX_MODE, 0x00);
        if (!back || back.length < 1 || (back[0] & 0x0F) !== 0x0A) return false;
        return true;
    }

    /**
     * Run the Generate RandomID command and return the 10-byte ID.
     * @returns {Buffer} 10-byte random ID.
     */
    generateRandomId() {
        this._writeReg(_REG_COMMAND, _CMD_IDLE);
        this._writeReg(_REG_COM_IRQ, _IRQ_ALL);
        this._writeReg(_REG_DIV_IRQ, 0x14);
        this._writeReg(_REG_COMMAND, _CMD_RANDOM_ID);
        for (let i = 0; i < 50; i++) {
            if (this._readReg(_REG_COM_IRQ) & 0x10) break;
            _delay(1);
        }
        this._writeReg(_REG_COMMAND, _CMD_IDLE);
        return this._readFifo(10);
    }
}

module.exports = { MFRC522Minimal, MFRC522Full };
