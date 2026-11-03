"use strict";
const { SDSPIMinimal, SDSPIFull, CardType, crc7, crc16 } = require('../../packages/periph/src/chips/memory/sd_spi');
const { BlockDevice, NotReady, OutOfRange, Crc, NotErased } = require('../../packages/periph/src/storage/block_device');
const { RamBlockDevice } = require('../../packages/periph/src/storage/ram_block_device');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function rejects(fn, ErrType) {
    try { await fn(); } catch (e) { return ErrType ? e instanceof ErrType : true; }
    return false;
}

// Behavioural model of an SD card in SPI mode behind the SPI connection
// interface plus the optional extensions. Needs 80 idle clocks before CMD0,
// checks command CRC7, models R1/R3/R7, CSD/CID, single/multi block transfers
// with tokens, busy and CRC16.
class SdModel {
    constructor(v2 = true, sdhc = true) {
        Object.assign(this, {
            v2, sdhc, csizeV2: 7, acmd41Calls: 0, acmd41Needed: 3, gotIdleClocks: false, selected: false,
            inIdle: false, crcOn: false, corruptReadCrc: false, speed: 0, cmdLog: [], csTogglesInCommand: 0,
            state: 'CMD', cmdBuf: [], out: [], appNext: false, streaming: false, streamBlock: 0, wBlock: 0,
            multiWrite: false, wData: [],
        });
        this.mem = Buffer.alloc(8192 * 512);
    }

    async idleClocks(n) { if (!this.selected && n >= 80) this.gotIdleClocks = true; }
    async setSpeed(hz) { this.speed = hz; return true; }
    async select() { this.selected = true; }
    async deselect() {
        this.selected = false;
        if (this.state === 'CMD' && this.cmdBuf.length) this.csTogglesInCommand++;
    }
    async write(data) { for (const b of data) this._feed(b); }
    async read(n) { const o = Buffer.alloc(n); for (let i = 0; i < n; i++) o[i] = this._feed(0xFF); return o; }
    async readInto(buf) { for (let i = 0; i < buf.length; i++) buf[i] = this._feed(0xFF); }

    _pushBlock(data) {
        this.out.push(0xFE, ...data);
        let c = crc16(data);
        if (this.corruptReadCrc) c ^= 1;
        this.out.push(c >> 8, c & 0xFF);
    }
    _blockOf(arg) { return this.sdhc ? arg : Math.floor(arg / 512); }

    _doCommand() {
        const wasApp = this.appNext;
        this.appNext = false;
        const cb = this.cmdBuf;
        const cmd = cb[0] & 0x3F;
        const arg = ((cb[1] << 24) | (cb[2] << 16) | (cb[3] << 8) | cb[4]) >>> 0;
        this.cmdLog.push(wasApp ? (0x80 | cmd) : cmd);
        if ((cb[5] >> 1) !== crc7(cb.slice(0, 5))) { this.out.push(0xFF, 0x09); return; }
        this.out.push(0xFF); // Ncr gap
        const idle = this.inIdle ? 0x01 : 0x00;
        if (wasApp) {
            if (cmd === 41) {
                this.acmd41Calls++;
                if (this.acmd41Calls >= this.acmd41Needed) { this.inIdle = false; this.out.push(0x00); }
                else this.out.push(0x01);
            } else this.out.push(idle);
            return;
        }
        switch (cmd) {
        case 0:
            if (!this.gotIdleClocks) { this.out = []; return; }
            this.inIdle = true; this.out.push(0x01); break;
        case 8:
            if (!this.v2) this.out.push(0x05); else this.out.push(0x01, 0, 0, 0x01, 0xAA); break;
        case 9: {
            const csd = Buffer.alloc(16);
            if (this.sdhc) {
                csd[0] = 0x40; csd[7] = (this.csizeV2 >> 16) & 0x3F; csd[8] = (this.csizeV2 >> 8) & 0xFF; csd[9] = this.csizeV2 & 0xFF;
            } else {
                csd[5] = 9; csd[6] = 0x03; csd[7] = 0xFF; csd[8] = 0xC0; csd[9] = 0x03; csd[10] = 0x80;
            }
            this.out.push(0x00, 0xFF); this._pushBlock(csd); break; }
        case 10:
            this.out.push(0x00, 0xFF);
            this._pushBlock(Buffer.from([0x03, 0x53, 0x44, 0x54, 0x45, 0x53, 0x54, 0x31, 0x10, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x23, 0x01]));
            break;
        case 12: this.streaming = false; this.out = [0xFF, 0x00, 0x00, 0x00, 0xFF]; break;
        case 13: this.out.push(0x00, 0x00); break;
        case 16: this.out.push(idle); break;
        case 17: { this.out.push(0x00, 0xFF); const b = this._blockOf(arg); this._pushBlock(this.mem.subarray(b * 512, (b + 1) * 512)); break; }
        case 18: this.out.push(0x00); this.streaming = true; this.streamBlock = this._blockOf(arg); break;
        case 24: case 25:
            this.out.push(0x00); this.state = 'WTOKEN'; this.wBlock = this._blockOf(arg); this.multiWrite = cmd === 25; break;
        case 32: case 33: this.out.push(0x00); break;
        case 38: this.out.push(0x00, 0x00, 0x00); break;
        case 55: this.appNext = true; this.out.push(idle); break;
        case 58: this.out.push(idle, this.sdhc ? 0xC0 : 0x80, 0xFF, 0x80, 0x00); break;
        case 59: this.crcOn = !!(arg & 1); this.out.push(idle); break;
        default: this.out.push(0x04); // illegal command
        }
    }

