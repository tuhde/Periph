'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HTU31DFull } = require('../../packages/periph/src/chips/humidity/htu31d');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new HTU31DFull(connection);
    await sensor.init();

    const m = await sensor.read();
    checkTrue('read_temperature_range', m.temperature_c > -40 && m.temperature_c < 125);
    checkTrue('read_humidity_range', m.humidity_pct >= 0 && m.humidity_pct <= 100);
    const h = await sensor.readHumidity();
    checkTrue('read_humidity_single', h >= 0 && h <= 100);
    const t = await sensor.readTemperature();
    checkTrue('read_temperature_single', t > -40 && t < 125);
    const [tr, rr] = await sensor.readRaw();
    checkTrue('read_raw_range', tr >= 0 && tr <= 65535 && rr >= 0 && rr <= 65535);
    checkTrue('dew_point_below_temperature', (await sensor.dewPoint()) <= t + 1);
    const serial = await sensor.serialNumber();
    checkTrue('serial_number_24bit', serial >= 0 && serial <= 0xFFFFFF);
    checkTrue('diagnostic_no_nvm_error', !(await sensor.diagnosticFlags()).nvm_err);
    for (let osr = 0; osr < 4; osr++) {
        sensor.setResolution(osr, osr);
        checkTrue('resolution_roundtrip_' + osr, JSON.stringify(sensor.getResolution()) === JSON.stringify([osr, osr]));
        const r = await sensor.read();
        checkTrue('read_at_osr_' + osr, r.humidity_pct >= 0 && r.humidity_pct <= 100 && r.temperature_c > -40);
    }
    sensor.setResolution(3, 3);
    checkTrue('conversion_time_osr33', Math.abs(sensor.conversionTime() - 0.02032) < 1e-6);
    await sensor.startConversion();
    await sleep(30);
    const r = await sensor.readResult();
    checkTrue('start_conversion_read_result', r.humidity_pct >= 0 && r.humidity_pct <= 100);
    await sensor.setHeater(true);
    checkTrue('heater_on', await sensor.isHeaterOn());
    await sensor.setHeater(false);
    checkTrue('heater_off', !(await sensor.isHeaterOn()));
    await sensor.softReset();
    checkTrue('read_after_reset', (await sensor.read()).humidity_pct >= 0);

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
