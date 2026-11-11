'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SH1106Full } = require('../../packages/periph/src/chips/display/sh1106');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x3C', 16);

let passed = 0;
let failed = 0;
function check(label, ok) {
    if (ok) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function run(chip) {
    check('geometry_128x64', chip.width === 128 && chip.height === 64);

    let s = await chip.readStatus();
    check('status_not_busy', (s & 0x80) === 0);
    check('status_display_on', (s & 0x40) === 0);

    await chip.sleep();
    check('sleep_sets_off_bit', ((await chip.readStatus()) & 0x40) !== 0);
    await chip.wake();
    check('wake_clears_off_bit', ((await chip.readStatus()) & 0x40) === 0);

    // RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.drawLine(0, 0, 31, 7);
    chip.drawLine(0, 31, 31, 24);
    chip.drawRect(40, 4, 20, 20);
    await chip.show();
    let match0 = true;
    let match3 = true;
    for (let half = 0; half < 2; half++) {
        let ram = await chip.readPage(0, 2 + half * 16, 16);
        for (let x = 0; x < 16; x++) {
            let expected = 0;
            for (let b = 0; b < 8; b++) if (chip.getPixel(half * 16 + x, b)) expected |= 1 << b;
            if (ram[x] !== expected) match0 = false;
        }
        ram = await chip.readPage(3, 2 + half * 16, 16);
        for (let x = 0; x < 16; x++) {
            let expected = 0;
            for (let b = 0; b < 8; b++) if (chip.getPixel(half * 16 + x, 24 + b)) expected |= 1 << b;
            if (ram[x] !== expected) match3 = false;
        }
    }
    check('ram_readback_page0', match0);
    check('ram_readback_page3', match3);

    await chip.setContrast(0x40);
    await chip.setContrast(0x80);
    await chip.setInvert(true);
    await chip.setInvert(false);
    await chip.setAllOn(true);
    await chip.setAllOn(false);
    await chip.setFlip(true, true);
    await chip.setFlip(false, false);
    await chip.setStartLine(16);
    await chip.setStartLine(0);
    await chip.setDisplayOffset(0);
    await chip.setMultiplex(64);
    await chip.setClock(1, 5);
    await chip.setPrecharge(2, 2);
    await chip.setComConfig(true);
    await chip.setVcomDeselect(0x35);
    await chip.setPumpVoltage(0);
    await chip.showPage(0);
    await chip.show();
    s = await chip.readStatus();
    check('commands_accepted', (s & 0x80) === 0);
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    try {
        const chip = new SH1106Full(connection);
        await chip.ready();
        await run(chip);
    } catch (e) {
        console.log('FAIL exception: ' + e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
