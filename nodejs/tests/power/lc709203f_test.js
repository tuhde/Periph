'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LC709203FFull }   = require('../../packages/periph/src/chips/power/lc709203f');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x0B', 16);

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
    const gauge = new LC709203FFull(connection);
    await gauge.ready;

    const v = await gauge.voltage();
    checkTrue('voltage in Li-ion range', v >= 2.5 && v <= 4.6);
    const pc = await gauge.percent();
    checkTrue('percent in range', pc >= 0 && pc <= 100);
    const t = await gauge.temperature();
    checkTrue('temperature plausible', t > 0 && t < 60);
    const r = await gauge.rsoc();
    checkTrue('rsoc in range', r >= 0 && r <= 100);
    checkTrue('profile_id readable', (await gauge.profileId()) > 0);
    checkEq('get_profile', await gauge.getProfile(), 1);

    await gauge.setTemperature(30.0);
    checkTrue('set_temperature', Math.abs((await gauge.temperature()) - 30.0) < 0.2);
    await gauge.setApa(0x19);
    checkEq('apa', await gauge.getApa(), 0x19);
    await gauge.setApt(0x1E);
    checkEq('apt', await gauge.getApt(), 0x1E);
    await gauge.setCurrentDirection(LC709203FFull.DIRECTION_AUTO);
    checkEq('direction', await gauge.getCurrentDirection(), 0);
    await gauge.setAlarmRsoc(20);
    checkEq('alarm_rsoc', await gauge.getAlarmRsoc(), 20);
    await gauge.setAlarmVoltage(3.5);
    checkTrue('alarm_voltage', Math.abs((await gauge.getAlarmVoltage()) - 3.5) < 0.001);
    checkTrue('poll_interrupt', (await gauge.pollInterrupt()) <= 3);

    await gauge.initRsoc();
    const r2 = await gauge.rsoc();
    checkTrue('rsoc after init_rsoc', r2 >= 0 && r2 <= 100);

    await gauge.sleep();
    checkTrue('is_sleeping', await gauge.isSleeping());
    await gauge.wake();
    checkTrue('wake: not sleeping', !(await gauge.isSleeping()));
    checkTrue('wake: voltage readable', (await gauge.voltage()) > 0);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
