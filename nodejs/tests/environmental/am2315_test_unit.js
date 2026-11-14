'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { AM2315Full, AM2315Minimal, AM2315Error, crc16 } = require('../../packages/periph/src/chips/environmental/am2315');

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

function resp(fn, ...payload) {
    const body = [fn, ...payload];
    const crc = crc16(body);
    return Buffer.from([...body, crc & 0xFF, crc >> 8]);
}
const readResp = (data) => resp(0x03, data.length, ...data);

function make(full = true) {
    const c = new I2CConnectionMock();
    const s = full ? new AM2315Full(c) : new AM2315Minimal(c);
    s._minIntervalMs = 0;     // tests do not wait the 2 s between transactions
    return [c, s];
}

const eq = (a, b) => Buffer.from(a).equals(Buffer.from(b));
const last = (c) => c.writes[c.writes.length - 1];

async function main() {
    checkTrue('crc16_example1', crc16([0x03, 0x04, 0x01, 0xF4, 0x00, 0xFA]) === 0xA531);
    checkTrue('crc16_example2', crc16([0x03, 0x04, 0x03, 0x39, 0x01, 0x15]) === 0xFEE1);
    checkTrue('crc16_write', crc16([0x10, 0x10, 0x02, 0x01, 0x02]) === 0x92C0);

    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);

    c.queueRead(readResp([0x01, 0xF4, 0x00, 0xFA]));
    let r = await s.read();
    checkTrue('read_writes', c.writes.length === 2 && eq(c.writes[0], [0x00]) && eq(c.writes[1], [0x03, 0x00, 0x04]));
    checkTrue('read_humidity', close(r.humidity_pct, 50.0));
    checkTrue('read_temperature', close(r.temperature_c, 25.0));

    [c, s] = make();
    c.queueRead([0x03, 0x04, 0x03, 0x39, 0x01, 0x15, 0xE1, 0xFE]);
    r = await s.read();
    checkTrue('datasheet_humidity', close(r.humidity_pct, 82.5));
    checkTrue('datasheet_temperature', close(r.temperature_c, 27.7));

    [c, s] = make();
    c.queueRead(readResp([0x01, 0xF4, 0x80, 0x65]));
    checkTrue('negative_temperature', close((await s.read()).temperature_c, -10.1));

    [c, s] = make();
    c.failNextWrites(1);
    c.queueRead(readResp([0x01, 0xF4, 0x00, 0xFA]));
    checkTrue('wake_nack_ignored', close((await s.read()).humidity_pct, 50.0));

    const bad = Buffer.from(readResp([0x01, 0xF4, 0x00, 0xFA]));
    bad[bad.length - 1] ^= 0xFF;
    [c, s] = make();
    c.queueRead(bad);
    c.queueRead(readResp([0x01, 0xF4, 0x00, 0xFA]));
    checkTrue('crc_retry_recovers', close((await s.read()).humidity_pct, 50.0));
    checkTrue('crc_retry_wakes_again', c.writes.filter((w) => eq(w, [0x00])).length === 2);
    [c, s] = make();
    c.queueRead(bad);
    c.queueRead(bad);
    checkTrue('crc_mismatch_rejects', await rejects(() => s.read()));

    [c, s] = make();
    c.failNextReads(1);
    c.queueRead(readResp([0x01, 0xF4, 0x00, 0xFA]));
    checkTrue('bus_retry_recovers', close((await s.read()).temperature_c, 25.0));
    [c, s] = make();
    c.failNextReads(2);
    checkTrue('bus_error_rejects', await rejects(() => s.read()));

    [c, s] = make();
    c.queueRead([0x83, 0x00, 0x00, 0x00]);
    try {
        await s.read();
        checkTrue('error_response_rejects', false);
    } catch (e) {
        checkTrue('error_response_rejects', e instanceof AM2315Error);
        checkTrue('error_response_code', e.code === 0x83);
    }

    // minimum interval enforced
    const c2 = new I2CConnectionMock();
    const s2 = new AM2315Minimal(c2);
    s2._minIntervalMs = 50;
    c2.queueRead(readResp([0x01, 0xF4, 0x00, 0xFA]));
    const t0 = Date.now();
    await s2.read();
    checkTrue('interval_enforced', Date.now() - t0 >= 40);

    [c, s] = make();
    c.queueRead(readResp([0x00, 0x7B]));
    checkTrue('read_humidity_only', close(await s.readHumidity(), 12.3));
    checkTrue('read_humidity_cmd', eq(last(c), [0x03, 0x00, 0x02]));
    c.queueRead(readResp([0x80, 0x65]));
    checkTrue('read_temperature_only', close(await s.readTemperature(), -10.1));
    checkTrue('read_temperature_cmd', eq(last(c), [0x03, 0x02, 0x02]));
    c.queueRead(readResp([0x01, 0xF4, 0x80, 0x65]));
    checkTrue('read_raw', JSON.stringify(await s.readRaw()) === JSON.stringify([0x01F4, 0x8065]));

    [c, s] = make();
    c.queueRead(readResp([0x01, 0xF4, 0x00, 0xFA]));
    c.queueRead(readResp([0x01, 0xF9, 0x01, 0x00]));
    r = await s.readFresh();
    checkTrue('read_fresh_second', close(r.humidity_pct, 50.5) && close(r.temperature_c, 25.6));

    [c, s] = make();
    c.queueRead(readResp([1, 2, 3]));
    checkTrue('read_registers', eq(await s.readRegisters(0x08, 3), [1, 2, 3]));
    checkTrue('read_registers_count_0', await rejects(() => s.readRegisters(0, 0), RangeError));
    checkTrue('read_registers_count_11', await rejects(() => s.readRegisters(0, 11), RangeError));

    [c, s] = make();
    c.queueRead(readResp([0x09, 0x0F, 0x10, 0x12, 0x34, 0x56, 0x78]));
    const info = await s.readDeviceInfo();
    checkTrue('device_info_cmd', eq(last(c), [0x03, 0x08, 0x07]));
    checkTrue('device_info', info.model === 0x090F && info.version === 0x10 && info.device_id === 0x12345678);

    [c, s] = make();
    c.queueRead(readResp([0x12, 0x34, 0xAB, 0xCD]));
    checkTrue('read_user_registers', JSON.stringify(await s.readUserRegisters()) === JSON.stringify([0x1234, 0xABCD]));
    checkTrue('read_user_registers_cmd', eq(last(c), [0x03, 0x10, 0x04]));

    [c, s] = make();
    c.queueRead(resp(0x10, 0x10, 0x04));
    await s.writeUserRegisters(0x1234, 0xABCD);
    const body = [0x10, 0x10, 0x04, 0x12, 0x34, 0xAB, 0xCD];
    const crc = crc16(body);
    checkTrue('write_user_registers_frame', eq(last(c), [...body, crc & 0xFF, crc >> 8]));

    [c, s] = make();
    c.queueRead(resp(0x10, 0x10, 0x02));
    await s.writeRegisters(0x10, [1, 2]);
    checkTrue('write_registers_frame', eq(last(c), [0x10, 0x10, 0x02, 1, 2, 0xC0, 0x92]));
    checkTrue('write_registers_reject_low', await rejects(() => s.writeRegisters(0x0F, [1]), RangeError));
    checkTrue('write_registers_reject_high', await rejects(() => s.writeRegisters(0x13, [1, 2]), RangeError));
    checkTrue('write_registers_reject_empty', await rejects(() => s.writeRegisters(0x10, []), RangeError));

    [c, s] = make();
    c.queueRead([0x82, 0x00, 0x00, 0x00, 0x00]);
    checkTrue('write_error_response', await rejects(() => s.writeRegisters(0x10, [1]), AM2315Error));

    [c, s] = make();
    await s.wake();
    checkTrue('wake_write', c.writes.length === 1 && eq(c.writes[0], [0x00]));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
