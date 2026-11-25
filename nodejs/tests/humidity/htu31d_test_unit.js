'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HTU31DFull, HTU31DMinimal, crc8 } = require('../../packages/periph/src/chips/humidity/htu31d');

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

function queueTrh(c, t, rh) { c.queueRead(Buffer.from([...word(t), ...word(rh)])); }
function queueRh(c, rh) { c.queueRead(Buffer.from(word(rh))); }

function make(full = true) {
    const c = new I2CConnectionMock();
    return [c, full ? new HTU31DFull(c) : new HTU31DMinimal(c)];
}

const cmds = (c) => c.writes.map((w) => w[0]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);

async function main() {
    checkTrue('crc8_DC', crc8([0xDC]) === 0x79);
    checkTrue('crc8_683A', crc8([0x68, 0x3A]) === 0x7C);
    checkTrue('crc8_4E85', crc8([0x4E, 0x85]) === 0x6B);

    let [c, s] = make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_reset', sameCmds(c, [0x1E]));

    // read: 0x5E then 0x00, conversion formulas
    [c, s] = make();
    queueTrh(c, 0x683A, 0x8000);
    let m = await s.read();
    checkTrue('read_commands', sameCmds(c, [0x5E, 0x00]));
    checkTrue('read_temperature', close(m.temperature_c, -40.0 + 165.0 * 0x683A / 65535.0));
    checkTrue('read_humidity', close(m.humidity_pct, 100.0 * 0x8000 / 65535.0));
    queueTrh(c, 0xFFFF, 0xFFFF);
    m = await s.read();
    checkTrue('temperature_max', close(m.temperature_c, 125.0));
    checkTrue('humidity_full_scale', close(m.humidity_pct, 100.0));
    queueTrh(c, 0, 0);
    m = await s.read();
    checkTrue('temperature_min', close(m.temperature_c, -40.0));
    checkTrue('humidity_zero', close(m.humidity_pct, 0.0));
    checkTrue('minimal_class_read', make(false)[1].constructor.name === 'HTU31DMinimal');

    // CRC mismatch (temperature, humidity)
    [c, s] = make();
    let bad = Buffer.from([...word(0x683A), ...word(0x8000)]); bad[2] ^= 0xFF;
    c.queueRead(bad);
    checkTrue('crc_mismatch_temperature', await rejects(() => s.read()));
    bad = Buffer.from([...word(0x683A), ...word(0x8000)]); bad[5] ^= 0xFF;
    c.queueRead(bad);
    checkTrue('crc_mismatch_humidity', await rejects(() => s.read()));

    // single values and raw
    [c, s] = make();
    queueRh(c, 0x4000);
    checkTrue('read_humidity_single', close(await s.readHumidity(), 100.0 * 0x4000 / 65535.0) && sameCmds(c, [0x5E, 0x10]));
    c.writes.length = 0;
    queueTrh(c, 0x4000, 0);
    // read(3) of a 6-byte frame returns the first 3 bytes
    checkTrue('read_temperature_single', close(await s.readTemperature(), -40.0 + 165.0 * 0x4000 / 65535.0) && sameCmds(c, [0x5E, 0x00]));
    queueTrh(c, 0x1234, 0x5678);
    checkTrue('read_raw', JSON.stringify(await s.readRaw()) === '[4660,22136]');

    // resolution
    [c, s] = make();
    checkTrue('default_resolution', JSON.stringify(s.getResolution()) === '[3,3]');
    checkTrue('conversion_time_osr33', close(s.conversionTime(), 0.02032));
    s.setResolution(0, 0);
    queueTrh(c, 0x4000, 0x4000);
    await s.read();
    checkTrue('convert_osr00', cmds(c)[0] === 0x40);
    checkTrue('conversion_time_osr00', close(s.conversionTime(), 0.00268));
    c.writes.length = 0;
    s.setResolution(2, 1);
    queueTrh(c, 0x4000, 0x4000);
    await s.read();
    checkTrue('convert_osr21', cmds(c)[0] === (0x40 | (2 << 3) | (1 << 1)));
    c.writes.length = 0;
    s.setResolution(1, 2);
    await s.startConversion();
    checkTrue('start_conversion_only', sameCmds(c, [0x40 | (1 << 3) | (2 << 1)]));
    checkTrue('get_resolution', JSON.stringify(s.getResolution()) === '[1,2]');
    let threw = false;
    try { s.setResolution(4, 0); } catch (e) { threw = true; }
    checkTrue('resolution_rejects_high_rh', threw);
    threw = false;
    try { s.setResolution(0, -1); } catch (e) { threw = true; }
    checkTrue('resolution_rejects_negative_t', threw);

    // read_result
    [c, s] = make();
    queueTrh(c, 0x2000, 0x6000);
    m = await s.readResult();
    checkTrue('read_result_command', sameCmds(c, [0x00]));
    checkTrue('read_result_humidity', close(m.humidity_pct, 100.0 * 0x6000 / 65535.0));

    // heater
    [c, s] = make();
    await s.setHeater(true);
    await s.setHeater(false);
    checkTrue('heater_commands', sameCmds(c, [0x04, 0x02]));
    c.queueRead(Buffer.from([0x01]));
    checkTrue('is_heater_on_true', await s.isHeaterOn());
    c.queueRead(Buffer.from([0x00]));
    checkTrue('is_heater_on_false', !(await s.isHeaterOn()));

    // diagnostic
    [c, s] = make();
    c.queueRead(Buffer.from([0xA5]));
    checkTrue('diagnostic_byte', (await s.diagnostic()) === 0xA5 && sameCmds(c, [0x08]));
    c.queueRead(Buffer.from([0xA5]));
    const f = await s.diagnosticFlags();
    checkTrue('flags_nvm_err', f.nvm_err === true);
    checkTrue('flags_rh_overrun_clear', f.rh_overrun === false);
    checkTrue('flags_rh_high_err', f.rh_high_err === true);
    checkTrue('flags_t_high_err', f.t_high_err === true);
    checkTrue('flags_heater_on', f.heater_on === true);
    checkTrue('flags_t_low_err_clear', f.t_low_err === false);

    // serial number
    [c, s] = make();
    c.queueRead(Buffer.from([0xAB, 0xCD, 0xEF, crc8([0xAB, 0xCD, 0xEF])]));
    checkTrue('serial_number', (await s.serialNumber()) === 0xABCDEF && sameCmds(c, [0x0A]));
    c.queueRead(Buffer.from([0xAB, 0xCD, 0xEF, crc8([0xAB, 0xCD, 0xEF]) ^ 0x55]));
    checkTrue('serial_crc_mismatch', await rejects(() => s.serialNumber()));

    // software reset
    [c, s] = make();
    s.setResolution(0, 1);
    await s.softReset();
    checkTrue('software_reset_command', sameCmds(c, [0x1E]));
    checkTrue('software_reset_defaults', JSON.stringify(s.getResolution()) === '[3,3]');

    // dew point: 25 degC / 50 %RH -> about 13.86 degC
    [c, s] = make();
    queueTrh(c, Math.round(65.0 * 65535.0 / 165.0), Math.round(0.5 * 65535.0));
    checkTrue('dew_point', close(await s.dewPoint(), 13.86, 0.15));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