    _feed(b) {
        if (this.state === 'WTOKEN') {
            if (b === 0xFE || b === 0xFC) { this.state = 'WDATA'; this.wData = []; }
            else if (b === 0xFD) { this.state = 'CMD'; this.out.push(0xFF, 0x00, 0x00, 0xFF); return 0xFF; }
        } else if (this.state === 'WDATA') {
            this.wData.push(b);
            if (this.wData.length === 514) {
                const got = (this.wData[512] << 8) | this.wData[513];
                const ok = !this.crcOn || got === crc16(Buffer.from(this.wData.slice(0, 512)));
                if (ok) { Buffer.from(this.wData.slice(0, 512)).copy(this.mem, this.wBlock * 512); this.wBlock++; }
                this.out.push(ok ? 0xE5 : 0xEB, 0x00, 0x00, 0xFF);
                this.state = this.multiWrite ? 'WTOKEN' : 'CMD';
                return 0xFF; // the data response follows on the NEXT byte clock
            }
        } else if (this.cmdBuf.length || (b & 0xC0) === 0x40) {
            this.cmdBuf.push(b);
            if (this.cmdBuf.length === 6) { this._doCommand(); this.cmdBuf = []; }
            return 0xFF;
        }
        if (this.out.length) return this.out.shift();
        if (this.streaming && this.state === 'CMD') {
            this._pushBlock(this.mem.subarray(this.streamBlock * 512, (this.streamBlock + 1) * 512));
            this.streamBlock++;
            return this.out.shift();
        }
        return 0xFF;
    }
}

