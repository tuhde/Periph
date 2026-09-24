'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { VL53L1XMinimal, VL53L1XFull } = require('../../packages/periph/src/chips/tof/vl53l1x');

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
    const minimal = new VL53L1XMinimal(connection);
    await minimal.init();
    checkTrue('init', true);
    const d = await minimal.distance();
    checkTrue('distance in range', d >= 0 && d <= 65535);
    checkTrue('range valid is boolean', typeof (await minimal.rangeValid()) === 'boolean');

    const full = new VL53L1XFull(connection);
    await full.init();
    checkTrue('model id', (await full.modelId()) === 0xEA);
    checkTrue('module type', (await full.moduleType()) === 0xCC);
    checkTrue('revision id', (await full.revisionId()) > 0);
    checkTrue('default budget', (await full.timingBudget()) === 100000);
    checkTrue('default mode', (await full.distanceMode()) === 'long');

    await full.distance();
    const m = await full.readMeasurement();
    checkTrue('measurement record', m.signalRateMcps >= 0 && m.effectiveSpadCount >= 0);

    await full.setTimingBudget(50000);
    checkTrue('budget roundtrip', (await full.timingBudget()) === 50000);
    await full.setDistanceMode('short');
    checkTrue('mode short', (await full.distanceMode()) === 'short' && (await full.timingBudget()) === 50000);
    const ds = await full.distance();
    checkTrue('short distance', ds >= 0 && ds <= 65535);
    await full.setDistanceMode('long');
    await full.setTimingBudget(100000);

    await full.setSignalRateLimit(0.5);
    checkTrue('signal rate roundtrip', (await full.signalRateLimit()) === 0.5);
    await full.setSignalRateLimit(1.0);
    await full.setSigmaThreshold(60);
    checkTrue('sigma roundtrip', (await full.sigmaThreshold()) === 60);
    await full.setSigmaThreshold(90);

    await full.setRoi(8, 8);
    const r = await full.roi();
    checkTrue('roi roundtrip', r.width === 8 && r.height === 8);
    const oc = await full.opticalCenter();
    checkTrue('optical center', oc >= 0 && oc <= 255);
    await full.setRoi(16, 16);
    checkTrue('roi restored', (await full.roiCenter()) === 199);

    const original = await full.offset();
    await full.setOffset(-10.25);
    checkTrue('offset roundtrip', (await full.offset()) === -10.25);
    await full.setOffset(original);
    await full.setCrosstalkCompensation(0.01);
    checkTrue('crosstalk roundtrip', Math.abs((await full.crosstalkCompensation()) - 0.01) < 0.0001);
    await full.setCrosstalkCompensation(0);

    await full.setInterruptThresholds(100, 800);
    const th = await full.interruptThresholds();
    checkTrue('thresholds roundtrip', th.lowMm === 100 && th.highMm === 800);

    await full.startContinuous(150);
    const period = await full.interMeasurement();
    checkTrue('inter measurement', period >= 148 && period <= 150);
    let contOk = true;
    for (let i = 0; i < 3; i++) {
        const v = await full.readContinuous();
        contOk = contOk && v >= 0 && v <= 65535;
    }
    checkTrue('continuous readings', contOk);
    await sleep(200);
    checkTrue('poll interrupt new sample', (await full.pollInterrupt()) === VL53L1XFull.SOURCE_NEW_SAMPLE_READY);
    await full.stopContinuous();
    await sleep(200);
    await full.pollInterrupt();

    await full.recalibrate();
    const after = await full.distance();
    checkTrue('recalibrate then distance', after >= 0 && after <= 65535);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
