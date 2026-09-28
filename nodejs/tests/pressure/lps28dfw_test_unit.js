'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LPS28DFWMinimal, LPS28DFWFull } = require('../../packages/periph/src/chips/pressure/lps28dfw');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

function packPress(hPa, sens = 4096.0) {
    let raw = Math.round(hPa * sens);
    if (raw < 0) raw += 0x1000000;
    return [raw & 0xFF, (raw >> 8) & 0xFF, (raw >> 16) & 0xFF];
}

function packTemp(celsius) {
    let raw = Math.round(celsius * 100.0);
    if (raw < 0) raw += 0x10000;
    return [raw & 0xFF, (raw >> 8) & 0xFF];
}

function newConnection() {
    const c = new I2CConnectionMock();
    c.setRegister(0x0F, [0xB4]); // WHO_AM_I
    return c;
}

function lastWriteTo(connection, reg) {
    for (let i = connection.writes.length - 1; i >= 0; i--) {
        const w = connection.writes[i];
        if (w.length === 2 && w[0] === reg) return w[1];
    }
    return null;
}

async function main() {
    // --- Minimal constructor: WHO_AM_I check, CTRL_REG2/CTRL_REG1 defaults ---
    const connection = newConnection();
    const chip = new LPS28DFWMinimal(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish
    checkTrue('init_writes_ctrl_reg2', lastWriteTo(connection, 0x11) === 0x18);
    checkTrue('init_writes_ctrl_reg1', lastWriteTo(connection, 0x10) === 0x22);

    // --- readPressure()/readTemperature(): Mode 1, known values ---
    connection.setRegister(0x28, packPress(1013.25));
    checkTrue('read_pressure_known', Math.abs((await chip.readPressure()) - 1013.25) < 0.001);
    connection.setRegister(0x2B, packTemp(23.5));
    checkTrue('read_temperature_known', Math.abs((await chip.readTemperature()) - 23.5) < 0.001);

    // --- Negative pressure/temperature (sign extension) ---
    connection.setRegister(0x28, packPress(-50.0));
    checkTrue('read_pressure_negative', Math.abs((await chip.readPressure()) - (-50.0)) < 0.001);
    connection.setRegister(0x2B, packTemp(-10.0));
    checkTrue('read_temperature_negative', Math.abs((await chip.readTemperature()) - (-10.0)) < 0.001);

    // --- Full: configure() writes CTRL_REG2 then CTRL_REG1 ---
    const fullConn = newConnection();
    const full = new LPS28DFWFull(fullConn);
    await flushMicrotasks();
    await full.configure(LPS28DFWFull.ODR_50_HZ, LPS28DFWFull.AVG_64, 1, true, 1);
    checkTrue('configure_ctrl_reg2', lastWriteTo(fullConn, 0x11) === 0x78);
    checkTrue('configure_ctrl_reg1', lastWriteTo(fullConn, 0x10) === 0x2C);

    // --- read(): burst pressure+temperature, Mode 2 sensitivity ---
    fullConn.setRegister(0x28, packPress(2000.0, 2048.0));
    fullConn.setRegister(0x2B, packTemp(18.25));
    const r = await full.read();
    checkTrue('read_pressure_mode2', Math.abs(r.pressure - 2000.0) < 0.001);
    checkTrue('read_temperature', Math.abs(r.temperature - 18.25) < 0.001);

    // --- isDataReady() ---
    fullConn.setRegister(0x27, [0x01]);
    checkTrue('is_data_ready_true', (await full.isDataReady()) === true);
    fullConn.setRegister(0x27, [0x00]);
    checkTrue('is_data_ready_false', (await full.isDataReady()) === false);

    // --- readOneshot(): saves/restores ODR, triggers ONESHOT, polls P_DA ---
    const oneshotConn = newConnection();
    const oneshot = new LPS28DFWFull(oneshotConn);
    await flushMicrotasks();
    oneshotConn.setRegister(0x10, [0x22]); // saved CTRL_REG1 (ODR=4)
    oneshotConn.setRegister(0x11, [0x18]); // saved CTRL_REG2
    oneshotConn.setRegister(0x27, [0x01]); // P_DA already set
    oneshotConn.setRegister(0x28, packPress(1000.0));
    oneshotConn.setRegister(0x2B, packTemp(20.0));
    const result = await oneshot.readOneshot();
    checkTrue('read_oneshot_result', Math.abs(result.pressure - 1000.0) < 0.001);
    checkTrue('read_oneshot_restores_odr', lastWriteTo(oneshotConn, 0x10) === 0x22);

    // --- readOneshot() timeout: bounded loop must not hang (200 * 5ms spin) ---
    const timeoutConn = newConnection();
    const timeoutSensor = new LPS28DFWFull(timeoutConn);
    await flushMicrotasks();
    timeoutConn.setRegister(0x27, [0x00]); // P_DA never set
    await timeoutSensor.readOneshot(); // must return, not hang
    checkTrue('read_oneshot_bounded_no_hang', true);

    // --- setOffset(): packs signed 16-bit RPDS ---
    await full.setOffset(-0.5); // Mode 2 active: -0.5*2048 = -1024 = 0xFC00
    checkTrue('set_offset_low', lastWriteTo(fullConn, 0x1A) === 0x00);
    checkTrue('set_offset_high', lastWriteTo(fullConn, 0x1B) === 0xFC);

    // --- softreset() --- (CTRL_REG2 is currently 0x78 from configure() above)
    await full.softreset();
    checkTrue('softreset_writes_swreset', lastWriteTo(fullConn, 0x11) === 0x7A);

    // --- fifoConfigure(): regression for missing unconditional Bypass pass-through ---
    const fifoConn = newConnection();
    const fifoChip = new LPS28DFWFull(fifoConn);
    await flushMicrotasks();
    await fifoChip.fifoConfigure(LPS28DFWFull.FIFO_CONTINUOUS, 50, true);
    const fifoCtrlWrites = fifoConn.writes.filter((w) => w.length === 2 && w[0] === 0x14).map((w) => w[1]);
    checkTrue('fifo_configure_bypass_pass_through', fifoCtrlWrites.slice(0, -1).includes(0x00));
    checkTrue('fifo_configure_final_ctrl', fifoCtrlWrites[fifoCtrlWrites.length - 1] === 0x0A);
    checkTrue('fifo_configure_watermark', lastWriteTo(fifoConn, 0x15) === 50);

    // --- fifoRead(): N=3 samples packed back-to-back ---
    let fifoBytes = [];
    for (const hpa of [1000.0, 1010.0, 1020.0]) fifoBytes = fifoBytes.concat(packPress(hpa));
    fifoConn.setRegister(0x78, fifoBytes);
    const samples = await fifoChip.fifoRead(3);
    checkTrue('fifo_read_length', samples.length === 3);
    checkTrue('fifo_read_values',
        Math.abs(samples[0] - 1000.0) < 0.01 && Math.abs(samples[1] - 1010.0) < 0.01 && Math.abs(samples[2] - 1020.0) < 0.01);

    // --- fifoRead() empty ---
    checkTrue('fifo_read_zero', (await fifoChip.fifoRead(0)).length === 0);

    // --- fifoLevel() ---
    fifoConn.setRegister(0x25, [42]);
    checkTrue('fifo_level', (await fifoChip.fifoLevel()) === 42);

    // --- setThreshold(): packs 15-bit unsigned THS_P + enables PHE/PLE ---
    const threshConn = newConnection();
    const threshChip = new LPS28DFWFull(threshConn);
    await flushMicrotasks();
    threshConn.setRegister(0x0B, [0x00]);
    await threshChip.setThreshold(1020.0, true, true); // Mode 1: 1020*16=16320=0x3FC0
    checkTrue('set_threshold_low', lastWriteTo(threshConn, 0x0C) === 0xC0);
    checkTrue('set_threshold_high', lastWriteTo(threshConn, 0x0D) === 0x3F);
    checkTrue('set_threshold_enables_phe_ple', lastWriteTo(threshConn, 0x0B) === 0x03);

    // --- chipId() ---
    checkTrue('chip_id', (await full.chipId()) === 0xB4);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
