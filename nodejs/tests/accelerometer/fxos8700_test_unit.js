'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { FXOS8700Minimal, FXOS8700Full, FXOS8700Source } =
    require('../../packages/periph/src/chips/accelerometer/fxos8700');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function close(a, b, eps = 1e-6) {
    return Math.abs(a - b) < eps;
}

function eq(buf, arr) {
    if (!buf || buf.length !== arr.length) return false;
    for (let i = 0; i < arr.length; i++) if (buf[i] !== arr[i]) return false;
    return true;
}

async function throws(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

/** Bytes of the last write to `reg` (without the register byte), or null. */
function lastWrite(conn, reg) {
    let value = null;
    for (const w of conn.writes) {
        if (w.length >= 2 && w[0] === reg) value = w.subarray(1);
    }
    return value;
}

async function make(registers = {}) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x0D, [0xC7]);
    for (const [reg, value] of Object.entries(registers)) conn.setRegister(Number(reg), [value]);
    const chip = new FXOS8700Full(conn);
    await chip.init();
    return { conn, chip };
}

/** Mock that clears M_CTRL_REG1.m_ost after it is read, like the hardware. */
class OneShotMock extends I2CConnectionMock {
    async writeRead(data, n) {
        const out = await super.writeRead(data, n);
        if (data[0] === 0x5B) this.registers.set(0x5B, (this.registers.get(0x5B) || 0) & ~0x20);
        return out;
    }
}

/** Mock returning shifted accelerometer data while CTRL_REG2.st is set. */
class SelfTestMock extends I2CConnectionMock {
    constructor(delta) { super(); this.delta = delta; }
    async writeRead(data, n) {
        const out = await super.writeRead(data, n);
        if (data[0] === 0x01 && ((this.registers.get(0x2B) || 0) & 0x80)) {
            this.delta.forEach((d, axis) => {
                const raw = (d << 2) & 0xFFFF;
                out[2 * axis] = raw >> 8;
                out[2 * axis + 1] = raw & 0xFF;
            });
        }
        return out;
    }
}

