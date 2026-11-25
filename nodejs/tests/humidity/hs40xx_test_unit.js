'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HS40xxFull, HS40xxMinimal, HS4001Full, HS4001Minimal, HS4003Full, HS4003Minimal } = require('../../packages/periph/src/chips/humidity/hs40xx');

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

function crc8(bytes) {
    let crc = 0xFF;
    for (const b of bytes) {
        crc ^= b;
        for (let i = 0; i < 8; i++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x1D) & 0xFF : (crc << 1) & 0xFF;
    }
    return crc;
}

function ht(h, t) {
    const d = [h >> 8, h & 0xFF, t >> 8, t & 0xFF];
    return Buffer.from([...d, crc8(d)]);
}

function tOnly(t) {
    const d = [t >> 8, t & 0xFF];
    return Buffer.from([...d, crc8([0, 0, ...d])]);
}

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new HS40xxFull(c) : new HS40xxMinimal(c)];
}

const hex = (c) => c.writes.map((w) => w.toString('hex'));
const sameWrites = (c, expected) => JSON.stringify(hex(c)) === JSON.stringify(expected);

async function main() {
    checkTrue('crc_datasheet_example', crc8([0x05, 0x80, 0x05, 0x80]) === 0xF2);

    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    checkTrue('alias_hs4001_full', HS4001Full === HS40xxFull);
    checkTrue('alias_hs4003_full', HS4003Full === HS40xxFull);
    checkTrue('alias_hs4001_minimal', HS4001Minimal === HS40xxMinimal);
    checkTrue('alias_hs4003_minimal', HS4003Minimal === HS40xxMinimal);

    // datasheet example frame
    [c, s] = make(false);
    c.queueRead(Buffer.from([0x05, 0x80, 0x05, 0x80, 0xF2]));
    let m = await s.read();
    checkTrue('read_command', sameWrites(c, ['f5']));
    checkTrue('example_humidity', close(m.humidity_pct, 8.59, 0.01));
    checkTrue('example_temperature', close(m.temperature_c, -25.8, 0.05));

    // full scale and zero
    [c, s] = make(false);
    c.queueRead(ht(0x3FFF, 0x3FFF));
    m = await s.read();
    checkTrue('humidity_full_scale', close(m.humidity_pct, 100.0));
    checkTrue('temperature_full_scale', close(m.temperature_c, 125.0));
    c.queueRead(ht(0, 0));
    m = await s.read();
    checkTrue('humidity_zero', close(m.humidity_pct, 0.0));
    checkTrue('temperature_zero', close(m.temperature_c, -40.0));

    // NACKed read header is retried; a persistent NACK rejects
    [c, s] = make(false);
    c.failNextReads(2);
    c.queueRead(ht(0x2000, 0x1000));
    checkTrue('nack_retry', close((await s.read()).humidity_pct, 50.0, 0.01));
    c.failNextReads(100000);
    checkTrue('nack_timeout_rejects', await rejects(() => s.read()));

    // CRC mismatch: retried once, then rejects
    [c, s] = make(false);
    const bad = Buffer.from(ht(0x2000, 0x1000));
    bad[4] ^= 0xFF;
    c.queueRead(bad);
    c.queueRead(ht(0x2000, 0x1000));
    checkTrue('crc_retry_ok', close((await s.read()).humidity_pct, 50.0, 0.01));
    checkTrue('crc_retry_reissues_command', sameWrites(c, ['f5', 'f5']));
    [c, s] = make(false);
    c.queueRead(bad);
    c.queueRead(bad);
    checkTrue('crc_twice_rejects', await rejects(() => s.read()));

    // Full: single values
    [c, s] = make();
    c.queueRead(tOnly(0x1000));
    checkTrue('read_temperature', close(await s.readTemperature(), 4096 / 16383 * 165.0 - 40.0));
    checkTrue('read_temperature_command', sameWrites(c, ['f3']));
    c.queueRead(ht(0x2000, 0x1000));
    checkTrue('read_humidity', close(await s.readHumidity(), 50.0, 0.01));
    c.queueRead(ht(0x1234, 0x0ABC));
    const raw = await s.readRaw();
    checkTrue('read_raw', raw.raw_humidity === 0x1234 && raw.raw_temperature === 0x0ABC);
    [c, s] = make();
    c.queueRead(ht(0x2000, 0x1000));
    checkTrue('read_hold', close((await s.readHold()).humidity_pct, 50.0, 0.01));
    checkTrue('read_hold_command', sameWrites(c, ['e5']));

    // registers
    [c, s] = make();
    c.queueRead(Buffer.from([0x5A]));
    checkTrue('read_register', (await s.readRegister(0x02)) === 0x5A);
    checkTrue('read_register_command', sameWrites(c, ['a702']));
    [c, s] = make();
    await s.writeRegister(0x03, 0x0A);
    checkTrue('write_register_command', sameWrites(c, ['a6030a']));

    // resolution
    [c, s] = make();
    c.queueRead(Buffer.from([0xF3]));
    await s.setResolution(8, 12);
    checkTrue('set_resolution_sequence', sameWrites(c, ['a700', 'a600f2']));
    [c, s] = make();
    c.queueRead(Buffer.from([0xFF]));
    await s.setResolution(null, 10);
    checkTrue('set_resolution_temperature_only', hex(c)[1] === 'a600fd');
    [c, s] = make();
    checkTrue('set_resolution_invalid', await rejects(() => s.setResolution(9, null)));
    checkTrue('set_resolution_invalid_no_bus', c.writes.length === 0);
    [c, s] = make();
    c.queueRead(Buffer.from([0x0B]));
    const r = await s.getResolution();
    checkTrue('get_resolution', r.humidity_bits === 12 && r.temperature_bits === 14);

    // periodic
    [c, s] = make();
    c.queueRead(Buffer.from([0x00]));
    c.queueRead(Buffer.from([0x01]));
    await s.startPeriodic(2.5);
    checkTrue('start_periodic_sequence', sameWrites(c, ['a703', 'a702', 'a602a1']));
    checkTrue('start_periodic_invalid', await rejects(() => s.startPeriodic(3)));
    c.queueRead(ht(0x2000, 0x1000));
    let p = await s.readPeriodic();
    checkTrue('read_periodic_no_alert', p.status === 0 && close(p.humidity_pct, 50.0, 0.01));
    await s.stopPeriodic();
    checkTrue('stop_periodic', hex(c)[hex(c).length - 1] === '30');

    // periodic with alerts: 6-byte frame with status
    [c, s] = make();
    c.queueRead(Buffer.from([0x00]));
    await s.enableInterrupt(HS40xxFull.SOURCE_RH_HIGH);
    checkTrue('enable_interrupt', sameWrites(c, ['a703', 'a60302']));
    c.queueRead(Buffer.concat([Buffer.from([0x0A]), ht(0x2000, 0x1000)]));
    p = await s.readPeriodic();
    checkTrue('read_periodic_status', p.status === 0x0A);
    c.queueRead(Buffer.concat([Buffer.from([0x02]), ht(0x2000, 0x1000)]));
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 0x02);
    c.queueRead(Buffer.from([0x02]));
    await s.disableInterrupt(HS40xxFull.SOURCE_RH_HIGH);
    checkTrue('disable_interrupt', hex(c)[hex(c).length - 1] === 'a60300');
    checkTrue('poll_interrupt_needs_alerts', await rejects(() => s.pollInterrupt()));

    // thresholds: 60 %RH -> 0x2666, reset 55 %RH -> 0x2333
    [c, s] = make();
    await s.setAlertThresholds({ rhHigh: 60.0, rhHighReset: 55.0 });
    checkTrue('rh_thresholds', sameWrites(c, ['a60f66', 'a61026', 'a60d33', 'a60e23']));
    [c, s] = make();
    await s.setAlertThresholds({ tempHigh: 125.0, tempLow: -40.0 });
    checkTrue('temp_thresholds', sameWrites(c, ['a607ff', 'a6083f', 'a60900', 'a60a00']));

    // polarity
    [c, s] = make();
    c.queueRead(Buffer.from([0xB1]));
    await s.setAlertPolarity(false);
    checkTrue('set_alert_polarity', hex(c)[hex(c).length - 1] === 'a602b0');

    // ID and reset
    [c, s] = make();
    c.queueRead(Buffer.from([0xDE, 0xAD, 0xBE, 0xEF]));
    checkTrue('read_id', (await s.readId()) === 0xDEADBEEF);
    checkTrue('read_id_command', sameWrites(c, ['d7']));
    [c, s] = make();
    await s.softReset();
    checkTrue('soft_reset', sameWrites(c, ['30', 'fe']));

    // dew point
    [c, s] = make();
    c.queueRead(ht(Math.trunc(0.5 * 16383), Math.trunc((25.0 + 40.0) / 165.0 * 16383)));
    checkTrue('dew_point', close(await s.dewPoint(), 13.86, 0.1));
    c.queueRead(ht(0, 0x1000));
    checkTrue('dew_point_zero_humidity', close(await s.dewPoint(), -273.15));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
