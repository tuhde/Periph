'use strict';
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { ADXL362Minimal, ADXL362Full } = require('../../packages/periph/src/chips/accelerometer/adxl362');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function close(a, b, eps = 1e-6) {
    return Math.abs(a - b) < eps;
}

function bytesEqual(buf, arr) {
    if (buf.length !== arr.length) return false;
    for (let i = 0; i < arr.length; i++) if (buf[i] !== arr[i]) return false;
    return true;
}

// ADXL362's power-up sequence uses real setTimeout-based delays (5ms + 40ms),
// not a busy-wait like AHT21's -- a single flushMicrotasks()/setImmediate
// tick fires before those timers, so waiting real time is required.
function wait(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function newConnection() {
    const c = new SPIConnectionMock();
    c.setRegister(0x00, [0xAD, 0x1D, 0xF2, 0x01]); // DEVID_AD, DEVID_MST, PARTID, REVID
    return c;
}

async function main() {
    // --- Construction: device-ID triple check, FILTER_CTL/POWER_CTL writes ---
    const conn = newConnection();
    const chip = new ADXL362Minimal(conn);
    await wait(60); // let the fire-and-forget _init() (5ms + 40ms real delays) finish
    checkTrue('init_writes_filter_ctl', bytesEqual(conn.writes[conn.writes.length - 2], [0x0A, 0x2C, 0x13]));
    checkTrue('init_writes_power_ctl', bytesEqual(conn.writes[conn.writes.length - 1], [0x0A, 0x2D, 0x02]));

    // --- read(): 12-bit sign-extended XYZ at +-2g (0.001 g/LSB) ---
    conn.setRegister(0x0E, [0x64, 0x00, 0xCE, 0x0F, 0xD0, 0x07]); // x=100,y=-50,z=2000 raw
    const { x, y, z } = await chip.read();
    checkTrue('read_x', close(x, 0.1));
    checkTrue('read_y', close(y, -0.05));
    checkTrue('read_z', close(z, 2.0));

    // --- ADXL362Full ---
    const fullConn = newConnection();
    const full = new ADXL362Full(fullConn);
    await wait(60);

    // deviceId()
    fullConn.setRegister(0x00, [0xAD, 0x1D, 0xF2, 0x07]);
    const id = await full.deviceId();
    checkTrue('device_id', id.devidAd === 0xAD && id.devidMst === 0x1D && id.partid === 0xF2 && id.revid === 0x07);

    // softReset()
    await full.softReset();
    checkTrue('soft_reset_writes_key', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x1F, 0x52]));

    // setRange(): read-modify-write FILTER_CTL
    fullConn.setRegister(0x2C, [0x13]);
    await full.setRange(4);
    checkTrue('set_range_4g', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2C, 0x53]));
    fullConn.setRegister(0x2C, [0x53]);
    await full.setRange(8);
    checkTrue('set_range_8g', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2C, 0x93]));

    // setOdr(): nearest supported rate
    fullConn.setRegister(0x2C, [0x93]);
    await full.setOdr(60); // nearest of 50/100 -> 50 Hz (code 0x02)
    checkTrue('set_odr_nearest', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2C, 0x92]));

    // setHalfBandwidth()
    fullConn.setRegister(0x2C, [0x00]);
    await full.setHalfBandwidth(true);
    checkTrue('set_half_bandwidth_on', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2C, 0x10]));
    fullConn.setRegister(0x2C, [0x10]);
    await full.setHalfBandwidth(false);
    checkTrue('set_half_bandwidth_off', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2C, 0x00]));

    // setNoiseMode()
    fullConn.setRegister(0x2D, [0x02]);
    await full.setNoiseMode(ADXL362Full.NOISE_ULTRALOW);
    checkTrue('set_noise_mode_ultralow', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2D, 0x22]));

    // setWakeupMode()
    fullConn.setRegister(0x2D, [0x22]);
    await full.setWakeupMode(true);
    checkTrue('set_wakeup_mode_on', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2D, 0x2A]));

    // setAutosleep()
    fullConn.setRegister(0x2D, [0x2A]);
    await full.setAutosleep(true);
    checkTrue('set_autosleep_on', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2D, 0x2E]));

    // setExternalClock()
    fullConn.setRegister(0x2D, [0x2E]);
    await full.setExternalClock(true);
    checkTrue('set_external_clock_on', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2D, 0x6E]));

    // setExternalSampleTrigger()
    fullConn.setRegister(0x2C, [0x92]);
    await full.setExternalSampleTrigger(true);
    checkTrue('set_external_sample_trigger_on', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2C, 0x9A]));

    // read8bit(): signed 8-bit, 16x LSB scale (range currently 8g from setRange(8) above)
    fullConn.setRegister(0x08, [100, 206, 50]); // x=100, y=-50 (0xCE), z=50
    const r8 = await full.read8bit();
    const sens8 = 0.004255 * 16;
    checkTrue('read_8bit_x', close(r8.x, 100 * sens8));
    checkTrue('read_8bit_y', close(r8.y, -50 * sens8));
    checkTrue('read_8bit_z', close(r8.z, 50 * sens8));

    // temperature(): bias=350 LSB @25C, 0.065 C/LSB -> raw 427 = 30 C
    fullConn.setRegister(0x14, [0xAB, 0x01]);
    checkTrue('temperature', close(await full.temperature(), 30.0, 0.01));

    // status()/awake()/dataReady()
    fullConn.setRegister(0x0B, [0x41]); // AWAKE + DATA_READY
    checkTrue('status_raw', (await full.status()) === 0x41);
    checkTrue('awake_true', (await full.awake()) === true);
    checkTrue('data_ready_true', (await full.dataReady()) === true);
    fullConn.setRegister(0x0B, [0x00]);
    checkTrue('awake_false', (await full.awake()) === false);

    // fifoEntries(): 10-bit count from FIFO_ENTRIES_L/H
    fullConn.setRegister(0x0C, [0xFF, 0x01]); // 0x1FF = 511
    checkTrue('fifo_entries', (await full.fifoEntries()) === 0x1FF);

    // configureFifo(): FIFO_CONTROL (AH/FIFO_TEMP/FIFO_MODE) + FIFO_SAMPLES
    await full.configureFifo(ADXL362Full.FIFO_STREAM, true, 300);
    checkTrue('configure_fifo_control',
        bytesEqual(fullConn.writes[fullConn.writes.length - 2], [0x0A, 0x28, 0x0E]));
    checkTrue('configure_fifo_samples',
        bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x29, 0x2C]));

    // readFifo(): decodes axis + value per entry, including temperature axis
    fullConn.setRegister(0x0C, [2, 0]); // 2 entries
    fullConn.setRegister(0x0D, [100, 0, 171, 193]); // FIFO read command is [0x0D] alone
    const entries = await full.readFifo();
    checkTrue('read_fifo_count', entries.length === 2);
    checkTrue('read_fifo_axis0', entries[0].axis === ADXL362Full.AXIS_X);
    checkTrue('read_fifo_value0', close(entries[0].value, 100 * 0.004255));
    checkTrue('read_fifo_axis1', entries[1].axis === ADXL362Full.AXIS_TEMP);
    checkTrue('read_fifo_value1', close(entries[1].value, 30.0, 0.01));

    fullConn.setRegister(0x0C, [0, 0]);
    checkTrue('read_fifo_empty', (await full.readFifo()).length === 0);

    // setActivityThreshold(): regression for the 11-bit (not 10-bit) H-register bug.
    fullConn.setRegister(0x2C, [0x92]);
    await full.setRange(2);
    fullConn.setRegister(0x27, [0x00]);
    await full.setActivityThreshold(1.5, true);
    // raw = round(1.5 / 0.001) = 1500 = 0x5DC -> L=0xDC, H bits[10:8]=0x05.
    // writes[len-2] is the readReg(ACT_INACT_CTL) command phase, not a write.
    checkTrue('activity_threshold_low_byte',
        bytesEqual(fullConn.writes[fullConn.writes.length - 4], [0x0A, 0x20, 0xDC]));
    checkTrue('activity_threshold_high_byte_11bit',
        bytesEqual(fullConn.writes[fullConn.writes.length - 3], [0x0A, 0x21, 0x05]));
    checkTrue('activity_threshold_referenced',
        bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x27, 0x02]));

    // setActivityTime()
    await full.setActivityTime(200);
    checkTrue('activity_time', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x22, 200]));

    // setInactivityThreshold(): same 11-bit regression, absolute (not referenced)
    fullConn.setRegister(0x27, [0x00]);
    await full.setInactivityThreshold(1.5, false);
    checkTrue('inactivity_threshold_low_byte',
        bytesEqual(fullConn.writes[fullConn.writes.length - 4], [0x0A, 0x23, 0xDC]));
    checkTrue('inactivity_threshold_high_byte_11bit',
        bytesEqual(fullConn.writes[fullConn.writes.length - 3], [0x0A, 0x24, 0x05]));
    checkTrue('inactivity_threshold_absolute',
        bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x27, 0x00]));

    // setInactivityTime(): 16-bit
    await full.setInactivityTime(0x1234);
    checkTrue('inactivity_time_low', bytesEqual(fullConn.writes[fullConn.writes.length - 2], [0x0A, 0x25, 0x34]));
    checkTrue('inactivity_time_high', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x26, 0x12]));

    // enableActivityDetection() / enableInactivityDetection()
    fullConn.setRegister(0x27, [0x00]);
    await full.enableActivityDetection(true);
    checkTrue('enable_activity_detection', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x27, 0x01]));
    fullConn.setRegister(0x27, [0x01]);
    await full.enableInactivityDetection(true);
    checkTrue('enable_inactivity_detection', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x27, 0x05]));

    // setLinkLoopMode()
    fullConn.setRegister(0x27, [0x05]);
    await full.setLinkLoopMode(ADXL362Full.LINKLOOP_LOOP);
    checkTrue('set_link_loop_mode', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x27, 0x35]));

    // setInterrupt() / setInterruptPolarity()
    fullConn.setRegister(0x2A, [0x00]);
    await full.setInterrupt(1, ADXL362Full.SOURCE_AWAKE, true);
    checkTrue('set_interrupt_int1_awake', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2A, 0x40]));
    fullConn.setRegister(0x2B, [0x00]);
    await full.setInterrupt(2, ADXL362Full.SOURCE_FIFO_WATERMARK, true);
    checkTrue('set_interrupt_int2_watermark', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2B, 0x04]));

    fullConn.setRegister(0x2A, [0x40]);
    await full.setInterruptPolarity(1, true);
    checkTrue('set_interrupt_polarity_active_low', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2A, 0xC0]));

    // selfTest()
    fullConn.setRegister(0x2E, [0x00]);
    await full.selfTest(true);
    checkTrue('self_test_on', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2E, 0x01]));
    fullConn.setRegister(0x2E, [0x01]);
    await full.selfTest(false);
    checkTrue('self_test_off', bytesEqual(fullConn.writes[fullConn.writes.length - 1], [0x0A, 0x2E, 0x00]));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
