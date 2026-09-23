'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { DRV8830Minimal, DRV8830Full } = require('../../packages/periph/src/chips/motor/drv8830');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const minimal = new DRV8830Minimal(connection);
    await minimal.init();
    checkTrue('init', true);

    const motor = new DRV8830Full(connection);
    await motor.drive(2.0);
    let out = await motor.readOutput();
    checkTrue('drive forward direction', out.direction === 'forward');
    checkTrue('drive forward voltage', Math.abs(out.voltage - 2.0) < 0.1);

    await motor.drive(-1.0);
    out = await motor.readOutput();
    checkTrue('drive reverse direction', out.direction === 'reverse');

    await motor.brake();
    checkTrue('brake direction', (await motor.readOutput()).direction === 'brake');

    await motor.stop();
    checkTrue('stop direction', (await motor.readOutput()).direction === 'coast');

    await motor.clearFault();
    checkTrue('clear fault', !(await motor.readFault()).fault);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
