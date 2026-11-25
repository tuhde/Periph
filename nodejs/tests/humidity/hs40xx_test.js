'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { HS40xxFull, HS4001Full, HS4003Full } = require('../../packages/periph/src/chips/humidity/hs40xx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x54', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new HS40xxFull(connection);

    const d = await sensor.read();
    checkTrue('temperature range', d.temperature_c >= -40 && d.temperature_c <= 125);
    checkTrue('humidity range', d.humidity_pct >= 0 && d.humidity_pct <= 100);
    const t = await sensor.readTemperature();
    checkTrue('readTemperature range', t >= -40 && t <= 125);
    const rh = await sensor.readHumidity();
    checkTrue('readHumidity range', rh >= 0 && rh <= 100);
    const raw = await sensor.readRaw();
    checkTrue('raw ranges', raw.raw_humidity >= 0 && raw.raw_humidity <= 16383 && raw.raw_temperature >= 0 && raw.raw_temperature <= 16383);
    checkTrue('id nonzero', (await sensor.readId()) !== 0);

    await sensor.setResolution(12, 10);
    const res = await sensor.getResolution();
    checkTrue('resolution readback', res.humidity_bits === 12 && res.temperature_bits === 10);
    await sensor.setResolution(14, 14);

    await sensor.startPeriodic(0.5);
    await new Promise((r) => setTimeout(r, 1000));
    const p = await sensor.readPeriodic();
    checkTrue('periodic humidity', p.humidity_pct >= 0 && p.humidity_pct <= 100);
    await sensor.stopPeriodic();
    await sensor.softReset();

    const dp = await sensor.dewPoint();
    checkTrue('dew point plausible', dp > -60 && dp < 60);

    checkTrue('HS4001 alias', HS4001Full === HS40xxFull);
    checkTrue('HS4003 alias', HS4003Full === HS40xxFull);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
