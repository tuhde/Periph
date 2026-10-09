'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BMA150Minimal, BMA150Full } = require('../../packages/periph/src/chips/accelerometer/bma150');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new BMA150Minimal(connection);
const accelFull = new BMA150Full(connection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        const [x, y, z] = await accel.read();
        checkTrue('read_returns_numbers', typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
        const mag = Math.sqrt(x * x + y * y + z * z);
        checkTrue('magnitude_near_1g', mag >= 0.5 && mag <= 1.5);

        await accelFull.setRange(4);
        const [x2, y2, z2] = await accelFull.read();
        checkTrue('read_after_set_range_4g', typeof x2 === 'number' && typeof y2 === 'number' && typeof z2 === 'number');

        const temp = await accelFull.readTemperature();
        checkTrue('temperature_in_range', temp >= -30.0 && temp <= 97.5);
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
