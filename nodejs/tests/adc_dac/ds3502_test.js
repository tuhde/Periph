'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { DS3502Minimal, DS3502Full } = require('../../packages/periph/src/chips/adc_dac/ds3502');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x28', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const pot = new DS3502Minimal(connection, 15.0, 0.0);
    await pot.init();
    checkTrue('init', true);

    await pot.setWiper(100);
    checkTrue('wiper roundtrip', (await pot.getWiper()) === 100);
    await pot.setWiper(300);
    checkTrue('wiper clamps 127', (await pot.getWiper()) === 127);
    await pot.setVoltage(6.0);
    checkTrue('set voltage', Math.abs((await pot.getVoltage()) - 6.0) < 0.1);

    const full = new DS3502Full(connection, 15.0, 0.0);
    await full.init();
    checkTrue('init mode sram', (await full.getMode()) === false);
    await full.setRatio(0.5);
    checkTrue('ratio half', (await full.getWiper()) === 64 && Math.abs((await full.getRatio()) - 0.5) < 0.01);
    await full.setWiper(100);
    checkTrue('resistance nominal', Math.abs((await full.getResistance('L')) + (await full.getResistance('H')) - 10000.0) < 1.0);
    checkTrue('increment', (await full.increment(5)) === 105 && (await full.getWiper()) === 105);
    checkTrue('increment clamps', (await full.increment(-500)) === 0);
    await full.setMode(true);
    checkTrue('set mode eeprom', (await full.getMode()) === true);
    await full.setMode(false);
    checkTrue('set mode sram', (await full.getMode()) === false);
    await full.waitReady();
    checkTrue('wait ready', true);
    // storeWiper is not exercised: it consumes one of the EEPROM's limited write cycles.

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
