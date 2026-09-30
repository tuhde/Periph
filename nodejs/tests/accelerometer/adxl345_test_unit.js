'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ADXL345Minimal, ADXL345Full } = require('../../packages/periph/src/chips/accelerometer/adxl345');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

const _REG_DEVID          = 0x00;
const _REG_THRESH_TAP     = 0x1D;
const _REG_OFSX           = 0x1E;
const _REG_OFSY           = 0x1F;
const _REG_OFSZ           = 0x20;
const _REG_DUR            = 0x21;
const _REG_THRESH_FF      = 0x28;
const _REG_TIME_FF        = 0x29;
const _REG_BW_RATE        = 0x2C;
const _REG_POWER_CTL      = 0x2D;
const _REG_INT_ENABLE     = 0x2E;
const _REG_INT_MAP        = 0x2F;
const _REG_DATA_FORMAT    = 0x31;
const _REG_DATAX0         = 0x32;

function lastWriteTo(connection, reg) {
    for (let i = connection.writes.length - 1; i >= 0; i--) {
        const w = connection.writes[i];
        if (w.length === 2 && w[0] === reg) return w[1];
    }
    return null;
}

function countWritesTo(connection, reg) {
    return connection.writes.filter((w) => w.length === 2 && w[0] === reg).length;
}

