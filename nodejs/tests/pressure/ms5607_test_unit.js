'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MS5607Full, MS5607Minimal, crc4 } = require('../../packages/periph/src/chips/pressure/ms5607');

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

const COEFFS = [46372, 43981, 29059, 27842, 31553, 28165];
const D1_EX = 6465444, D2_EX = 8077636;

function makeProm(serial = 0x5A3) {
    const w = [0x3132, ...COEFFS, serial << 4];
    w[7] |= crc4(w);
    return w;
}

function queueProm(c, corrupt = false) {
    const w = makeProm();
    if (corrupt) w[3] ^= 0x0100;
    for (const x of w) c.queueRead(Buffer.from([x >> 8, x & 0xFF]));
}

function queueAdc(c, v) {
    c.queueRead(Buffer.from([v >> 16, (v >> 8) & 0xFF, v & 0xFF]));
}

async function make(full = true) {
    const c = new I2CConnectionMock();
    const s = full ? new MS5607Full(c) : new MS5607Minimal(c);
    return [c, s];
}

async function ready() {
    const [c, s] = await make();
    queueProm(c);
    await s.init();
    c.writes.length = 0;
    return [c, s];
}

const cmds = (c) => c.writes.map((w) => w[0]);
const sameCmds = (c, expected) => JSON.stringify(cmds(c)) === JSON.stringify(expected);

