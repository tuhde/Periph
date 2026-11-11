'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { CAT5171Minimal, CAT5171Full } = require('../../packages/periph/src/chips/adc_dac/cat5171');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x2C', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const pot = new CAT5171Minimal(connection);
    await pot.init();
    checkTrue('init', true);

    await pot.setWiper(100);
    checkTrue('wiper roundtrip', (await pot.getWiper()) === 100);
    await pot.setWiper(300);
    checkTrue('wiper clamps 255', (await pot.getWiper()) === 255);
    await pot.setResistance(50000.0);
    checkTrue('set resistance', Math.abs((await pot.getResistance()) - 50000.0) < 500.0);

    const full = new CAT5171Full(connection);
    await full.setRatio(0.5);
    checkTrue('ratio half', (await full.getWiper()) === 128 && Math.abs((await full.getRatio()) - 0.5) < 0.01);
    await full.setResistanceWa(30000.0);
    checkTrue('set resistance wa', Math.abs((await full.getResistanceWa()) - 30000.0) < 500.0);
    await full.setWiper(100);
    checkTrue('increment', (await full.increment(5)) === 105 && (await full.getWiper()) === 105);
    checkTrue('increment clamps', (await full.increment(-500)) === 0);

    await full.setWiper(77);
    await full.shutdown();
    checkTrue('is shutdown', full.isShutdown());
    checkTrue('shutdown keeps wcr', (await full.getWiper()) === 77);
    await full.resume();
    checkTrue('resume', !full.isShutdown() && (await full.getWiper()) === 77);

    await full.setWiper(10);
    await full.midscale();
    checkTrue('midscale', (await full.getWiper()) === 128);

    await full.setWiper(1);
    await full.reset();
    checkTrue('reset midscale', (await full.getWiper()) === 128);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
