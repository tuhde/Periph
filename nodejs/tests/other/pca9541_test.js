'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { PCA9541Minimal, PCA9541Full } = require('../../packages/periph/src/chips/other/pca9541');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x70', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const selector = new PCA9541Minimal(connection);
    await selector.init();
    checkTrue('init', true);
    const has = await selector.hasControl();
    checkTrue('has control is bool', has === true || has === false);

    const full = new PCA9541Full(connection);
    await full.init();
    const control = await full.getControl();
    checkTrue('status matches control', (await full.status()).hasControl === (((control & 1) !== 0) === ((control & 2) !== 0)));
    const all = await full.readAll();
    checkTrue('read all ranges', all.ie <= 0x0F && all.control <= 0xFF && all.istat <= 0xFF);
    await full.setInterruptMask({ busNotIdle: true });
    checkTrue('interrupt mask roundtrip', (await full.getInterruptMask()) === 0x04);
    await full.setInterruptMask();
    checkTrue('interrupt mask cleared', (await full.getInterruptMask()) === 0x00);
    await full.testInterrupt(true);
    checkTrue('test interrupt sets TESTON', (await full.status()).ownTest === true);
    await full.testInterrupt();
    checkTrue('test interrupt clears', (await full.status()).ownTest === false);
    checkTrue('acquire takes bus', (await full.acquire()) === true && (await full.hasControl()) === true);
    await full.disconnect();
    checkTrue('disconnect', (await full.isConnected()) === false);
    await full.connect();
    checkTrue('connect', (await full.isConnected()) === true);
    await full.release();
    checkTrue('release gives bus away', (await full.hasControl()) === false);
    await full.acquire();
    checkTrue('reacquire', (await full.hasControl()) === true);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
