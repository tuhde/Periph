'use strict';
// Unit test for the UC8276C driver — mocked display connection, no hardware.
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');
const { WireMode } = require('../../packages/periph/src/connection/display');
const {
    UC8276CMinimal, UC8276CFull, BLACK, WHITE, RED, MODE_KW,
} = require('../../packages/periph/src/chips/display/uc8276c');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
async function rejects(fn) { try { await fn(); return false; } catch (e) { return true; } }
function throwsSync(fn) { try { fn(); return false; } catch (e) { return true; } }
const cmd = (conn, i) => ({ cmd: conn.commands[i].cmd, data: Buffer.from(conn.commands[i].data) });
const isCmd = (conn, i, c, bytes) => conn.commands[i].cmd === c && Buffer.from(conn.commands[i].data).equals(Buffer.from(bytes));

async function main() {
    // --- initialisation ---
    let conn = new DisplayConnectionMock();
    let d = new UC8276CMinimal(conn);
    await d.ready();
    checkTrue('init: reset then wait BUSY_N (active low)',
        conn.events[0].type === 'reset' && conn.events[1].type === 'waitBusy'
        && conn.events[1].busyActiveHigh === false && conn.events[1].timeoutMs === 30000);
    checkTrue('init: PSR 0x0F then CDI 0xD7, no TRES at 400x300',
        conn.commands.length === 2 && isCmd(conn, 0, 0x00, [0x0F]) && isCmd(conn, 1, 0x50, [0xD7]));

    conn = new DisplayConnectionMock();
    await new UC8276CMinimal(conn, { width: 200, height: 100 }).ready();
    checkTrue('init: TRES for a smaller panel', isCmd(conn, 1, 0x61, [0x00, 0xC8, 0x00, 0x64]));

    checkTrue('width not a multiple of 8', throwsSync(() => new UC8276CMinimal(new DisplayConnectionMock(), { width: 101 })));
    checkTrue('width above 400', throwsSync(() => new UC8276CMinimal(new DisplayConnectionMock(), { width: 408 })));
    checkTrue('height above 300', throwsSync(() => new UC8276CMinimal(new DisplayConnectionMock(), { height: 301 })));
    const bad = new DisplayConnectionMock();
    bad.ready = false;
    checkTrue('init timeout rejects', await rejects(() => new UC8276CMinimal(bad).ready()));

    // --- framebuffer ---
    conn = new DisplayConnectionMock();
    d = new UC8276CFull(conn, { width: 16, height: 2 });
    await d.ready();
    checkTrue('white after init', d.getPixel(0, 0) === WHITE);
    d.setPixel(0, 0, BLACK);
    d.setPixel(9, 1, RED);
    checkTrue('black pixel', d.getPixel(0, 0) === BLACK);
    checkTrue('red pixel', d.getPixel(9, 1) === RED);
    checkTrue('bw byte 0 bit 7 cleared', d._bw[0] === 0x7F);
    checkTrue('red plane bit for x=9', d._red[3] === 0x40 && d._bw[3] === 0xBF);
    d.setPixel(-1, 0); d.setPixel(16, 0); d.setPixel(0, 2);
    checkTrue('out of range ignored', d.getPixel(0, 0) === BLACK);
    d.setPixel(0, 0, WHITE);
    checkTrue('white overwrites', d.getPixel(0, 0) === WHITE);

    // --- show sequence ---
    conn = new DisplayConnectionMock();
    d = new UC8276CMinimal(conn, { width: 16, height: 2 });
    await d.ready();
    let n = conn.events.length;
    d.setPixel(0, 0, RED);
    await d.show();
    let tail = conn.events.slice(n);
    checkTrue('show: DTM1 + bw bytes',
        tail[0].type === 'command' && tail[0].cmd === 0x10 && tail[1].type === 'data'
        && tail[1].data.equals(Buffer.from([0x7F, 0xFF, 0xFF, 0xFF])));
    checkTrue('show: DTM2 + red plane',
        tail[2].cmd === 0x13 && tail[3].data.equals(Buffer.from([0x80, 0, 0, 0])));
    checkTrue('show: PON wait DRF wait POF wait',
        [tail[4].cmd, tail[5].type, tail[6].cmd, tail[7].type, tail[8].cmd, tail[9].type].join()
        === '4,waitBusy,18,waitBusy,2,waitBusy');

    conn = new DisplayConnectionMock();
    d = new UC8276CMinimal(conn, { width: 16, height: 2 });
    await d.ready();
    n = conn.events.length;
    await d.show();
    checkTrue('show without red sends zeros', conn.events[n + 3].data.equals(Buffer.alloc(4)));

    // --- sleep / wake ---
    conn = new DisplayConnectionMock();
    d = new UC8276CMinimal(conn);
    await d.sleep();
    checkTrue('sleep: DSLP 0xA5', isCmd(conn, conn.commands.length - 1, 0x07, [0xA5]));
    const resets = conn.resetCount;
    await d.show();
    checkTrue('show after sleep resets and re-initialises', conn.resetCount === resets + 1);

    // --- timeouts ---
    conn = new DisplayConnectionMock();
    d = new UC8276CMinimal(conn, { width: 16, height: 2 });
    await d.ready();
    conn.ready = false;
    checkTrue('show timeout rejects', await rejects(() => d.show()));

    // --- settings ---
    conn = new DisplayConnectionMock();
    d = new UC8276CFull(conn, { width: 16, height: 2 });
    await d.ready();
    const base = conn.commands.length;
    await d.setMode(MODE_KW);
    await d.setFlip(true, false);
    checkTrue('setMode sets PSR.KW/R', isCmd(conn, base, 0x00, [0x1F]));
    checkTrue('setFlip clears SHL, keeps UD', isCmd(conn, base + 1, 0x00, [0x1B]));
    await d.setPower(10.0, 10.0, 10.0, 16.0);
    checkTrue('setPower layout', isCmd(conn, base + 2, 0x01, [0x03, 0x00, 0x26, 0x26, 0x26]));
    await d.setPower(2.4, 11.0, 5.0, 13.0);
    checkTrue('setPower gate level and clamps', isCmd(conn, base + 3, 0x01, [0x03, 0x03, 0x00, 0x2B, 0x0D]));
    await d.setVcomDc(-1.0);
    checkTrue('setVcomDc code', isCmd(conn, base + 4, 0x82, [0x12]));
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
    checkTrue('setInterval default = 0xD7', isCmd(conn, base + 9, 0x50, [0xD7]));
    checkTrue('setTemperatureSensor external, offset -3', isCmd(conn, base + 10, 0x41, [0x8D]));
    await d.writeLut(Buffer.alloc(56), null, Buffer.alloc(56), null, null);
    checkTrue('writeLut sends given tables then PSR.REG',
        conn.commands[base + 11].cmd === 0x20 && conn.commands[base + 12].cmd === 0x22
        && isCmd(conn, base + 13, 0x00, [0x3B]));
    await d.sendCommand(0xAB, [0x01]);
    checkTrue('raw command', isCmd(conn, conn.commands.length - 1, 0xAB, [0x01]));

    // --- drawing ---
    d.clear(WHITE);
    d.drawLine(0, 0, 1, 1);
    checkTrue('line diagonal', d.getPixel(0, 0) === BLACK && d.getPixel(1, 1) === BLACK);
    d.drawRect(4, 0, 4, 2, RED, true);
    checkTrue('filled rect', d.getPixel(4, 0) === RED && d.getPixel(7, 1) === RED);
    d.clear(WHITE);
    d.drawRect(0, 0, 4, 2);
    checkTrue('outline rect', d.getPixel(0, 0) === 0 && d.getPixel(3, 1) === 0 && d.getPixel(4, 0) === 1);
    d.clear(WHITE);
    d.blit(8, 0, [0xA0], 4, 1);
    checkTrue('blit 1010', d.getPixel(8, 0) === 0 && d.getPixel(9, 0) === 1 && d.getPixel(10, 0) === 0 && d.getPixel(11, 0) === 1);
    d.clear(BLACK);
    checkTrue('clear black', d.getPixel(5, 1) === BLACK);
    d.clear(RED);
    checkTrue('clear red', d.getPixel(5, 1) === RED);

    // --- partial window ---
    conn = new DisplayConnectionMock();
    d = new UC8276CFull(conn, { width: 32, height: 4 });
    await d.ready();
    n = conn.events.length;
    await d.showRegion(9, 1, 6, 2);
    tail = conn.events.slice(n);
    checkTrue('showRegion: PTIN then PTL widened to byte columns',
        tail[0].cmd === 0x91 && tail[1].cmd === 0x90
        && tail[1].data.equals(Buffer.from([0, 8, 0, 15, 0, 1, 0, 2, 0])));
    checkTrue('showRegion: DTM1 window rows', tail[2].cmd === 0x10 && tail[3].type === 'data' && tail[4].type === 'data');
    checkTrue('showRegion ends with POF wait and PTOUT',
        tail[tail.length - 1].cmd === 0x92 && tail[tail.length - 2].type === 'waitBusy');
    checkTrue('showRegion empty window', await rejects(() => d.showRegion(0, 0, 0, 1)));
    checkTrue('showRegion outside panel', await rejects(() => d.showRegion(40, 0, 4, 4)));

    // --- auto ---
    conn = new DisplayConnectionMock();
    d = new UC8276CFull(conn, { width: 16, height: 2 });
    await d.refreshAuto();
    checkTrue('refreshAuto 0xA5', isCmd(conn, conn.commands.length - 1, 0x17, [0xA5]));
    await d.refreshAuto(true);
    checkTrue('refreshAuto deep sleep 0xA7', isCmd(conn, conn.commands.length - 1, 0x17, [0xA7]));
    const waited = await d.waitBusy(10);
    const last = conn.events[conn.events.length - 1];
    checkTrue('waitBusy delegates with BUSY_N polarity',
        waited === true && last.type === 'waitBusy' && last.busyActiveHigh === false && last.timeoutMs === 10);

    // --- reads ---
    conn = new DisplayConnectionMock({ wireMode: WireMode.HALF_DUPLEX_PIN });
    d = new UC8276CFull(conn, { width: 16, height: 2 });
    await d.ready();
    conn.queueRead([0x05]);
    checkTrue('readStatus', (await d.readStatus()) === 5 && conn.commands[conn.commands.length - 1].cmd === 0x71);
    conn.queueRead([0xE7, 0x00]);
    checkTrue('readTemperature signed', (await d.readTemperature()) === -25);
    conn.queueRead([0x19]);
    checkTrue('readRevision 1-byte form', (await d.readRevision()).join() === ',25');
    conn.queueRead([0x00, 0x02, 0x01, 0x02, 0x03]);
    checkTrue('readRevision 5-byte form', (await d.readRevision(5)).join() === `2,${0x030201}`);
    conn.queueRead([0x0A]);
    checkTrue('readVcom', Math.abs((await d.readVcom()) - (-0.60)) < 1e-9);
    conn.queueRead([0x00]);
    checkTrue('readLowPower true when LPD = 0', (await d.readLowPower()) === true);
    conn.queueRead([0xFF, 0x11, 0x22]);
    checkTrue('readOtp drops the dummy byte', (await d.readOtp(2)).equals(Buffer.from([0x11, 0x22])));

    conn = new DisplayConnectionMock({ wireMode: WireMode.SIMPLEX });
    d = new UC8276CFull(conn, { width: 16, height: 2 });
    checkTrue('reads fail in SIMPLEX', await rejects(() => d.readStatus()));

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}

main();
