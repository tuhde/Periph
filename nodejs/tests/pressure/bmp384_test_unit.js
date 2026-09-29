'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMP384Minimal, BMP384Full } = require('../../packages/periph/src/chips/pressure/bmp384');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

// Arbitrary but fixed calibration NVM block (21 bytes at 0x31).
// NVM: T1=27664, T2=27728, T3=3, P1=-4079, P2=802, P3=-8, P4=5, P5=32832,
//      P6=7696, P7=-16, P8=10, P9=4064, P10=-5, P11=2.
function preloadCalibration(connection) {
    connection.setRegister(0x31, [
        0x10, 0x6C, // T1 u16 LE
        0x50, 0x6C, // T2 u16 LE
        0x03,       // T3 s8
        0x11, 0xF0, // P1 s16 LE
        0x22, 0x03, // P2 s16 LE
        0xF8,       // P3 s8
        0x05,       // P4 s8
        0x40, 0x80, // P5 u16 LE
        0x10, 0x1E, // P6 u16 LE
        0xF0,       // P7 s8
        0x0A,       // P8 s8
        0xE0, 0x0F, // P9 s16 LE
        0xFB,       // P10 s8
        0x02,       // P11 s8
    ]);
    connection.setRegister(0x00, [0x50]); // CHIP_ID
}

function lastWriteTo(connection, reg) {
    for (let i = connection.writes.length - 1; i >= 0; i--) {
        const w = connection.writes[i];
        if (w.length === 2 && w[0] === reg) return w[1];
    }
    return null;
}

// uncomp_press=6000000, uncomp_temp=8000000 -> t_lin=23.715563300065696 degC,
// pressure=1447.6955007429672 hPa (computed independently from the same
// Bosch compensation formula; cross-checked across all language ports).
const PRESS_BYTES = [0x80, 0x8D, 0x5B];
const TEMP_BYTES = [0x00, 0x12, 0x7A];
const EXPECTED_T_LIN = 23.715563300065696;
const EXPECTED_PRESSURE_HPA = 1447.6955007429672;

function setBurst(connection) {
    connection.setRegister(0x04, [...PRESS_BYTES, ...TEMP_BYTES]);
}

