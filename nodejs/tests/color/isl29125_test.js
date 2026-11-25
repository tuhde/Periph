'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ISL29125Minimal, ISL29125Full } = require('../../packages/periph/src/chips/color/isl29125');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const inRange = (v) => v >= 0 && v <= 0xFFFF;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new ISL29125Minimal(connection);
    await sensor.init();
    checkTrue('init', true);
    const c = await sensor.read();
    checkTrue('read range', [c.red, c.green, c.blue].every(inRange));

    const full = new ISL29125Full(connection);
    await full.init();
    checkTrue('device id', (await full.deviceId()) === 0x7D);
    await full.setRange(375);
    await full.setRange(10000);
    await full.setResolution(12);
    await sleep(400);
    const b12 = await full.readRgb();
    checkTrue('read rgb range 12 bit', [b12.red, b12.green, b12.blue].every(inRange));
    await full.setResolution(16);
    await full.setIrCompensation(true, 0x3F);
    await full.setSync(false);
    await sleep(400);
    const b = await full.readRgb();
    checkTrue('read rgb range', [b.red, b.green, b.blue].every(inRange));
    checkTrue('read single', inRange(await full.readGreen()) && inRange(await full.readRed()) && inRange(await full.readBlue()));
    await full.setMode('green');
    await sleep(300);
    checkTrue('green mode reads', inRange(await full.readGreen()));
    await full.setMode('rgb');
    await full.setThresholds(100, 60000);
    const t = await full.getThresholds();
    checkTrue('thresholds roundtrip', t.low === 100 && t.high === 60000);
    await full.setPersistence(2);
    await full.setInterruptChannel('green');
    await full.enableInterrupt(ISL29125Full.SOURCE_THRESHOLD);
    await full.disableInterrupt(ISL29125Full.SOURCE_THRESHOLD);
    await full.status();
    checkTrue('status clean', ((await full.pollInterrupt()) & ISL29125Full.STATUS_THRESHOLD) === 0);
    await full.setMode('power_down');
    await full.softReset();
    await full.setMode('rgb');
    checkTrue('soft reset id', (await full.deviceId()) === 0x7D);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
