'use strict';

/**
 * SD / SDHC / SDXC / microSD card in SPI mode (specs/memory/sd_spi.md).
 *
 * `SDSPIMinimal` runs the SPI-mode init sequence in `init()` and offers single
 * 512-byte block reads and writes. `SDSPIFull` adds multi-block transfers,
 * erase, CID/CSD/OCR access and CRC checking, and implements the common
 * `BlockDevice` contract (specs/feature_block_device.md).
 *
 * The connection must be an SPI connection with the optional extensions
 * `idleClocks`, `setSpeed`, `select` / `deselect` and `readInto`
 * (specs/transport_spi.md) and must clock 0xFF on MOSI while reading. Cards are
 * 3.3 V only. Call `await sd.init()` before use (the constructor cannot await
 * bus I/O).
 */

const {
    BlockDevice, NotReady, OutOfRange, Crc, Timeout, Io,
} = require('../../storage/block_device');

const CRC16_T = [0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
    0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF];

const INIT_TIMEOUT_MS = 1000;
const TOKEN_TIMEOUT_MS = 200;
const BUSY_TIMEOUT_MS = 500;
const ERASE_TIMEOUT_MS = 30000;

const BLOCK_SIZE = 512;

/** Card generations returned by `cardType()`. */
const CardType = Object.freeze({ NONE: 0, SDV1: 1, SDV2: 2, SDHC: 3 });

/**
 * CRC7 (polynomial x^7 + x^3 + 1) of `data`, not shifted.
 * @param {Uint8Array} data
 * @returns {number}
 */
function crc7(data) {
    let crc = 0;
    for (let b of data) {
        for (let i = 0; i < 8; i++) {
            crc = (crc << 1) & 0xFF;
            if ((b ^ crc) & 0x80) crc ^= 0x09;
            b = (b << 1) & 0xFF;
        }
    }
    return crc & 0x7F;
}

/**
 * CRC16-CCITT (polynomial 0x1021, initial value 0) of `data`.
 * @param {Uint8Array} data
 * @returns {number}
 */
function crc16(data) {
    let crc = 0;
    for (const b of data) {
        crc ^= b << 8;
        crc = ((crc << 4) & 0xFFFF) ^ CRC16_T[crc >> 12];
        crc = ((crc << 4) & 0xFFFF) ^ CRC16_T[crc >> 12];
    }
    return crc;
}

/** SD card in SPI mode — minimal interface (init, single-block read/write). */
class SDSPIMinimal {
    /**
     * @param {object} connection - SPI connection with the optional extensions.
     * @param {object} [options]
     * @param {number} [options.speedHz=12000000] - Data-phase clock after init (init always runs at 400 kHz).
     * @param {boolean} [options.crc=false] - Enable CMD59 and send/verify CRC16 on data blocks.
     */
    constructor(connection, options = {}) {
        this._c = connection;
        this._speed = options.speedHz ?? 12_000_000;
        this._wantCrc = options.crc ?? false;
        this._crcOn = false;
        this._type = CardType.NONE;
        this._blockAddressing = false;
        this._blockCount = 0;
        this._csd = Buffer.alloc(16);
    }

    // ---- bus primitives --------------------------------------------------

    async _rx() {
        return (await this._c.read(1))[0];
    }

    async _waitReady(ms) {
        const start = Date.now();
        for (;;) {
            if ((await this._rx()) === 0xFF) return;
            if (Date.now() - start >= ms) throw new Timeout('card busy');
        }
    }

    async _release() {
        await this._c.deselect();
        await this._c.idleClocks(8); // let the card free MISO
    }

    /** Send a command with CS already selected; returns R1 (0xFF on no response). */
    async _command(cmd, arg) {
        if (cmd !== 12 && cmd !== 0) await this._waitReady(BUSY_TIMEOUT_MS);
        const frame = Buffer.alloc(6);
        frame[0] = 0x40 | cmd;
        frame.writeUInt32BE(arg >>> 0, 1);
        frame[5] = (crc7(frame.subarray(0, 5)) << 1) | 1;
        await this._c.write(frame);
        if (cmd === 12) await this._rx(); // discard the stuff byte that follows CMD12
        for (let i = 0; i < 10; i++) { // Ncr <= 8 bytes
            const r = await this._rx();
            if (!(r & 0x80)) return r;
        }
        return 0xFF;
    }

