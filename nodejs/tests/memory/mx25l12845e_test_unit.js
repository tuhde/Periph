"use strict";
const { MX25L12845EMinimal, MX25L12845EFull, SIZE } = require('../../packages/periph/src/chips/memory/mx25l12845e');
const { BlockDevice, NotReady, OutOfRange, NotErased, WriteProtected, Timeout } = require('../../packages/periph/src/storage/block_device');
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

// Behavioural MX25L12845E: 16 MiB array programmable 1 -> 0 only, page wrap on PP, 4/32/64 KiB and chip
// erase, WEL/WIP handling (WIP reads high for a few RDSR polls after every program/erase), BP block protect
// with P_FAIL/E_FAIL, secured OTP, deep power-down, RES/REMS/RDID/SFDP. Every command opcode is recorded.
class FlashModel {
    constructor() {
        this.mem = Buffer.alloc(SIZE, 0xFF);
        this.otp = Buffer.alloc(512, 0xFF);
        for (let i = 0; i < 16; i++) this.otp[i] = 0xA0 + i;
        Object.assign(this, {
            inOtp: false, wel: false, dp: false, otpLocked: false, sr: 0, scur: 0x01, busyPolls: 0, busyAfterOp: 2,
            stuckBusy: false, cmds: [], pp: 0, se: 0, be32: 0, be64: 0, ce: 0,
        });
        this.id = Buffer.from([0xC2, 0x20, 0x18]);
    }
    _protectedFrom() {
        const bp = (this.sr >> 2) & 0xF;
        if (bp === 0) return SIZE;
        if (bp >= 8) return 0;
        return SIZE - (65536 << bp);
    }
    _isProtected(a, n) { return a + n > this._protectedFrom(); }
    _startBusy() { this.busyPolls = this.busyAfterOp; this.wel = false; }
    async write(d) { this._frame(Buffer.from(d), 0); }
    async writeRead(d, n) { return this._frame(Buffer.from(d), n); }
    _frame(d, n) {
        const op = d[0];
        this.cmds.push(op);
        const out = Buffer.alloc(n);
        if (this.dp && op !== 0xAB) return out;
        const busy = this.stuckBusy || this.busyPolls > 0;
        if (busy && ![0x05, 0x2B, 0x9F, 0x30].includes(op)) return out;
        const a = d.length >= 4 ? (d[1] << 16) | (d[2] << 8) | d[3] : 0;
        switch (op) {
        case 0x06: this.wel = true; break;
        case 0x04: this.wel = false; break;
        case 0x9F: for (let i = 0; i < Math.min(n, 3); i++) out[i] = this.id[i]; break;
        case 0x05:
            out[0] = (this.sr & 0xFC) | (this.wel ? 2 : 0) | (busy ? 1 : 0);
            if (this.busyPolls > 0) this.busyPolls--;
            break;
        case 0x01:
            if (this.wel && d.length >= 2) { this.sr = d[1] & 0xFC; this._startBusy(); }
            break;
        case 0x03: case 0x0B: case 0x5A:
            for (let i = 0; i < n; i++) {
                if (op === 0x5A) out[i] = (a + i) & 0xFF;
                else if (this.inOtp) out[i] = this.otp[(a + i) & 0x1FF];
                else out[i] = this.mem[(a + i) & 0xFFFFFF];
            }
            break;
        case 0x02:
            if (this.wel) {
                const data = d.subarray(4);
                this.pp++;
                if (this.inOtp) {
                    if (this.otpLocked) this.scur |= 0x20;
                    else data.forEach((b, i) => { this.otp[(a + i) & 0x1FF] &= b; });
                } else if (this._isProtected(a & ~255, 256)) this.scur |= 0x20;
                else {
                    const page = a & ~255;
                    data.forEach((b, i) => { this.mem[page + ((a + i) & 255)] &= b; });
                }
                this._startBusy();
            }
            break;
        case 0x20: case 0x52: case 0xD8:
            if (this.wel) {
                const size = op === 0x20 ? 4096 : op === 0x52 ? 32768 : 65536;
                const base = a - (a % size);
                if (op === 0x20) this.se++; else if (op === 0x52) this.be32++; else this.be64++;
                if (this._isProtected(base, size)) this.scur |= 0x40;
                else this.mem.fill(0xFF, base, base + size);
                this._startBusy();
            }
            break;
        case 0xC7: case 0x60:
            if (this.wel) {
                this.ce++;
                if ((this.sr >> 2) & 0xF) this.scur |= 0x40;
                else this.mem.fill(0xFF);
                this._startBusy();
            }
            break;
        case 0xB9: this.dp = true; break;
        case 0xAB: this.dp = false; if (n) out[0] = 0x17; break;
        case 0x90: if (n >= 2) { out[0] = 0xC2; out[1] = 0x17; } break;
        case 0xB1: this.inOtp = true; break;
        case 0xC1: this.inOtp = false; break;
        case 0x2B: out[0] = this.scur; break;
        case 0x2F:
            if (this.wel) { this.scur |= 0x02; this.otpLocked = true; this._startBusy(); }
            break;
        case 0x30: this.scur &= ~0x60; break;
        default: break;
        }
        return out;
    }
}

