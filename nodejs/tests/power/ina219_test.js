'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { INA219Full }   = require('../../packages/periph/src/chips/power/ina219');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const ina = new INA219Full(connection);

    checkTrue('voltage non-negative', (await ina.voltage())      >= 0.0);
    checkTrue('shunt_voltage finite', (await ina.shuntVoltage()) > -1.0);
    checkTrue('current finite',        (await ina.current())      > -10.0);
    checkTrue('power non-negative',   (await ina.power())        >= 0.0);

    checkTrue('conversion_ready', await ina.conversionReady());
    checkTrue('no overflow',      !(await ina.overflow()));

    await ina.configure(1, 3, 0x03, 0x03, 7);
    checkTrue('after configure: voltage non-negative', (await ina.voltage()) >= 0.0);

    await ina.shutdown();
    const end = Date.now() + 1;
    while (Date.now() < end) {}
    await ina.wake();
    checkTrue('wake: voltage non-negative', (await ina.voltage()) >= 0.0);

    await ina.reset();
    checkTrue('after reset: voltage non-negative', (await ina.voltage()) >= 0.0);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