    async _appCommand(cmd, arg) {
        const r = await this._command(55, 0);
        if (r > 1) return r;
        return this._command(cmd, arg);
    }

    async _readData(length) {
        const start = Date.now();
        let token;
        for (;;) {
            token = await this._rx();
            if (token !== 0xFF) break;
            if (Date.now() - start >= TOKEN_TIMEOUT_MS) throw new Timeout('no data token');
        }
        if (token !== 0xFE) throw new Io(`data error token 0x${token.toString(16)}`);
        const data = Buffer.alloc(length);
        await this._c.readInto(data);
        const crc = Buffer.alloc(2);
        await this._c.readInto(crc);
        if (this._crcOn && crc.readUInt16BE(0) !== crc16(data)) throw new Crc('data CRC16 mismatch');
        return data;
    }

    async _writeData(token, data) {
        await this._waitReady(BUSY_TIMEOUT_MS);
        await this._c.write(Buffer.from([token]));
        if (token === 0xFD) return; // stop-tran token has no payload
        await this._c.write(data);
        const crc = Buffer.from([0xFF, 0xFF]);
        if (this._crcOn) crc.writeUInt16BE(crc16(data), 0);
        await this._c.write(crc);
        const resp = (await this._rx()) & 0x1F;
        if (resp === 0x05) return;
        if (resp === 0x0B) throw new Crc('write CRC rejected');
        throw new Io(`write rejected 0x${resp.toString(16)}`);
    }

    async _readRegister(cmd) {
        await this._c.select();
        try {
            if ((await this._command(cmd, 0)) !== 0) throw new Io(`CMD${cmd} failed`);
            return await this._readData(16);
        } finally {
            await this._release();
        }
    }

    _address(block) {
        return this._blockAddressing ? block : block * BLOCK_SIZE;
    }

    // ---- init ------------------------------------------------------------

    /**
     * Run the SPI-mode init sequence (also usable to re-init a swapped card).
     * @returns {Promise<void>}
     * @throws {NotReady} No card responded, or the card is unsupported.
     * @throws {Timeout} The card never left the idle state.
     */
    async init() {
        this._type = CardType.NONE;
        this._blockCount = 0;
        this._crcOn = false;
        await this._c.setSpeed(400_000);
        await this._c.deselect();
        await this._c.idleClocks(80);

        let idle = false;
        for (let i = 0; i < 10 && !idle; i++) { // CMD0 with CS low enters SPI mode; fresh cards need a few tries
            await this._c.select();
            try {
                idle = (await this._command(0, 0)) === 0x01;
            } finally {
                await this._release();
            }
        }
        if (!idle) throw new NotReady('no card responded to CMD0');

        await this._c.select();
        try {
            let v2 = false;
            const r = await this._command(8, 0x1AA);
            if (r === 0x01) {
                const r7 = await this._c.read(4);
                if ((r7[2] & 0x0F) !== 0x01 || r7[3] !== 0xAA) throw new NotReady('CMD8 echo mismatch');
                v2 = true;
            } else if (!(r & 0x04)) { // anything but "illegal command" (v1 card) is a failure
                throw new NotReady('CMD8 failed');
            }

            const start = Date.now();
            for (;;) {
                if ((await this._appCommand(41, v2 ? 0x40000000 : 0)) === 0x00) break;
                if (Date.now() - start >= INIT_TIMEOUT_MS) throw new Timeout('ACMD41 never completed');
            }

            this._type = v2 ? CardType.SDV2 : CardType.SDV1;
            this._blockAddressing = false;
            if (v2 && (await this._command(58, 0)) === 0x00) {
                const ocr = await this._c.read(4);
                if (ocr[0] & 0x40) {
                    this._blockAddressing = true;
                    this._type = CardType.SDHC;
                }
            }
            if (!this._blockAddressing) await this._command(16, BLOCK_SIZE);
            if (this._wantCrc && (await this._command(59, 1)) === 0x00) this._crcOn = true;
        } catch (e) {
            this._type = CardType.NONE;
            throw e;
        } finally {
            await this._release();
        }

        await this._c.setSpeed(this._speed);
        this._csd = await this._readRegister(9);
        this._blockCount = SDSPIMinimal._csdBlocks(this._csd);
    }

