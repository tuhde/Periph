'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SAA1064Minimal, SAA1064Full } = require('../../packages/periph/src/chips/led/saa1064');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

let passed = 0, failed = 0;

function check(label, ok, extra) {
    if (ok) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label + (extra ? ': ' + extra : '')); failed++; }
}

async function attempt(label, fn) {
    try { await fn(); check(label, true); }
    catch (e) { check(label, false, e.message); }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const display = new SAA1064Minimal(connection);
    await display.ready;
    check('constants', SAA1064Minimal.DIGITS === 4);
    await attempt('show_digits', () => display.showDigits([1, 2, 3, 4]));
    await attempt('show_number', () => display.showNumber(1234));
    await attempt('write_raw', () => display.writeRaw([0x7F, 0x7F], 3));
    await attempt('clear', () => display.clear());

    const full = new SAA1064Full(connection);
    await full.ready;
    await attempt('set_mode', async () => { await full.setMode(false); await full.setMode(true); });
    await attempt('blank', async () => { await full.blank(true, false); await full.blank(false, true); await full.blank(false, false); });
    await attempt('set_current', async () => { await full.setCurrent(3); await full.setCurrent(21); await full.setCurrent(12); });
    await attempt('segment_test', async () => { await full.segmentTest(true); await full.segmentTest(false); });
    await attempt('set_segment_map', async () => full.setSegmentMap({ a: 0, b: 1, c: 2, d: 3, e: 4, f: 5, g: 6, dp: 7 }));
    await attempt('show_digit', () => full.showDigit(1, 5, true));
    await attempt('set_decimal_point', async () => { await full.setDecimalPoint(1, false); await full.setDecimalPoint(4, true); });
    await attempt('read_status', () => full.readStatus());
    check('power_reset_flag_cleared', (await full.powerResetFlag()) === false);
    await attempt('write_control', () => full.writeControl(0x47));
    await full.clear();

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.log('FAIL fatal: ' + e.message); process.exit(1); });
