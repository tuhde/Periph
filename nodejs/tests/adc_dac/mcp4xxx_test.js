'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Mcp4xxxFull, defaultAddress } = require('../../packages/periph/src/chips/adc_dac/mcp4xxx');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const PART = process.env.MCP4XXX_PART || 'MCP4532';
const I2C_ADDR = parseInt(process.env.I2C_ADDR || defaultAddress(PART).toString(16), 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log('FAIL %s: got %s, expected %s', label, got, expected);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip = new Mcp4xxxFull(connection, PART);
    await chip.init();
    const maxCode = chip.steps() - 1;

    // --- Wiper write / readback ---
    await chip.setWiper(100 % (maxCode + 1));
    checkEq('set/get wiper', await chip.getWiper(), 100 % (maxCode + 1));
    await chip.setWiper(9999);
    checkEq('clamps high', await chip.getWiper(), maxCode);
    await chip.setWiper(-5);
    checkEq('clamps low', await chip.getWiper(), 0);
    if (chip.channels() === 2) {
        await chip.setBoth(10, 20);
        checkEq('set_both ch0', await chip.getWiper(0), 10);
        checkEq('set_both ch1', await chip.getWiper(1), 20);
    }

    // --- Resistance and ratio ---
    await chip.setResistance(5075.0);
    checkTrue('set_resistance ~mid', Math.abs(await chip.getWiper() - maxCode / 2) <= 1);
    checkTrue('get_resistance ~5075', Math.abs(await chip.getResistance() - 5075.0) < 100.0);
    await chip.setRatio(0.5);
    checkTrue('get_ratio ~0.5', Math.abs(await chip.getRatio() - 0.5) < 0.01);
    if (chip.isPotentiometer()) {
        await chip.setWiper(0);
        checkTrue('R_WA at zero scale ~RAB', Math.abs(await chip.getResistanceWa() - 10075.0) < 100.0);
    }

    // --- Increment / decrement (continuous) ---
    await chip.setWiper(maxCode - 3);
    checkEq('increment saturates', await chip.increment(10), maxCode);
    await chip.setWiper(3);
    checkEq('decrement saturates', await chip.decrement(10), 0);
    checkEq('increment 1', await chip.increment(), 1);

    // --- Shutdown / terminals ---
    await chip.setWiper(Math.floor(maxCode / 4));
    await chip.shutdown();
    checkTrue('is_shutdown', await chip.isShutdown());
    await chip.resume();
    checkTrue('not shutdown', !(await chip.isShutdown()));
    checkEq('wiper kept across shutdown', await chip.getWiper(), Math.floor(maxCode / 4));
    await chip.setTerminals(true, false, true);
    const t = await chip.getTerminals();
    checkTrue('terminals', t.a && !t.w && t.b);
    await chip.setTerminals();
    checkEq('tcon restored', (await chip.getTcon()) & 0xFF, 0xFF);

    // --- Non-volatile parts ---
    if (chip.isNonvolatile()) {
        checkTrue('status no busy', !(await chip.readStatus()).eepromBusy);
        await chip.setWiper(maxCode / 2);
        checkEq('store_wiper', await chip.storeWiper(), maxCode / 2);
        checkEq('get_nv_wiper', await chip.getNvWiper(), maxCode / 2);
        await chip.writeData(0, 0x155);
        checkEq('data roundtrip', await chip.readData(0), 0x155);
    }

    // --- Reset ---
    await chip.setWiper(1);
    await chip.reset();
    checkEq('reset wiper', await chip.getWiper(), maxCode / 2);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    connection.close();
    process.exit(failed === 0 ? 0 : 1);
}

main();
