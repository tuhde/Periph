'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HT16K33Minimal, HT16K33Full } = require('../../packages/periph/src/chips/led/ht16k33');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x70', 16);

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
    const matrix = new HT16K33Minimal(connection);
    await matrix.ready;
    check('constants', HT16K33Minimal.COMMONS === 8 && HT16K33Minimal.ROWS === 16);
    await attempt('set_pixel_show', async () => { matrix.setPixel(0, 0); matrix.setPixel(7, 15); await matrix.show(); });
    await attempt('write_raw', () => matrix.writeRaw([0xFF, 0x00], 0));
    await attempt('set_brightness', async () => { await matrix.setBrightness(0); await matrix.setBrightness(15); });
    await attempt('clear', () => matrix.clear());

    const full = new HT16K33Full(connection);
    await full.ready;
    full.setPixel(3, 4);
    await full.show();
    check('get_pixel', full.getPixel(3, 4) && !full.getPixel(3, 5));
    await attempt('blink', async () => { for (const f of [2, 1, 0.5, 0]) await full.setBlink(f); });
    await attempt('display_off_on', async () => { await full.displayOff(); await full.displayOn(); });
    await attempt('standby_wake', async () => { await full.standby(); await full.wake(); });
    await attempt('set_int_output', async () => {
        await full.setIntOutput(true); await full.setIntOutput(true, true); await full.setIntOutput(false);
    });
    await attempt('read_keys', () => full.readKeys());
    await attempt('key_pressed', () => full.keyPressed());
    await full.clear();

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
