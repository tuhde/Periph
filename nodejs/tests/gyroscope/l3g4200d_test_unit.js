'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { L3G4200DFull, L3G4200DMinimal } = require('../../packages/periph/src/chips/gyroscope/l3g4200d');

const _REG_WHO_AM_I      = 0x0F;
const _REG_CTRL_REG1     = 0x20;
const _REG_CTRL_REG2     = 0x21;
const _REG_CTRL_REG3     = 0x22;
const _REG_CTRL_REG4     = 0x23;
const _REG_CTRL_REG5     = 0x24;
const _REG_OUT_TEMP      = 0x26;
const _REG_STATUS        = 0x27;
const _REG_OUT_X_L       = 0x28;
const _REG_OUT_X_H       = 0x29;
const _REG_OUT_Y_L       = 0x2A;
const _REG_OUT_Y_H       = 0x2B;
const _REG_OUT_Z_L       = 0x2C;
const _REG_OUT_Z_H       = 0x2D;
const _REG_FIFO_CTRL     = 0x2E;
const _REG_FIFO_SRC      = 0x2F;
const _REG_INT1_CFG      = 0x30;
const _REG_INT1_SRC      = 0x31;
const _REG_INT1_THS_XH   = 0x32;
const _REG_INT1_THS_XL   = 0x33;
const _REG_INT1_THS_YH   = 0x34;
const _REG_INT1_THS_YL   = 0x35;
const _REG_INT1_THS_ZH   = 0x36;
const _REG_INT1_THS_ZL   = 0x37;
const _REG_INT1_DURATION = 0x38;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setRegister(_REG_WHO_AM_I, [0xD3]);

    const gyro = new L3G4200DMinimal(connection);
    await flushMicrotasks();
    checkTrue('init', true);

    const ctrl1Default = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1 && w[1] === 0x0F);
    checkTrue('init_ctrl1_default', ctrl1Default.length > 0);
    const ctrl4Default = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG4 && w[1] === 0x80);
    checkTrue('init_ctrl4_default', ctrl4Default.length > 0);

    // angular_rate: raw X=+16, Y=0, Z=-16 (LE) at ±250 dps
    connection.setRegister(_REG_OUT_X_L | 0x80,
                            [0x10, 0x00,
                             0x00, 0x00,
                             0xF0, 0xFF]);
    const [x, y, z] = await gyro.angularRate();
    const k = Math.PI / 180.0;
    const expected_x = 16.0 * 0.00875 * k;
    const expected_z = -16.0 * 0.00875 * k;
    checkTrue('angular_rate_x', Math.abs(x - expected_x) < 1e-9);
    checkTrue('angular_rate_y', Math.abs(y - 0.0) < 1e-9);
    checkTrue('angular_rate_z', Math.abs(z - expected_z) < 1e-9);

    // Full driver
    const gyroFull = new L3G4200DFull(connection);
    await flushMicrotasks();
    checkTrue('full_init', true);

    checkTrue('who_am_i', (await gyroFull.whoAmI()) === 0xD3);

    await gyroFull.configure(1, 0, 500);
    const configCtrl1 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1).pop();
    const configCtrl4 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG4).pop();
    checkTrue('configure_odr_200Hz', configCtrl1[1] === (0x0F | (1 << 6)));
    checkTrue('configure_fs_500dps', configCtrl4[1] === (0x80 | (1 << 4)));

    await gyroFull.setFullScale(2000);
    const fs2000 = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG4).pop();
    checkTrue('set_full_scale_2000', fs2000[1] === (0x80 | (2 << 4)));
    checkTrue('set_full_scale_state', gyroFull._fullScale === 2000);

    connection.setRegister(_REG_STATUS, [0x08]);
    checkTrue('data_ready', (await gyroFull.dataReady()) === true);
    checkTrue('status', (await gyroFull.status()) === 0x08);

    connection.setRegister(_REG_OUT_TEMP, [0x80]);
    checkTrue('temperature_signed', (await gyroFull.temperature()) === -128);

    await gyroFull.powerDown();
    const pdClear = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1).pop();
    checkTrue('power_down_clears_pd', (pdClear[1] & 0x08) === 0);
    await gyroFull.wakeUp();
    const wakeSet = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1).pop();
    checkTrue('wake_up_sets_pd', (wakeSet[1] & 0x08) === 0x08);

    await gyroFull.sleep();
    const sleep = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1).pop();
    checkTrue('sleep_only_pd', sleep[1] === 0x08);

    await gyroFull.enableAxes(false, true, false);
    const yOnly = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG1).pop();
    checkTrue('enable_axes_y_only', (yOnly[1] & 0x07) === 0x02);

    await gyroFull.enableFifo(2, 10);
    const fifoEn = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG5).pop();
    const fifoCtrl = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_FIFO_CTRL).pop();
    checkTrue('enable_fifo_sets_fifo_en', (fifoEn[1] & 0x40) === 0x40);
    checkTrue('enable_fifo_mode_wtm', fifoCtrl[1] === ((2 << 5) | 10));

    await gyroFull.disableFifo();
    const fifoDis = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG5).pop();
    checkTrue('disable_fifo_clears_fifo_en', (fifoDis[1] & 0x40) === 0);

    // read_fifo (3 samples). Each sample is 6 bytes (X_L, X_H, Y_L, Y_H, Z_L, Z_H) LE.
    // _fullScale is now 2000, sensitivity 0.07 dps/digit.
    connection.setRegister(_REG_OUT_X_L | 0x80,
                            [0x10, 0x00,
                             0x20, 0x00,
                             0x30, 0x00,
                             0x40, 0x00,
                             0x50, 0x00,
                             0x60, 0x00,
                             0x70, 0x00,
                             0x80, 0x00,
                             0x90, 0x00]);
    connection.setRegister(_REG_FIFO_SRC, [3]);
    const samples = await gyroFull.readFifo();
    checkTrue('read_fifo_len', samples.length === 3);
    if (samples.length === 3) {
        const e1 = 16 * 0.07 * k;
        const e2 = 32 * 0.07 * k;
        const e3 = 48 * 0.07 * k;
        checkTrue('read_fifo_sample0_x', Math.abs(samples[0][0] - e1) < 1e-9);
        checkTrue('read_fifo_sample0_y', Math.abs(samples[0][1] - e2) < 1e-9);
        checkTrue('read_fifo_sample0_z', Math.abs(samples[0][2] - e3) < 1e-9);
    }

    connection.setRegister(_REG_FIFO_SRC, [0x1A]);
    checkTrue('fifo_samples', (await gyroFull.fifoSamples()) === 26);

    await gyroFull.enableHighpass(2, 5);
    const hpcf = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG2).pop();
    const hpenSet = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG5).pop();
    checkTrue('enable_highpass_hpcf', hpcf[1] === ((2 << 4) | 5));
    checkTrue('enable_highpass_hpen', (hpenSet[1] & 0x10) === 0x10);

    await gyroFull.disableHighpass();
    const hpenClear = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG5).pop();
    checkTrue('disable_highpass_clears_hpen', (hpenClear[1] & 0x10) === 0);

    await gyroFull.setInterrupt(true, false, true, false, true, false, false, true);
    const intCfg = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT1_CFG).pop();
    checkTrue('set_interrupt_cfg', intCfg[1] === (0x40 | 0x20 | 0x08 | 0x02));

    gyroFull._fullScale = 250;
    await gyroFull.setThreshold('x', 87.5);
    const xh = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT1_THS_XH).pop();
    const xl = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT1_THS_XL).pop();
    const expectedRaw = Math.trunc(87.5 / 0.00875) & 0x7FFF;
    checkTrue('set_threshold_xh', xh[1] === ((expectedRaw >> 8) & 0x7F));
    checkTrue('set_threshold_xl', xl[1] === (expectedRaw & 0xFF));

    await gyroFull.setDuration(4, true);
    const dur = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_INT1_DURATION).pop();
    checkTrue('set_duration', dur[1] === (0x80 | 4));

    connection.setRegister(_REG_INT1_SRC, [0x7F]);
    checkTrue('read_int_source', (await gyroFull.readIntSource()) === 0x7F);

    await gyroFull.setDataReadyPin(true);
    const drdy = connection.writes.filter((w) => w.length === 2 && w[0] === _REG_CTRL_REG3).pop();
    checkTrue('set_data_ready_pin', (drdy[1] & 0x08) === 0x08);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
