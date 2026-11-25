'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMA180Minimal, BMA180Full } = require('../../packages/periph/src/chips/accelerometer/bma180');

// 14-bit two's complement encoding: raw = (MSB << 6) | (LSB >> 2).
// To get raw = 0x200 (X), MSB = 0x08; to get raw = -0x200, MSB = 0xF8.
const REG_CHIP_ID       = 0x00;
const REG_VERSION       = 0x01;
const REG_ACC_X_LSB     = 0x02;
const REG_ACC_Y_LSB     = 0x04;
const REG_ACC_Z_LSB     = 0x06;
const REG_TEMP          = 0x08;
const REG_STATUS_REG3   = 0x0B;
const REG_CTRL_REG0     = 0x0D;
const REG_RESET         = 0x10;
const REG_BW_TCS        = 0x20;
const REG_CTRL_REG3     = 0x21;
const REG_SLOPE_TAPSENS = 0x24;
const REG_HIGH_LOW_INFO = 0x25;
const REG_LOW_DUR       = 0x26;
const REG_HIGH_DUR      = 0x27;
const REG_LOW_TH        = 0x29;
const REG_HIGH_TH       = 0x2A;
const REG_SLOPE_TH      = 0x2B;
const REG_CD1           = 0x2C;
const REG_CD2           = 0x2D;
const REG_TCO_X         = 0x2E;
const REG_TCO_Y         = 0x2F;
const REG_TCO_Z         = 0x30;
const REG_GAIN_T        = 0x31;
const REG_GAIN_Y        = 0x33;
const REG_GAIN_Z        = 0x34;
const REG_OFFSET_LSB1   = 0x35;
const REG_OFFSET_T      = 0x37;

