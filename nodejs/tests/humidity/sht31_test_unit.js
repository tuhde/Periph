'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { SHT31Full, SHT31Minimal, crc8 } = require('../../packages/periph/src/chips/humidity/sht31');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-6) => Math.abs(a - b) <= tol;

async function rejects(fn, type = Error) {
    try { await fn(); } catch (e) { return e instanceof type; }
    return false;
}

function frame(rawT, rawRh) {
    const t = [rawT >> 8, rawT & 0xFF];
    const h = [rawRh >> 8, rawRh & 0xFF];
    return Buffer.from([...t, crc8(t), ...h, crc8(h)]);
}

async function make(full = true) {
    const c = new I2CConnectionMock();
    const s = full ? new SHT31Full(c) : new SHT31Minimal(c);
    await s.init();
    return [c, s];
}

const cmds = (c) => c.writes.map((w) => (w[0] << 8) | w[1]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);

async function main() {
    checkTrue('crc8_beef', crc8([0xBE, 0xEF]) === 0x92);

    let [c, s] = await make();
    checkTrue('init_soft_reset', sameCmds(c, [0x30A2]));

    c.writes.length = 0;
    c.queueRead(frame(0x6666, 0x8000));
    let [t, rh] = await s.read();
    checkTrue('read_command', sameCmds(c, [0x2400]));
    checkTrue('read_temperature', close(t, -45.0 + 175.0 * 0x6666 / 65535.0));
    checkTrue('read_humidity', close(rh, 100.0 * 0x8000 / 65535.0));
    checkTrue('minimal_class', (await make(false))[1] instanceof SHT31Minimal);

    // NACKed read header: polls until ready
    [c, s] = await make();
    c.failNextReads(3);
    c.queueRead(frame(0, 65535));
    [t, rh] = await s.read();
    checkTrue('read_after_nacks', close(t, -45.0) && close(rh, 100.0));

    // CRC mismatch: one retry, then Error
    [c, s] = await make();
    c.writes.length = 0;
    const bad = Buffer.from(frame(0x1234, 0x5678)); bad[2] ^= 0xFF;
    c.queueRead(bad);
    c.queueRead(frame(0x1234, 0x5678));
    [t, rh] = await s.read();
    checkTrue('crc_retry_resends_command', sameCmds(c, [0x2400, 0x2400]));
    checkTrue('crc_retry_value', close(t, -45.0 + 175.0 * 0x1234 / 65535.0));
    c.queueRead(bad);
    c.queueRead(bad);
    checkTrue('crc_persistent_raises', await rejects(() => s.read()));

    // raw and single values
    [c, s] = await make();
    c.queueRead(frame(1000, 2000));
    const raw = await s.readRaw();
    checkTrue('read_raw', raw[0] === 1000 && raw[1] === 2000);
    c.queueRead(frame(0, 0));
    checkTrue('read_temperature_only', close(await s.readTemperature(), -45.0));
    c.queueRead(frame(0, 65535));
    checkTrue('read_humidity_only', close(await s.readHumidity(), 100.0));

    // repeatability and clock stretching
    [c, s] = await make();
    c.writes.length = 0;
    s.setRepeatability('low');
    c.queueRead(frame(0, 0)); await s.read();
    s.setRepeatability('medium');
    c.queueRead(frame(0, 0)); await s.read();
    s.setClockStretching(true);
    c.queueRead(frame(0, 0)); await s.read();
    s.setRepeatability('high');
    c.queueRead(frame(0, 0)); await s.read();
    checkTrue('repeatability_commands', sameCmds(c, [0x2416, 0x240B, 0x2C0D, 0x2C06]));
    let threw = false;
    try { s.setRepeatability('ultra'); } catch (e) { threw = e instanceof RangeError; }
    checkTrue('repeatability_invalid', threw);

    // periodic mode
    [c, s] = await make();
    c.writes.length = 0;
    await s.startPeriodic(2);
    await s.startPeriodic(0.5, 'low');
    await s.startPeriodic(10, 'medium');
    await s.startPeriodic(1, 'high', true);
    checkTrue('periodic_commands', sameCmds(c, [0x2236, 0x202F, 0x2721, 0x2B32]));
    checkTrue('periodic_bad_rate', await rejects(() => s.startPeriodic(3), RangeError));
    checkTrue('periodic_bad_repeatability', await rejects(() => s.startPeriodic(1, 'x'), RangeError));
    c.writes.length = 0;
    c.queueRead(frame(0x4000, 0x4000));
    const res = await s.fetch();
    checkTrue('fetch_command', sameCmds(c, [0xE000]));
    checkTrue('fetch_value', close(res[0], -45.0 + 175.0 * 0x4000 / 65535.0));
    c.failNextReads(1);
    checkTrue('fetch_null_when_nacked', (await s.fetch()) === null);
    c.writes.length = 0;
    await s.stopPeriodic();
    checkTrue('stop_periodic', sameCmds(c, [0x3093]));

    // heater, status, reset
    c.writes.length = 0;
    await s.setHeater(true);
    await s.setHeater(false);
    await s.clearStatus();
    checkTrue('heater_and_clear_commands', sameCmds(c, [0x306D, 0x3066, 0x3041]));
    c.queueRead(Buffer.from([0x80, 0x10, crc8([0x80, 0x10])]));
    checkTrue('read_status', (await s.readStatus()) === 0x8010 && cmds(c).pop() === 0xF32D);
    c.queueRead(Buffer.from([0x80, 0x10, 0x00]));
    checkTrue('read_status_crc', await rejects(() => s.readStatus()));
    c.writes.length = 0;
    await s.softReset();
    checkTrue('soft_reset', sameCmds(c, [0x30A2]));

    // dew point: 25 degC / 50 %RH -> 13.86 degC
    [c, s] = await make();
    c.queueRead(frame(Math.round((25.0 + 45.0) * 65535.0 / 175.0), Math.round(50.0 * 65535.0 / 100.0)));
    checkTrue('dew_point', close(await s.dewPoint(), 13.86, 0.05));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