async function open(Cls, model, options) {
    const f = new Cls(model, options);
    await f.init();
    return f;
}

async function testInit() {
    const m = new FlashModel();
    const f = await open(MX25L12845EMinimal, m);
    checkTrue('init ok on C2 20 18', (await f.jedecId()).toString('hex') === 'c22018');
    checkTrue('size 16 MiB', f.size() === 16 * 1024 * 1024);
    const wrong = new FlashModel();
    wrong.id = Buffer.from([0xC2, 0x20, 0x17]);
    checkTrue('wrong ID -> NotReady', await rejects(() => open(MX25L12845EMinimal, wrong), NotReady));
    const stuck = new FlashModel();
    stuck.stuckBusy = true;
    checkTrue('chip stuck busy -> Timeout at init', await rejects(() => open(MX25L12845EMinimal, stuck, { initTimeoutMs: 20 }), Timeout));
    const asleep = new FlashModel();
    asleep.dp = true;
    await open(MX25L12845EMinimal, asleep);
    checkTrue('init releases deep power-down (RDP)', !asleep.dp);
}

async function testMinimalWrite() {
    const m = new FlashModel();
    const f = await open(MX25L12845EMinimal, m);
    const pattern = Buffer.from(Array.from({ length: 600 }, (_, i) => (i * 7 + 3) & 0xFF));
    await f.writeBytes(0x1F0, pattern);
    checkTrue('write across a page boundary into blank flash', (await f.readBytes(0x1F0, 600)).equals(pattern));
    checkTrue('blank flash: no erase needed', m.se === 0);
    checkTrue('neighbouring bytes untouched', m.mem[0x1EF] === 0xFF && m.mem[0x1F0 + 600] === 0xFF);
    const ppBefore = m.pp;
    await f.writeBytes(0x1F0, pattern);
    checkTrue('identical rewrite programs and erases nothing', m.pp === ppBefore && m.se === 0);
    const more = Buffer.from(pattern.map((b) => b & 0xF0));
    await f.writeBytes(0x1F0, more);
    checkTrue('1->0 only rewrite needs no erase', (await f.readBytes(0x1F0, 600)).equals(more) && m.se === 0);
    await f.writeBytes(0x1F0, Buffer.alloc(10, 0xFF));
    checkTrue('0->1 needs a sector erase', m.se === 1 && (await f.readBytes(0x1F0, 10)).equals(Buffer.alloc(10, 0xFF)));
    checkTrue('rest of the sector survived the erase', (await f.readBytes(0x200, 20)).equals(more.subarray(16, 36)));
    const big = Buffer.from(Array.from({ length: 9000 }, (_, i) => (i ^ (i >> 8)) & 0xFF));
    await f.writeBytes(0x2FF0, big);
    checkTrue('multi-sector write + read back', (await f.readBytes(0x2FF0, 9000)).equals(big));
    checkTrue('write past the end', await rejects(() => f.writeBytes(SIZE - 2, big.subarray(0, 5)), OutOfRange));
    await f.eraseSector(0x2000);
    checkTrue('erase_sector', m.mem[0x2000] === 0xFF);
}

async function testProtection() {
    const m = new FlashModel();
    const f = await open(MX25L12845EFull, m);
    checkTrue('BP = 0 at power-up', (await f.protection()) === 0);
    await f.setProtection(8);
    checkTrue('BP reads back', (await f.protection()) === 8);
    checkTrue('program into protected area -> WriteProtected', await rejects(() => f.pageProgram(0x100, Buffer.from([0])), WriteProtected));
    checkTrue('protected data unchanged', m.mem[0x100] === 0xFF);
    checkTrue('fail flags cleared by the driver (CLSR)', ((await f.readSecurity()) & 0x60) === 0);
    checkTrue('erase of protected sector -> WriteProtected', await rejects(() => f.eraseSector(0), WriteProtected));
    checkTrue('info().readOnly for BP >= 8', f.info().readOnly);
    checkTrue('BP > 15 rejected', await rejects(() => f.setProtection(16), OutOfRange));
    await f.setProtection(0);
    await f.pageProgram(0x100, Buffer.from([0]));
    checkTrue('program after unprotect', m.mem[0x100] === 0x00);
}