async function main() {
    // --- construction reads calibration and applies default config ---
    const connection = new I2CConnectionMock();
    preloadCalibration(connection);
    const chip = new BMP384Minimal(connection);
    await flushMicrotasks();
    checkTrue('ctor_writes_osr', lastWriteTo(connection, 0x1C) === ((1 << 3) | 4));
    checkTrue('ctor_writes_config', lastWriteTo(connection, 0x1F) === (2 << 1));
    checkTrue('ctor_writes_odr', lastWriteTo(connection, 0x1D) === 0x03);
    checkTrue('ctor_writes_pwr', lastWriteTo(connection, 0x1B) === ((0x03 << 4) | 0x02 | 0x01));

    // --- temperature()/pressure() against the fixed fixture ---
    const conn2 = new I2CConnectionMock();
    preloadCalibration(conn2);
    const chip2 = new BMP384Minimal(conn2);
    await flushMicrotasks();
    setBurst(conn2);
    checkTrue('temperature_value', Math.abs((await chip2.temperature()) - EXPECTED_T_LIN) < 1e-6);

    setBurst(conn2);
    checkTrue('pressure_value', Math.abs((await chip2.pressure()) - EXPECTED_PRESSURE_HPA) < 1e-6);

    // --- forced-mode temperature() triggers PWR_CTRL before reading ---
    const conn3 = new I2CConnectionMock();
    preloadCalibration(conn3);
    const chip3 = new BMP384Minimal(conn3);
    await flushMicrotasks();
    chip3._mode = 0x01; // MODE_FORCED
    setBurst(conn3);
    await chip3.temperature();
    checkTrue('forced_temperature_triggers', lastWriteTo(conn3, 0x1B) === ((0x01 << 4) | 0x02 | 0x01));

    // --- Full: configure() writes OSR/CONFIG/ODR ---
    const conn4 = new I2CConnectionMock();
    preloadCalibration(conn4);
    const full4 = new BMP384Full(conn4);
    await flushMicrotasks();
    await full4.configure(1, 1, 2, 0x03);
    checkTrue('configure_writes_osr', lastWriteTo(conn4, 0x1C) === ((1 << 3) | 1));
    checkTrue('configure_writes_config', lastWriteTo(conn4, 0x1F) === (2 << 1));
    checkTrue('configure_writes_odr', lastWriteTo(conn4, 0x1D) === 0x03);

    // --- read(): combined burst read ---
    const conn5 = new I2CConnectionMock();
    preloadCalibration(conn5);
    const full5 = new BMP384Full(conn5);
    await flushMicrotasks();
    setBurst(conn5);
    const result5 = await full5.read();
    checkTrue('read_pressure', Math.abs(result5.pressure - EXPECTED_PRESSURE_HPA) < 1e-6);
    checkTrue('read_temperature', Math.abs(result5.temperature - EXPECTED_T_LIN) < 1e-6);

    // --- read() in forced mode also triggers PWR_CTRL (regression: read()
    // must trigger exactly like temperature()/pressure()/readForced() do) ---
    const conn6 = new I2CConnectionMock();
    preloadCalibration(conn6);
    const full6 = new BMP384Full(conn6);
    await flushMicrotasks();
    await full6.setMode(BMP384Full.MODE_FORCED);
    setBurst(conn6);
    await full6.read();
    checkTrue('read_forced_mode_triggers', lastWriteTo(conn6, 0x1B) === ((0x01 << 4) | 0x02 | 0x01));

    // --- readForced(): triggers, waits, reads, then restores previous mode ---
    const conn7 = new I2CConnectionMock();
    preloadCalibration(conn7);
    const full7 = new BMP384Full(conn7);
    await flushMicrotasks();
    setBurst(conn7);
    const result7 = await full7.readForced();
    checkTrue('read_forced_value', Math.abs(result7.pressure - EXPECTED_PRESSURE_HPA) < 1e-6);
    checkTrue('read_forced_restores_mode', lastWriteTo(conn7, 0x1B) === ((0x03 << 4) | 0x02 | 0x01));

    // --- setMode() ---
    const conn8 = new I2CConnectionMock();
    preloadCalibration(conn8);
    const full8 = new BMP384Full(conn8);
    await flushMicrotasks();
    await full8.setMode(BMP384Full.MODE_SLEEP);
    checkTrue('set_mode_writes_pwr', lastWriteTo(conn8, 0x1B) === ((0x00 << 4) | 0x02 | 0x01));

    // --- isDataReady() ---
    const conn9 = new I2CConnectionMock();
    preloadCalibration(conn9);
    const full9 = new BMP384Full(conn9);
    await flushMicrotasks();
    conn9.setRegister(0x03, [1 << 5]);
    checkTrue('is_data_ready_true', (await full9.isDataReady()) === true);
    conn9.setRegister(0x03, [0x00]);
    checkTrue('is_data_ready_false', (await full9.isDataReady()) === false);

    // --- softreset(): writes CMD, re-reads calibration, re-applies config ---
    const conn10 = new I2CConnectionMock();
    preloadCalibration(conn10);
    const full10 = new BMP384Full(conn10);
    await flushMicrotasks();
    await full10.softreset();
    checkTrue('softreset_writes_cmd', lastWriteTo(conn10, 0x7E) === 0xB6);
    checkTrue('softreset_reapplies_pwr', lastWriteTo(conn10, 0x1B) === ((0x03 << 4) | 0x02 | 0x01));

    // --- fifoConfigure() ---
    const conn11 = new I2CConnectionMock();
    preloadCalibration(conn11);
    const full11 = new BMP384Full(conn11);
    await flushMicrotasks();
    await full11.fifoConfigure(true, true, 300, true);
    checkTrue('fifo_configure_cfg1', lastWriteTo(conn11, 0x17) === ((1 << 4) | (1 << 3) | (1 << 1) | 1));
    checkTrue('fifo_configure_wtm_lo', lastWriteTo(conn11, 0x15) === (300 & 0xFF));
    checkTrue('fifo_configure_wtm_hi', lastWriteTo(conn11, 0x16) === ((300 >> 8) & 0x01));

    // --- fifoRead(): pressure, temperature, sensortime, error, empty, unknown frames ---
    const conn12 = new I2CConnectionMock();
    preloadCalibration(conn12);
    const full12 = new BMP384Full(conn12);
    await flushMicrotasks();
    const fifoBytes = [
        0x84, ...PRESS_BYTES,      // pressure frame
        0x90, ...TEMP_BYTES,       // temperature frame
        0xA0, 0x01, 0x02, 0x03,    // sensortime frame
        0x44,                      // error frame
        0x80,                      // empty frame
        0xFF,                      // unknown header
    ];
    conn12.setRegister(0x12, [fifoBytes.length & 0xFF, (fifoBytes.length >> 8) & 0x01]);
    conn12.setRegister(0x14, fifoBytes);
    full12._tLin = EXPECTED_T_LIN; // so a lone pressure frame is comparable to the fixture
    const frames = await full12.fifoRead();
    checkTrue('fifo_read_count', frames.length === 6);
    checkTrue('fifo_read_press_type', frames[0].type === 'pressure');
    checkTrue('fifo_read_press_value', Math.abs(frames[0].value - EXPECTED_PRESSURE_HPA) < 1e-6);
    checkTrue('fifo_read_temp_type', frames[1].type === 'temperature');
    checkTrue('fifo_read_temp_value', Math.abs(frames[1].value - EXPECTED_T_LIN) < 1e-6);
    checkTrue('fifo_read_sensortime', frames[2].type === 'sensortime' && frames[2].value === 0x030201);
    checkTrue('fifo_read_error', frames[3].type === 'error' && frames[3].value === null);
    checkTrue('fifo_read_empty', frames[4].type === 'empty' && frames[4].value === null);
    checkTrue('fifo_read_unknown', frames[5].type === 'unknown' && frames[5].value === null);

    // --- fifoRead(): empty FIFO returns [] ---
    const conn13 = new I2CConnectionMock();
    preloadCalibration(conn13);
    const full13 = new BMP384Full(conn13);
    await flushMicrotasks();
    conn13.setRegister(0x12, [0x00, 0x00]);
    const emptyFrames = await full13.fifoRead();
    checkTrue('fifo_read_empty_fifo', Array.isArray(emptyFrames) && emptyFrames.length === 0);

    // --- fifoFlush() ---
    const conn14 = new I2CConnectionMock();
    preloadCalibration(conn14);
    const full14 = new BMP384Full(conn14);
    await flushMicrotasks();
    await full14.fifoFlush();
    checkTrue('fifo_flush_writes_cmd', lastWriteTo(conn14, 0x7E) === 0xB0);

    // --- altitude(): fixture pressure (1447 hPa) is above the sea-level
    // reference, so altitude comes out negative ---
    const conn15 = new I2CConnectionMock();
    preloadCalibration(conn15);
    const full15 = new BMP384Full(conn15);
    await flushMicrotasks();
    setBurst(conn15);
    checkTrue('altitude_negative_for_high_pressure', (await full15.altitude(1013.25)) < 0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