function lastWriteTo(mock, reg) {
    const writes = mock.writes;
    for (let i = writes.length - 1; i >= 0; i--) {
        const w = writes[i];
        if (w.length >= 2 && w[0] === reg) return w[w.length - 1];
    }
    return 0xFF;
}

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    // --- Minimal ----------------------------------------------------------
    const mock = new I2CConnectionMock();
    mock.setRegister(REG_CHIP_ID, [0x03]);
    mock.setRegister(REG_CTRL_REG0, [0x00]);
    mock.setRegister(REG_OFFSET_LSB1, [0x00]);
    mock.setRegister(REG_BW_TCS, [0x00]);
    // raw x = +0x200, raw y = -0x200, raw z = 0.
    mock.setRegister(REG_ACC_X_LSB, [0x00, 0x08]);
    mock.setRegister(REG_ACC_Y_LSB, [0x00, 0xF8]);
    mock.setRegister(REG_ACC_Z_LSB, [0x00, 0x00]);

    const accel = new BMA180Minimal(mock);
    checkTrue('construct_minimal', true);
    // Wait for async init.
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));

    let ctrl0 = lastWriteTo(mock, REG_CTRL_REG0);
    let olsb1 = lastWriteTo(mock, REG_OFFSET_LSB1);
    let bw    = lastWriteTo(mock, REG_BW_TCS);
    checkTrue('init_sets_ee_w', (ctrl0 & 0x10) === 0x10);
    checkTrue('init_sets_range_2g', olsb1 === 0x04);
    checkTrue('init_sets_bw_150hz', bw === 0x40);

    // read(): raw_x = +512 -> 0.125 g; raw_y = -512 -> -0.125 g; raw_z = 0.
    const [x, y, z] = await accel.read();
    checkTrue('read_x_plus_0_125g', Math.abs(x - 0.125) < 1e-9);
    checkTrue('read_y_minus_0_125g', Math.abs(y - (-0.125)) < 1e-9);
    checkTrue('read_z_zero_g', Math.abs(z) < 1e-9);

    // --- Full -------------------------------------------------------------
    const mock2 = new I2CConnectionMock();
    mock2.setRegister(REG_CHIP_ID, [0x03]);
    mock2.setRegister(REG_CTRL_REG0, [0x00]);
    mock2.setRegister(REG_OFFSET_LSB1, [0x00]);
    mock2.setRegister(REG_BW_TCS, [0x00]);
    mock2.setRegister(REG_ACC_X_LSB, [0x00, 0x08]);
    mock2.setRegister(REG_ACC_Y_LSB, [0x00, 0x00]);
    mock2.setRegister(REG_ACC_Z_LSB, [0x00, 0x04]);

    const accelFull = new BMA180Full(mock2);
    checkTrue('construct_full', true);
    await new Promise(r => setImmediate(r));
    await new Promise(r => setImmediate(r));

    // setRange(8): OFFSET_LSB1 = (0x04 & ~0x0E) | 0x0A = 0x0A.
    await accelFull.setRange(8);
    checkTrue('set_range_8g', lastWriteTo(mock2, REG_OFFSET_LSB1) === 0x0A);

    // setBandwidth(40): BW_TCS = (0x40 & 0x0F) | 0x20 = 0x20.
    await accelFull.setBandwidth(40);
    checkTrue('set_bandwidth_40hz', lastWriteTo(mock2, REG_BW_TCS) === 0x20);

    // setFilterMode(1): BW_TCS = (last & 0x0F) | 0x80 = 0x80.
    await accelFull.setFilterMode(1);
    checkTrue('set_filter_mode_high_pass', (lastWriteTo(mock2, REG_BW_TCS) & 0xF0) === 0x80);

    // setMode(2): TCO_Z = (0x00 & ~0x03) | 0x02 = 0x02.
    mock2.setRegister(REG_TCO_Z, [0x00]);
    await accelFull.setMode(2);
    checkTrue('set_mode_2', lastWriteTo(mock2, REG_TCO_Z) === 0x02);

    // setResolution(12): OFFSET_T |= 0x01.
    mock2.setRegister(REG_OFFSET_T, [0x00]);
    await accelFull.setResolution(12);
    checkTrue('set_resolution_12bit', (lastWriteTo(mock2, REG_OFFSET_T) & 0x01) === 0x01);
    await accelFull.setResolution(14);
    checkTrue('set_resolution_14bit', (lastWriteTo(mock2, REG_OFFSET_T) & 0x01) === 0x00);

    // readRaw(): +512, 0, +256.
    mock2.setRegister(REG_ACC_X_LSB, [0x00, 0x08]);
    mock2.setRegister(REG_ACC_Y_LSB, [0x00, 0x00]);
    mock2.setRegister(REG_ACC_Z_LSB, [0x00, 0x04]);
    const [rx, ry, rz] = await accelFull.readRaw();
    checkTrue('read_raw_x', rx === 512);
    checkTrue('read_raw_y', ry === 0);
    checkTrue('read_raw_z', rz === 256);

    // readTemperature: 0x02 -> 25.0, 0x82 -> -39.0.
    mock2.setRegister(REG_TEMP, [0x02]);
    checkTrue('read_temperature_25C', Math.abs(await accelFull.readTemperature() - 25.0) < 1e-9);
    mock2.setRegister(REG_TEMP, [0x82]);
    checkTrue('read_temperature_neg', Math.abs(await accelFull.readTemperature() - (-39.0)) < 1e-9);

    // newDataAvailable: all set -> true.
    mock2.setRegister(REG_ACC_X_LSB, [0x01]);
    mock2.setRegister(REG_ACC_Y_LSB, [0x01]);
    mock2.setRegister(REG_ACC_Z_LSB, [0x01]);
    let r1 = await accelFull.newDataAvailable();
    checkTrue('new_data_available_true', r1 ? true : false);
    mock2.setRegister(REG_ACC_X_LSB, [0x00]);
    let r2 = await accelFull.newDataAvailable();
    checkTrue('new_data_available_false', !r2);

    // setShadow: GAIN_Y bit 0.
    mock2.setRegister(REG_GAIN_Y, [0x00]);
    await accelFull.setShadow(false);
    checkTrue('set_shadow_false_sets_bit', (lastWriteTo(mock2, REG_GAIN_Y) & 0x01) === 0x01);
    await accelFull.setShadow(true);
    checkTrue('set_shadow_true_clears_bit', (lastWriteTo(mock2, REG_GAIN_Y) & 0x01) === 0x00);

    // setSampleSkip: OFFSET_LSB1 bit 0.
    mock2.setRegister(REG_OFFSET_LSB1, [0x00]);
    await accelFull.setSampleSkip(true);
    checkTrue('set_sample_skip_true', (lastWriteTo(mock2, REG_OFFSET_LSB1) & 0x01) === 0x01);
    await accelFull.setSampleSkip(false);
    checkTrue('set_sample_skip_false', (lastWriteTo(mock2, REG_OFFSET_LSB1) & 0x01) === 0x00);

    // setLowG: range=8 -> code = round(0.3/8*255) = 10.
    mock2.setRegister(REG_HIGH_LOW_INFO, [0x00]);
    mock2.setRegister(REG_LOW_DUR, [0x01]);
    await accelFull.setLowG(0.3, 40, 0.05, 0x07, 0, true);
    checkTrue('set_low_g_threshold', lastWriteTo(mock2, REG_LOW_TH) === 10);
    checkTrue('set_low_g_dur_preserves_bit0', (lastWriteTo(mock2, REG_LOW_DUR) & 0x01) === 0x01);
    checkTrue('set_low_g_axes', (lastWriteTo(mock2, REG_HIGH_LOW_INFO) & 0x0E) === 0x0E);
    checkTrue('set_low_g_low_filt_bit', (lastWriteTo(mock2, REG_HIGH_LOW_INFO) & 0x01) === 0x01);

    // setHighG: range=8 -> code = round(1.8/8*255) = 57.
    mock2.setRegister(REG_HIGH_LOW_INFO, [0x00]);
    mock2.setRegister(REG_HIGH_DUR, [0x00]);
    await accelFull.setHighG(1.8, 20, 0.0, 0x07, 0, true);
    checkTrue('set_high_g_threshold', lastWriteTo(mock2, REG_HIGH_TH) === 57);
    checkTrue('set_high_g_axes', (lastWriteTo(mock2, REG_HIGH_LOW_INFO) & 0xE0) === 0xE0);
    checkTrue('set_high_g_high_filt_bit', (lastWriteTo(mock2, REG_HIGH_LOW_INFO) & 0x10) === 0x10);

    // setSlope: range=8 -> code = round(0.3 / (0.0156 * 8 / 2)) = 5; samples=3 -> TCO_X = 0x01.
    mock2.setRegister(REG_TCO_X, [0x00]);
    mock2.setRegister(REG_SLOPE_TAPSENS, [0x00]);
    mock2.setRegister(REG_CTRL_REG3, [0x00]);
    await accelFull.setSlope(0.3, 3, 0x07, true);
    checkTrue('set_slope_threshold', lastWriteTo(mock2, REG_SLOPE_TH) === 5);
    checkTrue('set_slope_dur_3', (lastWriteTo(mock2, REG_TCO_X) & 0x03) === 0x01);
    checkTrue('set_slope_axes', (lastWriteTo(mock2, REG_SLOPE_TAPSENS) & 0xE0) === 0xE0);
    {
        const cr3 = lastWriteTo(mock2, REG_CTRL_REG3);
        checkTrue('set_slope_ctrl_reg3', (cr3 & 0x44) === 0x44 && (cr3 & 0x80) === 0x00);
    }

    // setAlert(true): slope_alert + adv_int set; slope_int cleared.
    mock2.setRegister(REG_CTRL_REG3, [0x00]);
    await accelFull.setAlert(true);
    {
        const cr3 = lastWriteTo(mock2, REG_CTRL_REG3);
        checkTrue('set_alert_ctrl_reg3', (cr3 & 0x84) === 0x84 && (cr3 & 0x40) === 0x00);
    }

    // setTap: window=250 -> GAIN_T bits 2:0 = 0x04.
    mock2.setRegister(REG_GAIN_T, [0x00]);
    await accelFull.setTap(0.5, 250, 0x07, true);
    checkTrue('set_tap_dur_250ms', (lastWriteTo(mock2, REG_GAIN_T) & 0x07) === 0x04);

    // setLatch(true): CTRL_REG3 |= 0x01.
    mock2.setRegister(REG_CTRL_REG3, [0x00]);
    await accelFull.setLatch(true);
    checkTrue('set_latch_true', (lastWriteTo(mock2, REG_CTRL_REG3) & 0x01) === 0x01);

    // clearInterrupt: CTRL_REG0 |= 0x40.
    mock2.setRegister(REG_CTRL_REG0, [0x00]);
    await accelFull.clearInterrupt();
    checkTrue('clear_interrupt_sets_reset_int', (lastWriteTo(mock2, REG_CTRL_REG0) & 0x40) === 0x40);

    // setWakeUp(true, 80): TCO_Y bits 1:0 = 0x01; GAIN_Z bit 0 set.
    mock2.setRegister(REG_TCO_Y, [0x00]);
    mock2.setRegister(REG_GAIN_Z, [0x00]);
    await accelFull.setWakeUp(true, 80);
    checkTrue('set_wake_up_80ms_dur', (lastWriteTo(mock2, REG_TCO_Y) & 0x03) === 0x01);
    checkTrue('set_wake_up_sets_bit', (lastWriteTo(mock2, REG_GAIN_Z) & 0x01) === 0x01);

    // sleep / wake: CTRL_REG0 bit 1.
    mock2.setRegister(REG_CTRL_REG0, [0x00]);
    await accelFull.sleep();
    checkTrue('sleep_sets_bit', (lastWriteTo(mock2, REG_CTRL_REG0) & 0x02) === 0x02);
    await accelFull.wake();
    checkTrue('wake_clears_bit', (lastWriteTo(mock2, REG_CTRL_REG0) & 0x02) === 0x00);

    // softReset: writes RESET 0xB6.
    mock2.setRegister(REG_CHIP_ID, [0x03]);
    await accelFull.softReset();
    checkTrue('soft_reset_writes_0xB6', lastWriteTo(mock2, REG_RESET) === 0xB6);

    // readVersion: 0xAB -> (0xA, 0xB).
    mock2.setRegister(REG_VERSION, [0xAB]);
    const [al, ml] = await accelFull.readVersion();
    checkTrue('read_version_al', al === 0xA);
    checkTrue('read_version_ml', ml === 0xB);

    // readCustomer / writeCustomer.
    mock2.setRegister(REG_CD1, [0xA5]);
    checkTrue('read_customer_0', (await accelFull.readCustomer(0)) === 0xA5);
    await accelFull.writeCustomer(1, 0x5A);
    checkTrue('write_customer_1', lastWriteTo(mock2, REG_CD2) === 0x5A);

    // pollInterrupt: STATUS_REG3 byte.
    mock2.setRegister(REG_STATUS_REG3, [0x80]);
    checkTrue('poll_interrupt', (await accelFull.pollInterrupt()) === 0x80);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.error(e); process.exit(2); });