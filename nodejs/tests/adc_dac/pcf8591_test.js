'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { PCF8591Full }   = require('../../packages/periph/src/chips/adc_dac/pcf8591');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x48', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const adc = new PCF8591Full(connection);

    const ch0 = await adc.read_channel(0);
    checkTrue('read_channel(0) in [0, 255]', ch0 >= 0 && ch0 <= 255);

    const ch3 = await adc.read_channel(3);
    checkTrue('read_channel(3) in [0, 255]', ch3 >= 0 && ch3 <= 255);

    const chOob = await adc.read_channel(99);
    checkTrue('read_channel(99) clamped to valid range', chOob >= 0 && chOob <= 255);

    const allRaw = await adc.read_all();
    checkTrue('read_all returns array', Array.isArray(allRaw));
    checkTrue('read_all returns 4 values', allRaw.length === 4);
    for (const v of allRaw) {
        checkTrue('read_all value in [0, 255]', v >= 0 && v <= 255);
    }

    const v0 = await adc.read_channel_voltage(0, 3.3, 0.0);
    checkTrue('read_channel_voltage returns number', typeof v0 === 'number');
    checkTrue('read_channel_voltage in [0, 3.3]', v0 >= 0.0 && v0 <= 3.3);

    const vAll = await adc.read_all_voltage(3.3, 0.0);
    checkTrue('read_all_voltage returns 4 numbers', vAll.length === 4 && vAll.every(v => typeof v === 'number'));

    await adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, false, false);
    checkTrue('configure 4 single-ended accepted', true);

    await adc.configure(PCF8591Full.MODE_3_DIFFERENTIAL, false, false);
    const diff = await adc.read_differential(0);
    checkTrue('read_differential in [-128, 127]', diff >= -128 && diff <= 127);

    await adc.configure(PCF8591Full.MODE_MIXED, false, false);
    checkTrue('configure mixed mode accepted', true);

    await adc.configure(PCF8591Full.MODE_2_DIFFERENTIAL, false, false);
    checkTrue('configure 2 differential accepted', true);

    await adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, true, false);
    const auto = await adc.read_all();
    checkTrue('read_all with auto-increment returns 4 values', auto.length === 4);

    await adc.configure(PCF8591Full.MODE_4_SINGLE_ENDED, false, true);
    checkTrue('configure enables DAC', true);

    await adc.set_dac(0);
    checkTrue('set_dac(0) accepted', true);

    await adc.set_dac(255);
    checkTrue('set_dac(255) accepted', true);

    await adc.set_dac(128);
    checkTrue('set_dac(128) accepted', true);

    await adc.set_dac_voltage(0.0);
    checkTrue('set_dac_voltage(0.0) accepted', true);

    await adc.set_dac_voltage(1.0);
    checkTrue('set_dac_voltage(1.0) accepted', true);

    await adc.set_dac_voltage(0.5);
    checkTrue('set_dac_voltage(0.5) accepted', true);

    await adc.disable_dac();
    checkTrue('disable_dac accepted', true);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