(async () => {
    checkTrue('crc16 check value', crc16(Buffer.from('123456789')) === 0x31C3);
    checkTrue('crc7 of CMD0 frame -> 0x95', ((crc7(Buffer.from([0x40, 0, 0, 0, 0])) << 1) | 1) === 0x95);

    { // init v2 SDHC
        const m = new SdModel();
        const sd = new SDSPIMinimal(m);
        await sd.init();
        checkTrue('SDHC init succeeds', sd.cardType() === CardType.SDHC);
        checkTrue('80 idle clocks sent before CMD0', m.gotIdleClocks);
        checkTrue('block count from CSD v2', sd.blockCount() === 8192);
        checkTrue('speed raised to 12 MHz after init', m.speed === 12000000);
        checkTrue('no CS release mid-command', m.csTogglesInCommand === 0);
    }
    { // init v1
        const m = new SdModel(false, false);
        const sd = new SDSPIMinimal(m);
        await sd.init();
        checkTrue('SDv1 detected', sd.cardType() === CardType.SDV1);
        checkTrue('block count from CSD v1 (1 GiB)', sd.blockCount() === 2097152);
        checkTrue('CMD16 sent for byte-addressed card', m.cmdLog.includes(16));
    }
    { // no card
        const dead = {
            async idleClocks() {}, async setSpeed() { return true; }, async select() {}, async deselect() {},
            async write() {}, async read(n) { return Buffer.alloc(n, 0xFF); }, async readInto(b) { b.fill(0xFF); },
        };
        checkTrue('no card -> NotReady', await rejects(() => new SDSPIMinimal(dead).init(), NotReady));
    }
    { // minimal block io
        const sd = new SDSPIMinimal(new SdModel());
        await sd.init();
        const w = Buffer.from(Array.from({ length: 512 }, (_, i) => (i * 7 + 3) & 0xFF));
        await sd.writeBlock(5, w);
        checkTrue('block round-trip', (await sd.readBlock(5)).equals(w));
        checkTrue('read past end -> OutOfRange', await rejects(() => sd.readBlock(8192), OutOfRange));
    }
    { // full multi-block
        const sd = new SDSPIFull(new SdModel());
        await sd.init();
        checkTrue('geometry', sd.blockSize() === 512 && sd.eraseSize() === 512 && !sd.needsErase());
        checkTrue('implements BlockDevice', sd instanceof BlockDevice);
        checkTrue('info().name from CID PNM', sd.info().name === 'TEST1');
        const w = Buffer.from(Array.from({ length: 4 * 512 }, (_, k) => (k ^ (k >> 8)) & 0xFF));
        await sd.write(100, w);
        await sd.sync();
        checkTrue('multi-block round-trip', (await sd.read(100, 4)).equals(w));
        await sd.erase(100, 4);
        checkTrue('multi write past end -> OutOfRange', await rejects(() => sd.write(8190, w), OutOfRange));
        checkTrue('OCR CCS bit', ((await sd.ocr()) & 0x40000000) !== 0);
        checkTrue('is_present', await sd.isPresent());
    }
    { // crc error
        const m = new SdModel();
        const sd = new SDSPIFull(m);
        await sd.init();
        m.corruptReadCrc = true;
        checkTrue('corrupt read CRC16 -> Crc', await rejects(() => sd.readBlock(1), Crc));
        await sd.setCrc(false);
        checkTrue('CRC ignored when off', (await sd.readBlock(1)).length === 512);
    }
    { // block device contract shared with RAM
        const sd = new SDSPIFull(new SdModel());
        await sd.init();
        for (const [name, dev] of [['SD', sd], ['RAM', new RamBlockDevice(512, 64)]]) {
            const w = Buffer.alloc(512, 0xA0);
            await dev.write(3, w);
            checkTrue(`${name} contract: write+read`, (await dev.read(3, 1)).equals(w));
            checkTrue(`${name} contract: out of range`, await rejects(() => dev.read(dev.blockCount(), 1), OutOfRange));
        }
    }
    { // RAM NOR semantics
        const nor = new RamBlockDevice(512, 16, 8);
        checkTrue('NOR: erased reads 0xFF', (await nor.read(0, 1)).every(b => b === 0xFF));
        await nor.write(0, Buffer.alloc(512, 0));
        checkTrue('NOR: program without erase -> NotErased', await rejects(() => nor.write(0, Buffer.alloc(512, 1)), NotErased));
        await nor.erase(0, 8);
        await nor.write(0, Buffer.alloc(512, 2));
        checkTrue('NOR: erase then program', (await nor.read(0, 1))[0] === 2);
        checkTrue('NOR: unaligned erase -> OutOfRange', await rejects(() => nor.erase(1, 8), OutOfRange));
        const auto = new RamBlockDevice(512, 16, 8, true);
        await auto.write(2, Buffer.alloc(512, 1));
        await auto.write(2, Buffer.alloc(512, 2));
        checkTrue('NOR: auto_erase rewrites', (await auto.read(2, 1))[0] === 2);
    }

    console.log(`\n${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
})();
