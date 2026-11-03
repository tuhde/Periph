'use strict';

/**
 * Macronix MX25L12845E 128-Mbit (16 MiB) serial NOR flash (specs/memory/mx25l12845e.md).
 *
 * `MX25L12845EMinimal` is a byte-addressable store with transparent erase: `readBytes`, an
 * erase-aware `writeBytes` (read-modify-erase-write per 4 KiB sector, programming only what
 * changed) and `eraseSector`. `MX25L12845EFull` adds the raw program / erase commands, status
 * and block protection, deep power-down, SFDP and the secured OTP, and implements the common
 * `BlockDevice` contract (specs/feature_block_device.md): 512-byte blocks, 4 KiB erase units,
 * `needsErase() === true`.
 *
 * Plain single-I/O SPI Mode 0 over a bare SPI connection (`write` and `writeRead`; CS is held
 * low for each frame by the connection). Call `await flash.init()` before use (the constructor
 * cannot await bus I/O).
 */

const {
    BlockDevice, NotReady, OutOfRange, NotErased, WriteProtected, Timeout, Io,
} = require('../../storage/block_device');

const WREN = 0x06;
const RDID = 0x9F;
const RES = 0xAB;
const REMS = 0x90;
const RDSR = 0x05;
const WRSR = 0x01;
const READ = 0x03;
const FAST_READ = 0x0B;
const PP = 0x02;
const SE = 0x20;
const BE32K = 0x52;
const BE64K = 0xD8;
const CE = 0xC7;
const DP = 0xB9;
const RDP = 0xAB;
const RDSFDP = 0x5A;
const ENSO = 0xB1;
const EXSO = 0xC1;
const RDSCUR = 0x2B;
const WRSCUR = 0x2F;
const CLSR = 0x30;

const SR_WIP = 0x01;
const SR_WEL = 0x02;
const SR_BP_MASK = 0x3C;
const SR_BP_SHIFT = 2;
const SCUR_P_FAIL = 0x20;
const SCUR_E_FAIL = 0x40;

const T_WRSR_MS = 150;
const T_PP_MS = 10;
const T_SE_MS = 400;
const T_BE_MS = 3000;
const T_CE_MS = 250000;
const READ_CHUNK = 4096;

const PAGE_SIZE = 256;
const SECTOR_SIZE = 4096;
const SIZE = 16 * 1024 * 1024;
const BLOCK_SIZE = 512;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function header(cmd, address) {
    return Buffer.from([cmd, (address >> 16) & 0xFF, (address >> 8) & 0xFF, address & 0xFF]);
}

/** MX25L12845E — minimal driver: byte-addressed read, erase-aware write. */
class MX25L12845EMinimal {
    /**
     * @param {object} connection - SPI connection (`write`, `writeRead`).
     * @param {object} [options]
     * @param {number} [options.initTimeoutMs=250000] - How long `init()` waits for a chip that is still busy
     *   (a reset during a chip erase can leave it busy for up to 200 s).
     */
    constructor(connection, options = {}) {
        this._c = connection;
        this._initTimeout = options.initTimeoutMs ?? T_CE_MS;
    }

    /**
     * Leave deep power-down, check the JEDEC ID (C2 20 18) and wait until the chip is idle.
     * @returns {Promise<void>}
     * @throws {NotReady} The JEDEC ID did not match.
     * @throws {Timeout} The chip stayed busy.
     */
    async init() {
        await this._c.write(Buffer.from([RDP])); // leave deep power-down if a previous session left it there
        await sleep(1); // tRES2 = 100 us
        const id = await this.jedecId();
        if (id.toString('hex') !== 'c22018') throw new NotReady(`MX25L12845E not found (JEDEC ID ${id.toString('hex')})`);
        await this._waitReady(this._initTimeout);
    }

    // ---- bus primitives --------------------------------------------------

