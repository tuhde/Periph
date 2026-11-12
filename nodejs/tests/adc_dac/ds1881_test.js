'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { DS1881Minimal, DS1881Full } = require('../../packages/periph/src/chips/adc_dac/ds1881');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x28', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const pot = new DS1881Minimal(connection);
    await pot.init();
    checkTrue('init', true);

    await pot.setAttenuation(0, 20);
    await pot.setAttenuation(1, 33);
    checkTrue('position roundtrip', (await pot.getPosition(0)) === 20 && (await pot.getPosition(1)) === 33);
    checkTrue('attenuation roundtrip', (await pot.getAttenuation(0)) === 20 && (await pot.getAttenuation(1)) === 33);
    await pot.mute(0);
    checkTrue('mute', (await pot.getPosition(0)) === 63 && (await pot.getAttenuation(0)) === 80);
    await pot.setPosition(1, 5);
    checkTrue('set position', (await pot.getPosition(1)) === 5);

    const full = new DS1881Full(connection);
    await full.init();
    const cfg = await full.getConfig();
    checkTrue('config default', cfg.option === 1 && cfg.zeroCrossing && !cfg.nonvolatile);
    await full.setBoth(10, 40);
    checkTrue('set both', (await full.readAll()).slice(0, 2).join() === '10,40');
    await full.setAttenuationBoth(15);
    checkTrue('set attenuation both', (await full.readAll()).slice(0, 2).join() === '15,15');

    await full.setZeroCrossing(false);
    checkTrue('zero crossing off', !(await full.getConfig()).zeroCrossing);
    await full.setZeroCrossing(true);

    await full.setOption(2);
    checkTrue('option 2', (await full.getConfig()).option === 2 && full.maxPosition() === 32);
    await full.setBoth(13, 33);
    checkTrue('option 2 positions', (await full.readAll()).slice(0, 2).join() === '13,33' && full.positionToDb(13) === 14);
    await full.setOption(1);
    checkTrue('option 1 again', (await full.getConfig()).option === 1);

    await full.reset();
    checkTrue('reset mutes', (await full.readAll()).slice(0, 2).join() === '63,63');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    await connection.close();
    process.exit(failed === 0 ? 0 : 1);
}

main();
