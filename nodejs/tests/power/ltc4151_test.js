'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LTC4151Full }   = require('../../packages/periph/src/chips/power/ltc4151');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x6A', 16);

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
    const ltc = new LTC4151Full(connection);

    checkTrue('voltage in range',     (await ltc.voltage()) >= 7.0 && (await ltc.voltage()) <= 80.0);
    checkTrue('current non-negative', (await ltc.current()) >= 0.0);
    checkTrue('power non-negative',   (await ltc.power())   >= 0.0);
    const sv = await ltc.senseVoltage();
    checkTrue('sense voltage range',  sv >= 0.0 && sv <= 0.08192);
    const av = await ltc.adinVoltage();
    checkTrue('adin range',           av >= 0.0 && av <= 2.048);

    const d = await ltc.readAll();
    checkTrue('read_all vin',         d.vin >= 7.0 && d.vin <= 80.0);
    const raw = await ltc.readRaw(LTC4151Full.VIN);
    checkTrue('read_raw vin',         raw > 0 && raw < 4096);

    checkEq('control default',        await ltc.readControl(), 0x0C);
    checkTrue('not snapshot mode',    !(await ltc.isSnapshotMode()));

    const snap = await ltc.snapshot(LTC4151Full.VIN);
    checkTrue('snapshot vin',         snap >= 7.0 && snap <= 80.0);
    checkTrue('back in continuous',   !(await ltc.isSnapshotMode()));

    await ltc.setStuckBusTimer(false);
    checkEq('stuck-bus timer off',    await ltc.readControl(), 0x08);
    await ltc.setStuckBusTimer(true);
    checkEq('stuck-bus timer on',     await ltc.readControl(), 0x0C);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
