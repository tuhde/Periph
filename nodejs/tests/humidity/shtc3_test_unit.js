'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { SHTC3Full, SHTC3Minimal, crc8 } = require('../../packages/periph/src/chips/humidity/shtc3');

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

function word(code) {
    const d = [code >> 8, code & 0xFF];
    return [...d, crc8(d)];
}

function queueTrh(c, t, rh, corrupt = 0) {
    const a = word(t), b = word(rh);
    if (corrupt === 1) a[2] ^= 0xFF;
    if (corrupt === 2) b[2] ^= 0xFF;
    c.queueRead(Buffer.from([...a, ...b]));
}
function queueId(c, id, bad = false) {
    const w = word(id);
    if (bad) w[2] ^= 0x55;
    c.queueRead(Buffer.from(w));
}

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new SHTC3Full(c) : new SHTC3Minimal(c)];
}

const cmds = (c) => c.writes.map((w) => (w[0] << 8) | w[1]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);

async function main() {
    checkTrue('crc8_BEEF', crc8([0xBE, 0xEF]) === 0x92);
    checkTrue('crc8_00', crc8([0x00]) === 0xAC);

    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    queueId(c, 0x0807);
    await s.init();
    checkTrue('init_sequence', sameCmds(c, [0x3517, 0x805D, 0xEFC8, 0xB098]));
    [c, s] = make();
    queueId(c, 0x0000);
    checkTrue('init_wrong_id', await rejects(() => s.init()));
    [c, s] = make();
    queueId(c, 0x0807, true);
    checkTrue('init_crc_mismatch', await rejects(() => s.init()));

    // read: measurement then sleep; the driver starts idle
    [c, s] = make();
    queueTrh(c, 0x6666, 0x6000);
    let m = await s.read();
    checkTrue('read_commands', sameCmds(c, [0x7866, 0xB098]));
    checkTrue('read_temperature', close(m.temperature_c, -45.0 + 175.0 * 0x6666 / 65536.0));
    checkTrue('read_humidity', close(m.humidity_pct, 37.5));
    c.writes.length = 0;
    queueTrh(c, 0x6666, 0x6000);
    await s.read();
    checkTrue('read_wakes_after_sleep', sameCmds(c, [0x3517, 0x7866, 0xB098]));
    queueTrh(c, 0xFFFF, 0xFFFF);
    m = await s.read();
    checkTrue('temperature_max', close(m.temperature_c, 130.0, 0.01));
    checkTrue('humidity_full_scale', m.humidity_pct <= 100.0 && m.humidity_pct > 99.9);
    queueTrh(c, 0, 0);
    m = await s.read();
    checkTrue('temperature_min', close(m.temperature_c, -45.0));
    checkTrue('humidity_zero', close(m.humidity_pct, 0.0));

    // CRC mismatch: retried once, then fails
    [c, s] = make();
    queueTrh(c, 0x6666, 0x6000, 1);
    queueTrh(c, 0x6666, 0x6000, 1);
    checkTrue('crc_mismatch_temperature', await rejects(() => s.read()));
    [c, s] = make();
    queueTrh(c, 0x6666, 0x6000, 2);
    queueTrh(c, 0x6666, 0x6000, 2);
    checkTrue('crc_mismatch_humidity', await rejects(() => s.read()));
    [c, s] = make();
    queueTrh(c, 0x6666, 0x6000, 1);
    queueTrh(c, 0x6666, 0x6000);
    m = await s.read();
    checkTrue('crc_glitch_recovered', close(m.humidity_pct, 37.5));
    checkTrue('crc_glitch_retried', cmds(c).filter((x) => x === 0x7866).length === 2);

    // Full: single values and raw
    [c, s] = make();
    queueTrh(c, 0x4000, 0x4000);
    checkTrue('read_humidity_single', close(await s.readHumidity(), 25.0));
    queueTrh(c, 0x4000, 0x4000);
    checkTrue('read_temperature_single', close(await s.readTemperature(), -45.0 + 175.0 * 0.25));
    queueTrh(c, 0x1234, 0x5678);
    checkTrue('read_raw', JSON.stringify(await s.readRaw()) === JSON.stringify([0x1234, 0x5678]));

    // mode selection
    [c, s] = make();
    s.setLowPower(true);
    queueTrh(c, 0x6666, 0x6000);
    await s.read();
    checkTrue('low_power_command', sameCmds(c, [0x609C, 0xB098]));
    c.writes.length = 0;
    s.setClockStretching(true);
    queueTrh(c, 0x6666, 0x6000);
    await s.read();
    checkTrue('low_power_stretching_command', sameCmds(c, [0x3517, 0x6458, 0xB098]));
    c.writes.length = 0;
    s.setLowPower(false);
    queueTrh(c, 0x6666, 0x6000);
    await s.read();
    checkTrue('stretching_command', sameCmds(c, [0x3517, 0x7CA2, 0xB098]));

    // sleep control
    [c, s] = make();
    s.setSleepBetween(false);
    queueTrh(c, 0x6666, 0x6000);
    queueTrh(c, 0x6666, 0x6000);
    await s.read();
    await s.read();
    checkTrue('no_sleep_between', sameCmds(c, [0x7866, 0x7866]));
    await s.sleep();
    checkTrue('manual_sleep', cmds(c).at(-1) === 0xB098);
    await s.wake();
    checkTrue('manual_wake', cmds(c).at(-1) === 0x3517);

    // soft reset and ID
    [c, s] = make();
    await s.softReset();
    checkTrue('soft_reset_command', sameCmds(c, [0x805D]));
    c.writes.length = 0;
    queueId(c, 0xAB07);
    checkTrue('read_id_value', (await s.readId()) === 0xAB07);
    checkTrue('read_id_command', sameCmds(c, [0xEFC8]));
    queueId(c, 0x0807, true);
    checkTrue('read_id_crc_mismatch', await rejects(() => s.readId()));
    await s.sleep();
    c.writes.length = 0;
    queueId(c, 0x0807);
    await s.readId();
    checkTrue('read_id_wakes_and_sleeps', sameCmds(c, [0x3517, 0xEFC8, 0xB098]));

    // dew point: Magnus, 25 degC / 50 %RH is about 13.9 degC
    [c, s] = make();
    queueTrh(c, Math.round((25.0 + 45.0) * 65536.0 / 175.0), 0x8000);
    checkTrue('dew_point', Math.abs((await s.dewPoint()) - 13.86) < 0.15);
    queueTrh(c, Math.round((25.0 + 45.0) * 65536.0 / 175.0), 0);
    checkTrue('dew_point_dry_finite', (await s.dewPoint()) < -20.0);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
