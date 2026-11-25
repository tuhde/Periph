'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HTU21DFFull, HTU21DFMinimal, crc8 } = require('../../packages/periph/src/chips/humidity/htu21df');

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

const rhCode = (rh) => (Math.round((rh + 6.0) * 65536.0 / 125.0) & 0xFFFC) | 0x02;
const tCode = (t) => Math.round((t + 46.85) * 65536.0 / 175.72) & 0xFFFC;

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new HTU21DFFull(c) : new HTU21DFMinimal(c)];
}

const cmds = (c) => c.writes.map((w) => w[0]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);
const lastWrite = (c, a, b) => { const w = c.writes[c.writes.length - 1]; return w.length === 2 && w[0] === a && w[1] === b; };

async function main() {
    checkTrue('crc8_683A', crc8([0x68, 0x3A]) === 0x7C);
    checkTrue('crc8_4E85', crc8([0x4E, 0x85]) === 0x6B);
    checkTrue('crc8_DC', crc8([0xDC]) === 0x79);

    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_soft_reset', sameCmds(c, [0xFE]));

    // read: 0xF3 then 0xF5, status bits ignored
    [c, s] = make();
    c.queueRead(frame(tCode(25.0)));
    c.queueRead(frame(rhCode(50.0)));
    let [t, rh] = await s.read();
    checkTrue('read_commands', sameCmds(c, [0xF3, 0xF5]));
    checkTrue('read_humidity', close(rh, 50.0, 0.05));
    checkTrue('read_temperature', close(t, 25.0, 0.05));
    checkTrue('minimal_class_read', (await make(false)[1].constructor.name) === 'HTU21DFMinimal');

    // datasheet value 0x683A -> status bits cleared 0x6838
    [c, s] = make();
    c.queueRead(frame(0x683A)); c.queueRead(frame(0x683A));
    [t, rh] = await s.read();
    checkTrue('conv_temperature', close(t, -46.85 + 175.72 * 0x6838 / 65536.0));
    checkTrue('conv_humidity', close(rh, -6.0 + 125.0 * 0x6838 / 65536.0));

    // NACKed read header: polls until ready
    [c, s] = make();
    c.failNextReads(3);
    c.queueRead(frame(tCode(20.0)));
    c.queueRead(frame(rhCode(40.0)));
    [t, rh] = await s.read();
    checkTrue('read_after_nacks', close(rh, 40.0, 0.05));

    // never ready
    [c, s] = make();
    c.failNextReads(1000);
    checkTrue('timeout_rejects', await rejects(() => s.read()));

    // CRC mismatch: retried once, then rejects
    const bad = Buffer.from(frame(0x6000)); bad[2] ^= 0xFF;
    [c, s] = make();
    c.queueRead(bad); c.queueRead(bad);
    checkTrue('crc_mismatch_rejects', await rejects(() => s.read()));
    [c, s] = make();
    c.queueRead(bad); c.queueRead(frame(tCode(25.0))); c.queueRead(frame(rhCode(50.0)));
    checkTrue('crc_retry_recovers', close((await s.read())[0], 25.0, 0.05));
    checkTrue('crc_retry_commands', sameCmds(c, [0xF3, 0xF3, 0xF5]));

    // not clamped
    [c, s] = make();
    c.queueRead(frame(0)); c.queueRead(frame(0));
    checkTrue('humidity_not_clamped', close((await s.read())[1], -6.0));

    // single values and raw codes
    [c, s] = make();
    c.queueRead(frame(rhCode(30.0)));
    checkTrue('read_humidity_only', close(await s.readHumidity(), 30.0, 0.05));
    c.queueRead(frame(tCode(-10.0)));
    checkTrue('read_temperature_only', close(await s.readTemperature(), -10.0, 0.05));
    checkTrue('single_commands', sameCmds(c, [0xF5, 0xF3]));
    c.queueRead(frame(0x1237)); c.queueRead(frame(0x4321));
    const raw = await s.readRaw();
    checkTrue('read_raw', raw[0] === 0x1234 && raw[1] === 0x4320);

    // compensated humidity: 50 %RH at 35 degC -> 51.5
    c.queueRead(frame(tCode(35.0))); c.queueRead(frame(rhCode(50.0)));
    checkTrue('humidity_compensated', close(await s.readHumidityCompensated(), 51.5, 0.1));

    // hold master
    [c, s] = make();
    s.setHoldMaster(true);
    c.queueRead(frame(tCode(25.0))); c.queueRead(frame(rhCode(50.0)));
    await s.read();
    checkTrue('hold_master_commands', sameCmds(c, [0xE3, 0xE5]));

    // user register
    [c, s] = make();
    c.setRegister(0xE7, [0x02]);
    await s.setResolution(3);
    checkTrue('set_resolution_3', lastWrite(c, 0xE6, 0x83));
    await s.setResolution(1);
    checkTrue('set_resolution_1', lastWrite(c, 0xE6, 0x03));
    await s.setResolution(2);
    checkTrue('set_resolution_2', lastWrite(c, 0xE6, 0x82));
    checkTrue('set_resolution_invalid', await rejects(() => s.setResolution(4), RangeError));
    for (const [reg, res] of [[0x83, 3], [0x03, 1], [0x82, 2], [0x02, 0]]) {
        c.setRegister(0xE7, [reg]);
        checkTrue('get_resolution_' + res, (await s.getResolution()) === res);
    }
    c.setRegister(0xE7, [0x02]);
    await s.setHeater(true);
    checkTrue('heater_on_write', lastWrite(c, 0xE6, 0x06));
    c.setRegister(0xE7, [0x06]);
    checkTrue('heater_on', await s.heater());
    await s.setHeater(false);
    checkTrue('heater_off_write', lastWrite(c, 0xE6, 0x02));
    c.setRegister(0xE7, [0x02]);
    checkTrue('heater_off', !(await s.heater()));
    checkTrue('battery_ok', !(await s.batteryLow()));
    c.setRegister(0xE7, [0x42]);
    checkTrue('battery_low', await s.batteryLow());
    checkTrue('read_user_register', (await s.readUserRegister()) === 0x42);

    // soft reset
    c.writes.length = 0;
    await s.softReset();
    checkTrue('soft_reset', sameCmds(c, [0xFE]));

    // dew point: 25 degC / 50 %RH -> about 13.86 degC
    [c, s] = make();
    c.queueRead(frame(tCode(25.0))); c.queueRead(frame(rhCode(50.0)));
    checkTrue('dew_point', close(await s.dewPoint(), 13.86, 0.1));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
