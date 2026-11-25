'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MPL115A2Minimal, MPL115A2Full } = require('../../packages/periph/src/chips/pressure/mpl115a2');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function make() {
    // Datasheet worked example: a0=0x3ECE, b1=0xB3F9, b2=0xC517, c12=0x33C8;
    // ADC bytes 0x66 0x80 0x7E 0xC0 -> Padc=410, Tadc=507 -> 96.59 kPa.
    const c = new I2CConnectionMock();
    c.setRegister(0x04, [0x3E, 0xCE, 0xB3, 0xF9, 0xC5, 0x17, 0x33, 0xC8]);
    c.setRegister(0x00, [0x66, 0x80, 0x7E, 0xC0]);
    return c;
}

async function main() {
    const c = make();
    const d = new MPL115A2Full(c);
    await d.ready;
    checkTrue('init', true);

    const k = await d.coefficients();
    checkTrue('coefficients', Math.abs(k.a0 - 2009.75) < 1e-9 && Math.abs(k.b1 + 2.375854) < 1e-5
        && Math.abs(k.b2 + 0.92047) < 1e-5 && Math.abs(k.c12 - 0.000790) < 1e-6);

    const p = await d.readPressure();
    checkTrue('pressure', Math.abs(p - 96586) < 30);
    const cv = c.writes.filter((w) => w.length === 2 && w[0] === 0x12);
    checkTrue('convert_written', cv.length === 1 && cv[0][1] === 0x00);

    const t = await d.readTemperature();
    checkTrue('temperature', Math.abs(t - (25 + (507 - 498) / -5.35)) < 1e-9);
    const r = await d.readRaw();
    checkTrue('read_raw', r.padc === 410 && r.tadc === 507);
    const both = await d.read();
    checkTrue('read', Math.abs(both.pressure_pa - p) < 1e-9 && Math.abs(both.temperature_c - t) < 1e-9);
    checkTrue('compensate', Math.abs(await d.compensate(410, 507) - p) < 1e-9);
    checkTrue('compensate_limits', (await d.compensate(0, 0)) > 40000 && (await d.compensate(1023, 0)) < 130000);
    checkTrue('pressure_hpa', Math.abs(await d.pressureHpa() - p / 100) < 1e-9);
    checkTrue('altitude', Math.abs(await d.altitude() - 44330 * (1 - Math.pow(p / 101325, 1 / 5.255))) < 1e-6);
    checkTrue('altitude_sea_level', Math.abs(await d.altitude(p)) < 1e-9);

    c.setRegister(0x04, [0x00, 0x08, 0, 0, 0, 0, 0, 0]);
    await d.reloadCoefficients();
    checkTrue('reload_coefficients', (await d.coefficients()).a0 === 1);

    const m = new MPL115A2Minimal(make());
    checkTrue('minimal_pressure', Math.abs(await m.readPressure() - 96586) < 30);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
