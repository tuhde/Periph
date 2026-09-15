'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LPS22DFMinimal, LPS22DFFull } = require('../../packages/periph/src/chips/pressure/lps22df');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

let passed = 0, failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const lps = new LPS22DFMinimal(connection);

    const t = await lps.temperature();
    checkTrue('temperature_range', t >= -40 && t <= 85);

    const p = await lps.pressure();
    checkTrue('pressure_range', p >= 26000 && p <= 126000);

    const lpsFull = new LPS22DFFull(connection);
    await lpsFull.configure(3, 0, true, 1, true);
    const p2 = await lpsFull.pressure();
    checkTrue('configure_then_read', p2 >= 26000 && p2 <= 126000);

    const alt = await lpsFull.altitude(101325.0);
    checkTrue('altitude', alt >= -500 && alt <= 10000);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();