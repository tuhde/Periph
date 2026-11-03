'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { UC1611sFull } = require('../../packages/periph/src/chips/display/uc1611s');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x38', 16);

let passed = 0;
let failed = 0;
function check(label, ok) {
    if (ok) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function run(chip) {
    check('geometry_240x128', chip.width === 240 && chip.height === 128);

    let s = await chip.readStatus();
    check('status_version_bit', (s[0] & 0x80) !== 0);
    check('status_product_code_1', (s[2] >> 4) === 1);
    check('status_display_enabled', (s[0] & 0x08) !== 0);
    check('status_mirror_x_bottom_view', (s[0] & 0x40) !== 0 && (s[0] & 0x20) === 0);

    await chip.sleep();
    check('sleep_clears_display_enable', ((await chip.readStatus())[0] & 0x08) === 0);
    await chip.wake();
    check('wake_sets_display_enable', ((await chip.readStatus())[0] & 0x08) !== 0);

    await chip.setFlip(false, true);
    s = await chip.readStatus();
    check('flip_my_status', (s[0] & 0x40) === 0 && (s[0] & 0x20) !== 0);
    await chip.setFlip(true, false);
    s = await chip.readStatus();
    check('flip_mx_status', (s[0] & 0x40) !== 0 && (s[0] & 0x20) === 0);

    // RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
    chip.fill(false);
    chip.drawLine(0, 0, 31, 7);
    chip.drawLine(0, 31, 31, 24);
    chip.drawRect(40, 4, 20, 20);
    await chip.show();
    let match0 = true;
    let match3 = true;
    for (let half = 0; half < 2; half++) {
        let ram = await chip.readRam(0, half * 32, 32);
        for (let x = 0; x < 32; x++) {
            let expected = 0;
            for (let b = 0; b < 8; b++) if (chip.getPixel(half * 32 + x, b)) expected |= 1 << b;
            if (ram[x] !== expected) match0 = false;
        }
        ram = await chip.readRam(3, half * 32, 32);
        for (let x = 0; x < 32; x++) {
            let expected = 0;
            for (let b = 0; b < 8; b++) if (chip.getPixel(half * 32 + x, 24 + b)) expected |= 1 << b;
            if (ram[x] !== expected) match3 = false;
        }
    }
    check('ram_readback_page0', match0);
    check('ram_readback_page3', match3);

    await chip.setContrast(0x70);
    await chip.setContrast(0x8F);
    await chip.setInvert(true);
    await chip.setInvert(false);
    await chip.setAllOn(true);
    await chip.setAllOn(false);
    await chip.setScrollLine(16);
    await chip.setScrollLine(0);
    await chip.setFixedLines(0);
    await chip.setBiasRatio(2);
    await chip.setTemperatureCompensation(1);
    await chip.setLineRate(3);
    await chip.setPump(true);
    await chip.setPanelLoading(true);
    await chip.setPartialDisplay(true, 0, 127);
    await chip.setPartialDisplay(false);
    await chip.setNLineInversion(0);
    await chip.setIsolationClock(1, 0);
    chip.setWritePattern(1);
    await chip.showPage(0);
    chip.setWritePattern(0);
    await chip.setDisplayPattern(1);
    await chip.setDisplayPattern(0);
    await chip.show();
    check('commands_accepted', true);

    await chip.reset();
    check('reset_restores_display_enable', ((await chip.readStatus())[0] & 0x08) !== 0);
    console.log(`INFO driver retries ${chip.retryCount()}, unrecovered ${chip.errorCount()}`);
    check('no_unrecovered_transfer_errors', chip.ok());
}

async function main() {
    const command = new I2CConnection(I2C_BUS, I2C_ADDR);
    const data = new I2CConnection(I2C_BUS, I2C_ADDR + 1);
    try {
        const chip = new UC1611sFull(command, data);
        await chip.ready();
        await run(chip);
    } catch (e) {
        console.log('FAIL exception: ' + e.message);
        failed++;
    }
    await command.close();
    await data.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
