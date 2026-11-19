'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HS30xxFull, HS3001Full, HS3002Full, HS3003Full } = require('../../packages/periph/src/chips/humidity/hs30xx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x44', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new HS30xxFull(connection);

    const d = await sensor.read();
    checkTrue('temperature range', d.temperature_c >= -40 && d.temperature_c <= 125);
    checkTrue('humidity range', d.humidity_pct >= 0 && d.humidity_pct <= 100);
    const t = await sensor.readTemperature();
    checkTrue('readTemperature range', t >= -40 && t <= 125);
    const rh = await sensor.readHumidity();
    checkTrue('readHumidity range', rh >= 0 && rh <= 100);
    const raw = await sensor.readRaw();
    checkTrue('raw ranges', raw.raw_humidity >= 0 && raw.raw_humidity <= 16383 && raw.raw_temperature >= 0 && raw.raw_temperature <= 16383);
    checkTrue('raw status valid', raw.status === 0);

    sensor.setMeasurementWait(60);
    const rh2 = await sensor.readHumidity();
    checkTrue('read with 60 ms wait', rh2 >= 0 && rh2 <= 100);
    sensor.setMeasurementWait(40);

    const dp = await sensor.dewPoint();
    checkTrue('dew point plausible', dp > -60 && dp < 60);

    checkTrue('HS3001 alias', HS3001Full === HS30xxFull);
    checkTrue('HS3002 alias', HS3002Full === HS30xxFull);
    checkTrue('HS3003 alias', HS3003Full === HS30xxFull);

    // NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
