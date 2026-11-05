'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { FXOS8700Minimal, FXOS8700Full, FXOS8700Source, I2C_ADDRESS } =
    require('../../packages/periph/src/chips/accelerometer/fxos8700');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || String(I2C_ADDRESS), 10);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const chip = new FXOS8700Minimal(connection);
const full = new FXOS8700Full(connection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    try {
        await chip.init();
        const a = await chip.readAccel();
        checkTrue('accel_magnitude_about_1g', Math.hypot(a.x, a.y, a.z) >= 0.7 && Math.hypot(a.x, a.y, a.z) <= 1.3);
        const m = await chip.readMag();
        checkTrue('mag_plausible', Math.abs(m.x) <= 1200 && Math.abs(m.y) <= 1200 && Math.abs(m.z) <= 1200);

        await full.init();
        const h = await full.readHybrid();
        checkTrue('hybrid_plausible', Math.abs(h.accel.z) <= 2.5 && Math.abs(h.mag.x) <= 1200);
        const ra = await full.readRawAccel();
        checkTrue('raw_accel_range', Math.abs(ra.x) <= 8192 && Math.abs(ra.y) <= 8192 && Math.abs(ra.z) <= 8192);
        const rm = await full.readRawMag();
        checkTrue('raw_mag_range', Math.abs(rm.x) <= 32768 && Math.abs(rm.y) <= 32768);
        const t = await full.readTemperature();
        checkTrue('temperature_plausible', t >= -100 && t <= 150);

        await full.setRange(4);
        await full.setDataRate(100);
        await sleep(200);
        checkTrue('range_4g_plausible', Math.abs((await full.readAccel()).z) <= 4.5);
        await full.setRange(2);
        await full.setDataRate(200);
        await full.setAccelOversampling(2);
        await full.setAccelOversampling(0);
        await full.setMagOversampling(7);
        checkTrue('system_mode_wake', (await full.readSystemMode()) === 1);
        await sleep(200);
        checkTrue('new_accel_data_boolean', typeof (await full.newAccelData()) === 'boolean');
        checkTrue('new_mag_data_boolean', typeof (await full.newMagData()) === 'boolean');

        await full.setAccelOffset(0.02, -0.02, 0.0);
        const ao = await full.getAccelOffset();
        checkTrue('accel_offset_roundtrip', Math.abs(ao.x - 0.02) < 0.003 && Math.abs(ao.y + 0.02) < 0.003);
        await full.setAccelOffset(0, 0, 0);
        await full.setMagOffset(5.0, -5.0, 0.0);
        const mo = await full.getMagOffset();
        checkTrue('mag_offset_roundtrip', Math.abs(mo.x - 5.0) < 0.15 && Math.abs(mo.y + 5.0) < 0.15);
        await full.setMagOffset(0, 0, 0);

        await full.resetMagMinMax();
        await sleep(200);
        const mm = await full.readMagMinMax();
        checkTrue('min_max_ordered', mm.min.x <= mm.max.x && mm.min.y <= mm.max.y && mm.min.z <= mm.max.z);
        const hd = await full.heading();
        checkTrue('heading_in_range', hd >= 0 && hd < 360);

        await full.setFifo(1, 8);
        await sleep(500);
        const level = await full.fifoLevel();
        checkTrue('fifo_level', level >= 0 && level <= 32);
        checkTrue('fifo_samples', (await full.readFifo(4)).length <= 4);
        await full.setFifo(0);

        await full.setFreefallMotion(true, { thresholdG: 0.3, debounceMs: 50, latch: true });
        checkTrue('motion_source_readable', (await full.readMotionSource()) <= 255);
        await full.setTransient({ thresholdG: 0.3 });
        checkTrue('transient_source_readable', (await full.readTransientSource()) <= 255);
        await full.setPulse({ single: 7 });
        checkTrue('pulse_source_readable', (await full.readPulseSource()) <= 255);
        await full.setOrientation({ debounceMs: 100 });
        checkTrue('orientation_readable', (await full.readOrientation()).portraitLandscape <= 3);

        await full.enableInterrupt(FXOS8700Source.SOURCE_DATA_READY, 1);
        checkTrue('poll_interrupt_mask', (await full.pollInterrupt()) <= 255);
        await full.disableInterrupt(FXOS8700Source.SOURCE_DATA_READY);

        const st = await full.selfTest();
        checkTrue('self_test_runs', typeof st.ok === 'boolean' && st.dz > 0);
        await full.setMode(2);
        const once = await full.measureMagOnce();
        checkTrue('measure_mag_once', Math.abs(once.x) <= 1200);
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
