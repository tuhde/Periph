'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { INA260Full } = require('../../packages/periph/src/chips/power/ina260');

const _REG_CONFIG  = 0x00;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x01;
const _REG_MASK    = 0x06;
const _REG_ALERT   = 0x07;
const _REG_MFR_ID  = 0xFE;
const _REG_DIE_ID  = 0xFF;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnectionMock();

    // Construction makes no bus access: no calibration, chip power-on state is the default.
    const sensor = new INA260Full(connection);
    checkTrue('init_no_writes', connection.writes.length === 0);

    // Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
    connection.setRegister(_REG_BUS, [0x19, 0x00]);
    checkTrue('voltage', (await sensor.voltage()) === 8.0);

    // Current: raw signed = -100 (0xFF9C) -> -0.125 A
    connection.setRegister(_REG_CURRENT, [0xFF, 0x9C]);
    checkTrue('current_negative', Math.abs((await sensor.current()) - (-0.125)) < 1e-12);

    // Current: datasheet example 0x2710 = 10000 -> 12.5 A
    connection.setRegister(_REG_CURRENT, [0x27, 0x10]);
    checkTrue('current', Math.abs((await sensor.current()) - 12.5) < 1e-12);

    // Power: datasheet example 0x3A7F = 14975 -> 149.75 W
    connection.setRegister(_REG_POWER, [0x3A, 0x7F]);
    checkTrue('power', Math.abs((await sensor.power()) - 149.75) < 1e-9);

    const last = () => connection.writes[connection.writes.length - 1];
    const is = (w, reg, hi, lo) => w.length === 3 && w[0] === reg && w[1] === hi && w[2] === lo;

    // configure(avg=2, vbusCt=3, ishCt=5, mode=6) -> 0x6000|(2<<9)|(3<<6)|(5<<3)|6 = 0x64EE
    await sensor.configure(2, 3, 5, 6);
    checkTrue('configure', is(last(), _REG_CONFIG, 0x64, 0xEE));

    // trigger(): same settings, MODE = 3 -> 0x64EB
    await sensor.trigger();
    checkTrue('trigger', is(last(), _REG_CONFIG, 0x64, 0xEB));

    // conversionReady(): CVRF bit (0x0008)
    connection.setRegister(_REG_MASK, [0x00, 0x08]);
    checkTrue('conversion_ready_true', (await sensor.conversionReady()) === true);
    connection.setRegister(_REG_MASK, [0x00, 0x00]);
    checkTrue('conversion_ready_false', (await sensor.conversionReady()) === false);

    // overflow(): OVF bit (0x0004)
    connection.setRegister(_REG_MASK, [0x00, 0x04]);
    checkTrue('overflow_true', (await sensor.overflow()) === true);

    // setAlert(POL, 1.5 W, polarity, latch): limit 150 (0x0096) written first, then mask 0x0803
    connection.writes.length = 0;
    await sensor.setAlert(INA260Full.POL, 1.5, 1, 1);
    checkTrue('set_alert_pol_limit_first', connection.writes.length === 2 &&
        is(connection.writes[0], _REG_ALERT, 0x00, 0x96) && is(connection.writes[1], _REG_MASK, 0x08, 0x03));

    // setAlert(OCL, 5.0 A): 4000 (0x0FA0), mask 0x8000
    connection.writes.length = 0;
    await sensor.setAlert(INA260Full.OCL, 5.0);
    checkTrue('set_alert_ocl', connection.writes.length === 2 &&
        is(connection.writes[0], _REG_ALERT, 0x0F, 0xA0) && is(connection.writes[1], _REG_MASK, 0x80, 0x00));

    // setAlert(UCL, -1.0 A): -800 -> 0xFCE0
    connection.writes.length = 0;
    await sensor.setAlert(INA260Full.UCL, -1.0);
    checkTrue('set_alert_ucl_negative', is(connection.writes[0], _REG_ALERT, 0xFC, 0xE0));

    // setAlert(CNVR): limit register not written
    connection.writes.length = 0;
    await sensor.setAlert(INA260Full.CNVR);
    checkTrue('set_alert_cnvr', connection.writes.length === 1 && is(connection.writes[0], _REG_MASK, 0x04, 0x00));

    // clearAlert(): Mask/Enable = 0
    await sensor.clearAlert();
    checkTrue('clear_alert', is(last(), _REG_MASK, 0x00, 0x00));

    // alertFlags(): raw Mask/Enable register
    connection.setRegister(_REG_MASK, [0x08, 0x03]);
    checkTrue('alert_flags', (await sensor.alertFlags()) === 0x0803);

    // reset(): CONFIG = 0x8000
    await sensor.reset();
    checkTrue('reset', is(last(), _REG_CONFIG, 0x80, 0x00));

    // shutdown(): MODE = 000 with default settings -> 0x6120
    await sensor.shutdown();
    checkTrue('shutdown', is(last(), _REG_CONFIG, 0x61, 0x20));

    // wake(): restore MODE = 111 -> 0x6127
    await sensor.wake();
    checkTrue('wake', is(last(), _REG_CONFIG, 0x61, 0x27));

    // manufacturerId() / dieId()
    connection.setRegister(_REG_MFR_ID, [0x54, 0x49]);
    checkTrue('manufacturer_id', (await sensor.manufacturerId()) === 0x5449);
    connection.setRegister(_REG_DIE_ID, [0x22, 0x70]);
    checkTrue('die_id', (await sensor.dieId()) === 0x2270);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
