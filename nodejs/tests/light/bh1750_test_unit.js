'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BH1750Full, BH1750Minimal } = require('../../packages/periph/src/chips/light/bh1750');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-6) => Math.abs(a - b) <= tol;

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

function queueWord(c, raw) { c.queueRead(Buffer.from([raw >> 8, raw & 0xFF])); }

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new BH1750Full(c) : new BH1750Minimal(c)];
}

const cmds = (c) => c.writes.map((w) => w[0]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);

async function main() {
    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_power_on', sameCmds(c, [0x01]));

    // Minimal read: one-time H mode, raw / 1.2 (datasheet example 0x8342)
    [c, s] = make(false);
    queueWord(c, 0x8342);
    checkTrue('read_value', close(await s.read(), 0x8342 / 1.2));
    checkTrue('read_command', sameCmds(c, [0x20]));

    // one-time modes
    [c, s] = make();
    queueWord(c, 0x0012);
    checkTrue('measure_once_h', close(await s.measureOnce(BH1750Full.MODE_H), 15.0) && sameCmds(c, [0x20]));
    c.writes.length = 0;
    queueWord(c, 0x0012);
    checkTrue('measure_once_h2', close(await s.measureOnce(BH1750Full.MODE_H2), 7.5) && sameCmds(c, [0x21]));
    c.writes.length = 0;
    queueWord(c, 0x0012);
    checkTrue('measure_once_l', close(await s.measureOnce(BH1750Full.MODE_L), 15.0) && sameCmds(c, [0x23]));
    checkTrue('measure_once_bad_mode', await rejects(() => s.measureOnce(7)));

    // read / readRaw follow the current mode
    [c, s] = make();
    queueWord(c, 0x0012);
    await s.measureOnce(BH1750Full.MODE_H2);
    c.writes.length = 0;
    queueWord(c, 0x0012);
    checkTrue('read_uses_current_mode', close(await s.read(), 7.5) && sameCmds(c, [0x21]));
    c.writes.length = 0;
    queueWord(c, 0x0012);
    checkTrue('read_raw', (await s.readRaw()) === 0x12 && sameCmds(c, [0x21]));

    // continuous mode
    [c, s] = make();
    await s.startContinuous(BH1750Full.MODE_H);
    await s.startContinuous(BH1750Full.MODE_H2);
    await s.startContinuous(BH1750Full.MODE_L);
    checkTrue('continuous_opcodes', sameCmds(c, [0x10, 0x11, 0x13]));
    await s.startContinuous(BH1750Full.MODE_H2);
    c.writes.length = 0;
    queueWord(c, 0x0012);
    checkTrue('read_continuous', close(await s.readContinuous(), 7.5) && c.writes.length === 0);
    checkTrue('continuous_bad_mode', await rejects(() => s.startContinuous(-1)));

    // power control and reset
    [c, s] = make();
    await s.powerDown();
    await s.powerOn();
    checkTrue('power_opcodes', sameCmds(c, [0x00, 0x01]));
    c.writes.length = 0;
    await s.reset();
    checkTrue('reset_powers_on_first', sameCmds(c, [0x01, 0x07]));

    // measurement-time register
    [c, s] = make();
    checkTrue('mtreg_default', s.getMeasurementTimeRegister() === 69);
    await s.setMeasurementTime(69);
    checkTrue('mtreg_69_opcodes', sameCmds(c, [0x42, 0x65]));
    c.writes.length = 0;
    await s.setMeasurementTime(254);
    checkTrue('mtreg_254_opcodes', sameCmds(c, [0x47, 0x7E]));
    checkTrue('mtreg_cached', s.getMeasurementTimeRegister() === 254);
    c.writes.length = 0;
    await s.setMeasurementTime(31);
    checkTrue('mtreg_31_opcodes', sameCmds(c, [0x40, 0x7F]));
    c.writes.length = 0;
    checkTrue('mtreg_low_rejected', await rejects(() => s.setMeasurementTime(30)));
    checkTrue('mtreg_high_rejected', await rejects(() => s.setMeasurementTime(255)));
    checkTrue('mtreg_rejected_no_write', c.writes.length === 0);
    checkTrue('mtreg_unchanged_after_reject', s.getMeasurementTimeRegister() === 31);

    // sensitivity, lux per count and measurement time scale with MTreg
    [c, s] = make();
    checkTrue('lux_per_count_default', close(s.luxPerCount(), 1 / 1.2));
    checkTrue('lux_per_count_h2', close(s.luxPerCount(BH1750Full.MODE_H2), 0.5 / 1.2));
    checkTrue('measurement_time_h', close(s.measurementTime(), 0.180));
    checkTrue('measurement_time_l', close(s.measurementTime(BH1750Full.MODE_L), 0.024));
    await s.setSensitivity(2.0);
    checkTrue('sensitivity_mtreg', s.getMeasurementTimeRegister() === 138);
    checkTrue('sensitivity_lux_per_count', close(s.luxPerCount(), 1 / 1.2 / 2));
    checkTrue('sensitivity_time', close(s.measurementTime(), 0.360));
    queueWord(c, 0x0012);
    checkTrue('sensitivity_scales_read', close(await s.measureOnce(BH1750Full.MODE_H), 7.5));
    checkTrue('sensitivity_low_limit', await rejects(() => s.setSensitivity(0.4)));
    checkTrue('sensitivity_high_limit', await rejects(() => s.setSensitivity(3.7)));
    await s.setSensitivity(0.45);
    checkTrue('sensitivity_min', s.getMeasurementTimeRegister() === 31);
    await s.setSensitivity(3.68);
    checkTrue('sensitivity_max', s.getMeasurementTimeRegister() === 254);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
