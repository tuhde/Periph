'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ADS1013Minimal, ADS1013Full } = require('../../packages/periph/src/chips/adc_dac/ads1013');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x48', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const adc = new ADS1013Minimal(connection);
    checkTrue('construct minimal', adc instanceof ADS1013Minimal);
    const raw = await adc.readRaw();
    checkTrue('raw in range', raw >= -2048 && raw <= 2047);
    checkTrue('voltage matches raw', Math.abs((await adc.readVoltage()) - 0.001 * raw) < 0.05);

    const full = new ADS1013Full(connection);
    await full.reset();
    checkTrue('reset config', ((await full.readConfig()) & 0x7FFF) === 0x0583);
    checkTrue('full scale fixed', full.fullScale() === 2.048 && full.lsb() === 0.001);

    await full.setDataRate(ADS1013Full.DR_250);
    checkTrue('dr roundtrip', full.getDataRate() === ADS1013Full.DR_250 && full.dataRateSps() === 250);
    await full.readRaw();
    checkTrue('config dr bits', (((await full.readConfig()) >> 5) & 0x7) === ADS1013Full.DR_250);
    await full.setDataRate(ADS1013Full.DR_1600);

    await full.startContinuous();
    await sleep(50);
    const cont = await full.readContinuousRaw();
    checkTrue('continuous raw in range', cont >= -2048 && cont <= 2047);
    checkTrue('continuous mode bit', (await full.readConfig() & 0x0100) === 0);
    await full.stopContinuous();
    checkTrue('stop mode bit', ((await full.readConfig()) & 0x0100) !== 0);

    await full.reset();
    checkTrue('final reset', ((await full.readConfig()) & 0x7FFF) === 0x0583);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
