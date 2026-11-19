'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HS30xxFull, HS30xxMinimal, HS3001Full, HS3001Minimal, HS3002Full, HS3003Minimal } = require('../../packages/periph/src/chips/humidity/hs30xx');

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

function frame(rhRaw, tRaw, status = 0) {
    return Buffer.from([(status << 6) | (rhRaw >> 8), rhRaw & 0xFF, tRaw >> 6, (tRaw & 0x3F) << 2]);
}

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new HS30xxFull(c) : new HS30xxMinimal(c)];
}

const hex = (c) => c.writes.map((w) => w.toString('hex'));
const sameWrites = (c, expected) => JSON.stringify(hex(c)) === JSON.stringify(expected);

async function main() {
    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);

    checkTrue('alias_hs3001_full', HS3001Full === HS30xxFull);
    checkTrue('alias_hs3002_full', HS3002Full === HS30xxFull);
    checkTrue('alias_hs3003_minimal', HS3003Minimal === HS30xxMinimal);
    checkTrue('alias_hs3001_minimal', HS3001Minimal === HS30xxMinimal);

    // datasheet-style conversions: 0x2000 -> 50 %RH, 0x1000 -> 1.25 degC
    [c, s] = make(false);
    c.queueRead(frame(0x2000, 0x1000));
    let m = await s.read();
    checkTrue('read_request_is_empty_write', sameWrites(c, ['']));
    checkTrue('humidity_50', close(m.humidity_pct, 50.0, 0.01));
    checkTrue('temperature_1_25', close(m.temperature_c, 1.25, 0.01));
    checkTrue('humidity_exact', close(m.humidity_pct, 8192 / 16383 * 100.0));
    checkTrue('temperature_exact', close(m.temperature_c, 4096 / 16383 * 165.0 - 40.0));

    // full scale and zero
    [c, s] = make(false);
    c.queueRead(frame(0x3FFF, 0x3FFF));
    m = await s.read();
    checkTrue('humidity_full_scale', close(m.humidity_pct, 100.0));
    checkTrue('temperature_full_scale', close(m.temperature_c, 125.0));
    c.queueRead(frame(0, 0));
    m = await s.read();
    checkTrue('humidity_zero', close(m.humidity_pct, 0.0));
    checkTrue('temperature_zero', close(m.temperature_c, -40.0));

    // undetermined bits 1:0 of byte 3 are masked off
    [c, s] = make(false);
    c.queueRead(Buffer.from([0x20, 0x00, 0x10, 0x03]));
    checkTrue('masks_undetermined_bits', close((await s.read()).temperature_c, 0x400 / 16383 * 165.0 - 40.0));

    // stale then valid: one retry
    [c, s] = make(false);
    c.queueRead(frame(0x2000, 0x1000, 1));
    c.queueRead(frame(0x2000, 0x1000));
    checkTrue('stale_retry_ok', close((await s.read()).humidity_pct, 50.0, 0.01));
    checkTrue('stale_retry_single_request', sameWrites(c, ['']));

    // stale twice: rejects
    [c, s] = make(false);
    c.queueRead(frame(0, 0, 1));
    c.queueRead(frame(0, 0, 1));
    checkTrue('stale_twice_rejects', await rejects(() => s.read()));

    // Full: single values, raw, wait
    [c, s] = make();
    c.queueRead(frame(0x2000, 0x1000));
    checkTrue('read_temperature_full', close(await s.readTemperature(), 1.25, 0.01));
    c.queueRead(Buffer.from([0x20, 0x00]));
    checkTrue('read_humidity_full', close(await s.readHumidity(), 50.0, 0.01));
    c.queueRead(frame(0x1234, 0x0ABC));
    let raw = await s.readRaw();
    checkTrue('read_raw', raw.raw_humidity === 0x1234 && raw.raw_temperature === 0x0ABC && raw.status === 0);
    c.queueRead(frame(0x1234, 0x0ABC, 1));
    checkTrue('read_raw_status_stale', (await s.readRaw()).status === 1);
    checkTrue('set_measurement_wait_invalid', (() => { try { s.setMeasurementWait(0); } catch (e) { return true; } return false; })());
    s.setMeasurementWait(5);
    c.queueRead(frame(0x2000, 0x1000));
    checkTrue('read_after_wait_override', close(await s.readHumidity(), 50.0, 0.01));

    // programming mode commands
    [c, s] = make();
    await s.enterProgrammingMode();
    await s.exitProgrammingMode();
    checkTrue('programming_commands', sameWrites(c, ['a00000', '800000']));

    // readNvm
    [c, s] = make();
    c.queueRead(Buffer.from([0x81, 0x12, 0x34]));
    checkTrue('read_nvm_value', (await s.readNvm(0x1E)) === 0x1234);
    checkTrue('read_nvm_command', sameWrites(c, ['1e0000']));
    c.queueRead(Buffer.from([0x80, 0x12, 0x34]));
    checkTrue('read_nvm_bad_status', await rejects(() => s.readNvm(0x1E)));

    // NVM access without a powerCycle rejects before any bus traffic
    [c, s] = make();
    checkTrue('get_resolution_needs_power_cycle', await rejects(() => s.getResolution()));
    checkTrue('set_resolution_needs_power_cycle', await rejects(() => s.setResolution(12, 12)));
    checkTrue('read_id_needs_power_cycle', await rejects(() => s.readId()));
    checkTrue('no_bus_without_power_cycle', c.writes.length === 0);

    // getResolution: register bits 11:10 (default 0xC00 = 14 bit)
    [c, s] = make();
    let cycles = 0;
    c.queueRead(Buffer.from([0x81, 0x0C, 0x00]));
    c.queueRead(Buffer.from([0x81, 0x08, 0x05]));
    let r = await s.getResolution(() => { cycles++; });
    checkTrue('get_resolution', r.humidity_bits === 14 && r.temperature_bits === 12);
    checkTrue('get_resolution_power_cycled', cycles === 1);
    checkTrue('get_resolution_sequence', sameWrites(c, ['a00000', '060000', '110000', '800000']));

    // setResolution: read-modify-write preserves the other bits
    [c, s] = make();
    c.queueRead(Buffer.from([0x81, 0x0C, 0x07]));
    c.queueRead(Buffer.from([0x81, 0x0C, 0x21]));
    await s.setResolution(8, 12, () => {});
    checkTrue('set_resolution_sequence', sameWrites(c, ['a00000', '060000', '460007', '110000', '510821', '800000']));

    // setResolution: only one channel
    [c, s] = make();
    c.queueRead(Buffer.from([0x81, 0x00, 0x00]));
    await s.setResolution(null, 10, () => {});
    checkTrue('set_resolution_temperature_only', sameWrites(c, ['a00000', '110000', '510400', '800000']));

    // setResolution: invalid values reject before any bus traffic
    [c, s] = make();
    checkTrue('set_resolution_invalid', await rejects(() => s.setResolution(9, null, () => {})));
    checkTrue('set_resolution_invalid_no_bus', c.writes.length === 0);

    // a failed NVM read still leaves programming mode
    [c, s] = make();
    c.queueRead(Buffer.from([0x00, 0, 0]));
    checkTrue('read_failure_rejects', await rejects(() => s.getResolution(() => {})));
    checkTrue('read_failure_exits_programming', hex(c)[hex(c).length - 1] === '800000');

    // readId
    [c, s] = make();
    c.queueRead(Buffer.from([0x81, 0xDE, 0xAD]));
    c.queueRead(Buffer.from([0x81, 0xBE, 0xEF]));
    checkTrue('read_id', (await s.readId(() => {})) === 0xDEADBEEF);

    // dew point: 25 degC / 50 %RH -> about 13.86 degC
    [c, s] = make();
    c.queueRead(frame(Math.trunc(0.5 * 16383), Math.trunc((25.0 + 40.0) / 165.0 * 16383)));
    checkTrue('dew_point', close(await s.dewPoint(), 13.86, 0.1));
    c.queueRead(frame(0, 0x1000));
    checkTrue('dew_point_zero_humidity', close(await s.dewPoint(), -273.15));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