    static _csdBlocks(csd) {
        if ((csd[0] >> 6) === 1) { // CSD v2: C_SIZE = CSD[69:48]
            const csize = ((csd[7] & 0x3F) << 16) | (csd[8] << 8) | csd[9];
            return (csize + 1) * 1024;
        }
        const readBlLen = csd[5] & 0x0F; // CSD v1
        const csize = ((csd[6] & 0x03) << 10) | (csd[7] << 2) | (csd[8] >> 6);
        const mult = ((csd[9] & 0x03) << 1) | (csd[10] >> 7);
        return Math.floor(((csize + 1) * 2 ** (mult + 2) * 2 ** readBlLen) / BLOCK_SIZE);
    }

    // ---- minimal API -----------------------------------------------------

    /** @returns {number} Capacity in 512-byte blocks (from the CSD). */
    blockCount() { return this._blockCount; }

    /** @returns {number} Alias of blockCount() (FatFs terminology). */
    sectorCount() { return this._blockCount; }

    /** @returns {number} Card generation, a `CardType` value. */
    cardType() { return this._type; }

    /**
     * Read one 512-byte block (CMD17).
     * @param {number} block
     * @returns {Promise<Buffer>}
     * @throws {OutOfRange} `block` beyond the card.
     */
    async readBlock(block) {
        if (block < 0 || block >= this._blockCount) throw new OutOfRange(`block ${block} out of range`);
        await this._c.select();
        try {
            if ((await this._command(17, this._address(block))) !== 0) throw new Io('CMD17 failed');
            return await this._readData(BLOCK_SIZE);
        } finally {
            await this._release();
        }
    }

    /**
     * Write one 512-byte block (CMD24).
     * @param {number} block
     * @param {Buffer} data - 512 bytes.
     * @returns {Promise<void>}
     * @throws {OutOfRange} `block` beyond the card.
     */
    async writeBlock(block, data) {
        if (data.length !== BLOCK_SIZE) throw new RangeError('data must be 512 bytes');
        if (block < 0 || block >= this._blockCount) throw new OutOfRange(`block ${block} out of range`);
        await this._c.select();
        try {
            if ((await this._command(24, this._address(block))) !== 0) throw new Io('CMD24 failed');
            await this._writeData(0xFE, data);
            await this._waitReady(BUSY_TIMEOUT_MS);
        } finally {
            await this._release();
        }
    }
}

/**
 * SD card in SPI mode — full interface; implements `BlockDevice`.
 *
 * Adds multi-block transfers (CMD18/CMD25), erase, CID/CSD/OCR access and CRC
 * checking (on by default).
 */
class SDSPIFull extends SDSPIMinimal {
    /**
     * @param {object} connection - SPI connection with the optional extensions.
     * @param {object} [options]
     * @param {number} [options.speedHz=12000000]
     * @param {boolean} [options.crc=true] - CMD59 and CRC16 checking.
     */
    constructor(connection, options = {}) {
        super(connection, { ...options, crc: options.crc ?? true });
        this._name = '';
    }

    async init() {
        await super.init();
        try {
            const cid = await this._readRegister(10);
            this._name = cid.subarray(3, 8).toString('latin1'); // PNM = CID[103:64]
        } catch (_e) {
            this._name = '';
        }
    }

    blockSize() { return BLOCK_SIZE; }
    eraseSize() { return BLOCK_SIZE; }
    needsErase() { return false; }

    /**
     * Read `count` consecutive blocks (CMD18 for `count > 1`).
     * @param {number} block
     * @param {number} [count=1]
     * @returns {Promise<Buffer>}
     */
    async read(block, count = 1) {
        if (count < 1) return Buffer.alloc(0);
        if (block < 0 || block + count > this._blockCount) throw new OutOfRange('read beyond end');
        if (count === 1) return this.readBlock(block);
        const parts = [];
        await this._c.select();
        try {
            if ((await this._command(18, this._address(block))) !== 0) throw new Io('CMD18 failed');
            try {
                for (let i = 0; i < count; i++) parts.push(await this._readData(BLOCK_SIZE));
            } finally {
                await this._command(12, 0);
                await this._waitReady(BUSY_TIMEOUT_MS);
            }
        } finally {
            await this._release();
        }
        return Buffer.concat(parts);
    }

