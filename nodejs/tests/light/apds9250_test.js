'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { APDS9250Minimal, APDS9250Full } = require('../../packages/periph/src/chips/light/apds9250');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x52', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const inRange = (v) => v >= 0 && v <= 0xFFFFF;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new APDS9250Minimal(connection);
    await sensor.init();
    checkTrue('init', true);
    const c = await sensor.read();
    checkTrue('read range', [c.red, c.green, c.blue, c.ir].every(inRange));

    const full = new APDS9250Full(connection);
    await full.init();
    checkTrue('part id', (await full.partId()).part === 0xB);
    checkTrue('defaults', (await full.getResolution()) === 18 && (await full.getMeasurementRate()) === 100 && (await full.getGain()) === 3);
    await full.setGain(18);
    checkTrue('gain 18', (await full.getGain()) === 18);
    await full.setGain(3);
    await full.setResolution(16);
    checkTrue('resolution 16', (await full.getResolution()) === 16 && Math.abs((await full.integrationTime()) - 0.025) < 1e-9);
    await full.setResolution(18);
    await full.setMeasurementRate(200);
    checkTrue('rate 200', (await full.getMeasurementRate()) === 200);
    await full.setMeasurementRate(100);
    const b = await full.readChannels();
    checkTrue('read channels range', [b.red, b.green, b.blue, b.ir].every(inRange));
    checkTrue('read single green', inRange(await full.readGreen()));
    await full.setMode(false);
    checkTrue('als mode reads', inRange(await full.readIr()));
    await full.setMode(true);
    await full.setThresholds(100, 200000);
    const t = await full.getThresholds();
    checkTrue('thresholds roundtrip', t.low === 100 && t.high === 200000);
    await full.setPersistence(3);
    await full.setVariance(64);
    await full.setInterruptChannel('green');
    await full.enableInterrupt(APDS9250Full.SOURCE_VARIATION);
    await full.disableInterrupt();
    await full.status();
    checkTrue('status clean', ((await full.pollInterrupt()) & APDS9250Full.STATUS_INTERRUPT) === 0);
    await full.disable();
    await full.enable();
    await full.softReset();
    checkTrue('soft reset defaults', (await full.getGain()) === 3 && (await full.getResolution()) === 18);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
