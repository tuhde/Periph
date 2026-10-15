'use strict';
// Unit test for the UC8159 driver — mocked display connection, no hardware.
const { DisplayConnectionMock } = require('../../packages/periph/src/connection/display_mock');
const { WireMode } = require('../../packages/periph/src/connection/display');
const {
    UC8159Minimal, UC8159Full, BLACK, WHITE, GREEN, BLUE, RED, YELLOW, ORANGE,
} = require('../../packages/periph/src/chips/display/uc8159');

let passed = 0;
let failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
async function rejects(fn) { try { await fn(); return false; } catch (e) { return true; } }
const isCmd = (conn, i, c, bytes) => conn.commands[i].cmd === c && Buffer.from(conn.commands[i].data).equals(Buffer.from(bytes));

async function main() {
    let conn = new DisplayConnectionMock();
    let d = new UC8159Minimal(conn);
    await d.ready();
    checkTrue('init: reset then wait BUSY (active low, 60 s)',
        conn.events[0].type === 'reset' && conn.events[1].type === 'waitBusy'
        && conn.events[1].busyActiveHigh === false && conn.events[1].timeoutMs === 60000);
    checkTrue('init: vendor command sequence', conn.commands.length === 10
        && isCmd(conn, 0, 0x00, [0xEF, 0x08]) && isCmd(conn, 1, 0x01, [0x37, 0x00, 0x23, 0x23])
        && isCmd(conn, 2, 0x03, [0x00]) && isCmd(conn, 3, 0x06, [0xC7, 0xC7, 0x1D])
        && isCmd(conn, 4, 0x30, [0x3C]) && isCmd(conn, 5, 0x41, [0x00]) && isCmd(conn, 6, 0x50, [0x37])
        && isCmd(conn, 7, 0x60, [0x22]) && isCmd(conn, 8, 0x61, [0x02, 0x58, 0x01, 0xC0])
        && isCmd(conn, 9, 0xE3, [0xAA]));
    checkTrue('geometry', d.width === 600 && d.height === 448);
    const bad = new DisplayConnectionMock();
    bad.ready = false;
    checkTrue('init timeout rejects', await rejects(() => new UC8159Minimal(bad).ready()));

    conn = new DisplayConnectionMock();
    d = new UC8159Full(conn);
    await d.ready();
    checkTrue('white after init', d.getPixel(0, 0) === WHITE && d.getPixel(599, 447) === WHITE);
    for (let c = 0; c < 8; c++) d.setPixel(7 + c, 3, c);
    let ok = true;
    for (let c = 0; c < 8; c++) ok = ok && d.getPixel(7 + c, 3) === c;
    checkTrue('eight colours roundtrip', ok);
    checkTrue('neighbours untouched', d.getPixel(6, 3) === WHITE && d.getPixel(15, 3) === WHITE);
    d.setPixel(599, 447, ORANGE);
    checkTrue('last pixel', d.getPixel(599, 447) === ORANGE && d.getPixel(598, 447) === WHITE);
    d.setPixel(-1, 0, RED); d.setPixel(600, 0, RED); d.setPixel(0, 448, RED);
    checkTrue('out of range ignored', d.getPixel(0, 0) === WHITE && d.getPixel(700, 9) === BLACK);
    d.clear(GREEN);
    checkTrue('clear green', d.getPixel(0, 0) === GREEN && d.getPixel(333, 222) === GREEN && d.getPixel(599, 447) === GREEN);
    d.clear();
    checkTrue('clear default white', d.getPixel(1, 1) === WHITE);

    conn = new DisplayConnectionMock();
    d = new UC8159Minimal(conn);
    await d.ready();
    d.setPixel(0, 0, BLACK); d.setPixel(1, 0, ORANGE); d.setPixel(599, 447, RED);
    const n = conn.events.length;
    await d.show();
    const tail = conn.events.slice(n);
    checkTrue('show starts with DTM1', tail[0].cmd === 0x10);
    const data = Buffer.concat(tail.filter((e) => e.type === 'data').map((e) => Buffer.from(e.data)));
    checkTrue('frame is 134400 bytes', data.length === 134400);
    checkTrue('first bytes black/orange then white', data[0] === 0x06 && data[1] === 0x11);
    checkTrue('last byte white/red', data[134399] === 0x14);
    const ctl = tail.filter((e) => e.type !== 'data');
    checkTrue('tail PON wait DRF wait POF wait',
        ctl.slice(-6).map((e) => (e.type === 'command' ? e.cmd : e.type)).join() === '4,waitBusy,18,waitBusy,2,waitBusy');

    conn = new DisplayConnectionMock();
    d = new UC8159Minimal(conn);
    await d.sleep();
    checkTrue('sleep: DSLP 0xA5', isCmd(conn, conn.commands.length - 1, 0x07, [0xA5]));
    const resets = conn.resetCount;
    await d.show();
    checkTrue('show after sleep resets and re-initialises', conn.resetCount === resets + 1);

    conn = new DisplayConnectionMock();
    d = new UC8159Minimal(conn);
    await d.ready();
    conn.ready = false;
    checkTrue('show timeout rejects', await rejects(() => d.show()));

    conn = new DisplayConnectionMock();
    d = new UC8159Full(conn);
    await d.ready();
    const base = conn.commands.length;
    await d.setFlip(true, false);
    await d.setFlip(false, true);
    await d.setFlip(false, false);
    checkTrue('flip', isCmd(conn, base, 0x00, [0xEB, 0x08]) && isCmd(conn, base + 1, 0x00, [0xE7, 0x08])
        && isCmd(conn, base + 2, 0x00, [0xEF, 0x08]));
    await d.setFrameRate(0x3A);
    checkTrue('frame rate code', isCmd(conn, base + 3, 0x30, [0x3A]));
    await d.setInterval(7);
    await d.setInterval(3, BLACK, 0);
    checkTrue('interval', isCmd(conn, base + 4, 0x50, [0x37]) && isCmd(conn, base + 5, 0x50, [0x03]));
    await d.setTemperatureSensor(true, -3);
    checkTrue('temperature sensor', isCmd(conn, base + 6, 0x41, [0x8D]));
    await d.setVcomDc(-0.10); await d.setVcomDc(-2.0); await d.setVcomDc(-9.0);
    checkTrue('vcom_dc codes', isCmd(conn, base + 7, 0x82, [0x02]) && isCmd(conn, base + 8, 0x82, [0x28])
        && isCmd(conn, base + 9, 0x82, [0x50]));
    await d.sendCommand(0x13, [0x00]);
    checkTrue('raw command', isCmd(conn, base + 10, 0x13, [0x00]));

    d.drawLine(0, 0, 9, 9, RED);
    checkTrue('line diagonal', d.getPixel(0, 0) === RED && d.getPixel(9, 9) === RED && d.getPixel(5, 4) === WHITE);
    d.clear();
    d.drawRect(1, 1, 5, 4, BLUE);
    checkTrue('rect outline', d.getPixel(1, 1) === BLUE && d.getPixel(5, 4) === BLUE && d.getPixel(3, 2) === WHITE);
    d.drawRect(10, 1, 3, 3, YELLOW, true);
    checkTrue('rect filled', d.getPixel(11, 2) === YELLOW && d.getPixel(13, 3) === WHITE);
    d.clear();
    d.blit(4, 2, [0xA0, 0x40], 4, 2, GREEN);
    checkTrue('blit', d.getPixel(4, 2) === GREEN && d.getPixel(5, 2) === WHITE && d.getPixel(6, 2) === GREEN
        && d.getPixel(5, 3) === GREEN);

    conn = new DisplayConnectionMock({ wireMode: WireMode.HALF_DUPLEX_PIN });
    d = new UC8159Full(conn);
    await d.ready();
    conn.queueRead([0x0F]);
    checkTrue('readStatus', (await d.readStatus()) === 0x0F && conn.commands[conn.commands.length - 1].cmd === 0x71);
    conn.queueRead([0xE7, 0x00]);
    checkTrue('readTemperature signed', (await d.readTemperature()) === -25);
    conn.queueRead([0x01]);
    checkTrue('readLowPower false when normal', (await d.readLowPower()) === false);
    conn.queueRead([0x0C]);
    checkTrue('readVcom', Math.abs((await d.readVcom()) - (-0.60)) < 1e-9);
    conn = new DisplayConnectionMock({ wireMode: WireMode.SIMPLEX });
    d = new UC8159Full(conn);
    checkTrue('reads fail in SIMPLEX', await rejects(() => d.readStatus()));

    console.log(`${passed} passed, ${failed} failed`);
    process.exit(failed ? 1 : 0);
}

main();
