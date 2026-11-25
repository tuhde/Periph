'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMA180Minimal, BMA180Full } = require('../../packages/periph/src/chips/accelerometer/bma180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

    const accel = new BMA180Minimal(connection);
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));

    const [x, y, z] = await accel.read();
    checkTrue('read_returns_three_numbers',
           typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
    checkTrue('magnitude_near_1g', Math.abs(Math.sqrt(x*x + y*y + z*z) - 1.0) < 0.5);

    const accelFull = new BMA180Full(connection);
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));
    checkTrue('construct_full', accelFull instanceof BMA180Full);

    await accelFull.setRange(4);
    const [x2, y2, z2] = await accelFull.read();
    checkTrue('read_after_set_range_4g',
           typeof x2 === 'number' && typeof y2 === 'number' && typeof z2 === 'number');

    const temp = await accelFull.readTemperature();
    checkTrue('temperature_in_range', temp >= -40.0 && temp <= 87.5);

    const [al, ml] = await accelFull.readVersion();
    checkTrue('read_version_ok', al >= 0 && al <= 0xF && ml >= 0 && ml <= 0xF);

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(2); });