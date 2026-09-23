'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MCP9808Minimal, MCP9808Full } = require('../../packages/periph/src/chips/temperature/mcp9808');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x18', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const minimal = new MCP9808Minimal(connection);
    await minimal.init();
    checkTrue('init', true);
    const t = await minimal.readTemperature();
    checkTrue('temperature plausible', t >= -40 && t <= 125);

    const full = new MCP9808Full(connection);
    await full.setResolution(0.5);
    checkTrue('resolution 0.5', (await full.getResolution()) === 0.5);
    await full.setResolution(0.0625);
    checkTrue('resolution 0.0625', (await full.getResolution()) === 0.0625);

    await full.setUpperLimit(80.0);
    checkTrue('upper limit roundtrip', (await full.getUpperLimit()) === 80.0);
    await full.setLowerLimit(-10.25);
    checkTrue('lower limit roundtrip', (await full.getLowerLimit()) === -10.25);
    await full.setCriticalLimit(100.0);
    checkTrue('critical limit roundtrip', (await full.getCriticalLimit()) === 100.0);

    await full.setHysteresis(1.5);
    checkTrue('hysteresis roundtrip', (await full.getHysteresis()) === 1.5);
    await full.setHysteresis(0);

    await full.shutdown();
    checkTrue('shutdown', await full.isShutdown());
    await full.wake();
    checkTrue('wake', !(await full.isShutdown()));

    // Lower limit above ambient forces TA < TLOWER; the status bit is live
    // regardless of whether the Alert output is enabled.
    await full.setLowerLimit(t + 20);
    await sleep(300);
    checkTrue('poll interrupt lower', ((await full.pollInterrupt()) & MCP9808Full.SOURCE_LOWER) !== 0);
    await full.setLowerLimit(-10.25);
    await sleep(300);
    checkTrue('poll interrupt clear', ((await full.pollInterrupt()) & MCP9808Full.SOURCE_LOWER) === 0);

    await full.configureAlert('all', 'comparator', 'active_low');
    await full.enableAlert();
    checkTrue('alert not asserted in window', !(await full.isAlertAsserted()));
    await full.disableAlert();
    await full.clearInterrupt();

    // The lock bits are one-way until power-on reset and are never set here.
    checkTrue('not critical locked', !(await full.isCriticalLimitLocked()));
    checkTrue('not window locked', !(await full.isWindowLimitsLocked()));

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