    async _status() {
        return (await this._c.writeRead(Buffer.from([RDSR]), 1))[0];
    }

    async _wren() {
        await this._c.write(Buffer.from([WREN]));
        if (!((await this._status()) & SR_WEL)) throw new Io('WEL not set after WREN'); // the next command would be ignored
    }

    async _waitReady(timeoutMs) {
        const start = Date.now();
        while ((await this._status()) & SR_WIP) {
            if (Date.now() - start >= timeoutMs) throw new Timeout('chip busy');
            if (timeoutMs > 20) await sleep(1); // long operations: do not hammer the bus
        }
    }

    async _checkFail() {
        const scur = (await this._c.writeRead(Buffer.from([RDSCUR]), 1))[0];
        if (scur & (SCUR_P_FAIL | SCUR_E_FAIL)) {
            await this._c.write(Buffer.from([CLSR]));
            if ((await this._status()) & SR_BP_MASK) throw new WriteProtected('program/erase hit the block-protected area');
            throw new Io(`program/erase failed (security register 0x${scur.toString(16).padStart(2, '0')})`);
        }
    }

    async _eraseCmd(cmd, address, timeoutMs) {
        await this._wren();
        await this._c.write(header(cmd, address));
        await this._waitReady(timeoutMs);
        await this._checkFail();
    }

    async _program(address, data) {
        await this._wren();
        await this._c.write(Buffer.concat([header(PP, address), Buffer.from(data)]));
        await this._waitReady(T_PP_MS);
        await this._checkFail();
    }

    async _readRaw(cmd, address, dummy, length) {
        const parts = [];
        while (length > 0) {
            const n = Math.min(READ_CHUNK, length);
            const frame = dummy ? Buffer.concat([header(cmd, address), Buffer.from([0])]) : header(cmd, address);
            parts.push(Buffer.from(await this._c.writeRead(frame, n)));
            address += n;
            length -= n;
        }
        return Buffer.concat(parts);
    }

    // ---- Minimal API -----------------------------------------------------

    /** @returns {Promise<Buffer>} The three RDID bytes (manufacturer, memory type, density). */
    async jedecId() {
        return Buffer.from(await this._c.writeRead(Buffer.from([RDID]), 3));
    }

    /** @returns {number} Capacity in bytes (16 777 216). */
    size() { return SIZE; }

    /**
     * Read `length` bytes starting at `address` (READ, 03h), any length and alignment.
     * @param {number} address
     * @param {number} length
     * @returns {Promise<Buffer>}
     * @throws {OutOfRange} The range leaves the array.
     */
    async readBytes(address, length) {
        if (address < 0 || address + length > SIZE) throw new OutOfRange('read beyond end');
        return this._readRaw(READ, address, false, length);
    }

    /**
     * Erase the 4 KiB sector containing `address` (SE, 20h).
     * @param {number} address
     * @returns {Promise<void>}
     */
    async eraseSector(address) {
        if (address < 0 || address >= SIZE) throw new OutOfRange('address beyond end');
        await this._eraseCmd(SE, address, T_SE_MS);
    }