async function main() {
    checkTrue('crc4_zero', crc4([0, 0, 0, 0, 0, 0, 0, 0]) === 0);
    const prom = makeProm();
    checkTrue('crc4_roundtrip', crc4(prom) === (prom[7] & 0xF));

    let [c, s] = await make();
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    queueProm(c);
    await s.init();
    checkTrue('init_sequence', sameCmds(c, [0x1E, 0xA0, 0xA2, 0xA4, 0xA6, 0xA8, 0xAA, 0xAC, 0xAE]));
    checkTrue('init_coefficients', JSON.stringify(s.coefficients()) === JSON.stringify(COEFFS));
    checkTrue('init_crc_ok', s.promCrcOk());
    checkTrue('serial_code', s.serialCode() === 0x5A3);
    [c, s] = await make();
    queueProm(c, true);
    checkTrue('init_bad_crc', await rejects(() => s.init()));

    // datasheet example and second-order branches (reference values from the independent Python test)
    [c, s] = await ready();
    let r = s.compensate(D1_EX, D2_EX);
    checkTrue('example_temperature', close(r.temperature_c, 20.0));
    checkTrue('example_pressure', close(r.pressure_pa, 110002.0));
    r = s.compensate(D1_EX, D2_EX - 300000);
    checkTrue('cold_temperature', close(r.temperature_c, 9.52));
    checkTrue('cold_pressure', close(r.pressure_pa, 107505.0));
    r = s.compensate(D1_EX, D2_EX - 300000, false);
    checkTrue('cold_first_order_temperature', close(r.temperature_c, 9.93));
    checkTrue('cold_first_order_pressure', close(r.pressure_pa, 107577.0));
    r = s.compensate(D1_EX, D2_EX - 1200000);
    checkTrue('very_cold_temperature', close(r.temperature_c, -26.98));
    checkTrue('very_cold_pressure', close(r.pressure_pa, 99055.0));
    r = s.compensate(D1_EX, D2_EX - 1200000, false);
    checkTrue('very_cold_first_order', close(r.temperature_c, -20.28) && close(r.pressure_pa, 100302.0));

    // Minimal read: D2 first, then D1, OSR 4096
    [c, s] = await make(false);
    queueProm(c);
    await s.init();
    c.writes.length = 0;
    queueAdc(c, D2_EX);
    queueAdc(c, D1_EX);
    r = await s.read();
    checkTrue('read_commands', sameCmds(c, [0x58, 0x00, 0x48, 0x00]));
    checkTrue('read_values', close(r.temperature_c, 20.0) && close(r.pressure_pa, 110002.0));
    queueAdc(c, 0);
    checkTrue('adc_zero_fails', await rejects(() => s.read()));

    // Full: single values and raw
    [c, s] = await ready();
    queueAdc(c, D2_EX); queueAdc(c, D1_EX);
    checkTrue('read_pressure', close(await s.readPressure(), 110002.0));
    c.writes.length = 0;
    queueAdc(c, D2_EX);
    checkTrue('read_temperature', close(await s.readTemperature(), 20.0));
    checkTrue('read_temperature_d2_only', sameCmds(c, [0x58, 0x00]));
    queueAdc(c, D2_EX); queueAdc(c, D1_EX);
    const raw = await s.readRaw();
    checkTrue('read_raw', raw[0] === D1_EX && raw[1] === D2_EX);

    // oversampling
    const osrs = [256, 512, 1024, 2048, 4096];
    for (let i = 0; i < 5; i++) {
        [c, s] = await ready();
        s.setOversampling(osrs[i]);
        queueAdc(c, D2_EX); queueAdc(c, D1_EX);
        await s.read();
        checkTrue('osr_' + osrs[i] + '_commands', c.writes[0][0] === (0x50 | (i << 1)) && c.writes[2][0] === (0x40 | (i << 1)));
        checkTrue('osr_' + osrs[i] + '_get', s.getOversampling() === osrs[i]);
    }
    [c, s] = await make();
    let threw = false;
    try { s.setOversampling(300); } catch (e) { threw = true; }
    checkTrue('osr_invalid', threw);
    checkTrue('default_osr', s.getOversampling() === 4096);
    checkTrue('conversion_time_table', JSON.stringify(osrs.map((o) => s.conversionTime(o))) === JSON.stringify([0.0006, 0.00117, 0.00228, 0.00454, 0.00904]));
    checkTrue('conversion_time_current', s.conversionTime() === 0.00904);
    threw = false;
    try { s.conversionTime(100); } catch (e) { threw = true; }
    checkTrue('conversion_time_invalid', threw);

    // non-blocking
    [c, s] = await ready();
    await s.startConversion(MS5607Full.TEMPERATURE);
    checkTrue('start_conversion_temperature', sameCmds(c, [0x58]));
    await s.startConversion(MS5607Full.PRESSURE, 512);
    checkTrue('start_conversion_pressure_osr', c.writes[1][0] === 0x42);
    checkTrue('start_conversion_invalid', await rejects(() => s.startConversion(0, 99)));
    queueAdc(c, D1_EX);
    checkTrue('read_adc_value', (await s.readAdc()) === D1_EX);
    queueAdc(c, 0);
    checkTrue('read_adc_zero_is_error', await rejects(() => s.readAdc()));

    // reset, PROM
    [c, s] = await ready();
    queueProm(c);
    await s.reset();
    checkTrue('reset_commands', c.writes[0][0] === 0x1E && c.writes.length === 9);
    queueProm(c);
    checkTrue('read_prom_words', JSON.stringify(await s.readProm()) === JSON.stringify(makeProm()));

    // altitude
    [c, s] = await ready();
    queueAdc(c, D2_EX); queueAdc(c, D1_EX);
    const expected = 44330.0 * (1.0 - Math.pow(110002.0 / 101325.0, 1.0 / 5.255));
    const h = await s.altitude();
    checkTrue('altitude_formula', close(h, expected, 1e-6));
    checkTrue('altitude_below_sea_level', h < 0);
    queueAdc(c, D2_EX); queueAdc(c, D1_EX);
    checkTrue('altitude_reference', close(await s.altitude(110002.0), 0.0, 1e-6));

    // bus errors propagate
    [c, s] = await make();
    c.failNextWrites(1);
    checkTrue('bus_error_propagates', await rejects(() => s.init()));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => { console.error(err); process.exit(1); });
