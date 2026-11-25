'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { PCF8579Minimal, PCF8579Full } = require('../../packages/periph/src/chips/display/pcf8579');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x3C', 16);

let passed = 0, failed = 0;

function check(label, condition) {
    if (condition) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

    // Minimal: construct (clears RAM, display on), pixels, columns.
    const lcd = new PCF8579Minimal(connection, 0, 16);
    try {
        await lcd.clear();
        await lcd.setPixel(0, 0);
        await lcd.setPixel(39, 15);
        await lcd.setPixel(45, 30); // out of range, ignored
        await lcd.writeColumns(2, 1, [0xFF, 0x81, 0xFF]);
        check('minimal_draw', true);
    } catch (e) { console.log('FAIL minimal_draw: ' + e.message); failed++; }

    // Full: exercise every method; readRam verifies the bytes really landed.
    const full = new PCF8579Full(connection, 0, 16);
    try {
        await full.writeRam(16, 0, [0xAA, 0x55, 0x0F]);
        const got = await full.readRam(16, 0, 3);
        check('ram_roundtrip', Buffer.from(got).equals(Buffer.from([0xAA, 0x55, 0x0F])));
    } catch (e) { console.log('FAIL ram_roundtrip: ' + e.message); failed++; }

    try {
        await full.fill(true);
        await full.fill(false);
        await full.drawLine(0, 0, 39, 15);
        await full.drawRect(0, 0, 40, 16);
        await full.drawRect(4, 4, 8, 8, true, true);
        await full.showBitmap(0, 0, [0x18, 0x24, 0x42, 0x81]);
        check('graphics', await full.getPixel(0, 0));
    } catch (e) { console.log('FAIL graphics: ' + e.message); failed++; }

    try {
        await full.setDisplay(PCF8579Full.DISPLAY_INVERSE);
        await full.setDisplay(PCF8579Full.DISPLAY_ALL_ON);
        await full.setDisplay(PCF8579Full.DISPLAY_NORMAL);
        await full.setStartBank(1);
        await full.scroll(1);
        await full.scroll(-2);
                await full.writeRam(16, 0, new Array(8).fill(0), PCF8579Full.ACCESS_HALF_GRAPHIC);
        await full.writeRam(16, 0, new Array(8).fill(0), PCF8579Full.ACCESS_FULL_GRAPHIC);
        await full.setMode(24, false);
        await full.setMode(16, false);
        await full.reset();
        check('configuration', true);
    } catch (e) { console.log('FAIL configuration: ' + e.message); failed++; }

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
}

main();