    /**
     * Erase-aware write; resolves when the chip is ready. Per 4 KiB sector: if only 1->0 transitions are
     * needed just the changed pages are programmed, otherwise the sector is erased and rewritten with the
     * merged contents.
     * @param {number} address
     * @param {Buffer|Uint8Array} data
     * @returns {Promise<void>}
     * @throws {OutOfRange} The range leaves the array.
     * @throws {WriteProtected} The chip reported a failure while block-protect bits are set.
     */
    async writeBytes(address, data) {
        data = Buffer.from(data);
        const length = data.length;
        if (address < 0 || address + length > SIZE) throw new OutOfRange('write beyond end');
        let pos = 0;
        while (pos < length) {
            const base = address - (address % SECTOR_SIZE);
            const off = address - base;
            const n = Math.min(SECTOR_SIZE - off, length - pos);
            const chunk = data.subarray(pos, pos + n);
            const old = await this._readRaw(READ, base, false, SECTOR_SIZE);
            let needsErase = false;
            for (let i = 0; i < n; i++) {
                if ((old[off + i] & chunk[i]) !== chunk[i]) { needsErase = true; break; } // would need a 0 -> 1 transition
            }
            if (!needsErase) {
                let done = 0;
                while (done < n) {
                    const a = address + done;
                    const part = Math.min(PAGE_SIZE - (a % PAGE_SIZE), n - done);
                    if (!old.subarray(off + done, off + done + part).equals(chunk.subarray(done, done + part))) {
                        await this._program(a, chunk.subarray(done, done + part));
                    }
                    done += part;
                }
            } else {
                const merged = Buffer.from(old);
                chunk.copy(merged, off);
                await this._eraseCmd(SE, base, T_SE_MS);
                for (let page = 0; page < SECTOR_SIZE; page += PAGE_SIZE) {
                    const block = merged.subarray(page, page + PAGE_SIZE);
                    if (!block.every((b) => b === 0xFF)) await this._program(base + page, block); // blank pages need no program
                }
            }
            address += n;
            pos += n;
        }
    }
}

/**
 * MX25L12845E — full driver: raw commands, protection, power, OTP, SFDP; implements `BlockDevice`.
 */
class MX25L12845EFull extends MX25L12845EMinimal {
    /**
     * @param {object} connection - SPI connection.
     * @param {object} [options]
     * @param {boolean} [options.autoErase=false] - `write()` erases sectors itself when a 0->1 transition is
     *   needed; otherwise it rejects with `NotErased`.
     * @param {number} [options.initTimeoutMs] - See `MX25L12845EMinimal`.
     */
    constructor(connection, options = {}) {
        super(connection, options);
        this._autoErase = options.autoErase ?? false;
        this._bp = 0;
    }

    /** As `MX25L12845EMinimal#init`, then caches the block-protect bits for `info()`. */
    async init() {
        await super.init();
        await this.protection();
    }

    // ---- raw operations --------------------------------------------------

    /** FAST_READ (0Bh) of `length` bytes. @returns {Promise<Buffer>} */
    async fastRead(address, length) {
        if (address < 0 || address + length > SIZE) throw new OutOfRange('read beyond end');
        return this._readRaw(FAST_READ, address, true, length);
    }

    /** One PP of 1..256 bytes that must not cross a 256-byte page boundary; the bytes must be erased. */
    async pageProgram(address, data) {
        const n = data.length;
        if (n === 0 || n > PAGE_SIZE || address < 0 || address + n > SIZE) throw new OutOfRange('bad page program range');
        if ((address % PAGE_SIZE) + n > PAGE_SIZE) throw new OutOfRange('page program would wrap inside the page');
        await this._program(address, data);
    }

    /** Erase the 32 KiB block containing `address` (BE32K, 52h). */
    async eraseBlock32k(address) {
        if (address < 0 || address >= SIZE) throw new OutOfRange('address beyond end');
        await this._eraseCmd(BE32K, address, T_BE_MS);
    }

    /** Erase the 64 KiB block containing `address` (BE, D8h). */
    async eraseBlock64k(address) {
        if (address < 0 || address >= SIZE) throw new OutOfRange('address beyond end');
        await this._eraseCmd(BE64K, address, T_BE_MS);
    }

    /** Erase the whole chip (CE, C7h); 80 s typical, 200 s max; needs BP3:0 == 0. */
    async chipErase() {
        await this._wren();
        await this._c.write(Buffer.from([CE]));
        await this._waitReady(T_CE_MS);
        await this._checkFail();
    }

    // ---- status / protection ---------------------------------------------

    /** @returns {Promise<number>} The status register. */
    async readStatus() { return this._status(); }

