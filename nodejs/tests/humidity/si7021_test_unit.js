'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { Si7021Full, Si7021Minimal, crc8 } = require('../../packages/periph/src/chips/humidity/si7021');

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

function frame(code) {
    const d = [code >> 8, code & 0xFF];
    return Buffer.from([...d, crc8(d)]);
}

const rhCode = (rh) => Math.round((rh + 6.0) * 65536.0 / 125.0);
const tCode = (t) => Math.round((t + 46.85) * 65536.0 / 175.72);
const tBytes = (t) => Buffer.from([tCode(t) >> 8, tCode(t) & 0xFF]);

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new Si7021Full(c) : new Si7021Minimal(c)];
}

const cmds = (c) => c.writes.map((w) => w[0]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);
const lastWrite = (c, a, b) => { const w = c.writes[c.writes.length - 1]; return w.length === 2 && w[0] === a && w[1] === b; };

async function main() {
    checkTrue('crc8_zero', crc8([0x00, 0x00]) === 0x00);
    checkTrue('crc8_single', crc8([0x01]) === 0x31);

    let [c, s] = make();
    checkTrue('init_no_bus_access', c.writes.length === 0);

    // read: 0xF5 then 0xE0
    c.queueRead(frame(rhCode(50.0)));
    c.queueRead(tBytes(25.0));
    let [t, rh] = await s.read();
    checkTrue('read_commands', sameCmds(c, [0xF5, 0xE0]));
    checkTrue('read_humidity', close(rh, 50.0, 0.01));
    checkTrue('read_temperature', close(t, 25.0, 0.01));
    checkTrue('minimal_class_read', (await make(false)[1].constructor.name) === 'Si7021Minimal');

    // NACKed read header: polls until ready
    [c, s] = make();
    c.failNextReads(3);
    c.queueRead(frame(rhCode(40.0)));
    c.queueRead(tBytes(20.0));
    [t, rh] = await s.read();
    checkTrue('read_after_nacks', close(rh, 40.0, 0.01));

    // never ready
    [c, s] = make();
    c.failNextReads(1000);
    checkTrue('timeout_rejects', await rejects(() => s.read()));

    // CRC mismatch
    [c, s] = make();
    const bad = Buffer.from(frame(0x6000)); bad[2] ^= 0xFF;
    c.queueRead(bad);
    checkTrue('crc_mismatch_rejects', await rejects(() => s.read()));

    // clamping
    [c, s] = make();
    c.queueRead(frame(0x0000)); c.queueRead(Buffer.from([0, 0]));
    checkTrue('clamp_low', (await s.read())[1] === 0);
    c.queueRead(frame(0xFFFC)); c.queueRead(Buffer.from([0, 0]));
    checkTrue('clamp_high', (await s.read())[1] === 100);

    // single values and raw codes
    [c, s] = make();
    c.queueRead(frame(rhCode(30.0)));
    checkTrue('read_humidity_only', close(await s.readHumidity(), 30.0, 0.01));
    c.queueRead(frame(tCode(-10.0)));
    checkTrue('read_temperature_only', close(await s.readTemperature(), -10.0, 0.01));
    c.queueRead(tBytes(20.0));
    checkTrue('read_temperature_previous', close(await s.readTemperaturePrevious(), 20.0, 0.01));
    checkTrue('single_commands', sameCmds(c, [0xF5, 0xF3, 0xE0]));
    c.queueRead(frame(0x1234));
    checkTrue('read_raw_humidity', (await s.readRawHumidity()) === 0x1234);
    c.queueRead(frame(0x4321));
    checkTrue('read_raw_temperature', (await s.readRawTemperature()) === 0x4321);

    // hold master
    [c, s] = make();
    s.setClockStretching(true);
    c.queueRead(frame(rhCode(50.0))); c.queueRead(tBytes(25.0));
    await s.read();
    c.queueRead(frame(0x6666));
    await s.readTemperature();
    checkTrue('hold_master_commands', sameCmds(c, [0xE5, 0xE0, 0xE3]));

    // user register
    [c, s] = make();
    c.setRegister(0xE7, [0x3A]);
    await s.setResolution(11, 11);
    checkTrue('set_resolution_11_11', lastWrite(c, 0xE6, 0xBB));
    await s.setResolution(8, 12);
    checkTrue('set_resolution_8_12', lastWrite(c, 0xE6, 0x3B));
    await s.setResolution(10, 13);
    checkTrue('set_resolution_10_13', lastWrite(c, 0xE6, 0xBA));
    checkTrue('set_resolution_invalid', await rejects(() => s.setResolution(12, 12)));
    c.setRegister(0xE7, [0xBB]);
    checkTrue('get_resolution_11_11', JSON.stringify(await s.getResolution()) === '[11,11]');
    c.setRegister(0xE7, [0x3B]);
    checkTrue('get_resolution_8_12', JSON.stringify(await s.getResolution()) === '[8,12]');
    c.setRegister(0xE7, [0xBA]);
    checkTrue('get_resolution_10_13', JSON.stringify(await s.getResolution()) === '[10,13]');
    c.setRegister(0xE7, [0x3A]);
    checkTrue('get_resolution_default', JSON.stringify(await s.getResolution()) === '[12,14]');
    await s.setHeater(true);
    checkTrue('heater_on_write', lastWrite(c, 0xE6, 0x3E));
    c.setRegister(0xE7, [0x3E]);
    checkTrue('is_heater_on', await s.isHeaterOn());
    await s.setHeater(false);
    checkTrue('heater_off_write', lastWrite(c, 0xE6, 0x3A));
    c.setRegister(0xE7, [0x3A]);
    checkTrue('heater_off_read', !(await s.isHeaterOn()));
    checkTrue('vdd_ok', !(await s.vddLow()));
    c.setRegister(0xE7, [0x7A]);
    checkTrue('vdd_low', await s.vddLow());
    c.setRegister(0xE7, [0x3A]);
    checkTrue('read_user_register', (await s.readUserRegister()) === 0x3A);
    await s.writeUserRegister(0x00);
    checkTrue('write_user_register_keeps_reserved', lastWrite(c, 0xE6, 0x3A));
    checkTrue('conversion_time', close(await s.conversionTime(), 0.017));

    // software reset, identification
    c.writes.length = 0;
    await s.softReset();
    checkTrue('software_reset', sameCmds(c, [0xFE]));
    [c, s] = make();
    c.setRegister(0xFA0F, [0x11, 0, 0x22, 0, 0x33, 0, 0x44, 0]);
    c.setRegister(0xFCC9, [0x15, 0x66, 0, 0x77, 0x88, 0]);
    c.setRegister(0x84B8, [0xFF]);
    c.setAddressWidth(2);
    checkTrue('serial_number', (await s.serialNumber()) === 0x1122334415667788n);
    checkTrue('device_type', (await s.deviceType()) === 0x15);
    checkTrue('firmware_revision', (await s.firmwareRevision()) === 0xFF);

    // dew point: 25 degC / 50 %RH -> 13.86 degC
    [c, s] = make();
    c.queueRead(frame(rhCode(50.0))); c.queueRead(tBytes(25.0));
    checkTrue('dew_point', close(await s.dewPoint(), 13.86, 0.05));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
