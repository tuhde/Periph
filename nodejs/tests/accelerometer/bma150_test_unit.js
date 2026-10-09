'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BMA150Minimal, BMA150Full } = require('../../packages/periph/src/chips/accelerometer/bma150');

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    // Preload CHIP_ID = 0x02; range/bw cleared; raw x=-2g, y=0, z=1g.
    const mock = new I2CConnectionMock();
    mock.setRegister(BMA150Minimal._REG_CHIP_ID !== undefined ? BMA150Minimal._REG_CHIP_ID : 0x00, [0x02]);
    mock.setRegister(0x14, [0x00]);
    mock.setRegister(0x02, [0x00, 0x80]);
    mock.setRegister(0x04, [0x00, 0x00]);
    mock.setRegister(0x06, [0x00, 0x40]);

    const accel = new BMA150Minimal(mock);
    checkTrue('construct_minimal', true);

    // Wait for init() to finish.
    await new Promise(r => setImmediate(r));

    // Init reads CHIP_ID then writes RANGE_BW = (0x00 & 0xE0) | 0x00 | 0x02 = 0x02.
    const initRb = mock.writes.find(w => w.length === 2 && w[0] === 0x14 && w[1] === 0x02);
    checkTrue('init_writes_range_bw_0x02', !!initRb);

    const [x, y, z] = await accel.read();
    checkTrue('read_x_minus_2g', Math.abs(x - (-2.0)) < 1e-9);
    checkTrue('read_y_zero_g', Math.abs(y - 0.0) < 1e-9);
    checkTrue('read_z_plus_1g', Math.abs(z - 1.0) < 1e-9);

    // Full driver
    const mock2 = new I2CConnectionMock();
    mock2.setRegister(0x00, [0x02]);
    mock2.setRegister(0x14, [0x00]);
    mock2.setRegister(0x02, [0x00, 0x80]);
    mock2.setRegister(0x04, [0x00, 0x00]);
    mock2.setRegister(0x06, [0x00, 0x40]);

    const accelFull = new BMA150Full(mock2);
    await new Promise(r => setImmediate(r));
    checkTrue('construct_full', true);

    await accelFull.setRange(4);
    const setRange4 = mock2.writes.find(w => w.length === 2 && w[0] === 0x14 && w[1] === 0x0A);
    checkTrue('set_range_4g', !!setRange4);

    await accelFull.setBandwidth(190);
    const setBw190 = mock2.writes.find(w => w.length === 2 && w[0] === 0x14 && w[1] === 0x0B);
    checkTrue('set_bandwidth_190hz', !!setBw190);

    const [rx, ry, rz] = await accelFull.readRaw();
    checkTrue('read_raw_x', rx === -512);
    checkTrue('read_raw_y', ry === 0);
    checkTrue('read_raw_z', rz === 256);

    // setLowG(0.4, 40): with range=4 -> round(0.4*255/4) = 26.
    mock2.setRegister(0x11, [0x00]);
    mock2.setRegister(0x0B, [0x00]);
    await accelFull.setLowG(0.4, 40);
    const lgThres = mock2.writes.find(w => w.length === 2 && w[0] === 0x0C && w[1] === 26);
    const lgDur   = mock2.writes.find(w => w.length === 2 && w[0] === 0x0D && w[1] === 40);
    const lgIc    = mock2.writes.find(w => w.length === 2 && w[0] === 0x0B && (w[1] & 0x01));
    checkTrue('set_low_g_threshold', !!lgThres);
    checkTrue('set_low_g_duration', !!lgDur);
    checkTrue('set_low_g_enables_int', !!lgIc);

    // setHighG(4.0, 2): clamped to 255.
    mock2.setRegister(0x0B, [0x00]);
    await accelFull.setHighG(4.0, 2);
    const hgThres = mock2.writes.find(w => w.length === 2 && w[0] === 0x0E && w[1] === 255);
    const hgDur   = mock2.writes.find(w => w.length === 2 && w[0] === 0x0F && w[1] === 2);
    const hgIc    = mock2.writes.find(w => w.length === 2 && w[0] === 0x0B && (w[1] & 0x02));
    checkTrue('set_high_g_threshold_clamped', !!hgThres);
    checkTrue('set_high_g_duration', !!hgDur);
    checkTrue('set_high_g_enables_int', !!hgIc);

    // setAnyMotion(0.5, 3): with range=4 -> 64.
    await accelFull.setAnyMotion(0.5, 3);
    const amThres = mock2.writes.find(w => w.length === 2 && w[0] === 0x10 && w[1] === 64);
    const amDur   = mock2.writes.find(w => w.length === 2 && w[0] === 0x11 && (w[1] & 0xC0) === 0x40);
    const amCfg   = mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && (w[1] & 0x40));
    const amIc    = mock2.writes.find(w => w.length === 2 && w[0] === 0x0B && (w[1] & 0x40));
    checkTrue('set_any_motion_threshold', !!amThres);
    checkTrue('set_any_motion_dur_3samples', !!amDur);
    checkTrue('set_any_motion_enables_adv_int', !!amCfg);
    checkTrue('set_any_motion_enables_int', !!amIc);

    await accelFull.setLatch(true);
    const latchTrue = mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && (w[1] & 0x10));
    checkTrue('set_latch_true', !!latchTrue);

    await accelFull.setLatch(false);
    const latchFalse = mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && !(w[1] & 0x10));
    checkTrue('set_latch_false', !!latchFalse);

    mock2.setRegister(0x0A, [0x00]);
    await accelFull.clearInterrupt();
    const clrInt = mock2.writes.find(w => w.length === 2 && w[0] === 0x0A && (w[1] & 0x40));
    checkTrue('clear_interrupt_writes_reset', !!clrInt);

    mock2.setRegister(0x0A, [0x00]);
    mock2.setRegister(0x14, [0x00]);
    await accelFull.softReset();
    const sr = mock2.writes.find(w => w.length === 2 && w[0] === 0x0A && (w[1] & 0x02));
    checkTrue('soft_reset_writes_soft_reset_bit', !!sr);

    mock2.setRegister(0x15, [0x00]);
    await accelFull.setWakeUp(true, 80);
    const wu = mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && (w[1] & 0x03) === 0x03 && (w[1] & 0x06) === 0x02);
    checkTrue('set_wake_up_80ms', !!wu);

    await accelFull.setWakeUp(false);
    const wuOff = !mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && (w[1] & 0x01));
    checkTrue('set_wake_up_false', wuOff);

    mock2.setRegister(0x0A, [0x00]);
    await accelFull.sleep();
    const sleepW = mock2.writes.find(w => w.length === 2 && w[0] === 0x0A && (w[1] & 0x01));
    checkTrue('sleep_writes_sleep_bit', !!sleepW);

    await accelFull.wake();
    const wakeW = !mock2.writes.find(w => w.length === 2 && w[0] === 0x0A && (w[1] & 0x01));
    checkTrue('wake_clears_sleep_bit', wakeW);

    mock2.setRegister(0x0A, [0x00]);
    mock2.setRegister(0x09, [0x80]);
    const st = await accelFull.selfTest();
    checkTrue('self_test_returns_true_on_st_result', st === true);

    mock2.setRegister(0x01, [0xAB]);
    const [al, ml] = await accelFull.readVersion();
    checkTrue('read_version_al', al === 0x0A);
    checkTrue('read_version_ml', ml === 0x0B);

    mock2.setRegister(0x08, [0x40]);
    const temp = await accelFull.readTemperature();
    checkTrue('read_temperature', Math.abs(temp - 2.0) < 1e-9);

    mock2.setRegister(0x12, [0xA5]);
    const c1 = await accelFull.readCustomer(0);
    checkTrue('read_customer_0', c1 === 0xA5);
    await accelFull.writeCustomer(1, 0x5A);
    const wc1 = mock2.writes.find(w => w.length === 2 && w[0] === 0x13 && w[1] === 0x5A);
    checkTrue('write_customer_1', !!wc1);

    mock2.setRegister(0x15, [0x00]);
    await accelFull.setShadow(true);
    const sh = mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && (w[1] & 0x08));
    checkTrue('set_shadow_true', !!sh);

    await accelFull.setShadow(false);
    const shOff = !mock2.writes.find(w => w.length === 2 && w[0] === 0x15 && (w[1] & 0x08));
    checkTrue('set_shadow_false', shOff);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.log('FAIL exception:', e.message); process.exit(1); });
