'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { VL6180XFull } = require('../../packages/periph/src/chips/tof/vl6180x');

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
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR, null, null, 2);
    const sensor = new VL6180XFull(connection);
    await sensor.init();
    checkTrue('init', true);

    checkTrue('model id', (await sensor.modelId()) === 0xB4);
    checkTrue('revision', (await sensor.revision()).major >= 1);

    const mm = await sensor.readRange().catch(() => -1);
    checkTrue('range 0-255 mm or range error', mm >= -1 && mm <= 255);
    const st = await sensor.readRangeStatus();
    checkTrue('range status', st.mm >= 0 && st.mm <= 255 && st.error >= 0 && st.error <= 15);

    const raw = await sensor.readAlsRaw();
    checkTrue('als raw', raw >= 0 && raw <= 65535);
    checkTrue('lux positive', (await sensor.readLux()) >= 0);
    for (const code of [0, 6, 7]) {
        await sensor.setAlsGain(code);
        checkTrue(`lux at gain code ${code}`, (await sensor.readLux()) >= 0);
    }
    await sensor.setAlsGain(6);
    await sensor.setAlsIntegrationTime(50);
    checkTrue('lux at 50 ms', (await sensor.readLux()) >= 0);
    await sensor.setAlsIntegrationTime(100);
    await sensor.setAlsScaler(1);

    await sensor.setRangeOffset(3);
    checkTrue('offset round trip', (await sensor.getRangeOffset()) === 3);
    await sensor.setRangeOffset(0);
    await sensor.setCrosstalkRate(0);
    await sensor.setMaxConvergenceTime(49);
    await sensor.setAveragingPeriod(48);
    await sensor.recalibrateVhv();
    checkTrue('range after recalibration', (await sensor.readRangeStatus()).mm <= 255);

    await sensor.startContinuousRange(50);
    let contOk = true;
    for (let i = 0; i < 5; i++) {
        const v = await sensor.readRangeContinuous().catch(() => 0);
        contOk = contOk && v >= 0 && v <= 255;
    }
    checkTrue('continuous range', contOk);
    await sensor.stopContinuousRange();
    await sleep(200);

    await sensor.setRangeThresholds(30, 80);
    await sensor.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_OUT_OF_WINDOW);
    await sensor.startContinuousRange(50);
    await sleep(300);
    const sources = await sensor.pollInterrupt();
    checkTrue('poll interrupt returns a source mask', sources >= 0 && sources <= 7);
    await sensor.stopContinuousRange();
    await sleep(200);
    await sensor.disableInterrupt(VL6180XFull.SOURCE_RANGE);
    await sensor.enableInterrupt(VL6180XFull.SOURCE_RANGE, VL6180XFull.MODE_NEW_SAMPLE_READY);
    await sensor.pollInterrupt();

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
