'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { TMP102Minimal, TMP102Full } = require('../../packages/periph/src/chips/temperature/tmp102');

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
    const minimal = new TMP102Minimal(connection);
    const full = new TMP102Full(connection);
    await sleep(50);
    const t = await minimal.readTemperature();
    checkTrue('temperature plausible', t >= -40 && t <= 125);
    checkTrue('raw matches temperature', Math.abs((await full.readRaw()) * 0.0625 - t) < 1.0);
    checkTrue('default config', (((await full.readConfig()) & 0x6000) === 0x6000));

    await full.setConversionRate(1);
    checkTrue('rate roundtrip', (((await full.readConfig()) >> 6) & 3) === 1);
    await full.setConversionRate(4);

    await full.setTHigh(40.0);
    checkTrue('t_high roundtrip', (await full.getTHigh()) === 40.0);
    await full.setTLow(-10.25);
    checkTrue('t_low roundtrip', (await full.getTLow()) === -10.25);
    await full.setTHigh(80.0);
    await full.setTLow(75.0);

    await full.setFaultQueue(4);
    checkTrue('fault queue', (((await full.readConfig()) >> 11) & 3) === 2);
    await full.setFaultQueue(1);
    await full.setPolarity(true);
    checkTrue('polarity', ((await full.readConfig()) & 0x0400) !== 0);
    await full.setPolarity(false);
    await full.setThermostatMode(true);
    checkTrue('thermostat mode', ((await full.readConfig()) & 0x0200) !== 0);
    await full.setThermostatMode(false);

    await full.setExtendedMode(true);
    await sleep(300);
    checkTrue('extended mode reading', Math.abs((await full.readTemperature()) - t) < 3.0);
    await full.setExtendedMode(false);

    await full.shutdown();
    checkTrue('shutdown', ((await full.readConfig()) & 0x0100) !== 0);
    const start = Date.now();
    const t1 = await full.oneShot();
    checkTrue('one-shot plausible', t1 >= -40 && t1 <= 125);
    checkTrue('one-shot fast', Date.now() - start < 100);
    checkTrue('one-shot stays shutdown', ((await full.readConfig()) & 0x0100) !== 0);
    await full.wake();
    checkTrue('wake', ((await full.readConfig()) & 0x0100) === 0);

    // T_HIGH below ambient forces an alert after the fault queue elapses.
    await full.setThermostatMode(false);
    await full.setTLow(t - 21);
    await full.setTHigh(t - 20);
    await sleep(500);
    checkTrue('alert state active', await full.alertState());
    checkTrue('poll interrupt active', await full.pollInterrupt());
    await full.setTHigh(80.0);
    await full.setTLow(75.0);
    await sleep(500);
    checkTrue('alert state clear', !(await full.alertState()));

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
