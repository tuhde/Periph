'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { INA219Full } = require('../../packages/periph/src/chips/power/ina219');

const _REG_CONFIG  = 0x00;
const _REG_SHUNT   = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x04;
const _REG_CAL     = 0x05;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function approx(a, b, eps = 1e-9) {
    return Math.abs(a - b) < eps;
}

function bufEquals(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
}

async function main() {
    const connection = new I2CConnectionMock();

    // rShunt=0.1, maxCurrent=2.0 -> currentLsb=2.0/32768,
    // cal=floor(0.04096/(currentLsb*rShunt)) & 0xFFFE = 0x1A36.
    const sensor = new INA219Full(connection, 0.1, 2.0);
    // Constructor kicks off a fire-and-forget write; flush microtasks first.
    await new Promise((resolve) => setImmediate(resolve));
    checkTrue('init_writes_calibration',
        bufEquals(connection.writes[connection.writes.length - 1], Buffer.from([0x05, 0x1A, 0x36])));

    // Bus Voltage: raw=(1000<<3)|0b010 = 0x1F42 -> voltage=4.0V, CNVR=1, OVF=0.
    connection.setRegister(_REG_BUS, [0x1F, 0x42]);
    checkTrue('voltage', approx(await sensor.voltage(), 4.0));
    checkTrue('conversion_ready_true', (await sensor.conversionReady()) === true);
    checkTrue('overflow_false', (await sensor.overflow()) === false);

    // Bus Voltage: raw=(1000<<3)|0b001 = 0x1F41 -> CNVR=0, OVF=1.
    connection.setRegister(_REG_BUS, [0x1F, 0x41]);
    checkTrue('overflow_true', (await sensor.overflow()) === true);

    // Shunt Voltage: raw=-500 (0xFE0C) -> -0.005 V.
    connection.setRegister(_REG_SHUNT, [0xFE, 0x0C]);
    checkTrue('shunt_voltage', approx(await sensor.shuntVoltage(), -0.005));

    // Current: raw=1000 (0x03E8) -> 1000 * currentLsb.
    connection.setRegister(_REG_CURRENT, [0x03, 0xE8]);
    checkTrue('current', approx(await sensor.current(), 1000 * (2.0 / 32768)));

    // Power: raw=2000 (0x07D0) -> 2000 * 20 * currentLsb.
    connection.setRegister(_REG_POWER, [0x07, 0xD0]);
    checkTrue('power', approx(await sensor.power(), 2000 * 20 * (2.0 / 32768)));

    // configure(brng=0, pga=1, badc=0x0B, sadc=0x02, mode=5) -> config = 0x0D95;
    // re-writes Calibration afterward.
    await sensor.configure(0, 1, 0x0B, 0x02, 5);
    checkTrue('configure_writes_config',
        bufEquals(connection.writes[connection.writes.length - 2], Buffer.from([0x00, 0x0D, 0x95])));
    checkTrue('configure_rewrites_cal',
        bufEquals(connection.writes[connection.writes.length - 1], Buffer.from([0x05, 0x1A, 0x36])));

    // shutdown(): MODE forced to 0, other CONFIG bits preserved (0x0D95 -> 0x0D90).
    await sensor.shutdown();
    checkTrue('shutdown',
        bufEquals(connection.writes[connection.writes.length - 1], Buffer.from([0x00, 0x0D, 0x90])));

    // wake(): restores the previously configured mode (5) -> 0x0D95.
    await sensor.wake();
    checkTrue('wake',
        bufEquals(connection.writes[connection.writes.length - 1], Buffer.from([0x00, 0x0D, 0x95])));

    // trigger(): re-writes the current config unchanged.
    await sensor.trigger();
    checkTrue('trigger',
        bufEquals(connection.writes[connection.writes.length - 1], Buffer.from([0x00, 0x0D, 0x95])));

    // reset(): sets RST, re-writes Calibration. (This driver does not restore
    // the last configure()'d Configuration afterward.)
    await sensor.reset();
    checkTrue('reset_writes_rst',
        bufEquals(connection.writes[connection.writes.length - 2], Buffer.from([0x00, 0x80, 0x00])));
    checkTrue('reset_rewrites_cal',
        bufEquals(connection.writes[connection.writes.length - 1], Buffer.from([0x05, 0x1A, 0x36])));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
