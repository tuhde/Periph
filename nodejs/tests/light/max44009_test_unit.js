'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MAX44009Minimal, MAX44009Full } = require('../../packages/periph/src/chips/light/max44009');

MAX44009Minimal.initWaitMs = 0;   // skip the 900 ms first-conversion wait

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;
const dataWrites = (conn) => conn.writes.filter((w) => w.length > 1);
const reg = (conn, r) => conn.registers.get(r);

async function fresh(high = 0, low = 0) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x02, [0x03]);
    conn.setRegister(0x03, [high]);
    conn.setRegister(0x04, [low]);
    const chip = new MAX44009Full(conn);
    await chip.init();
    return { conn, chip };
}

async function main() {
    // -- init sequence
    {
        const { conn } = await fresh();
        const seq = dataWrites(conn).map((w) => [w[0], w[1]]);
        checkTrue('init_sequence', JSON.stringify(seq) === JSON.stringify([[1, 0], [2, 3], [5, 255], [6, 0], [7, 255]]));
        checkTrue('init_reads_int_status', conn.writes.some((w) => w.length === 1 && w[0] === 0x00));
    }
    // -- lux conversion
    {
        const { conn, chip } = await fresh();
        checkTrue('lux_zero', (await chip.lux()) === 0);
        conn.setRegister(0x03, [0x2A]); conn.setRegister(0x04, [0x05]);
        checkTrue('lux_exponent_mantissa', near(await chip.lux(), 4 * 0xA5 * 0.045));
        conn.setRegister(0x03, [0xEF]); conn.setRegister(0x04, [0x0F]);
        checkTrue('lux_max_valid', near(await chip.lux(), 188006.4));
        conn.setRegister(0x03, [0xF3]);
        checkTrue('lux_overrange_returns_max', near(await chip.lux(), 188006.4));
        conn.setRegister(0x03, [0x01]); conn.setRegister(0x04, [0xF7]);
        checkTrue('lux_low_nibble_only', near(await chip.lux(), 0x17 * 0.045));
    }
    // -- raw / overrange / resolution
    {
        const { conn, chip } = await fresh(0x5B, 0x0C);
        const raw = await chip.readRaw();
        checkTrue('read_raw', raw.exponent === 5 && raw.mantissa === 0xBC && raw.overrange === false);
        checkTrue('resolution', near(chip.resolution(), 32 * 0.045));
        checkTrue('is_overrange_false', chip.isOverrange() === false);
        conn.setRegister(0x03, [0xF0]);
        const over = await chip.readRaw();
        checkTrue('read_raw_overrange', over.overrange === true && chip.isOverrange() === true);
    }
    // -- consistent read repeats once when the high byte changes
    {
        const conn = new I2CConnectionMock();
        conn.setRegister(0x02, [0x03]); conn.setRegister(0x03, [0x10]); conn.setRegister(0x04, [0x01]);
        const chip = new MAX44009Minimal(conn);
        await chip.init();
        let highReads = 0;
        const orig = conn.writeRead.bind(conn);
        conn.writeRead = async (data, n) => {
            if (data[0] === 0x03) {
                highReads++;
                if (highReads === 2) { conn.setRegister(0x03, [0x20]); conn.setRegister(0x04, [0x02]); }
            }
            return orig(data, n);
        };
        const value = await chip.lux();
        checkTrue('lux_repeats_on_high_byte_change', near(value, 4 * 0x02 * 0.045) && highReads === 2);
    }
    // -- configuration
    {
        const { conn, chip } = await fresh();
        await chip.setContinuous(true);
        checkTrue('set_continuous', reg(conn, 2) === 0x83);
        await chip.setContinuous(false);
        checkTrue('set_continuous_off', reg(conn, 2) === 0x03);
        await chip.setManual(50, false);
        checkTrue('set_manual_50ms', reg(conn, 2) === 0x44);
        await chip.setManual(12, true);
        checkTrue('set_manual_nearest_12_5ms_divide', reg(conn, 2) === (0x40 | 0x08 | 0x06));
        await chip.setContinuous(true);
        await chip.setManual(1000, false);
        checkTrue('set_manual_keeps_continuous', reg(conn, 2) === 0xC0);
        await chip.setAutomatic();
        checkTrue('set_automatic', (reg(conn, 2) & 0x40) === 0 && (reg(conn, 2) & 0x80) !== 0);
        conn.setRegister(0x02, [0xCD]);
        const cfg = await chip.getConfig();
        checkTrue('get_config', cfg.continuous && cfg.manual && cfg.divideBy8 && cfg.integrationMs === 25.0);
    }
    // -- thresholds
    {
        const { conn, chip } = await fresh();
        await chip.setThresholds(0.0, 188006.4);
        checkTrue('threshold_extremes', reg(conn, 6) === 0x00 && reg(conn, 5) === 0xEF);
        await chip.setThresholds(5.0, 100.0);
        checkTrue('threshold_low_exponent0', reg(conn, 6) === 0x07);
        checkTrue('threshold_high_format', (reg(conn, 5) >> 4) === 4 && (reg(conn, 5) & 0x08) !== 0);
        const { lowLux, highLux } = await chip.getThresholds();
        checkTrue('threshold_roundtrip', Math.abs(lowLux - 5.0) < 0.5 && Math.abs(highLux - 100.0) / 100.0 < 0.13);
        let threw = false;
        try { await chip.setThresholds(10.0, 5.0); } catch (e) { threw = true; }
        checkTrue('threshold_order_checked', threw);
    }
    // -- threshold delay
    {
        const { conn, chip } = await fresh();
        await chip.setThresholdDelay(2.0);
        checkTrue('delay_encoding', reg(conn, 7) === 20 && near(await chip.getThresholdDelay(), 2.0));
        await chip.setThresholdDelay(99);
        checkTrue('delay_clamps_high', reg(conn, 7) === 255);
        await chip.setThresholdDelay(-1);
        checkTrue('delay_clamps_low', reg(conn, 7) === 0);
    }
    // -- interrupt
    {
        const { conn, chip } = await fresh();
        await chip.enableInterrupt();
        checkTrue('enable_interrupt', reg(conn, 1) === 0x01);
        await chip.disableInterrupt();
        checkTrue('disable_interrupt', reg(conn, 1) === 0x00);
        conn.setRegister(0x00, [0x01]);
        checkTrue('poll_interrupt', (await chip.pollInterrupt()) === 1);
        conn.setRegister(0x00, [0x00]);
        checkTrue('poll_interrupt_clear', (await chip.pollInterrupt()) === 0);

        const seen = [];
        chip._callback = (s) => seen.push(s);
        conn.setRegister(0x00, [0x01]);
        await chip._service();
        conn.setRegister(0x00, [0x00]);
        await chip._service();
        checkTrue('callback_receives_status', seen.length === 1 && seen[0] === 1);
        await chip.offInterrupt();
        checkTrue('off_interrupt_clears_callback', chip._callback === null);
    }
    // -- minimal class has no Full-only methods
    checkTrue('minimal_has_no_thresholds', typeof new MAX44009Minimal(new I2CConnectionMock()).setThresholds === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
