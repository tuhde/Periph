'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MCP3422Minimal, MCP3422Full } = require('../../packages/periph/src/chips/adc_dac/mcp3422');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;

function frame(raw, bits, rdy = false, cfg = 0x00) {
    const n = bits === 18 ? 3 : 2;
    const bytes = [];
    for (let i = n - 1; i >= 0; i--) bytes.push(Math.floor(raw / (2 ** (8 * i))) & 0xFF);
    // two's complement for negative values
    if (raw < 0) {
        const span = 2 ** (8 * n);
        const v = span + raw;
        for (let i = 0; i < n; i++) bytes[i] = Math.floor(v / (2 ** (8 * (n - 1 - i)))) & 0xFF;
    }
    bytes.push(cfg | (rdy ? 0x80 : 0));
    return bytes;
}

async function fresh(...frames) {
    const conn = new I2CConnectionMock();
    for (const f of frames) conn.queueRead(f);
    const chip = new MCP3422Full(conn);
    await chip.init();
    return { conn, chip };
}

const written = (conn) => conn.writes.map((w) => w[0]);

async function main() {
    // -- init writes the default config
    {
        const { conn } = await fresh();
        checkTrue('init_writes_default_config', conn.writes.length === 1 && conn.writes[0].length === 1 && conn.writes[0][0] === 0x08);
    }
    // -- one-shot config bytes and channel clamping
    {
        const { conn, chip } = await fresh(frame(0, 16), frame(0, 16), frame(0, 16), frame(0, 16));
        await chip.readRaw(0);
        checkTrue('one_shot_config_ch1', written(conn).pop() === 0x88);
        await chip.readRaw(1);
        checkTrue('one_shot_config_ch2', written(conn).pop() === 0xA8);
        await chip.readRaw(7);
        checkTrue('channel_clamped_high', written(conn).pop() === 0xA8);
        await chip.readRaw(-3);
        checkTrue('channel_clamped_low', written(conn).pop() === 0x88);
    }
    // -- decoding and sign extension
    async function decodeCase(bits, raw) {
        const { chip } = await fresh(frame(raw, bits));
        await chip.setResolution(bits);
        return chip.readRaw(0);
    }
    checkTrue('decode_12_negative', (await decodeCase(12, -100)) === -100);
    checkTrue('decode_12_max', (await decodeCase(12, 2047)) === 2047);
    checkTrue('decode_12_min', (await decodeCase(12, -2048)) === -2048);
    checkTrue('decode_14_negative', (await decodeCase(14, -8192)) === -8192);
    checkTrue('decode_14_positive', (await decodeCase(14, 8191)) === 8191);
    checkTrue('decode_16_positive', (await decodeCase(16, 12345)) === 12345);
    checkTrue('decode_16_negative', (await decodeCase(16, -12345)) === -12345);
    checkTrue('decode_16_min', (await decodeCase(16, -32768)) === -32768);
    checkTrue('decode_18_positive', (await decodeCase(18, 131071)) === 131071);
    checkTrue('decode_18_negative', (await decodeCase(18, -131072)) === -131072);
    checkTrue('decode_18_small_negative', (await decodeCase(18, -1)) === -1);
    {
        const { chip } = await fresh([0xFC, 0x00, 0x05, 0x00]);   // byte0's upper six bits are not data
        await chip.setResolution(18);
        checkTrue('decode_18_masks_upper_bits', (await chip.readRaw(0)) === 5);
    }
    // -- voltage, lsb, full scale
    {
        const { chip } = await fresh(frame(16384, 16), frame(-32768, 16));
        checkTrue('voltage_x1', near(await chip.readVoltage(0), 16384 * 2 * 2.048 / 65536));
        await chip.setGain(8);
        checkTrue('voltage_full_scale_x8', near(await chip.readVoltage(0), -0.256));
        checkTrue('lsb_16', near(chip.lsb(), 62.5e-6));
        checkTrue('full_scale_x8', near(chip.fullScale(), 0.256));
        await chip.setResolution(12);
        checkTrue('lsb_12', near(chip.lsb(), 1e-3));
        await chip.setResolution(18);
        checkTrue('lsb_18', near(chip.lsb(), 15.625e-6));
    }
    // -- readAll
    {
        const { conn, chip } = await fresh(frame(100, 16), frame(200, 16));
        const vals = await chip.readAll();
        checkTrue('read_all_two_values', vals.length === 2 && near(vals[0], 100 * 2 * 2.048 / 65536) && near(vals[1], 200 * 2 * 2.048 / 65536));
        checkTrue('read_all_channel_order', JSON.stringify(written(conn).slice(1)) === JSON.stringify([0x88, 0xA8]));
    }
    // -- RDY polling and timeout
    {
        const { chip } = await fresh(frame(1, 16, true), frame(1, 16, true), frame(777, 16));
        checkTrue('polls_until_ready', (await chip.readRaw(0)) === 777);
        const stale = [];
        for (let i = 0; i < 40; i++) stale.push(frame(1, 16, true));
        const t = await fresh(...stale);
        let threw = false;
        try { await t.chip.readRaw(0); } catch (e) { threw = true; }
        checkTrue('timeout_raises', threw);
    }
    // -- Full configuration
    {
        const { conn, chip } = await fresh(frame(0, 18));
        let a = false, b = false;
        try { await chip.setResolution(10); } catch (e) { a = true; }
        try { await chip.setGain(3); } catch (e) { b = true; }
        checkTrue('bad_values_raise', a && b);
        await chip.setResolution(18);
        await chip.setGain(8);
        checkTrue('getters', chip.getResolution() === 18 && chip.getGain() === 8);
        await chip.readRaw(0);
        checkTrue('one_shot_config_18bit_x8', written(conn).pop() === 0x8F);
    }
    // -- continuous mode
    {
        const { conn, chip } = await fresh(frame(5, 12), frame(300, 12), frame(301, 12));
        await chip.setResolution(12);
        await chip.startContinuous();
        checkTrue('start_continuous_config', written(conn).pop() === 0x10 && chip.isContinuous());
        checkTrue('read_continuous_discards_first_sample', (await chip.readContinuousRaw()) === 300);
        checkTrue('read_continuous_next', (await chip.readContinuousRaw()) === 301);
        await chip.startContinuous(1);
        checkTrue('start_continuous_channel', written(conn).pop() === 0x30);
        await chip.stopContinuous();
        checkTrue('stop_continuous_config', written(conn).pop() === 0x20 && !chip.isContinuous());
        let threw = false;
        try { await chip.readContinuousRaw(); } catch (e) { threw = true; }
        checkTrue('read_continuous_requires_mode', threw);
    }
    {
        const { chip } = await fresh(frame(5, 16), frame(16384, 16));
        await chip.startContinuous();
        checkTrue('read_continuous_voltage', near(await chip.readContinuous(), 16384 * 2 * 2.048 / 65536));
    }
    {
        const { conn, chip } = await fresh(frame(1, 12), frame(2, 12), frame(3, 12), frame(4, 12));
        await chip.setResolution(12);
        await chip.startContinuous();
        await chip.readContinuousRaw();
        const n = conn.writes.length;
        await chip.setGain(2);
        checkTrue('gain_change_rewrites_config', conn.writes.length === n + 1 && written(conn).pop() === 0x11);
        checkTrue('gain_change_discards_sample', (await chip.readContinuousRaw()) === 4);
    }
    // -- readConfig / isReady
    {
        const { chip } = await fresh(frame(0, 16, true, 0x08), frame(0, 16, false, 0x08));
        checkTrue('read_config', (await chip.readConfig()) === 0x88);
        checkTrue('is_ready', (await chip.isReady()) === true);
    }
    // -- readScan
    {
        const { conn, chip } = await fresh(frame(10, 16), frame(20, 16), frame(30, 16));
        const vals = await chip.readScan([1, 0, 1]);
        checkTrue('read_scan_order', JSON.stringify(written(conn).slice(1)) === JSON.stringify([0xA8, 0x88, 0xA8]) && vals.length === 3);
    }
    // -- general calls
    {
        const { conn, chip } = await fresh();
        await chip.setResolution(18);
        await chip.setGain(8);
        await chip.generalCallReset();
        const w = conn.writes[conn.writes.length - 1];
        checkTrue('general_call_reset_bytes', w.length === 2 && w[0] === 0x00 && w[1] === 0x06);
        checkTrue('general_call_reset_state', chip.getResolution() === 12 && chip.getGain() === 1 && chip.isContinuous());
        const g = await fresh(frame(321, 16));
        await g.chip.generalCallConvert();
        const w2 = g.conn.writes[g.conn.writes.length - 1];
        checkTrue('general_call_convert_bytes', w2.length === 2 && w2[1] === 0x08);
        checkTrue('read_pending_raw', (await g.chip.readPendingRaw()) === 321);
    }
    // -- minimal class has no Full-only methods
    checkTrue('minimal_has_no_continuous', typeof new MCP3422Minimal(new I2CConnectionMock()).startContinuous === 'undefined');

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
