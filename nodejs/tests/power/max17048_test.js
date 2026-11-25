'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MAX17048Full }   = require('../../packages/periph/src/chips/power/max17048');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x36', 16);

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
    const gauge = new MAX17048Full(connection);

    const v = await gauge.voltage();
    checkTrue('voltage in Li-ion range', v >= 2.5 && v <= 4.6);
    const p = await gauge.percent();
    checkTrue('percent in range', p >= 0 && p <= 100);
    checkTrue('version', ((await gauge.version()) & 0xFFF0) === 0x0010);
    checkTrue('raw_voltage matches voltage', Math.abs(((await gauge.rawVoltage()) >> 4) * 1.25e-3 - v) < 0.05);
    checkTrue('charge_rate readable', Math.abs(await gauge.chargeRate()) < 1000);

    await gauge.setRcomp(0x97);
    checkEq('rcomp', await gauge.getRcomp(), 0x97);
    await gauge.compensateTemperature(20);
    checkEq('compensate 20 C', await gauge.getRcomp(), 0x97);
    await gauge.setAlertSoc(10);
    checkEq('alert_soc', await gauge.getAlertSoc(), 10);
    await gauge.setAlertSoc(4);

    await gauge.setVoltageAlert(3.0, 4.2);
    const w = await gauge.getVoltageAlert();
    checkTrue('voltage_alert', Math.abs(w.minV - 3.0) < 0.001 && Math.abs(w.maxV - 4.2) < 0.001);
    await gauge.setVoltageAlert(0, 5.1);

    await gauge.setResetVoltage(2.8);
    checkTrue('reset_voltage', Math.abs((await gauge.getResetVoltage()) - 2.8) < 0.001);
    await gauge.setResetVoltage(3.0);

    await gauge.setHibernateThresholds(26.624, 0.06);
    const h = await gauge.getHibernateThresholds();
    checkTrue('hibernate_thresholds', Math.abs(h.hibThrPctPerH - 26.624) < 0.001 && Math.abs(h.actThrV - 0.06) < 0.001);

    await gauge.clearStatus(0x3F00);
    checkEq('status flags cleared', (await gauge.readStatus()) & 0x3F00, 0);
    checkEq('poll_interrupt', await gauge.pollInterrupt(), 0);

    await gauge.writeConfig(await gauge.readConfig());
    checkEq('config RCOMP', (await gauge.readConfig()) >> 8, 0x97);

    await gauge.enableSleep(true);
    await gauge.sleep();
    await new Promise((r) => setTimeout(r, 10));
    await gauge.wake();
    await gauge.enableSleep(false);
    checkTrue('wake: voltage readable', (await gauge.voltage()) > 0);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
