'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MS5611Full } = require('../../packages/periph/src/chips/pressure/ms5611');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x77', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new MS5611Full(connection);
    await sensor.init();
    checkTrue('prom_crc_ok', sensor.promCrcOk());

    const m = await sensor.read();
    checkTrue('read_temperature_range', m.temperature_c > -40 && m.temperature_c < 85);
    checkTrue('read_pressure_range', m.pressure_pa > 30000 && m.pressure_pa < 120000);
    const p = await sensor.readPressure();
    checkTrue('read_pressure_single', p > 30000 && p < 120000);
    const t = await sensor.readTemperature();
    checkTrue('read_temperature_single', t > -40 && t < 85);
    const [d1, d2] = await sensor.readRaw();
    checkTrue('read_raw_range', d1 > 0 && d1 < 16777216 && d2 > 0 && d2 < 16777216);
    checkTrue('compensate_matches_read', Math.abs(sensor.compensate(d1, d2).pressure_pa - m.pressure_pa) < 200);
    checkTrue('coefficients_nonzero', sensor.coefficients().every((c) => c > 0 && c < 65536));
    checkTrue('serial_code_range', sensor.serialCode() >= 0 && sensor.serialCode() < 4096);
    checkTrue('read_prom_length', (await sensor.readProm()).length === 8);
    for (const osr of [256, 512, 1024, 2048, 4096]) {
        sensor.setOversampling(osr);
        checkTrue('osr_' + osr + '_roundtrip', sensor.getOversampling() === osr);
        const v = await sensor.readPressure();
        checkTrue('osr_' + osr + '_read', v > 30000 && v < 120000);
    }
    sensor.setOversampling(4096);
    await sensor.startConversion(MS5611Full.TEMPERATURE);
    await sleep(sensor.conversionTime() * 1000 + 2);
    checkTrue('non_blocking_adc', (await sensor.readAdc()) > 0);
    const alt = await sensor.altitude();
    checkTrue('altitude_plausible', alt > -500 && alt < 6000);
    await sensor.reset();
    checkTrue('read_after_reset', (await sensor.readPressure()) > 30000);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(err => { console.error(err); process.exit(1); });
