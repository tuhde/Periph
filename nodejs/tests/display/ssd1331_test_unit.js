'use strict';
// Unit test for the SSD1331 driver — mocked display connection, no hardware.
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');
const S = require('../../packages/periph/src/chips/display/ssd1331');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// Command/parameter bytes as numbers; data events as ['D', n]; resets/delays as strings/arrays.
function stream(conn, start = 0) {
    const out = [];
    for (const e of conn.events.slice(start)) {
        if (e.type === 'command') {
            if (e.data.length) throw new Error('parameters must be separate commands (D/C# low)');
            out.push(e.cmd);
        } else if (e.type === 'data') out.push(['D', e.data.length]);
        else if (e.type === 'delay') out.push(['d', e.ms]);
        else if (e.type === 'reset') out.push('R');
    }
    return out;
}
const dataBytes = (conn, start = 0) => conn.events.slice(start).filter((e) => e.type === 'data')
    .reduce((n, e) => n + e.data.length, 0);

async function main() {
    // --- initialisation ---
    let conn = new DisplayConnectionMock();
    let d = new S.SSD1331Minimal(conn);
    await d.ready();
    checkTrue('init sequence (vendor)', eq(stream(conn), [
        'R', ['d', 100], 0xAE, 0x81, 0xFF, 0x82, 0xFF, 0x83, 0xFF, 0x87, 0x06,
        0x8A, 0x64, 0x8B, 0x78, 0x8C, 0x64, 0xA0, 0x72, 0xA1, 0x00, 0xA2, 0x00, 0xA4,
        0xA8, 0x3F, 0xAD, 0x8E, 0xB0, 0x0B, 0xB1, 0x31, 0xB3, 0xF0, 0xBB, 0x3A, 0xBE, 0x3E,
        0x2E, 0x25, 0x00, 0x00, 0x5F, 0x3F, ['d', 100], 0xAF]));
    checkTrue('init sends no pixel data', dataBytes(conn) === 0);
    checkTrue('geometry', d.width === 96 && d.height === 64);
    checkTrue('framebuffer 12288 bytes zero', d._fb.length === 12288 && d._fb.every((b) => b === 0));

    // --- framebuffer ---
    conn = new DisplayConnectionMock();
    d = new S.SSD1331Full(conn);
    await d.ready();
    d.setPixel(0, 0, S.RED);
    checkTrue('pixel RGB565 high byte first', d._fb[0] === 0xF8 && d._fb[1] === 0x00);
    d.setPixel(95, 63, S.BLUE);
    checkTrue('last pixel position', d._fb[12286] === 0x00 && d._fb[12287] === 0x1F);
    d.setPixel(1, 2, 0x1234);
    checkTrue('index and readback', d._fb[(2 * 96 + 1) * 2] === 0x12 && d.getPixel(1, 2) === 0x1234);
    d.setPixel(-1, 0); d.setPixel(0, -1); d.setPixel(96, 0); d.setPixel(0, 64);
    checkTrue('out of range ignored', d._fb[0] === 0xF8 && d.getPixel(99, 99) === 0);
    d.clear(S.GREEN);
    checkTrue('clear fills', d.getPixel(0, 0) === S.GREEN && d.getPixel(95, 63) === S.GREEN);
    checkTrue('rgb packing', S.rgb(255, 0, 0) === 0xF800 && S.rgb(0, 255, 0) === 0x07E0
        && S.rgb(0, 0, 255) === 0x001F && S.rgb(255, 255, 255) === 0xFFFF);
    d.clear();
    d.drawLine(0, 0, 4, 4, S.WHITE);
    checkTrue('line diagonal', d.getPixel(0, 0) === S.WHITE && d.getPixel(2, 2) === S.WHITE
        && d.getPixel(4, 4) === S.WHITE && d.getPixel(4, 0) === 0);
    d.clear();
    d.drawRect(2, 2, 4, 3, S.WHITE);
    checkTrue('rect outline', d.getPixel(2, 2) === S.WHITE && d.getPixel(5, 4) === S.WHITE
        && d.getPixel(3, 3) === 0 && d.getPixel(6, 2) === 0);
    d.drawRect(10, 10, 3, 3, S.RED, true);
    checkTrue('rect filled', d.getPixel(11, 11) === S.RED && d.getPixel(13, 13) === 0);
    d.blit(20, 20, [1, 2, 3, 4], 2, 2);
    checkTrue('blit', eq([d.getPixel(20, 20), d.getPixel(21, 20), d.getPixel(20, 21), d.getPixel(21, 21)], [1, 2, 3, 4]));

    // --- show / showRegion ---
    conn = new DisplayConnectionMock();
    d = new S.SSD1331Full(conn);
    await d.ready();
    d.clear(S.RED);
    let n = conn.events.length;
    await d.show();
    let s = stream(conn, n);
    checkTrue('show window then rows', eq(s.slice(0, 6), [0x15, 0, 0x5F, 0x75, 0, 0x3F]) && eq(s[6], ['D', 192]) && s.length === 6 + 64);
    checkTrue('show sends 12288 bytes', dataBytes(conn, n) === 12288);
    checkTrue('show first data byte', conn.events[n + 6].data[0] === 0xF8);
    n = conn.events.length;
    await d.showRegion(10, 20, 4, 2);
    checkTrue('showRegion window and rows', eq(stream(conn, n), [0x15, 10, 13, 0x75, 20, 21, ['D', 8], ['D', 8]]));
    n = conn.events.length;
    await d.showRegion(90, 60, 20, 20);
    checkTrue('showRegion clipped', eq(stream(conn, n).slice(0, 6), [0x15, 90, 95, 0x75, 60, 63]) && dataBytes(conn, n) === 48);
    n = conn.events.length;
    await d.showRegion(200, 0, 5, 5);
    checkTrue('showRegion off panel no-op', conn.events.length === n);

    // --- hardware primitives and settings ---
    conn = new DisplayConnectionMock();
    d = new S.SSD1331Full(conn);
    await d.ready();
    async function run(label, fn, expect) {
        const start = conn.events.length;
        await fn();
        checkTrue(label, eq(stream(conn, start), expect));
    }
    await run('hwLine red', () => d.hwLine(1, 2, 3, 4, S.RED), [0x21, 1, 2, 3, 4, 0x3E, 0, 0]);
    await run('hwRect filled', () => d.hwRect(10, 20, 5, 6, S.GREEN, S.BLUE, true),
        [0x26, 1, 0x22, 10, 20, 14, 25, 0, 0x3F, 0, 0, 0, 0x3E]);
    await run('hwRect fill off', () => d.hwRect(10, 20, 5, 6, S.WHITE),
        [0x26, 0, 0x22, 10, 20, 14, 25, 0x3E, 0x3F, 0x3E, 0, 0, 0]);
    await run('hwRect zero size no-op', () => d.hwRect(10, 20, 0, 6, S.WHITE), []);
    await run('hwClear', () => d.hwClear(0, 1, 2, 3), [0x25, 0, 1, 2, 3]);
    await run('hwDim', () => d.hwDim(0, 1, 2, 3), [0x24, 0, 1, 2, 3]);
    await run('hwCopy', () => d.hwCopy(1, 2, 3, 4, 5, 6), [0x23, 1, 2, 3, 4, 5, 6]);
    await run('scroll interval masked and started', () => d.scroll(1, 0, 64, 2, 7), [0x27, 1, 0, 64, 2, 3, 0x2F]);
    await run('scrollStop', () => d.scrollStop(), [0x2E]);
    await run('contrast', () => d.setContrast(1, 2, 3), [0x81, 1, 0x82, 2, 0x83, 3]);
    await run('master current 16', () => d.setMasterCurrent(16), [0x87, 0x0F]);
    await run('master current clamped low', () => d.setMasterCurrent(0), [0x87, 0x00]);
    await run('mode inverse', () => d.setDisplayMode(S.MODE_INVERSE), [0xA7]);
    await run('flip none keeps other bits', () => d.setFlip(false, false), [0xA0, 0x60]);
    await run('flip both', () => d.setFlip(true, true), [0xA0, 0x72]);
    await run('power save on', () => d.setPowerSave(true), [0xB0, 0x1A]);
    await run('power save off', () => d.setPowerSave(false), [0xB0, 0x0B]);
    await run('dim mode precharge masked', () => d.dimMode(1, 2, 3, 0xFF), [0xAB, 0, 1, 2, 3, 0x1F, 0xAC]);
    await run('display off/on', async () => { await d.displayOff(); await d.displayOn(); }, [0xAE, 0xAF]);
    await run('gray linear', () => d.grayLinear(), [0xB9]);
    await run('raw command params as commands', () => d.sendCommand(0xAA, [0x12, 0x34]), [0xAA, 0x12, 0x34]);

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}
main();
