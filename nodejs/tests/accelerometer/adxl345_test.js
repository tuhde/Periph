'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADXL345Minimal, ADXL345Full } = require('../../packages/periph/src/chips/accelerometer/adxl345');

let passed = 0, failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const _REG_DEVID = 0x00;
    const _REG_DATA_FORMAT = 0x31;
    const _REG_BW_RATE = 0x2C;
    const _REG_POWER_CTL = 0x2D;
    const _REG_DATAX0 = 0x32;
    const _REG_OFSX = 0x1E;
    const _REG_OFSY = 0x1F;
    const _REG_OFSZ = 0x20;
    const _REG_INT_ENABLE = 0x2E;
    const _REG_INT_MAP = 0x2F;
    const _REG_THRESH_TAP = 0x1D;
    const _REG_THRESH_FF = 0x28;
    const _REG_TIME_FF = 0x29;
    const _REG_FIFO_CTL = 0x38;
    const _REG_FIFO_STATUS = 0x39;

    const mock = new I2CConnectionMock();
    mock.setRegister(_REG_DEVID, [0xE5]);
    // x=0x0001, y=0x0002, z=0x0003 (little-endian).
    mock.setRegister(_REG_DATAX0, [0x01, 0x00, 0x02, 0x00, 0x03, 0x00]);

    const accel = new ADXL345Minimal(mock);
    // Wait for _init() promise chain to complete.
    await new Promise(r => setTimeout(r, 50));
    checkTrue('construct_minimal', true);

    // Init writes DATA_FORMAT=0x08, BW_RATE=0x0A, POWER_CTL=0x08.
    let dfOk = false, bwOk = false, pwrOk = false, devidRead = false;
    for (const w of mock.writes) {
        if (w.length === 2 && w[0] === _REG_DATA_FORMAT && w[1] === 0x08) dfOk = true;
        if (w.length === 2 && w[0] === _REG_BW_RATE      && w[1] === 0x0A) bwOk = true;
        if (w.length === 2 && w[0] === _REG_POWER_CTL    && w[1] === 0x08) pwrOk = true;
        if (w.length === 1 && w[0] === _REG_DEVID) devidRead = true;
    }
    checkTrue('init_writes_data_format_default', dfOk);
    checkTrue('init_writes_bw_rate_default', bwOk);
    checkTrue('init_writes_power_ctl_default', pwrOk);
    checkTrue('init_reads_devid', devidRead);

    // read() should compute (1, 2, 3) * 3.9e-3.
    const [x, y, z] = await accel.read();
    checkTrue('read_x', Math.abs(x - 0.0039) < 1e-9);
    checkTrue('read_y', Math.abs(y - 0.0078) < 1e-9);
    checkTrue('read_z', Math.abs(z - 0.0117) < 1e-9);

    // Full range switch to ±4 g.
    const mock2 = new I2CConnectionMock();
    mock2.setRegister(_REG_DEVID, [0xE5]);
    mock2.setRegister(_REG_DATA_FORMAT, [0x08]);
    mock2.setRegister(_REG_DATAX0, [0x00, 0x01, 0x00, 0x02, 0x00, 0x03]);
    const accelFull = new ADXL345Full(mock2);
    await new Promise(r => setTimeout(r, 50));
    await accelFull.setRange(4);
    let rangeOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_DATA_FORMAT && w[1] === (0x08 | 0x01)) rangeOk = true;
    }
    checkTrue('set_range_4g', rangeOk);

    // Data-rate 100 Hz.
    mock2.setRegister(_REG_BW_RATE, [0x0A]);
    await accelFull.setDataRate(100);
    let rateOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_BW_RATE && w[1] === 0x0A) rateOk = true;
    }
    checkTrue('set_data_rate_100hz', rateOk);

    // Low-power toggles bit 4 of BW_RATE.
    await accelFull.setLowPower(true);
    let lpOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_BW_RATE && (w[1] & 0x10)) lpOk = true;
    }
    checkTrue('set_low_power', lpOk);

    // Offset encoding: 0.5 g → 32 LSB.
    await accelFull.setOffset(0.5, -0.5, 0.0);
    let ofsxOk = false, ofsyOk = false, ofszOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_OFSX && w[1] === 32) ofsxOk = true;
        if (w.length === 2 && w[0] === _REG_OFSY && w[1] === 0xE0) ofsyOk = true;
        if (w.length === 2 && w[0] === _REG_OFSZ && w[1] === 0) ofszOk = true;
    }
    checkTrue('set_offset_x', ofsxOk);
    checkTrue('set_offset_y_signed', ofsyOk);
    checkTrue('set_offset_z_zero', ofszOk);

    // Interrupt enable.
    mock2.setRegister(_REG_INT_ENABLE, [0x00]);
    mock2.setRegister(_REG_INT_MAP, [0x00]);
    await accelFull.setInterrupt(ADXL345Full.INT_WATERMARK, true, 1);
    let intOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_INT_ENABLE && (w[1] & 0x02)) intOk = true;
    }
    checkTrue('enable_watermark_on_int1', intOk);

    // Tap threshold.
    await accelFull.setTapDetection(0.5, 10.0);
    let tapOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_THRESH_TAP && w[1] === 8) tapOk = true;
    }
    checkTrue('set_tap_threshold', tapOk);

    // Free-fall.
    await accelFull.setFreeFall(0.3, 100);
    let ffTOk = false, ffTimeOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_THRESH_FF && w[1] === 5)  ffTOk = true;
        if (w.length === 2 && w[0] === _REG_TIME_FF    && w[1] === 20) ffTimeOk = true;
    }
    checkTrue('set_free_fall_threshold', ffTOk);
    checkTrue('set_free_fall_time', ffTimeOk);

    // Sleep mode.
    await accelFull.setSleep(true);
    let sleepOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_POWER_CTL && (w[1] & 0x04)) sleepOk = true;
    }
    checkTrue('set_sleep_true', sleepOk);

    await accelFull.setSleep(false);
    let wakeOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_POWER_CTL && !(w[1] & 0x04)) wakeOk = true;
    }
    checkTrue('set_sleep_false', wakeOk);

    // FIFO mode.
    await accelFull.setFifoMode(ADXL345Full.FIFO_STREAM, 16);
    let fifoOk = false;
    for (const w of mock2.writes) {
        if (w.length === 2 && w[0] === _REG_FIFO_CTL && w[1] === (0x80 | 16)) fifoOk = true;
    }
    checkTrue('set_fifo_mode_stream_16', fifoOk);

    // FIFO count.
    mock2.setRegister(_REG_FIFO_STATUS, [0x07]);
    const cnt = await accelFull.fifoCount();
    checkTrue('fifo_count', cnt === 7);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });