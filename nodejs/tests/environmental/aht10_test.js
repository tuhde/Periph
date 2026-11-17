'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { AHT10Full }    = require('../../packages/periph/src/chips/environmental/aht10');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const aht = new AHT10Full(connection);

    checkTrue('is_calibrated', await aht.isCalibrated());
    checkTrue('not busy at idle', !(await aht.isBusy()));

    const r = await aht.read();
    checkTrue('temperature range', r.temperature_c >= -40.0 && r.temperature_c <= 120.0);
    checkTrue('humidity range', r.humidity_pct >= 0.0 && r.humidity_pct <= 100.0);

    const tr = await aht.readTemperature();
    checkTrue('readTemperature range', tr >= -40.0 && tr <= 120.0);

    const hr = await aht.readHumidity();
    checkTrue('readHumidity range', hr >= 0.0 && hr <= 100.0);

    const raw = await aht.readRaw();
    checkTrue('readRaw range', raw.raw_humidity >= 0 && raw.raw_humidity < 1048576 && raw.raw_temperature >= 0 && raw.raw_temperature < 1048576);
    checkTrue('readStatus calibrated', ((await aht.readStatus()) & 0x08) !== 0);
    const dp = await aht.dewPoint();
    checkTrue('dewPoint <= temperature', dp <= (await aht.readTemperature()) + 1.0);

    await aht.softReset();
    const end = Date.now() + 50;
    while (Date.now() < end) {}
    checkTrue('calibrated after reset', await aht.isCalibrated());

    const r2 = await aht.read();
    checkTrue('read after reset: temperature range', r2.temperature_c >= -40.0 && r2.temperature_c <= 120.0);
    checkTrue('read after reset: humidity range', r2.humidity_pct >= 0.0 && r2.humidity_pct <= 100.0);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
