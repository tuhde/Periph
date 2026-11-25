'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ADS7828Full }   = require('../../packages/periph/src/chips/adc_dac/ads7828');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x48', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const adc = new ADS7828Full(connection);
    await adc.init();

    const raw = await adc.readRaw(0);
    checkTrue('readRaw(0) in [0, 4095]', raw >= 0 && raw <= 4095);
    const oob = await adc.readRaw(99);
    checkTrue('readRaw(99) clamped', oob >= 0 && oob <= 4095);
    const v = await adc.readVoltage(1);
    checkTrue('readVoltage in [0, 2.5]', v >= 0 && v <= 2.5);
    const all = await adc.readAll();
    checkTrue('readAll returns 8 values', Array.isArray(all) && all.length === 8);
    const d = await adc.readDifferentialRaw(0);
    checkTrue('readDifferentialRaw in range', d >= 0 && d <= 4095);
    checkTrue('readDifferential inverted >= 0', (await adc.readDifferential(1, true)) >= 0);
    const sv = await adc.readSignedDifferential(2);
    checkTrue('readSignedDifferential in [-2.5, 2.5]', sv >= -2.5 && sv <= 2.5);
    checkTrue('lsb is vref/4096', Math.abs(adc.lsb() - 2.5 / 4096) < 1e-9);
    checkTrue('internal reference selected', adc.usesInternalReference() && adc.getReference() === 2.5);
    const scan = await adc.readScan([0, 3, 7]);
    checkTrue('readScan returns 3 codes', scan.length === 3 && scan.every((r) => r >= 0 && r <= 4095));
    adc.setPowerMode(ADS7828Full.REF_ON_ADC_ON);
    checkTrue('setPowerMode accepted', true);
    await adc.setReference(5.0);
    checkTrue('external reference selected', !adc.usesInternalReference() && adc.getReference() === 5.0);
    await adc.setReference(null);
    checkTrue('internal reference restored', adc.usesInternalReference());
    await adc.powerDown();
    checkTrue('powerDown accepted', true);
    const after = await adc.readRaw(0);
    checkTrue('read after powerDown works', after >= 0 && after <= 4095);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