    /**
     * Program `data.length / 512` consecutive blocks (CMD25 for more than one).
     * @param {number} block
     * @param {Buffer} data
     * @returns {Promise<void>}
     */
    async write(block, data) {
        const count = data.length / BLOCK_SIZE;
        if (!Number.isInteger(count) || count < 1) throw new RangeError('data must be a multiple of 512 bytes');
        if (block < 0 || block + count > this._blockCount) throw new OutOfRange('write beyond end');
        if (count === 1) return this.writeBlock(block, data);
        await this._c.select();
        try {
            await this._appCommand(23, count); // pre-erase hint; cards may ignore it
            if ((await this._command(25, this._address(block))) !== 0) throw new Io('CMD25 failed');
            let err = null;
            try {
                for (let i = 0; i < count; i++) await this._writeData(0xFC, data.subarray(i * BLOCK_SIZE, (i + 1) * BLOCK_SIZE));
            } catch (e) {
                err = e;
            }
            await this._waitReady(BUSY_TIMEOUT_MS);
            await this._writeData(0xFD, Buffer.alloc(0));
            await this._rx(); // stuff byte after the stop token
            await this._waitReady(BUSY_TIMEOUT_MS);
            if (err) throw err;
        } finally {
            await this._release();
        }
    }

    /**
     * Erase `count` blocks (CMD32/33/38); contents afterwards are card dependent.
     * @param {number} block
     * @param {number} [count=1]
     * @returns {Promise<void>}
     */
    async erase(block, count = 1) {
        if (count < 1) return;
        if (block < 0 || block + count > this._blockCount) throw new OutOfRange('erase beyond end');
        await this._c.select();
        try {
            if ((await this._command(32, this._address(block))) !== 0
                || (await this._command(33, this._address(block + count - 1))) !== 0
                || (await this._command(38, 0)) !== 0) throw new Io('erase command failed');
            await this._waitReady(ERASE_TIMEOUT_MS);
        } finally {
            await this._release();
        }
    }

    /** Wait until the card has finished programming. */
    async sync() {
        await this._c.select();
        try {
            await this._waitReady(BUSY_TIMEOUT_MS);
        } finally {
            await this._release();
        }
    }

    /** @returns {object} Geometry and capabilities (see BlockDevice#info). */
    info() {
        return {
            blockSize: BLOCK_SIZE, blockCount: this._blockCount, eraseSize: BLOCK_SIZE, needsErase: false,
            readOnly: false, badBlocks: false, name: this._name,
        };
    }

    /** @returns {Promise<Buffer>} The 16-byte CID register (manufacturer, name, revision, serial, date). */
    async cid() { return this._readRegister(10); }

    /** @returns {Buffer} The 16-byte CSD register as read during init. */
    csd() { return Buffer.from(this._csd); }

    /** @returns {Promise<number>} The OCR word (CMD58). */
    async ocr() {
        await this._c.select();
        try {
            if ((await this._command(58, 0)) !== 0) throw new Io('CMD58 failed');
            return (await this._c.read(4)).readUInt32BE(0);
        } finally {
            await this._release();
        }
    }

    /**
     * Enable/disable CMD59 and CRC16 checking.
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async setCrc(enabled) {
        await this._c.select();
        try {
            if ((await this._command(59, enabled ? 1 : 0)) !== 0) throw new Io('CMD59 failed');
        } finally {
            await this._release();
        }
        this._crcOn = !!enabled;
    }

    /**
     * Change the SCK frequency after init.
     * @param {number} hz
     * @returns {Promise<boolean>}
     */
    async setSpeed(hz) { return this._c.setSpeed(hz); }

    /** @returns {Promise<boolean>} True if a card responds to CMD13. */
    async isPresent() {
        await this._c.select();
        try {
            const r = await this._command(13, 0);
            if (r !== 0xFF) await this._rx(); // R2 second byte
            return r !== 0xFF;
        } finally {
            await this._release();
        }
    }

    async isBad(_unit) { return false; }
    async markBad(_unit) {}
}

// JavaScript has single inheritance: SDSPIFull extends SDSPIMinimal and conforms to the BlockDevice
// contract by duck typing; the marker makes `sd instanceof BlockDevice` true.
BlockDevice.mark(SDSPIFull);

module.exports = { SDSPIMinimal, SDSPIFull, CardType, crc7, crc16, BLOCK_SIZE };
