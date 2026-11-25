'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LM25066Full }   = require('../../packages/periph/src/chips/power/lm25066');

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
    const hotswap = new LM25066Full(connection, 1.0, false);

    checkTrue('model is LM25066', (await hotswap.model()) === 'LM25066');
    checkTrue('manufacturer id', (await hotswap.manufacturerId()) === 'NSC');
    const vin = await hotswap.voltageIn();
    checkTrue('vin readable', vin >= 0.0 && vin <= 24.0);
    const vout = await hotswap.voltageOut();
    checkTrue('vout readable', vout >= 0.0 && vout <= 24.0);
    checkTrue('current readable', (await hotswap.current()) > -1.0);
    checkTrue('power readable', (await hotswap.power()) > -5.0);
    const temp = await hotswap.temperature();
    checkTrue('temperature plausible', temp > -40.0 && temp < 150.0);
    const vaux = await hotswap.voltageAux();
    checkTrue('vaux readable', vaux >= 0.0 && vaux <= 1.2);
    checkTrue('block diagnostic', 'diagnostic' in (await hotswap.readBlock()));
    checkTrue('avg block', 'power' in (await hotswap.readAverage()));

    await hotswap.setAveraging(4);
    checkEq('averaging', await hotswap.getAveraging(), 4);
    await hotswap.setAveraging(0);

    await hotswap.setVinUvWarn(8.0);
    checkTrue('vin_uv_warn', Math.abs((await hotswap.getLimits()).vinUvWarn - 8.0) < 0.05);
    await hotswap.setVinUvWarn(null);
    checkTrue('vin_uv_warn disabled', (await hotswap.getLimits()).vinUvWarn === null);
    await hotswap.setOtWarn(110.0);
    checkTrue('ot_warn', Math.abs((await hotswap.getLimits()).otWarn - 110.0) < 0.1);
    await hotswap.setOtWarn(125.0);

    await hotswap.clearPowerPeak();
    checkTrue('peak cleared', (await hotswap.powerPeak()) >= 0.0);
    await hotswap.clearFaults();
    checkTrue('status_word readable', (await hotswap.statusWord()) >= 0);
    checkTrue('diagnostic readable', (await hotswap.diagnosticWord()) >= 0);
    checkEq('capability', await hotswap.capability(), 0xB0);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
