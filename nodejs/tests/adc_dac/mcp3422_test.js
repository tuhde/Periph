'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MCP3422Minimal, MCP3422Full } = require('../../packages/periph/src/chips/adc_dac/mcp3422');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const adc = new MCP3422Minimal(connection);
    await adc.init();
    checkTrue('init', true);
    const raw = await adc.readRaw(0);
    checkTrue('raw in 16 bit range', raw >= -32768 && raw <= 32767);
    const volts = await adc.readVoltage(1);
    checkTrue('voltage in range', volts >= -2.048 && volts <= 2.048);
    checkTrue('read all two values', (await adc.readAll()).length === 2);

    const full = new MCP3422Full(connection);
    await full.init();
    await full.setResolution(12);
    await full.setGain(2);
    checkTrue('selection roundtrip', full.getResolution() === 12 && full.getGain() === 2);
    checkTrue('full scale', Math.abs(full.fullScale() - 1.024) < 1e-9);
    const r12 = await full.readRaw(0);
    checkTrue('one shot 12 bit range', r12 >= -2048 && r12 <= 2047);
    await full.setGain(1);
    await full.startContinuous(0);
    checkTrue('continuous flag', full.isContinuous());
    const rc = await full.readContinuousRaw();
    checkTrue('continuous sample in range', rc >= -2048 && rc <= 2047);
    checkTrue('read config continuous bit', ((await full.readConfig()) & 0x10) !== 0);
    await full.stopContinuous();
    checkTrue('stopped', !full.isContinuous());
    checkTrue('read scan length', (await full.readScan([0, 1])).length === 2);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
