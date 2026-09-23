'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { TMP117Minimal, TMP117Full } = require('../../packages/periph/src/chips/temperature/tmp117');

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
    const minimal = new TMP117Minimal(connection);
    await minimal.init();
    checkTrue('init', true);

    const full = new TMP117Full(connection);
    await full.configure('continuous', 8, 0.125);
    await sleep(300);
    const t = await minimal.readTemperature();
    checkTrue('temperature plausible', t >= -40 && t <= 125);

    await full.configure('continuous', 32, 4.0);
    const cfg = await full.getConfig();
    checkTrue('config roundtrip', cfg.mode === 'continuous' && cfg.averaging === 32 && cfg.cycleSeconds === 4.0);
    await full.configure('shutdown', 0, 0.0155);
    checkTrue('shutdown', await full.isShutdown());
    await full.triggerOneShot();
    await sleep(50);
    checkTrue('one-shot data ready', await full.isDataReady());
    checkTrue('one-shot returns to shutdown', await full.isShutdown());
    await full.configure();
    checkTrue('continuous', !(await full.isShutdown()));

    await full.setHighLimit(80.0);
    checkTrue('high limit roundtrip', (await full.getHighLimit()) === 80.0);
    await full.setLowLimit(-10.25);
    checkTrue('low limit roundtrip', (await full.getLowLimit()) === -10.25);
    await full.setTemperatureOffset(0.5);
    checkTrue('offset roundtrip', (await full.getTemperatureOffset()) === 0.5);
    await full.setTemperatureOffset(0.0);

    // The EEPROM is never unlocked here, so no power-on default changes.
    checkTrue('eeprom not busy', !(await full.isEepromBusy()));
    await full.writeEepromScratch(2, 0xA55A);
    checkTrue('eeprom2 volatile roundtrip', (await full.readEepromScratch(2)) === 0xA55A);

    // High limit below ambient forces HIGH_Alert on the next conversion; in
    // Alert mode the flag latches until CONFIGURATION is read.
    await full.configureAlert('alert', 'active_low', 'alert');
    await full.configure('continuous', 0, 0.0155);
    await full.setHighLimit(t - 20);
    await sleep(100);
    checkTrue('poll interrupt high', ((await full.pollInterrupt()) & TMP117Full.SOURCE_HIGH) !== 0);
    await full.setHighLimit(80.0);
    await sleep(100);
    await full.pollInterrupt();
    checkTrue('poll interrupt clear', ((await full.pollInterrupt()) & TMP117Full.SOURCE_HIGH) === 0);

    // Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
    await full.reset();
    checkTrue('reset restores config', (await full.getConfig()).mode === 'continuous');

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
