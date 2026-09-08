'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AS5600Full } = require('../../packages/periph/src/chips/magnetometer/as5600');

const _REG_ZMCO        = 0x00;
const _REG_CONF_H      = 0x07;
const _REG_CONF_L      = 0x08;
const _REG_STATUS      = 0x0B;
const _REG_RAW_ANGLE_H = 0x0C;
const _REG_ANGLE_H     = 0x0E;
const _REG_AGC         = 0x1A;
const _REG_MAGNITUDE_H = 0x1B;
const _REG_BURN        = 0xFF;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    const connection = new I2CConnectionMock();
    // STATUS: MD=1 (magnet detected), MH=0, ML=0.
    connection.setRegister(_REG_STATUS, [0x08]);

    const sensor = new AS5600Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish before asserting on it
    checkTrue('init', true);

    checkTrue('is_magnet_detected', await sensor.isMagnetDetected());
    checkTrue('is_magnet_too_strong_false', !(await sensor.isMagnetTooStrong()));
    checkTrue('is_magnet_too_weak_false', !(await sensor.isMagnetTooWeak()));

    // ANGLE burst (0x0E-0x0F): H=0x01, L=0x23 -> raw = 0x0123 = 291.
    connection.setRegister(_REG_ANGLE_H, [0x01, 0x23]);
    checkTrue('angle_raw', (await sensor.angleRaw()) === 291);
    checkTrue('angle', Math.abs((await sensor.angle()) - (291 * 360.0 / 4096)) < 1e-9);

    // RAW_ANGLE burst (0x0C-0x0D): H=0x02, L=0x00 -> raw = 512 -> 45.0 degrees.
    connection.setRegister(_REG_RAW_ANGLE_H, [0x02, 0x00]);
    checkTrue('raw_angle', (await sensor.rawAngle()) === 512);
    checkTrue('raw_angle_degrees', Math.abs((await sensor.rawAngleDegrees()) - 45.0) < 1e-9);

    connection.setRegister(_REG_AGC, [128]);
    checkTrue('agc', (await sensor.agc()) === 128);

    // MAGNITUDE burst (0x1B-0x1C): H=0x00, L=0x64 -> raw = 100.
    connection.setRegister(_REG_MAGNITUDE_H, [0x00, 0x64]);
    checkTrue('magnitude', (await sensor.magnitude()) === 100);

    // STATUS: MD=1, MH=1 (magnet too strong).
    connection.setRegister(_REG_STATUS, [0x28]);
    checkTrue('is_magnet_too_strong_true', await sensor.isMagnetTooStrong());
    checkTrue('status_byte', (await sensor.statusByte()) === 0x28);

    // configure() must preserve CONF_H[7:6] reserved bits (preloaded as 0xC5).
    connection.setRegister(_REG_CONF_H, [0xC5, 0x00]);
    await sensor.configure(1, 2, 1, 3, 2, 5, true);
    checkTrue('configure',
        connection.registers.get(_REG_CONF_H) === 0xF6 && connection.registers.get(_REG_CONF_L) === 0xD9);

    await sensor.setZeroPosition(1000);
    checkTrue('zero_position', (await sensor.zeroPosition()) === 1000);

    await sensor.setMaxPosition(2000);
    checkTrue('max_position', (await sensor.maxPosition()) === 2000);

    await sensor.setMaxAngle(2048);
    checkTrue('max_angle', (await sensor.maxAngle()) === 2048);

    connection.setRegister(_REG_ZMCO, [0x02]);
    checkTrue('burn_count', (await sensor.burnCount()) === 2);

    // burnAngle(): MD=1 (STATUS=0x28), ZMCO=2 < 3 -> succeeds, writes BURN=0x80.
    await sensor.burnAngle();
    const lastWrite1 = connection.writes[connection.writes.length - 1];
    checkTrue('burn_angle', lastWrite1.length === 2 && lastWrite1[0] === _REG_BURN && lastWrite1[1] === 0x80);

    // burnSetting(): requires ZMCO=0.
    connection.setRegister(_REG_ZMCO, [0x00]);
    await sensor.burnSetting();
    const lastWrite2 = connection.writes[connection.writes.length - 1];
    checkTrue('burn_setting', lastWrite2.length === 2 && lastWrite2[0] === _REG_BURN && lastWrite2[1] === 0x40);

    // burnAngle() must throw when magnet not detected.
    connection.setRegister(_REG_STATUS, [0x00]);
    let threw = false;
    try { await sensor.burnAngle(); } catch (e) { threw = true; }
    checkTrue('burn_angle_throws_no_magnet', threw);

    // burnAngle() must throw when ZMCO limit (3) reached.
    connection.setRegister(_REG_STATUS, [0x08]);
    connection.setRegister(_REG_ZMCO, [0x03]);
    threw = false;
    try { await sensor.burnAngle(); } catch (e) { threw = true; }
    checkTrue('burn_angle_throws_zmco_limit', threw);

    // burnSetting() must throw when ZMCO != 0.
    connection.setRegister(_REG_ZMCO, [0x01]);
    threw = false;
    try { await sensor.burnSetting(); } catch (e) { threw = true; }
    checkTrue('burn_setting_throws_zmco_nonzero', threw);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
