'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMP180Full } = require('../../packages/periph/src/chips/pressure/bmp180');

const _REG_ID         = 0xD0;
const _REG_CAL_START  = 0xAA;
const _REG_CTRL_MEAS  = 0xF4;
const _REG_OUT_MSB    = 0xF6;
const _REG_SOFT_RESET = 0xE0;
const _CMD_TEMP       = 0x2E;
const _CMD_PRESSURE_OSS0 = 0x34;
const _SOFT_RESET_CMD = 0xB6;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function preloadCalibration(connection) {
    // Datasheet worked example (Figure 4, page 15): AC1=408, AC2=-72,
    // AC3=-14383, AC4=32741, AC5=32757, AC6=23153, B1=6190, B2=4,
    // MB=-32768, MC=-8711, MD=2868.
    connection.setRegister(_REG_CAL_START, [
        0x01, 0x98, // AC1 = 408
        0xFF, 0xB8, // AC2 = -72
        0xC7, 0xD1, // AC3 = -14383
        0x7F, 0xE5, // AC4 = 32741
        0x7F, 0xF5, // AC5 = 32757
        0x5A, 0x71, // AC6 = 23153
        0x18, 0x2E, // B1 = 6190
        0x00, 0x04, // B2 = 4
        0x80, 0x00, // MB = -32768
        0xDD, 0xF9, // MC = -8711
        0x0B, 0x34, // MD = 2868
    ]);
}

async function main() {
    const connection = new I2CConnectionMock();
    preloadCalibration(connection);

    // Construction fires _readCalibration() unawaited (fire-and-forget) -
    // flush microtasks before asserting on it, matching the ENS160 JS test.
    const sensor = new BMP180Full(connection);
    await flushMicrotasks();
    checkTrue('init', true);

    // pressure() re-reads OUT_MSB for both UT (2 bytes) and UP (3 bytes)
    // from the same register within one call, and this mock always returns
    // the register map's current contents - it cannot hand back a different
    // UT then a different UP within a single call. So UT and the top 16
    // bits of UP are necessarily the same value here (0x6CFA = 27898); the
    // expected T/p below are computed from the real compensation formula
    // with UT=UP=27898, not the datasheet's mismatched worked example.
    connection.setRegister(_REG_OUT_MSB, [0x6C, 0xFA]);
    checkTrue('temperature', (await sensor.temperature()) === 15.0);

    const cmdTempWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS && w[1] === _CMD_TEMP);
    checkTrue('temperature_writes_cmd_temp', cmdTempWrites.length > 0);

    connection.setRegister(_REG_OUT_MSB, [0x6C, 0xFA]);
    checkTrue('pressure', Math.abs((await sensor.pressure()) - 820.8) < 1e-6);

    const cmdPressureWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS && w[1] === _CMD_PRESSURE_OSS0);
    checkTrue('pressure_writes_cmd_pressure_oss0', cmdPressureWrites.length > 0);

    // chipId(): expect 0x55.
    connection.setRegister(_REG_ID, [0x55]);
    checkTrue('chip_id', (await sensor.chipId()) === 0x55);

    // oversampling()/setOversampling()
    checkTrue('oversampling_default', sensor.oversampling() === 0);
    sensor.setOversampling(BMP180Full.OSS_STANDARD);
    checkTrue('set_oversampling', sensor.oversampling() === 1);
    sensor.setOversampling(0); // restore ULP for the rest of the test

    // altitude(seaLevelHpa=1013.25 default): pressure() re-reads UT/UP internally.
    connection.setRegister(_REG_OUT_MSB, [0x6C, 0xFA]);
    const alt = await sensor.altitude();
    checkTrue('altitude_default_sea_level', Math.abs(alt - 1741.7604174) < 1e-3);

    // seaLevelPressure(altitudeM=100)
    connection.setRegister(_REG_OUT_MSB, [0x6C, 0xFA]);
    const slp = await sensor.seaLevelPressure(100);
    checkTrue('sea_level_pressure', Math.abs(slp - 830.599010429) < 1e-3);

    // reset(): writes soft-reset command, then re-reads calibration coefficients.
    preloadCalibration(connection);
    await sensor.reset();
    const softResetWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_SOFT_RESET && w[1] === _SOFT_RESET_CMD);
    checkTrue('reset_writes_soft_reset_cmd', softResetWrites.length > 0);
    const calReads = connection.writes.filter((w) => w.length === 1 && w[0] === _REG_CAL_START);
    checkTrue('reset_rereads_calibration', calReads.length >= 2);

    // Invalid calibration data (a coefficient of 0x0000) rejects the
    // fire-and-forget _readCalibration() promise; since the constructor
    // can't surface that synchronously (see the driver's own doc comment),
    // detect it via a subsequent call.
    const badConnection = new I2CConnectionMock();
    badConnection.setRegister(_REG_CAL_START, [
        0x00, 0x00, // AC1 = 0 (invalid)
        0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
        0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34,
    ]);
    let raisedInvalidCalibration = false;
    const badSensor = new BMP180Full(badConnection);
    try {
        await badSensor._readCalibration();
    } catch (e) {
        raisedInvalidCalibration = true;
    }
    checkTrue('invalid_calibration_rejects', raisedInvalidCalibration);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
