'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AS7262Full } = require('../../packages/periph/src/chips/color/as7262');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x49', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new AS7262Full(connection);
    await sensor.init();

    checkTrue('device type', (await sensor.deviceType()) === 0x40);
    const hw = await sensor.hardwareVersion();
    checkTrue('hardware version', hw[0] === 0x40 && hw[1] === 0x3E);
    const t = await sensor.temperature();
    checkTrue('temperature range', t >= -40 && t <= 125);

    const d = await sensor.read();
    checkTrue('calibrated channels', Object.keys(d).length === 6 && Object.values(d).every((v) => v >= 0));
    const r = await sensor.readRaw();
    checkTrue('raw channels', Object.values(r).every((v) => v >= 0 && v <= 65535));
    const raw2 = await sensor.readChannel(2, false);
    checkTrue('readChannel raw', raw2 >= 0 && raw2 <= 65535);

    await sensor.setGain(64);
    checkTrue('gain', (await sensor.getGain()) === 64);
    await sensor.setGain(16);
    await sensor.setIntegrationTime(50);
    checkTrue('integration time', Math.abs((await sensor.getIntegrationTime()) - 50.4) < 0.01);
    await sensor.setMode(2);
    checkTrue('mode', (await sensor.getMode()) === 2);
    await sensor.setMode(3);

    await sensor.measure();
    await sleep(600);
    checkTrue('data ready', await sensor.dataReady());
    checkTrue('last calibrated', Object.keys(await sensor.readCalibrated()).length === 6);

    await sensor.setIndicatorLed(true, 4);
    await sensor.setIndicatorLed(false);
    await sensor.softReset();
    checkTrue('reset int_t', (await sensor.getIntegrationTime()) === 255 * 2.8);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
