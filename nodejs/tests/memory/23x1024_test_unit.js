"use strict";
const { SRAM23X1024Minimal, SRAM23X1024Full } = require('../../packages/periph/src/chips/memory/_23x1024');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function bytesEqual(buf, arr) {
    if (buf.length !== arr.length) return false;
    for (let i = 0; i < arr.length; i++) if (buf[i] !== arr[i]) return false;
    return true;
}

async function rejects(fn, ErrType) {
    try { await fn(); } catch (e) { return ErrType ? e instanceof ErrType : true; }
    return false;
}

// Behavioural 23X1024: parses the instruction frames the driver sends and
// models the array, the MODE register and the Byte/Page/Sequential
// address-increment rules. Every frame is recorded on `frames`.
class SramModel {
    constructor() {
        this.mem = Buffer.alloc(131072);
        this.frames = [];
        this.mode = 0x40;
    }
    _next(addr) {
        if ((this.mode & 0xC0) === 0x80) return (addr & ~31) | ((addr + 1) & 31);
        return (addr + 1) & 0x1FFFF;
    }
    async write(data) {
        const d = Buffer.from(data);
        this.frames.push(d);
        if (d[0] === 0x01 && d.length === 2) this.mode = d[1];
        else if (d[0] === 0x02 && d.length >= 4) {
            let addr = (d[1] << 16) | (d[2] << 8) | d[3];
            for (let i = 4; i < d.length; i++) {
                this.mem[addr] = d[i];
                addr = this._next(addr);
                if ((this.mode & 0xC0) === 0x00) break;
            }
        }
    }
    async writeRead(data, n) {
        const d = Buffer.from(data);
        this.frames.push(d);
        const out = Buffer.alloc(n);
        if (d[0] === 0x05) out[0] = this.mode;
        else if (d[0] === 0x03 && d.length === 4) {
            let addr = (d[1] << 16) | (d[2] << 8) | d[3];
            for (let i = 0; i < n; i++) {
                out[i] = this.mem[addr];
                addr = this._next(addr);
                if ((this.mode & 0xC0) === 0x00) break;
            }
        }
        return out;
    }
}

(async () => {
    // --- init: WRMR 0x40 then RDMR ---
    const conn = new SramModel();
    const chip = new SRAM23X1024Minimal(conn);
    await chip.init();
    checkTrue('init_two_frames', conn.frames.length === 2);
    checkTrue('init_wrmr_sequential', bytesEqual(conn.frames[0], [0x01, 0x40]));
    checkTrue('init_rdmr', bytesEqual(conn.frames[1], [0x05]));

    // --- init fails on a MODE mismatch (floating MISO) ---
    const dead = new SramModel();
    dead.writeRead = async (data, n) => Buffer.alloc(n);
    checkTrue('init_rejects_on_bad_mode', await rejects(() => new SRAM23X1024Minimal(dead).init(), Error));

    // --- Minimal byte access: frame layout + round trip ---
    await chip.writeByte(0x1ABCD, 0x5A);
    checkTrue('write_byte_frame', bytesEqual(conn.frames[conn.frames.length - 1], [0x02, 0x01, 0xAB, 0xCD, 0x5A]));
    checkTrue('write_byte_stored', conn.mem[0x1ABCD] === 0x5A);
    checkTrue('read_byte_roundtrip', (await chip.readByte(0x1ABCD)) === 0x5A);
    checkTrue('read_byte_frame', bytesEqual(conn.frames[conn.frames.length - 1], [0x03, 0x01, 0xAB, 0xCD]));
    await chip.writeByte(0x1FFFF, 0x77);
    checkTrue('last_address', (await chip.readByte(0x1FFFF)) === 0x77);
    checkTrue('read_byte_out_of_range', await rejects(() => chip.readByte(0x20000), RangeError));

    // --- Full: block access ---
    const fc = new SramModel();
    const full = new SRAM23X1024Full(fc);
    await full.init();
    checkTrue('size', full.size() === 131072);
    const pattern = Buffer.from(Array.from({ length: 600 }, (_, i) => (i * 7 + 3) & 0xFF));
    await full.write(0x100, pattern);
    checkTrue('write_block', fc.mem.subarray(0x100, 0x100 + 600).equals(pattern));
    checkTrue('read_block', (await full.read(0x100, 600)).equals(pattern));
    checkTrue('write_chunked_256', fc.frames.filter(f => f[0] === 0x02).length === 3);

    full.maxChunk = 100;
    checkTrue('set_max_chunk', full.maxChunk === 100);
    fc.frames.length = 0;
    await full.write(0, pattern.subarray(0, 250));
    checkTrue('write_chunked_custom', fc.frames.map(f => f.length).join() === '104,104,54');
    full.maxChunk = 256;
    let threw = false;
    try { full.maxChunk = 257; } catch (e) { threw = e instanceof RangeError; }
    checkTrue('max_chunk_rejects_257', threw);

    // --- fill ---
    await full.fill(0x2000, 100, 0xA5);
    checkTrue('fill_range_only', fc.mem.subarray(0x2000, 0x2064).every(b => b === 0xA5) && fc.mem[0x2064] === 0 && fc.mem[0x1FFF] === 0);
    await full.fill(0x3000, 5);
    checkTrue('fill_default_zero', fc.mem[0x3000] === 0 && fc.mem[0x3004] === 0);

    // --- Range checks ---
    checkTrue('read_past_end', await rejects(() => full.read(0x1FFFF, 2), RangeError));
    checkTrue('write_past_end', await rejects(() => full.write(0x1FFFF, [1, 2]), RangeError));
    checkTrue('fill_past_end', await rejects(() => full.fill(0x1FFFF, 2), RangeError));

    // --- Page mode: transfers split at 32-byte boundaries, linear semantics preserved ---
    await full.setMode(SRAM23X1024Full.MODE_PAGE);
    checkTrue('set_mode_page', fc.mode === 0x80 && (await full.getMode()) === SRAM23X1024Full.MODE_PAGE);
    fc.frames.length = 0;
    const data64 = Buffer.from(Array.from({ length: 64 }, (_, i) => 0xC0 + i));
    await full.write(0x4010, data64);
    checkTrue('page_write_split_frames', fc.frames.length === 3);
    checkTrue('page_write_linear', fc.mem.subarray(0x4010, 0x4050).equals(data64) && fc.mem[0x4050] === 0);
    checkTrue('page_read_linear', (await full.read(0x4010, 64)).equals(data64));

    // --- Byte mode: one byte per transaction ---
    await full.setMode(SRAM23X1024Full.MODE_BYTE);
    fc.frames.length = 0;
    await full.write(0x500, data64.subarray(0, 4));
    checkTrue('byte_mode_frame_per_byte', fc.frames.length === 4);
    checkTrue('byte_mode_read', (await full.read(0x500, 4)).equals(data64.subarray(0, 4)));
    await full.setMode(SRAM23X1024Full.MODE_SEQUENTIAL);
    checkTrue('set_mode_sequential', (await full.getMode()) === SRAM23X1024Full.MODE_SEQUENTIAL);
    checkTrue('set_mode_rejects_reserved', await rejects(() => full.setMode(0xC0), RangeError));

    // --- getMode masks reserved bits ---
    fc.mode = 0x4F;
    checkTrue('get_mode_masks_reserved', (await full.getMode()) === SRAM23X1024Full.MODE_SEQUENTIAL);
    fc.mode = 0x40;

    // --- resetIo sends 0xFF and resyncs the cached mode ---
    fc.frames.length = 0;
    await full.resetIo();
    checkTrue('reset_io_frame', bytesEqual(fc.frames[0], [0xFF]));

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
})();
