'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AD5252Minimal, AD5252Full } = require('../../packages/periph/src/chips/adc_dac/ad5252');

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
    const pot = new AD5252Minimal(connection);
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

    const full = new AD5252Full(connection);
    await full.setBoth(10, 250);
    checkTrue('set both', (await full.getWiper(0)) === 10 && (await full.getWiper(1)) === 250);
    await full.setRatio(0, 0.5);
    checkTrue('ratio half', (await full.getWiper(0)) === 128 && Math.abs((await full.getRatio(0)) - 0.5) < 0.01);
    await full.setResistanceWa(1, 3000.0);
    checkTrue('set resistance wa', Math.abs((await full.getResistanceWa(1)) - 3000.0) < 50.0);
    await full.setWiper(0, 100);
    await full.increment(0);
    checkTrue('increment', (await full.getWiper(0)) === 101);
    await full.decrement(0);
    checkTrue('decrement', (await full.getWiper(0)) === 100);
    await full.double(0);
    checkTrue('double', (await full.getWiper(0)) === 200);
    await full.halve(0);
    checkTrue('halve', (await full.getWiper(0)) === 100);
    await full.setBoth(10, 20);
    await full.increment();
    checkTrue('increment both', (await full.getWiper(0)) === 11 && (await full.getWiper(1)) === 21);

    await full.setBoth(40, 60);
    await full.store();
    await full.setBoth(1, 2);
    await full.restore();
    checkTrue('store restore', (await full.getWiper(0)) === 40 && (await full.getWiper(1)) === 60);
    await full.setBoth(1, 2);
    await full.reset();
    checkTrue('reset restores eemem', (await full.getWiper(0)) === 40 && (await full.getWiper(1)) === 60);

    await full.writeEemem(15, 0xA5);
    checkTrue('eemem roundtrip', (await full.readEemem(15)) === 0xA5);
    const tol = await full.readTolerance(0);
    checkTrue('tolerance range', tol > -50 && tol < 50);
    checkTrue('rab actual', Math.abs((await full.rabActual(0)) - 10000 * (1 + tol / 100)) < 0.01);
    await full.nop();

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