async function testRawFull() {
    const m = new FlashModel();
    const f = await open(MX25L12845EFull, m);
    const data = Buffer.from(Array.from({ length: 300 }, (_, i) => i & 0xFF));
    await f.pageProgram(0x1000, data.subarray(0, 256));
    checkTrue('page_program 256', (await f.readBytes(0x1000, 256)).equals(data.subarray(0, 256)));
    checkTrue('page-crossing program refused', await rejects(() => f.pageProgram(0x10C0, data.subarray(0, 100)), OutOfRange));
    checkTrue('page_program > 256 refused', await rejects(() => f.pageProgram(0, data.subarray(0, 257)), OutOfRange));
    checkTrue('READ and FAST_READ agree', (await f.fastRead(0x1000, 256)).equals(await f.readBytes(0x1000, 256)));
    await f.eraseBlock32k(0x8000);
    await f.eraseBlock64k(0x10000);
    checkTrue('32 / 64 KiB block erase', m.be32 === 1 && m.be64 === 1);
    await f.chipErase();
    checkTrue('chip erase', m.ce === 1 && m.mem[0x1000] === 0xFF);
    const ids = await f.deviceIds();
    checkTrue('RES / REMS ids', (await f.electronicId()) === 0x17 && ids[0] === 0xC2 && ids[1] === 0x17);
    await f.deepPowerDown();
    checkTrue('deep power-down', m.dp);
    await f.releaseDeepPowerDown();
    checkTrue('release deep power-down', !m.dp);
    checkTrue('read_sfdp', (await f.readSfdp(0, 8))[1] === 1);
    checkTrue('idle after operations', !(await f.isBusy()));
    await f.writeStatus(0x04);
    checkTrue('write_status', m.sr === 0x04);
    await f.writeStatus(0);
}

async function testOtp() {
    const m = new FlashModel();
    const f = await open(MX25L12845EFull, m);
    const esn = await f.serialNumber();
    checkTrue('factory serial number', esn[0] === 0xA0 && esn[15] === 0xAF);
    checkTrue('OTP mode exited', !m.inOtp);
    await f.programOtp(16, Buffer.from([1, 2, 3, 4]));
    checkTrue('program_otp + read_otp', (await f.readOtp(16, 4)).equals(Buffer.from([1, 2, 3, 4])));
    checkTrue('ESN area refused', await rejects(() => f.programOtp(8, Buffer.from([1])), OutOfRange));
    checkTrue('past the end of the OTP refused', await rejects(() => f.programOtp(510, Buffer.from([1, 2, 3, 4])), OutOfRange));
    await f.lockOtp();
    checkTrue('lock_otp (WRSCUR)', m.otpLocked);
}

async function testBlockDevice() {
    const m = new FlashModel();
    const f = await open(MX25L12845EFull, m);
    checkTrue('is a BlockDevice', f instanceof BlockDevice);
    checkTrue('geometry', f.blockSize() === 512 && f.blockCount() === 32768 && f.eraseSize() === 4096 && f.needsErase());
    checkTrue('info() name', f.info().name === 'MX25L12845E');
    const w = Buffer.from(Array.from({ length: 1024 }, (_, k) => (k * 5 + 1) & 0xFF));
    await f.write(8, w);
    checkTrue('write + read blocks (autoErase off)', (await f.read(8, 2)).equals(w));
    await f.write(8, w);
    checkTrue('identical rewrite is fine', (await f.read(8, 2)).equals(w));
    checkTrue('0->1 without autoErase -> NotErased', await rejects(() => f.write(8, Buffer.alloc(512, 0xFF)), NotErased));
    checkTrue('NotErased did not erase anything', m.se === 0);
    await f.erase(8, 8);
    checkTrue('erase aligned sector', m.mem[8 * 512] === 0xFF);
    checkTrue('unaligned erase -> OutOfRange', await rejects(() => f.erase(9, 8), OutOfRange));
    checkTrue('erase past the end', await rejects(() => f.erase(32768 - 8, 16), OutOfRange));
    checkTrue('read past the end', await rejects(() => f.read(32768, 1), OutOfRange));
    await f.sync();
    const [se0, be640, be320] = [m.se, m.be64, m.be32];
    await f.erase(0, 128 + 64 + 8);
    checkTrue('erase picks 64K, then 32K, then 4K', m.be64 - be640 === 1 && m.be32 - be320 === 1 && m.se - se0 === 1);
    const m2 = new FlashModel();
    const g = await open(MX25L12845EFull, m2, { autoErase: true });
    await g.write(0, w);
    await g.write(0, Buffer.alloc(512, 0xFF));
    checkTrue('autoErase handles 0->1', m2.se === 1 && (await g.read(0, 1)).equals(Buffer.alloc(512, 0xFF)));
    checkTrue('neighbouring block kept', (await g.read(1, 1)).equals(w.subarray(512)));
    const ram = new RamBlockDevice(512, 64, 8);
    for (const [name, dev, blk] of [['flash', g, 64], ['RAM', ram, 0]]) {
        await dev.erase(blk, 8);
        const x = Buffer.alloc(512, 0x5A);
        await dev.write(blk, x);
        checkTrue(`contract ${name}: erase+write+read`, (await dev.read(blk, 1)).equals(x));
        checkTrue(`contract ${name}: out of range`, await rejects(() => dev.read(dev.blockCount(), 1), OutOfRange));
    }
}

async function testTimeout() {
    const m = new FlashModel();
    const f = await open(MX25L12845EFull, m);
    m.stuckBusy = true;
    checkTrue('wait_ready times out', await rejects(() => f.waitReady(5), Timeout));
    checkTrue('is_busy true while stuck', await f.isBusy());
    m.stuckBusy = false;
}

(async () => {
    await testInit();
    await testMinimalWrite();
    await testProtection();
    await testRawFull();
    await testOtp();
    await testBlockDevice();
    await testTimeout();
    console.log(`\n${passed} passed, ${failed} failed`);
    if (failed) process.exit(1);
})();