async function main() {
    // --- construction: writes defaults, reads DEVID ---
    const mock = new I2CConnectionMock();
    mock.setRegister(_REG_DEVID, [0xE5]);
    mock.setRegister(_REG_DATAX0, [0x01, 0x00, 0x02, 0x00, 0x03, 0x00]); // x=1,y=2,z=3
    const accel = new ADXL345Minimal(mock);
    await flushMicrotasks();

    checkTrue('init_writes_data_format_default', lastWriteTo(mock, _REG_DATA_FORMAT) === 0x08);
    checkTrue('init_writes_bw_rate_default', lastWriteTo(mock, _REG_BW_RATE) === 0x0A);
    checkTrue('init_writes_power_ctl_default', lastWriteTo(mock, _REG_POWER_CTL) === 0x08);

    // --- read(): (1,2,3) raw * 3.9e-3 g/LSB ---
    const [x, y, z] = await accel.read();
    checkTrue('read_x', Math.abs(x - 0.0039) < 1e-9);
    checkTrue('read_y', Math.abs(y - 0.0078) < 1e-9);
    checkTrue('read_z', Math.abs(z - 0.0117) < 1e-9);

    // --- Full: setRange(4) toggles DATA_FORMAT bit 0, keeps FULL_RES ---
    const mock2 = new I2CConnectionMock();
    mock2.setRegister(_REG_DEVID, [0xE5]);
    mock2.setRegister(_REG_DATA_FORMAT, [0x08]);
    mock2.setRegister(_REG_DATAX0, [0x00, 0x01, 0x00, 0x02, 0x00, 0x03]);
    const full = new ADXL345Full(mock2);
    await flushMicrotasks();

    await full.setRange(4);
    checkTrue('set_range_4g', lastWriteTo(mock2, _REG_DATA_FORMAT) === (0x08 | 0x01));

    // --- setDataRate(100): unchanged BW_RATE=0x0A ---
    mock2.setRegister(_REG_BW_RATE, [0x0A]);
    await full.setDataRate(100);
    checkTrue('set_data_rate_100hz', lastWriteTo(mock2, _REG_BW_RATE) === 0x0A);

    // --- setLowPower(true): bit 4 of BW_RATE set ---
    await full.setLowPower(true);
    checkTrue('set_low_power', (lastWriteTo(mock2, _REG_BW_RATE) & 0x10) === 0x10);

    // --- setOffset: 0.5g -> 32 LSB, -0.5g -> -32 (0xE0), 0 -> 0 ---
    await full.setOffset(0.5, -0.5, 0.0);
    checkTrue('set_offset_x', lastWriteTo(mock2, _REG_OFSX) === 32);
    checkTrue('set_offset_y_signed', lastWriteTo(mock2, _REG_OFSY) === (224 & 0xFF));
    checkTrue('set_offset_z_zero', lastWriteTo(mock2, _REG_OFSZ) === 0);

    // --- setInterrupt(WATERMARK, true, 1): bit 1 of INT_ENABLE ---
    mock2.setRegister(_REG_INT_ENABLE, [0x00]);
    mock2.setRegister(_REG_INT_MAP, [0x00]);
    await full.setInterrupt(ADXL345Full.INT_WATERMARK, true, 1);
    checkTrue('enable_watermark_on_int1', (lastWriteTo(mock2, _REG_INT_ENABLE) & 0x02) === 0x02);

    // --- setInterrupt(..., pin=2): routed via INT_MAP ---
    mock2.setRegister(_REG_INT_ENABLE, [0x00]);
    mock2.setRegister(_REG_INT_MAP, [0x00]);
    await full.setInterrupt(ADXL345Full.INT_ACTIVITY, true, 2);
    checkTrue('enable_activity_on_int2', (lastWriteTo(mock2, _REG_INT_MAP) & 0x10) === 0x10);

    // --- setTapDetection: 0.5g / 62.5mg = 8 LSB ---
    await full.setTapDetection(0.5, 10.0);
    checkTrue('set_tap_threshold', lastWriteTo(mock2, _REG_THRESH_TAP) === 8);

    // --- setFreeFall: 0.3g/62.5mg=4.8->5 LSB, 100ms/5=20 LSB ---
    await full.setFreeFall(0.3, 100);
    checkTrue('set_free_fall_threshold', lastWriteTo(mock2, _REG_THRESH_FF) === 5);
    checkTrue('set_free_fall_time', lastWriteTo(mock2, _REG_TIME_FF) === 20);

    // --- setSleep(true)/false toggles bit 2 of POWER_CTL ---
    await full.setSleep(true);
    checkTrue('set_sleep_true', (lastWriteTo(mock2, _REG_POWER_CTL) & 0x04) === 0x04);
    await full.setSleep(false);
    checkTrue('set_sleep_false', (lastWriteTo(mock2, _REG_POWER_CTL) & 0x04) === 0x00);

    // --- setSleep with an invalid wakeupHz rejects ---
    let threw = false;
    try { await full.setSleep(true, 3); } catch (e) { threw = true; }
    checkTrue('set_sleep_invalid_wakeup_throws', threw);

    // --- setLinkMode / setAutoSleep ---
    await full.setLinkMode(true);
    checkTrue('set_link_mode', (lastWriteTo(mock2, _REG_POWER_CTL) & 0x40) === 0x40);
    await full.setAutoSleep(true);
    checkTrue('set_auto_sleep', (lastWriteTo(mock2, _REG_POWER_CTL) & 0x20) === 0x20);

    // --- selfTest ---
    await full.selfTest(true);
    checkTrue('self_test_enabled', (lastWriteTo(mock2, _REG_DATA_FORMAT) & 0x80) === 0x80);
    await full.selfTest(false);
    checkTrue('self_test_disabled', (lastWriteTo(mock2, _REG_DATA_FORMAT) & 0x80) === 0x00);

    // --- fifoCount() / readFifo() ---
    const mock3 = new I2CConnectionMock();
    mock3.setRegister(_REG_DEVID, [0xE5]);
    const full3 = new ADXL345Full(mock3);
    await flushMicrotasks();
    mock3.setRegister(0x39, [3]); // FIFO_STATUS: 3 entries
    checkTrue('fifo_count', (await full3.fifoCount()) === 3);

    mock3.setRegister(_REG_DATAX0, [0x01, 0x00, 0x02, 0x00, 0x03, 0x00]);
    const fifoSamples = await full3.readFifo();
    checkTrue('read_fifo_count', fifoSamples.length === 3);
    checkTrue('read_fifo_sample', Math.abs(fifoSamples[0][0] - 0.0039) < 1e-9);

    // --- readFifo() with empty FIFO returns [] ---
    mock3.setRegister(0x39, [0]);
    const emptyFifo = await full3.readFifo();
    checkTrue('read_fifo_empty', Array.isArray(emptyFifo) && emptyFifo.length === 0);

    // --- setFifoMode ---
    await full3.setFifoMode(ADXL345Full.FIFO_STREAM, 16);
    checkTrue('set_fifo_mode', lastWriteTo(mock3, 0x38) === (0x80 | 16));

    // --- readInterruptSource ---
    mock3.setRegister(0x30, [0x44]); // ACTIVITY | FREE_FALL
    checkTrue('read_interrupt_source', (await full3.readInterruptSource()) === 0x44);

    // --- Register API: SPI command-byte framing lives in SPIConnection.readReg/
    // writeReg now, so the driver must address registers via RegisterConnection,
    // using a single 6-byte burst for the data registers (sets MB on SPI). ---
    const mock4 = new I2CConnectionMock();
    const regReads = [];
    const origReadReg = mock4.readReg.bind(mock4);
    mock4.readReg = (reg, length) => { regReads.push([reg, length]); return origReadReg(reg, length); };
    mock4.setRegister(_REG_DEVID, [0xE5]);
    const accelReg = new ADXL345Minimal(mock4);
    await flushMicrotasks();
    await accelReg.read();
    checkTrue('reg_devid_single_byte_read',
        regReads.some(([r, l]) => r === _REG_DEVID && l === 1));
    checkTrue('reg_burst_read_6_bytes',
        regReads.some(([r, l]) => r === _REG_DATAX0 && l === 6));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
