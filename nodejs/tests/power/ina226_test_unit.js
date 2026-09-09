'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { INA226Full } = require('../../packages/periph/src/chips/power/ina226');

const _REG_CONFIG  = 0x00;
const _REG_SHUNT   = 0x01;
const _REG_BUS     = 0x02;
const _REG_POWER   = 0x03;
const _REG_CURRENT = 0x04;
const _REG_CAL     = 0x05;
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

    // Construction: rShunt=0.1, maxCurrent=2.0 (defaults) -> currentLsb=6.103515625e-5,
    // cal=Math.floor(0.00512/(currentLsb*0.1))=838 (0x0346). Constructor writes CONFIG then CAL.
    const sensor = new INA226Full(connection);
    checkTrue('init', true);

    const configWrites = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CONFIG);
    checkTrue('init_writes_config_default', configWrites[0][1] === 0x41 && configWrites[0][2] === 0x27);

    const calWrites = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CAL);
    checkTrue('init_writes_calibration', calWrites[0][1] === 0x03 && calWrites[0][2] === 0x46);

    // Bus voltage: raw=6400 (0x1900) -> 6400 * 1.25e-3 = 8.0 V
    connection.setRegister(_REG_BUS, [0x19, 0x00]);
    checkTrue('voltage', (await sensor.voltage()) === 8.0);

    // Shunt voltage: raw signed = -100 (0xFF9C) -> -100 * 2.5e-6 V
    connection.setRegister(_REG_SHUNT, [0xFF, 0x9C]);
    checkTrue('shunt_voltage', Math.abs((await sensor.shuntVoltage()) - (-2.5e-4)) < 1e-12);

    // Current: raw signed = 1000 (0x03E8) -> 1000 * currentLsb
    connection.setRegister(_REG_CURRENT, [0x03, 0xE8]);
    checkTrue('current', Math.abs((await sensor.current()) - (1000 * sensor._currentLsb)) < 1e-12);

    // Power: raw = 500 (0x01F4) -> 500 * 25 * currentLsb
    connection.setRegister(_REG_POWER, [0x01, 0xF4]);
    checkTrue('power', Math.abs((await sensor.power()) - (500 * 25 * sensor._currentLsb)) < 1e-9);

    // configure(avg=2, vbusCt=3, vshCt=5, mode=6) -> config = 0x04EE
    await sensor.configure(2, 3, 5, 6);
    const lastConfigWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_CONFIG).pop();
    checkTrue('configure', lastConfigWrite[1] === 0x04 && lastConfigWrite[2] === 0xEE);

    // conversionReady(): CVRF bit (0x0008)
    connection.setRegister(_REG_MASK, [0x00, 0x08]);
    checkTrue('conversion_ready_true', (await sensor.conversionReady()) === true);
    connection.setRegister(_REG_MASK, [0x00, 0x00]);
    checkTrue('conversion_ready_false', (await sensor.conversionReady()) === false);

    // overflow(): OVF bit (0x0004)
    connection.setRegister(_REG_MASK, [0x00, 0x04]);
    checkTrue('overflow_true', (await sensor.overflow()) === true);

    // setAlert(POL, limit=1.5, polarity=1, latch=1):
    // raw = floor(1.5 / (25*currentLsb)) = 983 (0x03D7); mask = POL|0x0002|0x0001 = 0x0803
    await sensor.setAlert(INA226Full.POL, 1.5, 1, 1);
    const maskWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_MASK).pop();
    const alertWrite = connection.writes.filter((w) => w.length === 3 && w[0] === _REG_ALERT).pop();
    checkTrue('set_alert_mask', maskWrite[1] === 0x08 && maskWrite[2] === 0x03);
    checkTrue('set_alert_limit', alertWrite[1] === 0x03 && alertWrite[2] === 0xD7);

    // alertFlags(): raw Mask/Enable register
    connection.setRegister(_REG_MASK, [0x08, 0x03]);
    checkTrue('alert_flags', (await sensor.alertFlags()) === 0x0803);

    // reset(): writes CONFIG=0x8000, then re-writes CAL
    await sensor.reset();
    const writesAfterReset = connection.writes.slice(-2);
    checkTrue('reset_config', writesAfterReset[0][0] === _REG_CONFIG && writesAfterReset[0][1] === 0x80 && writesAfterReset[0][2] === 0x00);
    checkTrue('reset_cal', writesAfterReset[1][0] === _REG_CAL && writesAfterReset[1][1] === 0x03 && writesAfterReset[1][2] === 0x46);

    // shutdown(): reads CONFIG, saves mode, writes CONFIG & 0xFFF8
    connection.setRegister(_REG_CONFIG, [0x41, 0x27]);
    await sensor.shutdown();
    const shutdownWrite = connection.writes[connection.writes.length - 1];
    checkTrue('shutdown', shutdownWrite[0] === _REG_CONFIG && shutdownWrite[1] === 0x41 && shutdownWrite[2] === 0x20);

    // wake(): reads CONFIG, writes back with saved mode restored
    connection.setRegister(_REG_CONFIG, [0x41, 0x20]);
    await sensor.wake();
    const wakeWrite = connection.writes[connection.writes.length - 1];
    checkTrue('wake', wakeWrite[0] === _REG_CONFIG && wakeWrite[1] === 0x41 && wakeWrite[2] === 0x27);

    // manufacturerId() / dieId()
    connection.setRegister(_REG_MFR_ID, [0x54, 0x49]);
    checkTrue('manufacturer_id', (await sensor.manufacturerId()) === 0x5449);
    connection.setRegister(_REG_DIE_ID, [0x22, 0x60]);
    checkTrue('die_id', (await sensor.dieId()) === 0x2260);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
