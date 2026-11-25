'use strict';
const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPL115A2Full } = require('../../packages/periph/src/chips/pressure/mpl115a2');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

let passed = 0, failed = 0;

function check(label, ok, detail) {
    if (ok) { console.log('PASS ' + label); passed++; }
    else { console.log('FAIL ' + label + (detail ? ': ' + detail : '')); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const baro = new MPL115A2Full(connection);
    await baro.ready;

    const p = await baro.readPressure();
    check('pressure_range', p >= 50000 && p <= 115000, String(p));
    const t = await baro.readTemperature();
    check('temperature_range', t >= -40 && t <= 105, String(t));
    const raw = await baro.readRaw();
    check('raw_range', raw.padc >= 0 && raw.padc <= 1023 && raw.tadc >= 0 && raw.tadc <= 1023);
    const c = await baro.compensate(raw.padc, raw.tadc);
    check('compensate_range', c >= 50000 && c <= 115000);
    const r = await baro.read();
    check('read_keys', 'pressure_pa' in r && 'temperature_c' in r);
    const hpa = await baro.pressureHpa();
    check('pressure_hpa', hpa >= 500 && hpa <= 1150);
    const alt = await baro.altitude();
    check('altitude', alt >= -1000 && alt <= 6000);
    await baro.reloadCoefficients();
    check('coefficients', Object.keys(await baro.coefficients()).length === 4);

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main();
