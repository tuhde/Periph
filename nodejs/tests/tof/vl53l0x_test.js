'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { VL53L0XMinimal, VL53L0XFull } = require('../../packages/periph/src/chips/tof/vl53l0x');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x29', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const minimal = new VL53L0XMinimal(connection);
    await minimal.init();
    checkTrue('init', true);
    const d = await minimal.distance();
    checkTrue('distance in range', d >= 0 && d <= 8191);
    checkTrue('range valid is boolean', typeof (await minimal.rangeValid()) === 'boolean');

    const full = new VL53L0XFull(connection);
    await full.init();
    checkTrue('model id', (await full.modelId()) === 0xEE);
    checkTrue('revision id', (await full.revisionId()) > 0);
    const budget = await full.timingBudget();
    checkTrue('default budget', budget >= 20000 && budget <= 40000);

    await full.distance();
    const m = await full.readMeasurement();
    checkTrue('measurement record', m.rangeStatus >= 0 && m.rangeStatus <= 15 && m.signalRateMcps >= 0);

    await full.setTimingBudget(50000);
    checkTrue('budget roundtrip', Math.abs((await full.timingBudget()) - 50000) < 300);
    await full.setSignalRateLimit(0.1);
    checkTrue('signal rate roundtrip', Math.abs((await full.signalRateLimit()) - 0.1) < 0.01);
    await full.setVcselPulsePeriod('pre_range', 18);
    await full.setVcselPulsePeriod('final_range', 14);
    checkTrue('vcsel roundtrip', (await full.vcselPulsePeriod('pre_range')) === 18 &&
        (await full.vcselPulsePeriod('final_range')) === 14);
    await full.setProfile('default');
    checkTrue('profile default', (await full.vcselPulsePeriod('pre_range')) === 14 &&
        Math.abs((await full.timingBudget()) - 33000) < 300);

    const original = await full.offset();
    await full.setOffset(-10.25);
    checkTrue('offset roundtrip', (await full.offset()) === -10.25);
    await full.setOffset(original);

    await full.setInterruptThresholds(100, 800);
    const th = await full.interruptThresholds();
    checkTrue('thresholds roundtrip', th.lowMm === 100 && th.highMm === 800);

    // Back-to-back continuous ranging, then timed mode.
    await full.startContinuous();
    const readings = [];
    for (let i = 0; i < 3; i++) readings.push(await full.readContinuous());
    checkTrue('continuous readings', readings.every((r) => r >= 0 && r <= 8191));
    await full.stopContinuous();
    await sleep(50);
    await full.pollInterrupt();
    await full.startContinuous(100);
    const timed = [await full.readContinuous(), await full.readContinuous()];
    checkTrue('timed readings', timed.every((r) => r >= 0 && r <= 8191));
    await full.stopContinuous();
    await sleep(150);
    await full.pollInterrupt();

    await full.startContinuous();
    await sleep(100);
    checkTrue('poll interrupt new sample', (await full.pollInterrupt()) === VL53L0XFull.SOURCE_NEW_SAMPLE_READY);
    await full.stopContinuous();
    await sleep(50);
    await full.pollInterrupt();

    await full.recalibrate();
    const after = await full.distance();
    checkTrue('recalibrate then distance', after >= 0 && after <= 8191);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
