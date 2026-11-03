'use strict';
// Unit test for the IL0373 driver — mocked display connection, no hardware.
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');
const { WireMode } = require('../../packages/periph/src/connection/display');
const {
    IL0373Minimal, IL0373Full, BLACK, WHITE,
} = require('../../packages/periph/src/chips/display/il0373');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
async function rejects(fn) { try { await fn(); return false; } catch (e) { return true; } }
function throwsSync(fn) { try { fn(); return false; } catch (e) { return true; } }
const isCmd = (conn, i, c, bytes) => conn.commands[i].cmd === c && Buffer.from(conn.commands[i].data).equals(Buffer.from(bytes));

async function main() {
    // --- initialisation ---
    let conn = new DisplayConnectionMock();
    let d = new IL0373Minimal(conn);
    await d.ready();
    checkTrue('init: reset then wait BUSY (active low)',
        conn.events[0].type === 'reset' && conn.events[1].type === 'waitBusy'
        && conn.events[1].busyActiveHigh === false && conn.events[1].timeoutMs === 30000);
    checkTrue('init: PWR, BTST, PON, PSR 0x1F, TRES 104x212, CDI 0x97',
        conn.commands.length === 6 && isCmd(conn, 0, 0x01, [0x03, 0x00, 0x2B, 0x2B, 0x03])
        && isCmd(conn, 1, 0x06, [0x17, 0x17, 0x17]) && isCmd(conn, 2, 0x04, [])
        && isCmd(conn, 3, 0x00, [0x1F]) && isCmd(conn, 4, 0x61, [0x68, 0x00, 0xD4]) && isCmd(conn, 5, 0x50, [0x97]));
    checkTrue('init: PON settles 200 ms', conn.events[5].type === 'waitBusy' && conn.events[5].timeoutMs === 200);
    checkTrue('default size', d.width === 104 && d.height === 212);

    conn = new DisplayConnectionMock();
    await new IL0373Minimal(conn, { width: 160, height: 296 }).ready();
    checkTrue('init: TRES for the largest panel', isCmd(conn, 4, 0x61, [0xA0, 0x01, 0x28]));

    checkTrue('width not a multiple of 8', throwsSync(() => new IL0373Minimal(new DisplayConnectionMock(), { width: 101 })));
    checkTrue('width above 160', throwsSync(() => new IL0373Minimal(new DisplayConnectionMock(), { width: 168 })));
    checkTrue('height above 296', throwsSync(() => new IL0373Minimal(new DisplayConnectionMock(), { height: 297 })));
    const bad = new DisplayConnectionMock();
    bad.ready = false;
    checkTrue('init timeout rejects', await rejects(() => new IL0373Minimal(bad).ready()));

    // --- framebuffer ---
    conn = new DisplayConnectionMock();
    d = new IL0373Full(conn, { width: 16, height: 2 });
    await d.ready();
    checkTrue('white after init', d.getPixel(0, 0) === WHITE);
    d.setPixel(0, 0, BLACK);
    d.setPixel(9, 1, BLACK);
    checkTrue('black pixel', d.getPixel(0, 0) === BLACK);
    checkTrue('byte 0 bit 7 cleared', d._fb[0] === 0x7F);
    checkTrue('bit for x=9 cleared', d._fb[3] === 0xBF);
    d.setPixel(-1, 0); d.setPixel(16, 0); d.setPixel(0, 2);
    checkTrue('out of range ignored', d.getPixel(0, 0) === BLACK);
    d.setPixel(0, 0, WHITE);
    checkTrue('white overwrites', d.getPixel(0, 0) === WHITE);

    // --- show sequence ---
    conn = new DisplayConnectionMock();
    d = new IL0373Minimal(conn, { width: 16, height: 2 });
    await d.ready();
    let n = conn.events.length;
    d.setPixel(0, 0, BLACK);
    await d.show();
    let tail = conn.events.slice(n);
    checkTrue('show: DTM1 carries OLD = NOT NEW',
        tail[0].cmd === 0x10 && tail[1].type === 'data'
        && tail[1].data.equals(Buffer.from([0x80, 0x00, 0x00, 0x00])));
    checkTrue('show: DTM2 carries the framebuffer',
        tail[2].cmd === 0x13 && tail[3].data.equals(Buffer.from([0x7F, 0xFF, 0xFF, 0xFF])));
    checkTrue('show: DRF wait CDI 0xF7 POF wait',
        [tail[4].cmd, tail[5].type, tail[6].cmd, tail[7].cmd, tail[8].type].join() === '18,waitBusy,80,2,waitBusy'
        && Buffer.from(tail[6].data).equals(Buffer.from([0xF7])));
    n = conn.events.length;
    await d.show();
    tail = conn.events.slice(n);
    checkTrue('second show powers on first (PON, wait, CDI 0x97)',
        tail[0].cmd === 0x04 && tail[1].type === 'waitBusy' && tail[2].cmd === 0x50
        && Buffer.from(tail[2].data).equals(Buffer.from([0x97])) && tail[3].cmd === 0x10);

    // --- sleep / wake ---
    conn = new DisplayConnectionMock();
    d = new IL0373Minimal(conn);
    await d.sleep();
    const c = conn.commands.length;
    checkTrue('sleep: CDI 0xF7, POF, DSLP 0xA5',
        isCmd(conn, c - 3, 0x50, [0xF7]) && isCmd(conn, c - 2, 0x02, []) && isCmd(conn, c - 1, 0x07, [0xA5]));
    const resets = conn.resetCount;
    await d.show();
    checkTrue('show after sleep resets and re-initialises', conn.resetCount === resets + 1);

    // --- timeouts ---
    conn = new DisplayConnectionMock();
    d = new IL0373Minimal(conn, { width: 16, height: 2 });
    await d.ready();
    conn.ready = false;
    checkTrue('show timeout rejects', await rejects(() => d.show()));

    // --- settings ---
    conn = new DisplayConnectionMock();
    d = new IL0373Full(conn, { width: 16, height: 2 });
    await d.ready();
    const base = conn.commands.length;
    await d.setFlip(true, false);
    checkTrue('setFlip clears SHL, keeps UD', isCmd(conn, base, 0x00, [0x1B]));
    await d.setPower(14.0, 14.0, 20.0);
    checkTrue('setPower layout', isCmd(conn, base + 1, 0x01, [0x03, 0x00, 0x26, 0x26, 0x03]));
    await d.setPower(6.4, 15.0, 17.0);
    checkTrue('setPower gate level and clamps', isCmd(conn, base + 2, 0x01, [0x03, 0x03, 0x00, 0x2B, 0x03]));
    await d.setVcomDc(-1.0);
    checkTrue('setVcomDc code', isCmd(conn, base + 3, 0x82, [0x12]));
    await d.setVcomDc(-9.0);
    checkTrue('setVcomDc clamps', isCmd(conn, base + 4, 0x82, [0x3A]));
    await d.setFrameRate(50);
    await d.setFrameRate(200);
    await d.setFrameRate(30);
    checkTrue('setFrameRate 50 Hz = M7 N4', isCmd(conn, base + 5, 0x30, [0x3C]));
    checkTrue('setFrameRate 200 Hz = M7 N1', isCmd(conn, base + 6, 0x30, [0x39]));
    checkTrue('setFrameRate 30 Hz nearest = M1 N1', isCmd(conn, base + 7, 0x30, [0x09]));
    await d.setSoftStart(0x17, 0x17, 0x17);
    await d.setInterval(7);
    await d.setTemperatureSensor(true, -3);
    checkTrue('soft start', isCmd(conn, base + 8, 0x06, [0x17, 0x17, 0x17]));
    checkTrue('setInterval default = 0x97', isCmd(conn, base + 9, 0x50, [0x97]));
    checkTrue('setTemperatureSensor external, offset -3', isCmd(conn, base + 10, 0x41, [0x8D]));
    await d.writeLut(0, Buffer.alloc(44));
    await d.writeLut(4, Buffer.alloc(42));
    checkTrue('writeLut sends the table then PSR.REG',
        conn.commands[base + 11].cmd === 0x20 && conn.commands[base + 11].data.length === 44
        && isCmd(conn, base + 12, 0x00, [0x3B]) && conn.commands[base + 13].cmd === 0x24);
    checkTrue('writeLut bad table', await rejects(() => d.writeLut(5, [])));
    await d.sendCommand(0xAB, [0x01]);
    checkTrue('raw command', isCmd(conn, conn.commands.length - 1, 0xAB, [0x01]));

    // --- drawing ---
    d.clear(WHITE);
    d.drawLine(0, 0, 1, 1);
    checkTrue('line diagonal', d.getPixel(0, 0) === BLACK && d.getPixel(1, 1) === BLACK);
    d.drawRect(4, 0, 4, 2, BLACK, true);
    checkTrue('filled rect', d.getPixel(4, 0) === BLACK && d.getPixel(7, 1) === BLACK);
    d.clear(WHITE);
    d.drawRect(0, 0, 4, 2);
    checkTrue('outline rect', d.getPixel(0, 0) === 0 && d.getPixel(3, 1) === 0 && d.getPixel(4, 0) === 1);
    d.clear(WHITE);
    d.blit(8, 0, [0xA0], 4, 1);
    checkTrue('blit 1010', d.getPixel(8, 0) === 0 && d.getPixel(9, 0) === 1 && d.getPixel(10, 0) === 0 && d.getPixel(11, 0) === 1);
    d.clear(BLACK);
    checkTrue('clear black', d.getPixel(5, 1) === BLACK);

    // --- partial window ---
    conn = new DisplayConnectionMock();
    d = new IL0373Full(conn, { width: 32, height: 4 });
    await d.ready();
    n = conn.events.length;
    await d.showRegion(9, 1, 6, 2);
    tail = conn.events.slice(n);
    checkTrue('showRegion: PTIN then PTL widened to byte columns',
        tail[0].cmd === 0x91 && tail[1].cmd === 0x90
        && tail[1].data.equals(Buffer.from([8, 15, 0, 1, 0, 2, 0])));
    checkTrue('showRegion: DTM1 window rows inverted',
        tail[2].cmd === 0x10 && tail[3].data.equals(Buffer.from([0x00])) && tail[4].data.equals(Buffer.from([0x00])));
    checkTrue('showRegion: DTM2 window rows', tail[5].cmd === 0x13 && tail[6].data.equals(Buffer.from([0xFF])));
    checkTrue('showRegion ends with POF wait and PTOUT',
        tail[tail.length - 1].cmd === 0x92 && tail[tail.length - 2].type === 'waitBusy');
    checkTrue('showRegion empty window', await rejects(() => d.showRegion(0, 0, 0, 1)));
    checkTrue('showRegion outside panel', await rejects(() => d.showRegion(40, 0, 4, 4)));
    const waited = await d.waitBusy(10);
    const last = conn.events[conn.events.length - 1];
    checkTrue('waitBusy delegates with BUSY polarity',
        waited === true && last.type === 'waitBusy' && last.busyActiveHigh === false && last.timeoutMs === 10);

    // --- reads ---
    conn = new DisplayConnectionMock({ wireMode: WireMode.HALF_DUPLEX_PIN });
    d = new IL0373Full(conn, { width: 16, height: 2 });
    await d.ready();
    conn.queueRead([0x05]);
    checkTrue('readStatus', (await d.readStatus()) === 5 && conn.commands[conn.commands.length - 1].cmd === 0x71);
    conn.queueRead([0xE7, 0x00]);
    checkTrue('readTemperature signed', (await d.readTemperature()) === -25);
    conn.queueRead([0x19]);
    checkTrue('readRevision', (await d.readRevision()) === 0x19);
    conn.queueRead([0x0A]);
    checkTrue('readVcom', Math.abs((await d.readVcom()) - (-0.60)) < 1e-9);
    conn.queueRead([0x00]);
    checkTrue('readLowPower true when LPD = 0', (await d.readLowPower()) === true);
    conn.queueRead([0xFF, 0x11, 0x22]);
    checkTrue('readOtp drops the dummy byte', (await d.readOtp(2)).equals(Buffer.from([0x11, 0x22])));

    conn = new DisplayConnectionMock({ wireMode: WireMode.SIMPLEX });
    d = new IL0373Full(conn, { width: 16, height: 2 });
    checkTrue('reads fail in SIMPLEX', await rejects(() => d.readStatus()));

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}

main();
