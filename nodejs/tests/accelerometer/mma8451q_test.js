'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { MMA8451QMinimal } = require('../../packages/periph/src/chips/accelerometer/mma8451q');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new MMA8451QMinimal(connection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        await accel.init();
        const [x, y, z] = await accel.read();
        checkTrue('read_returns_numbers', typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
        const mag = Math.sqrt(x * x + y * y + z * z);
        checkTrue('magnitude_near_1g', mag >= 0.5 && mag <= 1.5);
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
