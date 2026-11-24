'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ISL29125Minimal, ISL29125Full } = require('../../packages/periph/src/chips/color/isl29125');

ISL29125Minimal.pollStepMs = 0;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const dataWrites = (conn) => conn.writes.filter((w) => w.length > 1).map((w) => Array.from(w));
const reg = (conn, r) => conn.registers.get(r);

async function fresh(id = 0x7D) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x00, [id]);
    conn.setRegister(0x01, [0x00]);
    conn.setRegister(0x02, [0x00]);
    conn.setRegister(0x03, [0x00]);
    const chip = new ISL29125Full(conn);
    await chip.init();
    conn.setRegister(0x08, [0x02]);
    return { conn, chip };
}

async function throws(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}

async function main() {
    {
        const { conn } = await fresh();
        checkTrue('init_sequence', JSON.stringify(dataWrites(conn)) === JSON.stringify([[0x08, 0x00], [0x03, 0x00], [0x02, 0xBF], [0x01, 0x0D]]));
        checkTrue('init_rejects_wrong_id', await throws(() => fresh(0x00)));
    }
    {
        const { conn, chip } = await fresh();
        conn.setRegister(0x09, [0x34, 0x12, 0xFF, 0xFF, 0x01, 0x00]);
        const c = await chip.read();
        checkTrue('read_green', c.green === 0x1234);
        checkTrue('read_red', c.red === 0xFFFF);
        checkTrue('read_blue', c.blue === 1);
        const d = await chip.readRgb();
        checkTrue('read_rgb', d.red === 0xFFFF && d.green === 0x1234 && d.blue === 1);
        checkTrue('read_single', (await chip.readRed()) === 0xFFFF && (await chip.readGreen()) === 0x1234 && (await chip.readBlue()) === 1);
        checkTrue('device_id', (await chip.deviceId()) === 0x7D);
    }
    {
        const { conn, chip } = await fresh();
        conn.setRegister(0x01, [0x0D]);
        const modes = ['power_down', 'green', 'red', 'blue', 'standby', 'rgb', 'green_red', 'green_blue'];
        for (let i = 0; i < modes.length; i++) {
            await chip.setMode(modes[i]);
            checkTrue('mode_' + modes[i], reg(conn, 0x01) === (0x08 | i));
        }
        await chip.setRange(375);
        checkTrue('range_375', reg(conn, 0x01) === 0x07);
        await chip.setRange(10000);
        checkTrue('range_10000', reg(conn, 0x01) === 0x0F);
        await chip.setResolution(12);
        checkTrue('resolution_12', reg(conn, 0x01) === 0x1F);
        await chip.setResolution(16);
        checkTrue('resolution_16', reg(conn, 0x01) === 0x0F);
        await chip.setSync(true);
        checkTrue('sync_external', reg(conn, 0x01) === 0x2F);
        await chip.setSync(false);
        checkTrue('sync_i2c', reg(conn, 0x01) === 0x0F);
        await chip.setIrCompensation(false, 0x3F);
        checkTrue('ir_comp_low', reg(conn, 0x02) === 0x3F);
        await chip.setIrCompensation(true, 0x3F);
        checkTrue('ir_comp_high', reg(conn, 0x02) === 0xBF);
        for (const [name, fn] of [
            ['mode', () => chip.setMode('x')], ['range', () => chip.setRange(100)],
            ['resolution', () => chip.setResolution(8)], ['ir', () => chip.setIrCompensation(false, 64)],
            ['persistence', () => chip.setPersistence(3)], ['channel', () => chip.setInterruptChannel('ir')],
            ['thresholds', () => chip.setThresholds(5, 4)], ['thresholds_range', () => chip.setThresholds(0, 0x10000)],
        ]) {
            checkTrue('invalid_' + name, await throws(fn));
        }
    }
    {
        const { conn, chip } = await fresh();
        conn.writes.length = 0;
        await chip.softReset();
        checkTrue('soft_reset', JSON.stringify(dataWrites(conn)) === JSON.stringify([[0x00, 0x46]]));
    }
    {
        const { conn, chip } = await fresh();
        await chip.setThresholds(0x1234, 0xABCD);
        checkTrue('threshold_low_bytes', [reg(conn, 0x04), reg(conn, 0x05)].join() === [0x34, 0x12].join());
        checkTrue('threshold_high_bytes', [reg(conn, 0x06), reg(conn, 0x07)].join() === [0xCD, 0xAB].join());
        const t = await chip.getThresholds();
        checkTrue('thresholds_roundtrip', t.low === 0x1234 && t.high === 0xABCD);
    }
    {
        const { conn, chip } = await fresh();
        await chip.setInterruptChannel('red');
        checkTrue('channel_red', reg(conn, 0x03) === 0x02);
        await chip.setInterruptChannel('blue');
        checkTrue('channel_blue', reg(conn, 0x03) === 0x03);
        await chip.setInterruptChannel('none');
        await chip.setPersistence(8);
        checkTrue('persistence_8', reg(conn, 0x03) === 0x0C);
        await chip.setPersistence(4);
        await chip.setInterruptChannel('green');
        checkTrue('persistence_keeps_channel', reg(conn, 0x03) === 0x09);
        await chip.enableInterrupt(ISL29125Full.SOURCE_CONVERSION);
        checkTrue('enable_conversion', reg(conn, 0x03) === 0x19);
        await chip.disableInterrupt(ISL29125Full.SOURCE_CONVERSION);
        checkTrue('disable_conversion', reg(conn, 0x03) === 0x09);
        await chip.disableInterrupt();
        checkTrue('disable_threshold', reg(conn, 0x03) === 0x08);
        await chip.enableInterrupt();
        checkTrue('enable_threshold_defaults_green', reg(conn, 0x03) === 0x09);
        await chip.setInterruptChannel('red');
        await chip.enableInterrupt(ISL29125Full.SOURCE_THRESHOLD);
        checkTrue('enable_threshold_keeps_channel', reg(conn, 0x03) === 0x0A);
    }
    {
        const { conn, chip } = await fresh();
        conn.setRegister(0x08, [0x03]);
        checkTrue('data_ready_true', (await chip.dataReady()) === true);
        conn.setRegister(0x08, [0x00]);
        checkTrue('data_ready_false', (await chip.dataReady()) === false);
        checkTrue('flags_kept_after_data_ready', (await chip.pollInterrupt()) === 0x03);
        checkTrue('poll_clears_latch', (await chip.pollInterrupt()) === 0);
        conn.setRegister(0x08, [0x04]);
        checkTrue('status_brownout', (await chip.status()) === 0x04);
    }
    {
        const { conn, chip } = await fresh();
        const seen = [];
        chip._callback = (s) => seen.push(s);
        conn.setRegister(0x08, [0x01]);
        await chip._service();
        conn.setRegister(0x08, [0x00]);
        await chip._service();
        checkTrue('callback_receives_status', seen.length === 1 && seen[0] === 0x01);
        await chip.offInterrupt();
        checkTrue('off_interrupt_clears_callback', chip._callback === null);
    }
    checkTrue('minimal_has_no_range', typeof new ISL29125Minimal(new I2CConnectionMock()).setRange === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
