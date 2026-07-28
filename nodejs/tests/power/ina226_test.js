'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { INA226Full }   = require('../../packages/periph/src/chips/power/ina226');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

let passed = 0;
let failed = 0;

function checkEq(label, got, expected) {
    if (got === expected) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log(`FAIL ${label}: got 0x${got.toString(16).toUpperCase().padStart(4,'0')}, expected 0x${expected.toString(16).toUpperCase().padStart(4,'0')}`);
        failed++;
    }
}

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const ina = new INA226Full(connection);

    checkEq('manufacturer_id', await ina.manufacturerId(), 0x5449);
    checkEq('die_id',          await ina.dieId(),          0x2260);

    checkTrue('voltage non-negative', (await ina.voltage())      >= 0.0);
    checkTrue('shunt_voltage finite', (await ina.shuntVoltage()) > -1.0);
    checkTrue('current finite',       (await ina.current())      > -10.0);
    checkTrue('power non-negative',   (await ina.power())        >= 0.0);

    checkTrue('conversion_ready', await ina.conversionReady());
    checkTrue('no overflow',      !(await ina.overflow()));

    await ina.configure(3, 4, 4, 7);
    checkEq('configure: mfr_id still valid', await ina.manufacturerId(), 0x5449);

    await ina.setAlert(INA226Full.POL, 1.0, false, true);
    checkTrue('set_alert POL: LEN bit set', ((await ina.alertFlags()) & 0x0001) !== 0);

    await ina.shutdown();
    // 1 ms synchronous delay
    const end = Date.now() + 1;
    while (Date.now() < end) {}
    await ina.wake();
    checkTrue('wake: voltage non-negative', (await ina.voltage()) >= 0.0);

    await ina.reset();
    checkEq('reset: mfr_id still valid', await ina.manufacturerId(), 0x5449);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
