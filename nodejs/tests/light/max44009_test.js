'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MAX44009Minimal, MAX44009Full } = require('../../packages/periph/src/chips/light/max44009');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x4A', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new MAX44009Minimal(connection);
    await sensor.init();
    checkTrue('init', true);
    const lx = await sensor.lux();
    checkTrue('lux in range', lx >= 0 && lx <= 188006.4);

    const full = new MAX44009Full(connection);
    await full.init();
    const raw = await full.readRaw();
    checkTrue('read raw ranges', raw.exponent >= 0 && raw.exponent <= 15 && raw.mantissa >= 0 && raw.mantissa <= 255 && raw.overrange === (raw.exponent === 15));
    await full.setContinuous(true);
    checkTrue('continuous on', (await full.getConfig()).continuous === true);
    await full.setContinuous(false);
    checkTrue('continuous off', (await full.getConfig()).continuous === false);
    await full.setManual(100, false);
    let cfg = await full.getConfig();
    checkTrue('manual 100 ms', cfg.manual && cfg.integrationMs === 100.0 && !cfg.divideBy8);
    await full.setManual(25, true);
    cfg = await full.getConfig();
    checkTrue('manual 25 ms divide', cfg.integrationMs === 25.0 && cfg.divideBy8);
    await full.setAutomatic();
    checkTrue('automatic', (await full.getConfig()).manual === false);
    await full.setThresholds(50.0, 500.0);
    const th = await full.getThresholds();
    checkTrue('thresholds roundtrip', Math.abs(th.lowLux - 50.0) / 50.0 < 0.2 && Math.abs(th.highLux - 500.0) / 500.0 < 0.2);
    await full.setThresholdDelay(2.0);
    checkTrue('threshold delay', Math.abs((await full.getThresholdDelay()) - 2.0) < 0.05);
    await full.enableInterrupt();
    await full.disableInterrupt();
    checkTrue('poll interrupt cleared', (await full.pollInterrupt()) === 0);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
