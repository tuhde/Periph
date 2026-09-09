'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BME680Full } = require('../../packages/periph/src/chips/environmental/bme680');

const _REG_RES_HEAT_VAL   = 0x00;
const _REG_RES_HEAT_RANGE = 0x02;
const _REG_RANGE_SW_ERR   = 0x04;
const _REG_MEAS_STATUS    = 0x1D;
const _REG_PRESS_MSB      = 0x1F;
const _REG_CTRL_GAS_0     = 0x70;
const _REG_CTRL_GAS_1     = 0x71;
const _REG_CTRL_HUM       = 0x72;
const _REG_CTRL_MEAS      = 0x74;
const _REG_CONFIG         = 0x75;
const _REG_CAL_BLOCK1     = 0x8A;
const _REG_ID             = 0xD0;
const _REG_RESET          = 0xE0;
const _REG_CAL_BLOCK2     = 0xE1;
const _CHIP_ID             = 0x61;
const _RESET_CMD           = 0xB6;

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

    // Calibration block 1 (23 bytes from 0x8A). No published worked example
    // exists for BME680 - these are self-consistent, hand-derived values
    // used to check every language's translation against the same formula.
    connection.setRegister(_REG_CAL_BLOCK1, [
        0x43, 0x67, 0x03, 0x00, 0x7D, 0x8E, 0x43, 0xD6, 0x58, 0x00, 0x27,
        0x0B, 0x8C, 0x00, 0x0F, 0xF9, 0x00, 0x00, 0xF8, 0xC6, 0x70, 0x17, 0x1E]);
    // Calibration block 2 (14 bytes from 0xE1).
    connection.setRegister(_REG_CAL_BLOCK2, [0x2B, 0xC8, 0x25, 0x00, 0x2D, 0x14, 0x78, 0x9C, 0x90, 0x65, 0x0C, 0xE5, 0xE2, 0x1E]);
    // Single-byte calibration: res_heat_val=50, res_heat_range=2, range_switching_error=0.
    connection.setRegister(_REG_RES_HEAT_VAL, [0x32]);
    connection.setRegister(_REG_RES_HEAT_RANGE, [0x20]);
    connection.setRegister(_REG_RANGE_SW_ERR, [0x00]);
    // ADC burst (13 bytes from 0x1F): pressAdc=415148, tempAdc=419888,
    // humAdc=20000, gasAdc=400, gasRange=5, gasValid=1, heatStab=1.
    connection.setRegister(_REG_PRESS_MSB, [0x65, 0x5A, 0xC0, 0x66, 0x83, 0x00, 0x4E, 0x20, 0x00, 0x00, 0x00, 0x64, 0x35]);

    const EXPECTED_T = 1.23;
    const EXPECTED_P = 969.4;
    const EXPECTED_H = 39.826;
    const EXPECTED_GAS = 271155.0;

    const sensor = new BME680Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish before asserting on it
    checkTrue('init', true);

    checkTrue('init_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 1);
    checkTrue('init_ctrl_meas_sleep', connection.registers.get(_REG_CTRL_MEAS) === ((1 << 5) | (1 << 2) | 0));
    checkTrue('init_config', connection.registers.get(_REG_CONFIG) === 0);
    checkTrue('init_res_heat_0', connection.registers.get(0x5A) === 0x52);
    checkTrue('init_gas_wait_0', connection.registers.get(0x64) === 0x65);
    checkTrue('init_ctrl_gas_1', connection.registers.get(_REG_CTRL_GAS_1) === ((1 << 4) | 0));

    await sensor.setHeater(300, 200);
    checkTrue('set_heater_res_heat_0', connection.registers.get(0x5A) === 0x4E);
    checkTrue('set_heater_gas_wait_0', connection.registers.get(0x64) === 0x72);

    await sensor.setHeaterProfile(4, 280, 50);
    checkTrue('set_heater_profile_res_heat_4', connection.registers.get(0x5A + 4) === 0x49);
    checkTrue('set_heater_profile_gas_wait_4', connection.registers.get(0x64 + 4) === 0x32);

    checkClose('temperature', await sensor.temperature(), EXPECTED_T, 0.01);
    checkClose('pressure', await sensor.pressure(), EXPECTED_P, 0.1);
    checkClose('humidity', await sensor.humidity(), EXPECTED_H, 0.01);
    checkClose('gas_resistance', await sensor.gasResistance(), EXPECTED_GAS, 1.0);

    const lastCtrlMeas = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_MEAS).pop();
    checkTrue('trigger_writes_forced_mode', (lastCtrlMeas[1] & 0x03) === 1);

    await sensor.configure(2, 3, 1, 0, 3);
    checkTrue('configure_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 1);
    checkTrue('configure_config', connection.registers.get(_REG_CONFIG) === (3 << 2));
    checkTrue('configure_ctrl_meas', connection.registers.get(_REG_CTRL_MEAS) === ((2 << 5) | (3 << 2) | 0));

    await sensor.setOversampling(3, 4, 2);
    checkTrue('set_oversampling_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 2);
    checkTrue('set_oversampling_ctrl_meas', connection.registers.get(_REG_CTRL_MEAS) === ((3 << 5) | (4 << 2) | 0));

    await sensor.setFilter(5);
    checkTrue('set_filter', connection.registers.get(_REG_CONFIG) === (5 << 2));

    await sensor.selectHeaterProfile(2);
    checkTrue('select_heater_profile', connection.registers.get(_REG_CTRL_GAS_1) === ((1 << 4) | 2));

    await sensor.setGasEnabled(false);
    checkTrue('set_gas_enabled_false', connection.registers.get(_REG_CTRL_GAS_1) === 2);
    await sensor.setGasEnabled(true);
    checkTrue('set_gas_enabled_true', connection.registers.get(_REG_CTRL_GAS_1) === ((1 << 4) | 2));

    await sensor.setHeaterOff(true);
    checkTrue('set_heater_off_true', connection.registers.get(_REG_CTRL_GAS_0) === 0x08);
    await sensor.setHeaterOff(false);
    checkTrue('set_heater_off_false', connection.registers.get(_REG_CTRL_GAS_0) === 0x00);

    const all = await sensor.readAll();
    checkClose('read_all_t', all.temperature, EXPECTED_T, 0.01);
    checkClose('read_all_p', all.pressure, EXPECTED_P, 0.1);
    checkClose('read_all_h', all.humidity, EXPECTED_H, 0.01);
    checkClose('read_all_gas', all.gasResistance, EXPECTED_GAS, 1.0);

    checkTrue('gas_valid', (await sensor.gasValid()) === true);
    checkTrue('heater_stable', (await sensor.heaterStable()) === true);

    connection.setRegister(_REG_MEAS_STATUS, [0xA0]);
    checkTrue('status', (await sensor.status()) === 0xA0);

    connection.setRegister(_REG_ID, [_CHIP_ID]);
    checkTrue('chip_id', (await sensor.chipId()) === 0x61);

    await sensor.reset();
    checkTrue('reset_writes_reset_cmd',
        connection.writes.some((w) => w.length === 2 && w[0] === _REG_RESET && w[1] === _RESET_CMD));
    checkTrue('reset_reapplies_ctrl_hum', connection.registers.get(_REG_CTRL_HUM) === 2);
    checkTrue('reset_reapplies_config', connection.registers.get(_REG_CONFIG) === (5 << 2));
    checkTrue('reset_reapplies_ctrl_meas', connection.registers.get(_REG_CTRL_MEAS) === ((3 << 5) | (4 << 2) | 0));
    checkTrue('reset_reapplies_ctrl_gas_1', connection.registers.get(_REG_CTRL_GAS_1) === ((1 << 4) | 2));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