    /** WREN + WRSR; waits for tW. */
    async writeStatus(value) {
        await this._wren();
        await this._c.write(Buffer.from([WRSR, value & 0xFF]));
        await this._waitReady(T_WRSR_MS);
        this._bp = (value & SR_BP_MASK) >> SR_BP_SHIFT;
    }

    /** @returns {Promise<number>} BP3:0 (0..15) from the status register. */
    async protection() {
        this._bp = ((await this._status()) & SR_BP_MASK) >> SR_BP_SHIFT;
        return this._bp;
    }

    /** Set BP3:0 (non-volatile); QE and SRWD are preserved. */
    async setProtection(bp) {
        if (!Number.isInteger(bp) || bp < 0 || bp > 15) throw new OutOfRange('BP must be 0..15');
        await this.writeStatus(((await this._status()) & ~SR_BP_MASK) | (bp << SR_BP_SHIFT));
    }

    /** @returns {Promise<boolean>} True while a program, erase or WRSR is running (WIP). */
    async isBusy() { return !!((await this._status()) & SR_WIP); }

    /** Wait until WIP = 0; rejects with `Timeout`. */
    async waitReady(timeoutMs) { await this._waitReady(timeoutMs); }

    /** @returns {Promise<number>} The security register (RDSCUR). */
    async readSecurity() { return (await this._c.writeRead(Buffer.from([RDSCUR]), 1))[0]; }

    /** CLSR: clear P_FAIL / E_FAIL. */
    async clearFailFlags() { await this._c.write(Buffer.from([CLSR])); }

    // ---- power / identification ------------------------------------------

    /** DP: enter deep power-down (only RDP / RES are accepted afterwards). */
    async deepPowerDown() { await this._c.write(Buffer.from([DP])); }

    /** RDP: leave deep power-down and wait tRES2. */
    async releaseDeepPowerDown() {
        await this._c.write(Buffer.from([RDP]));
        await sleep(1);
    }

    /** @returns {Promise<number>} RES electronic ID (17h). */
    async electronicId() { return (await this._c.writeRead(Buffer.from([RES, 0, 0, 0]), 1))[0]; }

    /** @returns {Promise<number[]>} REMS: `[manufacturer, device]` = [0xC2, 0x17]. */
    async deviceIds() {
        const r = await this._c.writeRead(Buffer.from([REMS, 0, 0, 0]), 2);
        return [r[0], r[1]];
    }

    /** RDSFDP (5Ah): `length` bytes of the SFDP table from `address`. @returns {Promise<Buffer>} */
    async readSfdp(address, length) { return this._readRaw(RDSFDP, address, true, length); }

    // ---- secured OTP (4 Kbit) --------------------------------------------

    /** Read `length` bytes at OTP offset `offset` (0..511). @returns {Promise<Buffer>} */
    async readOtp(offset, length) {
        if (offset < 0 || offset + length > 512) throw new OutOfRange('OTP range');
        await this._c.write(Buffer.from([ENSO]));
        try {
            return await this._readRaw(READ, offset, false, length);
        } finally {
            await this._c.write(Buffer.from([EXSO]));
        }
    }

    /** Program OTP bytes at `offset` (16..511 only: 0..15 are the ESN area); one-time programmable. */
    async programOtp(offset, data) {
        data = Buffer.from(data);
        if (offset < 16 || data.length === 0 || offset + data.length > 512) throw new OutOfRange('OTP range (0..15 is the ESN area)');
        await this._c.write(Buffer.from([ENSO]));
        try {
            let pos = 0;
            while (pos < data.length) {
                const n = Math.min(PAGE_SIZE - (offset % PAGE_SIZE), data.length - pos);
                await this._program(offset, data.subarray(pos, pos + n));
                offset += n;
                pos += n;
            }
        } finally {
            await this._c.write(Buffer.from([EXSO]));
        }
    }

