'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AD5243Minimal, AD5243Full } = require('../../packages/periph/src/chips/adc_dac/ad5243');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x2F', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const pot = new AD5243Minimal(connection);
    await pot.init();
    checkTrue('init', true);

    await pot.setWiper(0, 100);
    checkTrue('wiper0 roundtrip', (await pot.getWiper(0)) === 100);
    await pot.setWiper(1, 200);
    checkTrue('wiper1 roundtrip', (await pot.getWiper(1)) === 200);
    checkTrue('wiper0 independent', (await pot.getWiper(0)) === 100);
    await pot.setWiper(0, 300);
    checkTrue('wiper clamps 255', (await pot.getWiper(0)) === 255);
    await pot.setResistance(0, 5000.0);
    checkTrue('set resistance', Math.abs((await pot.getResistance(0)) - 5000.0) < 50.0);

    const full = new AD5243Full(connection);
    await full.setBoth(10, 250);
    checkTrue('set both', (await full.getWiper(0)) === 10 && (await full.getWiper(1)) === 250);
    await full.setRatio(0, 0.5);
    checkTrue('ratio half', (await full.getWiper(0)) === 128 && Math.abs((await full.getRatio(0)) - 0.5) < 0.01);
    await full.setResistanceWa(1, 3000.0);
    checkTrue('set resistance wa', Math.abs((await full.getResistanceWa(1)) - 3000.0) < 50.0);
    await full.setWiper(0, 100);
    checkTrue('increment', (await full.increment(0, 5)) === 105 && (await full.getWiper(0)) === 105);
    checkTrue('increment clamps', (await full.increment(0, -500)) === 0);

    await full.setWiper(1, 77);
    await full.shutdown(1);
    checkTrue('is shutdown', full.isShutdown(1));
    checkTrue('shutdown keeps rdac', (await full.getWiper(1)) === 77);
    await full.resume(1);
    checkTrue('resume', !full.isShutdown(1) && (await full.getWiper(1)) === 77);

    await full.setBoth(1, 2);
    await full.reset();
    checkTrue('reset midscale', (await full.getWiper(0)) === 128 && (await full.getWiper(1)) === 128);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
