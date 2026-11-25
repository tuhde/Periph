'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AD5248Full }   = require('../../packages/periph/src/chips/adc_dac/ad5248');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x2C', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log('FAIL %s: got %d, expected %d', label, got, expected);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const chip = new AD5248Full(connection);

    // --- Wiper write / readback ---
    await chip.setWiper(0, 100);
    checkEq('set/get wiper ch0', await chip.getWiper(0), 100);
    await chip.setWiper(1, 200);
    checkEq('set/get wiper ch1', await chip.getWiper(1), 200);
    checkEq('ch0 unchanged by ch1 write', await chip.getWiper(0), 100);

    // --- Resistance ---
    await chip.setResistance(0, 5320.0);
    checkEq('set_resistance midscale code', await chip.getWiper(0), 128);
    checkTrue('get_resistance ~5320', Math.abs((await chip.getResistance(0)) - 5320.0) < 1.0);

    // --- Ratio / increment / both ---
    await chip.setBoth(10, 20);
    checkEq('set_both ch0', await chip.getWiper(0), 10);
    checkEq('set_both ch1', await chip.getWiper(1), 20);
    await chip.setRatio(0, 0.5);
    checkTrue('get_ratio ~0.5', Math.abs((await chip.getRatio(0)) - 0.5) < 0.01);
    await chip.setWiper(0, 250);
    checkEq('increment clamps', await chip.increment(0, 10), 255);

    // --- Shutdown keeps RDAC ---
    await chip.setWiper(1, 77);
    await chip.shutdown(1);
    checkTrue('is_shutdown', chip.isShutdown(1));
    await chip.resume(1);
    checkEq('RDAC kept across shutdown', await chip.getWiper(1), 77);

    // --- Reset ---
    await chip.reset();
    checkEq('reset ch0', await chip.getWiper(0), 0x80);
    checkEq('reset ch1', await chip.getWiper(1), 0x80);

    await connection.close();

    console.log('===DONE: %d passed, %d failed===', passed, failed);
    process.exit(failed === 0 ? 0 : 1);
}

main();