    /** Lock the customer OTP area for good (WRSCUR, sets LDSO) — IRREVERSIBLE. */
    async lockOtp() {
        await this._wren();
        await this._c.write(Buffer.from([WRSCUR]));
        await this._waitReady(T_WRSR_MS);
    }

    /** @returns {Promise<Buffer>} OTP 000h-00Fh (the factory ESN when `readSecurity() & 1`; blank 0xFF otherwise). */
    async serialNumber() { return this.readOtp(0, 16); }

    // ---- BlockDevice -----------------------------------------------------

    blockSize() { return BLOCK_SIZE; }
    blockCount() { return SIZE / BLOCK_SIZE; }
    eraseSize() { return SECTOR_SIZE; }
    needsErase() { return true; }

    /**
     * Read `count` blocks of 512 bytes.
     * @returns {Promise<Buffer>}
     */
    async read(block, count = 1) {
        if (block < 0 || block + count > this.blockCount()) throw new OutOfRange('read beyond end');
        return this._readRaw(READ, block * BLOCK_SIZE, false, count * BLOCK_SIZE);
    }

    /**
     * Program `data.length / 512` blocks; with `autoErase` off a block that needs a 0->1 transition rejects
     * with `NotErased` (nothing is erased).
     */
    async write(block, data) {
        data = Buffer.from(data);
        const count = data.length / BLOCK_SIZE;
        if (!Number.isInteger(count) || count < 1 || block < 0 || block + count > this.blockCount()) throw new OutOfRange('write range');
        let address = block * BLOCK_SIZE;
        if (this._autoErase) return this.writeBytes(address, data);
        let pos = 0;
        while (pos < data.length) { // program page by page, only where no 0 -> 1 transition is needed
            const n = Math.min(PAGE_SIZE - (address % PAGE_SIZE), data.length - pos);
            const old = await this._readRaw(READ, address, false, n);
            const next = data.subarray(pos, pos + n);
            for (let i = 0; i < n; i++) {
                if ((old[i] & next[i]) !== next[i]) throw new NotErased('block needs an erase before it can be programmed');
            }
            if (!old.equals(next)) await this._program(address, next);
            address += n;
            pos += n;
        }
    }

    /** Erase `count` blocks; the range must be 4 KiB-aligned (multiples of 8 blocks). */
    async erase(block, count = 1) {
        if (block % 8 || count % 8 || block < 0 || block + count > this.blockCount()) throw new OutOfRange('erase range must be 4 KiB aligned');
        let address = block * BLOCK_SIZE;
        let left = count * BLOCK_SIZE;
        while (left > 0) {
            let step;
            if (address % 65536 === 0 && left >= 65536) { await this.eraseBlock64k(address); step = 65536; }
            else if (address % 32768 === 0 && left >= 32768) { await this.eraseBlock32k(address); step = 32768; }
            else { await this.eraseSector(address); step = SECTOR_SIZE; }
            address += step;
            left -= step;
        }
    }

    /** Wait until the chip is idle. */
    async sync() { await this._waitReady(T_CE_MS); }

    /** @returns {object} Geometry and capabilities (see BlockDevice#info); `readOnly` when BP >= 8 as last read by
     *   `init()`, `protection()`, `setProtection()` or `writeStatus()`. */
    info() {
        return {
            blockSize: BLOCK_SIZE, blockCount: this.blockCount(), eraseSize: SECTOR_SIZE, needsErase: true,
            readOnly: this._bp >= 8, badBlocks: false, name: 'MX25L12845E',
        };
    }

    async isBad(_unit) { return false; }
    async markBad(_unit) {}
}

// Single inheritance: MX25L12845EFull conforms to BlockDevice by duck typing; the marker makes
// `flash instanceof BlockDevice` true.
BlockDevice.mark(MX25L12845EFull);

module.exports = { MX25L12845EMinimal, MX25L12845EFull, SIZE, PAGE_SIZE, SECTOR_SIZE };
