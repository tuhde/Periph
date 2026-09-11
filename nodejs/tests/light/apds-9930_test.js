'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { APDS9930Full }  = require('../../packages/periph/src/chips/light/apds-9930');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x39', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const apds = new APDS9930Full(connection);

    await new Promise(r => setTimeout(r, 110));

    const st = await apds.status();
    checkTrue('status returns object', typeof st === 'object');
    checkTrue('status.avalid is bool', typeof st.avalid === 'boolean');
    checkTrue('status.pvalid is bool', typeof st.pvalid === 'boolean');

    const lx = await apds.lux();
    checkTrue('lux is number', typeof lx === 'number');
    checkTrue('lux >= 0', lx >= 0);

    const p = await apds.proximity();
    checkTrue('proximity >= 0', p >= 0);

    const c0 = await apds.ch0();
    const c1 = await apds.ch1();
    checkTrue('ch0 >= 0', c0 >= 0);
    checkTrue('ch1 >= 0', c1 >= 0);

    await apds.configureAls(0xDB, 0, false);
    await apds.configureProximity(8, 0, 0, false, 0xFF);
    await apds.disableWait();
    await apds.setAlsThresholds(0, 65535, 1);
    await apds.setProximityThresholds(0, 1023, 1);
    await apds.setProximityOffset(0);
    await apds.sleepAfterInterrupt(false);
    await apds.clearInterrupt('both');
    checkTrue('config methods accepted', true);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();