async function main() {
    // --- Initialization -----------------------------------------------------
    let { conn, chip } = await make();
    const initWrites = conn.writes.filter((w) => w.length === 2).map((w) => [w[0], w[1]]);
    checkTrue('init_sequence', JSON.stringify(initWrites) === JSON.stringify(
        [[0x2B, 0x40], [0x2A, 0x00], [0x5B, 0x1F], [0x5C, 0x20], [0x0E, 0x00], [0x2B, 0x00], [0x2A, 0x11]]));

    let c = new I2CConnectionMock();
    c.setRegister(0x0D, [0xC4]);
    await new FXOS8700Minimal(c).init();
    checkTrue('preproduction_who_am_i_accepted', true);

    c = new I2CConnectionMock();
    c.setRegister(0x0D, [0x1A]);
    checkTrue('wrong_who_am_i_rejects', await throws(() => new FXOS8700Minimal(c).init()));

    c = new I2CConnectionMock();
    c.setRegister(0x0D, [0xC7]);
    c.failNextWrites(1);
    await new FXOS8700Minimal(c).init();
    checkTrue('nack_on_reset_tolerated', true);

    // --- Measurements -------------------------------------------------------
    ({ conn, chip } = await make());
    conn.setRegister(0x01, [0x40, 0x00, 0xC0, 0x00, 0x00, 0x04]);
    conn.setRegister(0x07, [0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4]);   // mag half of the hybrid burst
    conn.setRegister(0x33, [0x00, 0x64, 0xFF, 0x9C, 0x01, 0xF4]);
    let a = await chip.readAccel();
    checkTrue('accel_x_1g', close(a.x, 1.0));
    checkTrue('accel_y_minus_1g', close(a.y, -1.0));
    checkTrue('accel_z_lsb', close(a.z, 1 / 4096));
    let r = await chip.readRawAccel();
    checkTrue('raw_accel', r.x === 4096 && r.y === -4096 && r.z === 1);
    let m = await chip.readMag();
    checkTrue('mag_x', close(m.x, 10.0));
    checkTrue('mag_y', close(m.y, -10.0));
    checkTrue('mag_z', close(m.z, 50.0));
    r = await chip.readRawMag();
    checkTrue('raw_mag', r.x === 100 && r.y === -100 && r.z === 500);
    const h = await chip.readHybrid();
    conn.registers.set(0x0B, 0);  // the burst overlay above covered SYSMOD
    checkTrue('hybrid_accel', close(h.accel.x, 1.0));
    checkTrue('hybrid_mag', close(h.mag.z, 50.0));
    await chip.setRange(4);
    checkTrue('range_4g_scale', close((await chip.readAccel()).x, 2.0));
    await chip.setRange(8);
    checkTrue('range_8g_scale', close((await chip.readAccel()).x, 4.0));
    checkTrue('range_8g_register', eq(lastWrite(conn, 0x0E), [0x02]));
    checkTrue('range_3_rejects', await throws(() => chip.setRange(3)));
    checkTrue('lnoise_at_8g_rejects', await throws(() => chip.setLowNoise(true)));
    conn.registers.set(0x51, 0xFB);
    checkTrue('temperature', close(await chip.readTemperature(), -5 * 0.96));
    await chip.setMode(0);
    checkTrue('temperature_accel_only_rejects', await throws(() => chip.readTemperature()));
    checkTrue('hybrid_in_accel_only_rejects', await throws(() => chip.readHybrid()));

    // --- Standby handling -----------------------------------------------------
    ({ conn, chip } = await make());
    conn.writes.length = 0;
    await chip.setDataRate(100);
    const w = conn.writes.filter((x) => x.length === 2).map((x) => [x[0], x[1]]);
    checkTrue('data_rate_through_standby', JSON.stringify(w) === JSON.stringify([[0x2A, 0x10], [0x2A, 0x18], [0x2A, 0x19]]));
    checkTrue('rate_77_rejects', await throws(() => chip.setDataRate(77)));
    await chip.setDataRate(1.56);
    checkTrue('rate_1p56_selects_dr7', ((lastWrite(conn, 0x2A)[0] >> 3) & 7) === 7);

    ({ conn, chip } = await make());
    await chip.setLowNoise(true);
    checkTrue('lnoise_bit', (lastWrite(conn, 0x2A)[0] & 0x05) === 0x05);
    await chip.setResolution(8);
    checkTrue('f_read_bit', (lastWrite(conn, 0x2A)[0] & 0x02) === 0x02);
    conn.setRegister(0x01, [0x40]);
    conn.setRegister(0x03, [0xC0]);
    conn.setRegister(0x05, [0x20]);
    checkTrue('accel_8bit_scale', close((await chip.readAccel()).x, 1.0));
    await chip.setResolution(14);
    await chip.setAccelOversampling(2, 3);
    checkTrue('mods_smods', (lastWrite(conn, 0x2B)[0] & 0x1B) === 0x1A);
    await chip.setMagOversampling(5, 2);
    checkTrue('m_os', (lastWrite(conn, 0x5B)[0] & 0x1C) === (5 << 2));
    checkTrue('m_aslp_os', (lastWrite(conn, 0x5D)[0] & 0x70) === (2 << 4));
    await chip.setHighPassOutput(true, 2);
    checkTrue('hpf_out', (lastWrite(conn, 0x0E)[0] & 0x10) === 0x10);
    checkTrue('hp_sel', (lastWrite(conn, 0x0F)[0] & 0x03) === 2);

    // --- Offsets --------------------------------------------------------------
    await chip.setAccelOffset(0.06, -0.06, 1.0);
    checkTrue('accel_offset_registers', eq(lastWrite(conn, 0x2F), [30, 0xE2, 127]));
    const ao = await chip.getAccelOffset();
    checkTrue('accel_offset_roundtrip', close(ao.x, 0.06) && close(ao.z, 0.254));
    await chip.setMagOffset(60.0, -60.0, 0.0);
    checkTrue('mag_offset_registers', eq(lastWrite(conn, 0x3F), [0x04, 0xB0, 0xFB, 0x50, 0x00, 0x00]));
    const mo = await chip.getMagOffset();
    checkTrue('mag_offset_roundtrip', close(mo.x, 60.0) && close(mo.y, -60.0));

    // --- Magnetometer helpers ---------------------------------------------------
    conn = new OneShotMock();
    conn.setRegister(0x0D, [0xC7]);
    chip = new FXOS8700Full(conn);
    await chip.init();
    await chip.setMagAutoCalibration(true);
    checkTrue('m_acal_set', (lastWrite(conn, 0x5B)[0] & 0x80) === 0x80);
    await chip.setMagAutoCalibration(false);
    checkTrue('m_acal_cleared', (lastWrite(conn, 0x5B)[0] & 0x80) === 0);
    await chip.resetMagMinMax();
    checkTrue('maxmin_rst', (lastWrite(conn, 0x5C)[0] & 0x04) === 0x04);
    await chip.triggerMagReset();
    checkTrue('m_rst', (lastWrite(conn, 0x5B)[0] & 0x40) === 0x40);
    await chip.setMagReset(2);
    checkTrue('m_rst_cnt', (lastWrite(conn, 0x5C)[0] & 0x03) === 2);
    conn.setRegister(0x4B, [0xFF, 0x9C]);
    conn.setRegister(0x45, [0x00, 0x64]);
    const mm = await chip.readMagMinMax();
    checkTrue('min_x', close(mm.min.x, -10.0));
    checkTrue('max_x', close(mm.max.x, 10.0));
    const once = await chip.measureMagOnce();
    checkTrue('one_shot', typeof once.x === 'number');

    // --- Heading ----------------------------------------------------------------
    ({ conn, chip } = await make());
    await chip.setMode(0);
    conn.setRegister(0x01, [0, 0, 0, 0, 0x40, 0]);
    conn.setRegister(0x33, [0x01, 0xF4, 0, 0, 0, 0]);
    checkTrue('heading_north', close(await chip.heading(), 0.0, 1e-6));
    checkTrue('heading_declination', close(await chip.heading(10.0), 10.0, 1e-6));
    conn.setRegister(0x33, [0x00, 0x00, 0xFE, 0x0C, 0, 0]);
    checkTrue('heading_east', close(await chip.heading(), 90.0, 1e-6));

    // --- FIFO -------------------------------------------------------------------
    ({ conn, chip } = await make({ 0x00: 0x42, 0x01: 0x40 }));
    await chip.setFifo(1, 16);
    checkTrue('fifo_circular', eq(lastWrite(conn, 0x09), [0x50]));
    await chip.setFifo(2, 8);
    const f = conn.writes.filter((x) => x.length === 2 && x[0] === 0x09).map((x) => x[1]);
    checkTrue('fifo_mode_change_via_disabled', JSON.stringify(f.slice(-3)) === JSON.stringify([0x50, 0x00, 0x88]));
    checkTrue('fifo_level', (await chip.fifoLevel()) === 2);
    const fs = await chip.readFifoStatus();
    checkTrue('fifo_status', !fs.overflow && fs.watermark && fs.count === 2);
    checkTrue('read_fifo_count', (await chip.readFifo()).length === 2);
    checkTrue('resolution_with_fifo_rejects', await throws(() => chip.setResolution(8)));
    await chip.setFifoTrigger({ transient: true, motion: true });
    checkTrue('trig_cfg', eq(lastWrite(conn, 0x0A), [0x24]));
    checkTrue('bad_fifo_mode_rejects', await throws(() => chip.setFifo(4)));

    // --- Detectors -----------------------------------------------------------------
    ({ conn, chip } = await make());
    await chip.setFreefallMotion(true, { axes: 7, thresholdG: 0.3, debounceMs: 60, latch: true });
    checkTrue('ffmt_cfg', eq(lastWrite(conn, 0x15), [0xF8]));
    checkTrue('ffmt_ths', eq(lastWrite(conn, 0x17), [5]));
    checkTrue('ffmt_count', eq(lastWrite(conn, 0x18), [6]));
    await chip.setFreefallMotion(false, { axes: 3, thresholdG: 0.2, debounceClear: true });
    checkTrue('freefall_cfg', eq(lastWrite(conn, 0x15), [0x18]));
    checkTrue('freefall_ths_dbcntm', eq(lastWrite(conn, 0x17), [0x83]));
    await chip.setTransient({ axes: 6, thresholdG: 0.5, debounceMs: 40, bypassHpf: true, latch: true });
    checkTrue('transient_cfg', eq(lastWrite(conn, 0x1D), [0x1D]));
    checkTrue('transient_ths', eq(lastWrite(conn, 0x1F), [8]));
    checkTrue('transient_count', eq(lastWrite(conn, 0x20), [4]));
    await chip.setPulse({ single: 1, double: 4, thresholdG: [0.5, 0.5, 1.0], latch: true });
    checkTrue('pulse_cfg', eq(lastWrite(conn, 0x21), [0x61]));
    checkTrue('pulse_ths', eq(lastWrite(conn, 0x23), [8, 8, 16]));
    checkTrue('pulse_tmlt', eq(lastWrite(conn, 0x26), [3]));
    checkTrue('pulse_ltcy', eq(lastWrite(conn, 0x27), [5]));
    checkTrue('pulse_wind', eq(lastWrite(conn, 0x28), [15]));
    checkTrue('pulse_filter_bits', (lastWrite(conn, 0x0F)[0] & 0x30) === 0x10);
    await chip.setPulse({ single: 1, lpf: false, hpf: false });
    checkTrue('pulse_no_lpf_tmlt', eq(lastWrite(conn, 0x26), [12]));
    checkTrue('pulse_bypass_hpf', (lastWrite(conn, 0x0F)[0] & 0x30) === 0x20);
    await chip.setOrientation({ debounceMs: 100 });
    checkTrue('pl_cfg', eq(lastWrite(conn, 0x11), [0xC0]));
    checkTrue('pl_count', eq(lastWrite(conn, 0x12), [10]));
    checkTrue('pl_ths', eq(lastWrite(conn, 0x14), [0x84]));
    checkTrue('pl_bf_zcomp', eq(lastWrite(conn, 0x13), [0x84]));
    checkTrue('orientation_overflow_rejects', await throws(() => chip.setOrientation({ thresholdDeg: 75, hysteresis: 7 })));
    checkTrue('orientation_bad_angle_rejects', await throws(() => chip.setOrientation({ thresholdDeg: 50 })));
    conn.registers.set(0x10, 0x80 | 0x40 | (3 << 1) | 1);
    const o = await chip.readOrientation();
    checkTrue('read_orientation', o.changed && o.lockout && o.portraitLandscape === 3 && o.back);
    await chip.setAutoSleep(true, { sleepRateHz: 12.5, timeoutS: 10,
        wakeSources: FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_PULSE });
    checkTrue('aslp_rate', (lastWrite(conn, 0x2A)[0] >> 6) === 1);
    checkTrue('aslp_count', eq(lastWrite(conn, 0x29), [31]));
    checkTrue('slpe', (lastWrite(conn, 0x2B)[0] & 0x04) === 0x04);
    checkTrue('wake_bits', (lastWrite(conn, 0x2C)[0] & 0x7C) === 0x18);
    checkTrue('bad_wake_source_rejects', await throws(() => chip.setAutoSleep(true, { wakeSources: 0x01 })));
    await chip.setFifoGate(true);
    checkTrue('fifo_gate', (lastWrite(conn, 0x2C)[0] & 0x80) === 0x80);
    await chip.setInterruptPins(true, true);
    checkTrue('ipol_pp_od', (lastWrite(conn, 0x2C)[0] & 0x03) === 0x03);

    // --- Interrupts -------------------------------------------------------------------
    ({ conn, chip } = await make());
    await chip.enableInterrupt(FXOS8700Source.SOURCE_FF_MT | FXOS8700Source.SOURCE_ORIENTATION, 1);
    checkTrue('ctrl4_enabled', eq(lastWrite(conn, 0x2D), [0x14]));
    checkTrue('ctrl5_int1', eq(lastWrite(conn, 0x2E), [0x14]));
    await chip.enableInterrupt(FXOS8700Source.SOURCE_FF_MT, 2);
    checkTrue('ctrl5_int2', eq(lastWrite(conn, 0x2E), [0x10]));
    await chip.disableInterrupt(FXOS8700Source.SOURCE_ORIENTATION);
    checkTrue('ctrl4_disabled', eq(lastWrite(conn, 0x2D), [0x04]));
    checkTrue('bad_pin_rejects', await throws(() => chip.enableInterrupt(1, 3)));
    conn.registers.set(0x0C, 0x14);
    conn.writes.length = 0;
    checkTrue('poll_interrupt', (await chip.pollInterrupt()) === 0x14);
    const regs = conn.writes.map((x) => x[0]);
    checkTrue('poll_reads_source_registers', regs.includes(0x16) && regs.includes(0x10));
    checkTrue('on_interrupt_bad_pin_rejects', await throws(() => chip.onInterrupt(() => {}, 3)));

    // --- Misc ---------------------------------------------------------------------------
    ({ conn, chip } = await make());
    await chip.writeRegister(0x5F, Buffer.from([1, 2]));
    checkTrue('write_register', eq(lastWrite(conn, 0x5F), [1, 2]));
    checkTrue('read_register', eq(await chip.readRegister(0x5F, 2), [1, 2]));
    conn.registers.set(0x00, 0x08);
    checkTrue('new_accel_data', await chip.newAccelData());
    checkTrue('new_mag_data_false', !(await chip.newMagData()));
    conn.registers.set(0x0B, 0x02);
    checkTrue('system_mode', (await chip.readSystemMode()) === 2);
    conn.registers.set(0x0B, 0x00);
    await chip.reset();
    checkTrue('reset_disables_hybrid_read', await throws(() => chip.readHybrid()));
    await chip.reinit();
    checkTrue('reinit_restores_hybrid', (await chip.readHybrid()) !== null);

    // --- Self-test ------------------------------------------------------------------------
    conn = new SelfTestMock([200, 300, 1300]);
    conn.setRegister(0x0D, [0xC7]);
    chip = new FXOS8700Full(conn);
    const st = await chip.selfTest();
    checkTrue('self_test_passes', st.ok);
    checkTrue('self_test_deltas', st.dx === 200 && st.dy === 300 && st.dz === 1300);
    checkTrue('self_test_restores_ctrl1', eq(lastWrite(conn, 0x2A), [0x11]));
    conn = new SelfTestMock([10, 10, 10]);
    conn.setRegister(0x0D, [0xC7]);
    checkTrue('self_test_fails_weak', !(await new FXOS8700Full(conn).selfTest()).ok);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    if (failed > 0) process.exit(1);
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
