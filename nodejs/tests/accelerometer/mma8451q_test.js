'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const {
    MMA8451QMinimal,
    MMA8451QFull,
    SOURCE_ORIENTATION,
    SOURCE_PULSE,
    SOURCE_FF_MT,
} = require('../../packages/periph/src/chips/accelerometer/mma8451q');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel     = new MMA8451QMinimal(connection);
const accelFull = new MMA8451QFull(connection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        // --- Minimal ---------------------------------------------------
        const [x, y, z] = await accel.read();
        checkTrue('read_returns_numbers', typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
        const mag = Math.sqrt(x * x + y * y + z * z);
        checkTrue('magnitude_near_1g', mag >= 0.5 && mag <= 1.5);

        // --- Full: configuration ---------------------------------------
        await accelFull.setRange(4);
        const [x2, y2, z2] = await accelFull.read();
        checkTrue('read_after_set_range_4g', typeof x2 === 'number' && typeof y2 === 'number' && typeof z2 === 'number');

        await accelFull.setDataRate(200);
        await accelFull.setOversampling(0);
        await accelFull.setRange(2);

        const status = await accelFull.readStatus();
        checkTrue('read_status_in_range', status >= 0 && status <= 0xFF);

        const mode = await accelFull.readSystemMode();
        checkTrue('read_system_mode_wake', mode === 1);

        const raw = await accelFull.readRaw();
        checkTrue('read_raw_returns_counts',
            raw.length === 3 && raw.every(v => Number.isInteger(v) && v >= -8192 && v <= 8191));

        // --- Full: offsets ---------------------------------------------
        await accelFull.setOffset(0.01, -0.01, 0.0);
        const [ox, oy, oz] = await accelFull.getOffset();
        checkTrue('get_offset_roundtrip',
            Math.abs(ox - 0.01) < 0.003 && Math.abs(oy + 0.01) < 0.003 && Math.abs(oz) < 0.003);
        await accelFull.setOffset(0, 0, 0);

        // --- Full: orientation -----------------------------------------
        await accelFull.setOrientation(true, 100, 45, 4, 4, 1);
        const orient = await accelFull.readOrientation();
        checkTrue('read_orientation_returns_fields',
            typeof orient.changed === 'boolean' &&
            typeof orient.lockout === 'boolean' &&
            typeof orient.portraitLandscape === 'number' &&
            typeof orient.back === 'boolean');
        await accelFull.setOrientation(false);

        // --- Full: FIFO -------------------------------------------------
        await accelFull.setFifo(1, 16);
        const level = await accelFull.fifoLevel();
        checkTrue('fifo_level_in_range', level >= 0 && level <= 32);
        const fstatus = await accelFull.readFifoStatus();
        checkTrue('read_fifo_status_returns_fields',
            typeof fstatus.overflow === 'boolean' &&
            typeof fstatus.watermark === 'boolean' &&
            typeof fstatus.count === 'number');
        await accelFull.setFifo(0, 0);

        // --- Full: detectors --------------------------------------------
        await accelFull.setFreefallMotion(false, 7, 0.2, 60, true, false);
        const motion = await accelFull.readMotionSource();
        checkTrue('read_motion_source_in_range', motion >= 0 && motion <= 0xFF);

        await accelFull.setTransient(7, 0.25, 0, false, true);
        const transient = await accelFull.readTransientSource();
        checkTrue('read_transient_source_in_range', transient >= 0 && transient <= 0xFF);

        await accelFull.setPulse(0, 4, [0.5, 0.5, 1.0], 40, 100, 300, true);
        const pulse = await accelFull.readPulseSource();
        checkTrue('read_pulse_source_in_range', pulse >= 0 && pulse <= 0xFF);

        // --- Full: interrupts -------------------------------------------
        await accelFull.enableInterrupt(SOURCE_ORIENTATION, 1);
        await accelFull.enableInterrupt(SOURCE_PULSE, 1);
        await accelFull.enableInterrupt(SOURCE_FF_MT, 1);
        const polled = await accelFull.pollInterrupt();
        checkTrue('poll_interrupt_in_range', polled >= 0 && polled <= 0xFF);
        await accelFull.disableInterrupt(SOURCE_ORIENTATION);
        await accelFull.disableInterrupt(SOURCE_PULSE);
        await accelFull.disableInterrupt(SOURCE_FF_MT);

        // --- Full: interrupt subscription -------------------------------
        await accelFull.onInterrupt(() => {});
        await new Promise(r => setTimeout(r, 50));
        await accelFull.offInterrupt();
        checkTrue('on_off_interrupt_runs', true);

        // --- Full: auto sleep -------------------------------------------
        await accelFull.setAutoSleep(true, 12.5, 10, SOURCE_PULSE | SOURCE_FF_MT);
        await new Promise(r => setTimeout(r, 50));
        const sleepMode = await accelFull.readSystemMode();
        checkTrue('read_system_mode_after_auto_sleep',
            sleepMode === 0 || sleepMode === 1 || sleepMode === 2);
        await accelFull.setAutoSleep(false);

        // --- Full: self test -------------------------------------------
        const st = await accelFull.selfTest();
        checkTrue('self_test_returns_result', typeof st.ok === 'boolean');
        console.log(`self-test: ok=${st.ok} dx=${st.dx} dy=${st.dy} dz=${st.dz}`);
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
