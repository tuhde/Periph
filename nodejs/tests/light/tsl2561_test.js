'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { TSL2561Minimal, TSL2561Full } = require('../../packages/periph/src/chips/light/tsl2561');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x39', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new TSL2561Minimal(connection);
    await sensor.init();
    checkTrue('init', true);
    const lx = await sensor.readLux();
    checkTrue('read lux in range', lx >= 0 && lx <= 40000);

    const full = new TSL2561Full(connection);
    await full.init();
    checkTrue('read id partno', ((await full.readId()) >> 4) === 1);
    checkTrue('is powered', await full.isPowered());
    const raw = await full.readRaw();
    checkTrue('read raw ranges', raw.ch0 >= 0 && raw.ch0 <= 65535 && raw.ch1 >= 0 && raw.ch1 <= 65535);
    await full.setGain(16);
    await full.setIntegrationTime(101);
    checkTrue('gain integration roundtrip', full.getGain() === 16 && full.getIntegrationTime() === 101);
    await full.setGain(1);
    await full.setIntegrationTime(402);
    checkTrue('auto range lux', (await full.autoRange()) >= 0);
    await full.setThresholds(100, 30000);
    const th = await full.getThresholds();
    checkTrue('thresholds roundtrip', th.low === 100 && th.high === 30000);
    await full.configureInterrupt(0, 0);
    await full.clearInterrupt();
    checkTrue('poll interrupt bool', typeof (await full.pollInterrupt()) === 'boolean');
    await full.powerDown();
    checkTrue('power down', !(await full.isPowered()));
    await full.powerUp();
    checkTrue('power up', await full.isPowered());

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
