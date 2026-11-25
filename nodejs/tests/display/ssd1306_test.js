'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SSD1306Full } = require('../../packages/periph/src/chips/display/ssd1306');

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

    // The SSD1306 cannot be read over its serial interfaces: the picture is checked by eye.
    chip.fill(false);
    chip.drawLine(0, 0, 31, 7);
    chip.drawLine(0, 31, 31, 24);
    chip.drawRect(40, 4, 20, 20);
    await chip.show();
    check('framebuffer_pixels', chip.getPixel(0, 0) && chip.getPixel(40, 4));

    await chip.setContrast(0x40);
    await chip.setContrast(0x7F);
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
    await chip.setClock(1, 8);
    await chip.setPrecharge(2, 2);
    await chip.setComConfig(true);
    await chip.setVcomh(0x20);
    await chip.setChargePump(true);
    await chip.setScrollHorizontal(true, 0, 7, 7);
    await chip.scrollStart();
    let refused = false;
    try { await chip.show(); } catch (e) { refused = true; }
    check('show_refused_while_scrolling', refused);
    await chip.scrollStop();
    await chip.setAddressingMode('horizontal');
    await chip.show();
    await chip.setAddressingMode('page');
    await chip.showPage(0);
    await chip.show();
    check('commands_accepted', true);
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    try {
        const chip = new SSD1306Full(connection);
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
