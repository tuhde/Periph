'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMP085Minimal, BMP085Full } = require('../../packages/periph/src/chips/pressure/bmp085');

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
    // Datasheet worked example: AC1=408, AC2=-72, AC3=-14383, AC4=32741,
    // AC5=32757, AC6=23153, B1=6190, B2=4, MB=-32768, MC=-8711, MD=2868.
    connection.setRegister(0xAA, [
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

function lastWriteTo(connection, reg) {
    for (let i = connection.writes.length - 1; i >= 0; i--) {
        const w = connection.writes[i];
        if (w.length === 2 && w[0] === reg) return w[1];
    }
    return null;
}

async function main() {
    // --- Minimal constructor: reads and unpacks calibration coefficients ---
    const connection = new I2CConnectionMock();
    preloadCalibration(connection);
    const chip = new BMP085Minimal(connection);
    await flushMicrotasks(); // let the fire-and-forget _readCalibration() finish
    checkTrue('init_reads_calibration', chip._ac1 === 408 && chip._mc === -8711);

    // --- Regression: raw 0xFFFF must be rejected even for SIGNED coefficients
    // (readInt16BE gives -1, not 0xFFFF, after sign conversion). Bypass the
    // real constructor here -- it fires _readCalibration() unawaited, and an
    // unhandled rejection from *that* call would crash the process before
    // we get a chance to test our own explicit call.
    const badConnection = new I2CConnectionMock();
    badConnection.setRegister(0xAA, new Array(22).fill(0xFF)); // every word raw 0xFFFF
    const badChip = Object.create(BMP085Minimal.prototype);
    badChip._conn = badConnection;
    let threw = false;
    try { await badChip._readCalibration(); } catch (e) { threw = true; }
    checkTrue('bad_calibration_all_ffff_rejected', threw);

    const badConnection2 = new I2CConnectionMock();
    // Only AC1 (a SIGNED coefficient) is the sentinel 0xFFFF; rest valid.
    badConnection2.setRegister(0xAA, [
        0xFF, 0xFF, // AC1 raw 0xFFFF -> signed -1
        0xFF, 0xB8, 0xC7, 0xD1, 0x7F, 0xE5, 0x7F, 0xF5, 0x5A, 0x71,
        0x18, 0x2E, 0x00, 0x04, 0x80, 0x00, 0xDD, 0xF9, 0x0B, 0x34,
    ]);
    const badChip2 = Object.create(BMP085Minimal.prototype);
    badChip2._conn = badConnection2;
    let threw2 = false;
    try { await badChip2._readCalibration(); } catch (e) { threw2 = true; }
    checkTrue('bad_calibration_signed_ffff_rejected', threw2);

    // --- temperature()/pressure(): datasheet worked example (UT=UP=27898
    // due to the mock's static register map) ---
    connection.setRegister(0xF6, [0x6C, 0xFA]); // UT = 27898
    checkTrue('temperature_known_value', (await chip.temperature()) === 15.0);
    checkTrue('temperature_writes_cmd_temp', lastWriteTo(connection, 0xF4) === 0x2E);

    connection.setRegister(0xF6, [0x6C, 0xFA]);
    checkTrue('pressure_known_value', Math.abs((await chip.pressure()) - 82080.0) < 1e-6);

    // --- Full: oversampling ---
    const fullConn = new I2CConnectionMock();
    preloadCalibration(fullConn);
    const full = new BMP085Full(fullConn, BMP085Full.OSS_HIGH_RES);
    await flushMicrotasks();
    checkTrue('constructor_oss', full.oversampling() === 2);
    full.setOversampling(3);
    checkTrue('set_oversampling', full.oversampling() === 3);
    fullConn.setRegister(0xF6, [0x6C, 0xFA, 0x00]);
    await full.pressure();
    checkTrue('pressure_writes_cmd_for_oss3', lastWriteTo(fullConn, 0xF4) === 0xF4);

    // --- altitude()/seaLevelPressure() ---
    const altConn = new I2CConnectionMock();
    preloadCalibration(altConn);
    const altSensor = new BMP085Full(altConn);
    await flushMicrotasks();
    altConn.setRegister(0xF6, [0x6C, 0xFA]);
    checkTrue('altitude_computed', (await altSensor.altitude(101325.0)) > 0);

    const slConn = new I2CConnectionMock();
    preloadCalibration(slConn);
    const slSensor = new BMP085Full(slConn);
    await flushMicrotasks();
    slConn.setRegister(0xF6, [0x6C, 0xFA]);
    checkTrue('sea_level_pressure_at_zero_alt', Math.abs((await slSensor.seaLevelPressure(0.0)) - 82080.0) < 1e-6);

    // --- chipId() ---
    const idConn = new I2CConnectionMock();
    preloadCalibration(idConn);
    const idSensor = new BMP085Full(idConn);
    await flushMicrotasks();
    idConn.setRegister(0xD0, [0x55]);
    checkTrue('chip_id', (await idSensor.chipId()) === 0x55);

    // --- reset(): writes soft-reset key, re-reads calibration ---
    const resetConn = new I2CConnectionMock();
    preloadCalibration(resetConn);
    const resetSensor = new BMP085Full(resetConn);
    await flushMicrotasks();
    await resetSensor.reset();
    checkTrue('reset_writes_key', lastWriteTo(resetConn, 0xE0) === 0xB6);
    checkTrue('reset_rereads_calibration', resetSensor._ac1 === 408);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
