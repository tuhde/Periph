'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const {
    MMA8451QMinimal,
    SOURCE_DATA_READY,
    SOURCE_FF_MT,
    SOURCE_PULSE,
    SOURCE_ORIENTATION,
    SOURCE_TRANSIENT,
    SOURCE_FIFO,
    SOURCE_AUTO_SLEEP,
} = require('../../packages/periph/src/chips/accelerometer/mma8451q');

// Mock writes accumulate; "off" checks must look at the most recent write to the register.
function lastWriteTo(mock, reg) {
    return [...mock.writes].reverse().find(w => w.length === 2 && w[0] === reg);
}

function lastValueTo(mock, reg) {
    const w = lastWriteTo(mock, reg);
    return w === undefined ? undefined : w[1];
}

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// Build a mock with a plausible chip state: WHO_AM_I, ACTIVE at 100 Hz,
// and an X/Y/Z sample of 0 g / 0 g / +1 g in 14-bit representation.
function newMock() {
    const mock = new I2CConnectionMock();
    mock.setRegister(0x0D, [0x1A]);   // WHO_AM_I
    mock.setRegister(0x2A, [0x19]);   // CTRL_REG1: DR = 011 (100 Hz), ACTIVE = 1
    mock.setRegister(0x2B, [0x00]);   // CTRL_REG2
    mock.setRegister(0x2C, [0x00]);   // CTRL_REG3
    mock.setRegister(0x2D, [0x00]);   // CTRL_REG4
    mock.setRegister(0x2E, [0x00]);   // CTRL_REG5
    mock.setRegister(0x0E, [0x00]);   // XYZ_DATA_CFG: ±2 g
    mock.setRegister(0x0F, [0x00]);   // HP_FILTER_CUTOFF
    // +1 g on Z: 4096 counts << 2 = 0x4000
    mock.setRegister(0x01, [0x00, 0x00, 0x00, 0x00, 0x40, 0x00]);
    return mock;
}

async function main() {
    // --- source constants -------------------------------------------------
    checkTrue('source_data_ready_is_0x01', SOURCE_DATA_READY === 0x01);
    checkTrue('source_ff_mt_is_0x04', SOURCE_FF_MT === 0x04);
    checkTrue('source_pulse_is_0x08', SOURCE_PULSE === 0x08);
    checkTrue('source_orientation_is_0x10', SOURCE_ORIENTATION === 0x10);
    checkTrue('source_transient_is_0x20', SOURCE_TRANSIENT === 0x20);
    checkTrue('source_fifo_is_0x40', SOURCE_FIFO === 0x40);
    checkTrue('source_auto_sleep_is_0x80', SOURCE_AUTO_SLEEP === 0x80);

    // --- Minimal: init sequence ------------------------------------------
    const mock = newMock();
    const accel = new MMA8451QMinimal(mock);
    checkTrue('construct_minimal', true);

    await accel.init();
    checkTrue('init_reads_who_am_i', mock.writes.some(w => w.length === 1 && w[0] === 0x0D));
    checkTrue('init_writes_ctrl_reg2_reset',
        mock.writes.some(w => w.length === 2 && w[0] === 0x2B && w[1] === 0x40));
    checkTrue('init_writes_xyz_data_cfg_0x00',
        mock.writes.some(w => w.length === 2 && w[0] === 0x0E && w[1] === 0x00));
    checkTrue('init_writes_ctrl_reg2_0x00',
        mock.writes.some(w => w.length === 2 && w[0] === 0x2B && w[1] === 0x00));
    checkTrue('init_writes_ctrl_reg1_0x19',
        mock.writes.some(w => w.length === 2 && w[0] === 0x2A && w[1] === 0x19));

    // --- 14-bit conversion ------------------------------------------------
    const [x, y, z] = await accel.read();
    checkTrue('read_x_zero_g', Math.abs(x - 0.0) < 1e-9);
    checkTrue('read_y_zero_g', Math.abs(y - 0.0) < 1e-9);
    checkTrue('read_z_plus_1g', Math.abs(z - 1.0) < 1e-9);

    // Negative value: -0.5 g → -2048 counts → raw16 = -8192 = 0xE000.
    mock.setRegister(0x01, [0xE0, 0x00, 0x00, 0x00, 0x00, 0x00]);
    const [nx] = await accel.read();
    checkTrue('read_x_minus_0_5g', Math.abs(nx - (-0.5)) < 1e-9);

    // --- identity mismatch -------------------------------------------------
    const mockBad = new I2CConnectionMock();
    mockBad.setRegister(0x0D, [0x00]);   // not 0x1A
    const bad = new MMA8451QMinimal(mockBad);
    let initFailed = false;
    try { await bad.init(); } catch (e) { initFailed = true; }
    checkTrue('init_rejects_wrong_who_am_i', initFailed);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.log('FAIL exception:', e.message, e.stack); process.exit(1); });
