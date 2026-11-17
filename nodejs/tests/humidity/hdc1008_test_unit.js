'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HDC1008Full, HDC1008Minimal } = require('../../packages/periph/src/chips/humidity/hdc1008');

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

const make = (full = true) => {
    const c = new I2CConnectionMock();
    return [c, full ? new HDC1008Full(c) : new HDC1008Minimal(c)];
};
const lastIs = (c, bytes) => JSON.stringify([...c.writes[c.writes.length - 1]]) === JSON.stringify(bytes);

async function main() {
    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_writes_default_config', lastIs(c, [0x02, 0x10, 0x00]));

    [c, s] = make(false);
    c.queueRead(Buffer.from([0x66, 0x66, 0x80, 0x00]));
    const [t, h] = await s.read();
    checkTrue('read_trigger_pointer_0', c.writes.length === 1 && lastIs(c, [0x00]));
    checkTrue('read_temperature', close(t, 0x6666 / 65536 * 165 - 40));
    checkTrue('read_humidity', close(h, 50.0));

    [c, s] = make(false);
    c.failNextReads(3);
    c.queueRead(Buffer.from([0x66, 0x66, 0x80, 0x00]));
    checkTrue('read_after_nack', close((await s.read())[1], 50.0));

    [c, s] = make(false);
    c.failNextReads(1000);
    checkTrue('read_timeout', await rejects(() => s.read()));

    [c, s] = make();
    c.queueRead(Buffer.from([0x80, 0x00]));
    checkTrue('read_temperature_single', close(await s.readTemperature(), 42.5));
    checkTrue('single_mode_cfg', JSON.stringify([...c.writes[0]]) === JSON.stringify([0x02, 0x00, 0x00]) && c.writes.length === 2);
    c.queueRead(Buffer.from([0x80, 0x00]));
    checkTrue('read_humidity_single', close(await s.readHumidity(), 50.0));
    checkTrue('humidity_pointer_1', lastIs(c, [0x01]));

    [c, s] = make();
    c.queueRead(Buffer.from([0x12, 0x34, 0x56, 0x78]));
    const raw = await s.readRaw();
    checkTrue('read_raw', raw[0] === 0x1234 && raw[1] === 0x5678);

    [c, s] = make();
    await s.setResolution(11, 8);
    checkTrue('resolution_11_8', lastIs(c, [0x02, 0x16, 0x00]));
    await s.setResolution(14, 14);
    checkTrue('resolution_14_14', lastIs(c, [0x02, 0x10, 0x00]));
    checkTrue('resolution_bad_t', await rejects(() => s.setResolution(8, 14), RangeError));
    checkTrue('resolution_bad_h', await rejects(() => s.setResolution(14, 12), RangeError));

    await s.setHeater(true);
    checkTrue('heater_on', lastIs(c, [0x02, 0x30, 0x00]));
    await s.setHeater(false);
    checkTrue('heater_off', lastIs(c, [0x02, 0x10, 0x00]));
    c.queueRead(Buffer.from([0x30, 0x00]));
    checkTrue('is_heater_on', await s.isHeaterOn());
    c.queueRead(Buffer.from([0x18, 0x00]));
    checkTrue('battery_low', await s.batteryLow());
    c.queueRead(Buffer.from([0x10, 0x00]));
    checkTrue('battery_ok', !(await s.batteryLow()));
    c.queueRead(Buffer.from([0x10, 0x00]));
    checkTrue('read_config', (await s.readConfig()) === 0x1000);

    [c, s] = make();
    await s.setHeater(true);
    await s.softwareReset();
    const n = c.writes.length;
    checkTrue('reset_then_config', JSON.stringify([...c.writes[n - 2]]) === JSON.stringify([0x02, 0x80, 0x00]) && lastIs(c, [0x02, 0x30, 0x00]));

    [c, s] = make();
    c.queueRead(Buffer.from([0x00, 0x12]));
    c.queueRead(Buffer.from([0x34, 0x56]));
    c.queueRead(Buffer.from([0x80, 0x00]));
    checkTrue('serial_id', (await s.serialId()) === 0x12 * 2 ** 24 + 0x3456 * 256 + 0x100);

    [c, s] = make();
    checkTrue('conv_time_14_14', close(s.conversionTime(), 0.01285, 1e-9));
    await s.setResolution(11, 8);
    checkTrue('conv_time_11_8', close(s.conversionTime(), 0.00365 + 0.0025, 1e-9));

    [c, s] = make();
    c.failNextReads(1);
    checkTrue('poll_not_ready', !(await s.pollInterrupt()));
    c.queueRead(Buffer.from([0x80, 0x00, 0x80, 0x00]));
    checkTrue('poll_ready', await s.pollInterrupt());

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
