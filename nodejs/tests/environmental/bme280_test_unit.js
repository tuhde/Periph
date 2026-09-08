'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BME280Full } = require('../../packages/periph/src/chips/environmental/bme280');

const _REG_CAL_START  = 0x88;
const _REG_ID         = 0xD0;
const _REG_RESET      = 0xE0;
const _REG_CAL_H2     = 0xE1;
const _REG_CTRL_HUM   = 0xF2;
const _REG_STATUS     = 0xF3;
const _REG_CTRL_MEAS  = 0xF4;
const _REG_CONFIG     = 0xF5;
const _REG_DATA_START = 0xF7;
const _RESET_CMD      = 0xB6;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function checkClose(label, actual, expected, tol = 1e-6) {
    checkTrue(label, Math.abs(actual - expected) < tol);
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    const connection = new I2CConnectionMock();

    // Calibration NVM block 1 (26 bytes from 0x88), BMP280 datasheet worked
    // example, plus dig_H1=75 at 0xA1.
    connection.setRegister(_REG_CAL_START, [
        0x70, 0x6B, 0x43, 0x67, 0x18, 0xFC, 0x7D, 0x8E, 0x43, 0xD6, 0xD0,
        0x0B, 0x27, 0x0B, 0x8C, 0x00, 0xF9, 0xFF, 0x8C, 0x3C, 0xF8, 0xC6,
        0x70, 0x17, 0x00, 0x4B]);
    // Calibration NVM block 2 (7 bytes from 0xE1): dig_H2=384, dig_H3=0,
    // dig_H4=301, dig_H5=50, dig_H6=30.
    connection.setRegister(_REG_CAL_H2, [0x80, 0x01, 0x00, 0x12, 0x2D, 0x03, 0x1E]);
    // ADC burst (8 bytes from 0xF7): adcP=415148, adcT=519888, adcH=32768.
    connection.setRegister(_REG_DATA_START, [0x65, 0x5A, 0xC0, 0x7E, 0xED, 0x00, 0x80, 0x00]);

    const EXPECTED_T = 25.08;
    const EXPECTED_P = 1006.5325390625;
    const EXPECTED_H = 79.0869140625;

    const sensor = new BME280Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish before asserting on it
    checkTrue('init', true);

    const ctrlHumWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_HUM);
    const ctrlMeasWrites = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS);
    const ctrlHumIdx = connection.writes.indexOf(ctrlHumWrites[0]);
    const ctrlMeasIdx = connection.writes.indexOf(ctrlMeasWrites[0]);
    checkTrue('init_writes_ctrl_hum_before_ctrl_meas', ctrlHumIdx < ctrlMeasIdx);
    checkTrue('init_ctrl_hum_osrs_x1', ctrlHumWrites[0][1] === 1);
    checkTrue('init_ctrl_meas_osrs_x1_sleep', ctrlMeasWrites[0][1] === ((1 << 5) | (1 << 2) | 0));

    checkClose('temperature', await sensor.temperature(), EXPECTED_T, 0.01);
    checkClose('pressure', await sensor.pressure(), EXPECTED_P, 0.01);
    checkClose('humidity', await sensor.humidity(), EXPECTED_H, 0.01);

    const lastCtrlMeas = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS).pop();
    checkTrue('trigger_writes_forced_mode', (lastCtrlMeas[1] & 0x03) === 1);

    await sensor.configure(2, 3, 1, 3, 2, 5);
    checkTrue('configure_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 1);
    checkTrue('configure_config', connection.registers.get(_REG_CONFIG) === ((5 << 5) | (2 << 2)));
    checkTrue('configure_ctrl_meas', connection.registers.get(_REG_CTRL_MEAS) === ((2 << 5) | (3 << 2) | 3));

    await sensor.setOversampling(3, 4, 2);
    checkTrue('set_oversampling_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 2);
    checkTrue('set_oversampling_ctrl_meas', connection.registers.get(_REG_CTRL_MEAS) === ((3 << 5) | (4 << 2) | 3));

    await sensor.setMode(1);
    checkTrue('set_mode', connection.registers.get(_REG_CTRL_MEAS) === ((3 << 5) | (4 << 2) | 1));

    await sensor.setFilter(3);
    checkTrue('set_filter', connection.registers.get(_REG_CONFIG) === ((5 << 5) | (3 << 2)));

    await sensor.setStandby(6);
    checkTrue('set_standby', connection.registers.get(_REG_CONFIG) === ((6 << 5) | (3 << 2)));

    connection.setRegister(_REG_STATUS, [0x08]);
    checkTrue('status', (await sensor.status()) === 0x08);

    checkClose('altitude', await sensor.altitude(), 56.07668235692459, 0.05);
    checkClose('sea_level_pressure', await sensor.seaLevelPressure(56.07668235692459), 1013.25, 0.05);
    checkClose('dew_point', await sensor.dewPoint(), 21.191706255732008, 0.05);

    connection.setRegister(_REG_ID, [0x60]);
    checkTrue('chip_id', (await sensor.chipId()) === 0x60);

    await sensor.reset();
    checkTrue('reset_writes_reset_cmd',
        connection.writes.some((w) => w.length === 2 && w[0] === _REG_RESET && w[1] === _RESET_CMD));
    checkTrue('reset_reapplies_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 2);
    checkTrue('reset_reapplies_config', connection.registers.get(_REG_CONFIG) === ((6 << 5) | (3 << 2)));
    checkTrue('reset_reapplies_ctrl_meas', connection.registers.get(_REG_CTRL_MEAS) === ((3 << 5) | (4 << 2) | 1));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
