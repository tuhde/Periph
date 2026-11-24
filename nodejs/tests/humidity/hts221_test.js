'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HTS221Full } = require('../../packages/periph/src/chips/humidity/hts221');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x5F', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new HTS221Full(connection);
    await sensor.init();

    checkTrue('who_am_i', (await sensor.whoAmI()) === 0xBC);
    const cal = await sensor.readCalibration();
    checkTrue('calibration_plausible', cal.H1_rH > cal.H0_rH && cal.T1_degC > cal.T0_degC);
    const m = await sensor.read();
    checkTrue('read_temperature_range', m.temperature_c > -40 && m.temperature_c < 85);
    checkTrue('read_humidity_range', m.humidity_pct >= 0 && m.humidity_pct <= 100);
    const h = await sensor.readHumidity();
    checkTrue('read_humidity_single', h >= 0 && h <= 100);
    checkTrue('read_temperature_single', (await sensor.readTemperature()) > -40);
    checkTrue('dew_point_below_temperature', (await sensor.dewPoint()) <= (await sensor.readTemperature()) + 1);

    await sensor.setAveraging(32, 64);
    checkTrue('averaging_roundtrip', JSON.stringify(await sensor.getAveraging()) === '[32,64]');
    await sensor.setAveraging(16, 32);

    await sensor.setOutputDataRate(1);
    checkTrue('odr_roundtrip', (await sensor.getOutputDataRate()) === 1);
    await sleep(1500);
    const c = await sensor.readContinuous();
    checkTrue('continuous_read', c.humidity_pct >= 0 && c.humidity_pct <= 100);
    await sensor.setOutputDataRate(0);

    await sensor.setHeater(true);
    checkTrue('heater_on', await sensor.isHeaterOn());
    await sensor.setHeater(false);
    checkTrue('heater_off', !(await sensor.isHeaterOn()));

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
