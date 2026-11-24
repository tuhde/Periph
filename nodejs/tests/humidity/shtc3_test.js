'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { SHTC3Full } = require('../../packages/periph/src/chips/humidity/shtc3');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x70', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new SHTC3Full(connection);
    await sensor.init();

    const m = await sensor.read();
    checkTrue('read_temperature_range', m.temperature_c > -45 && m.temperature_c < 130);
    checkTrue('read_humidity_range', m.humidity_pct >= 0 && m.humidity_pct <= 100);
    const h = await sensor.readHumidity();
    checkTrue('read_humidity_single', h >= 0 && h <= 100);
    const t = await sensor.readTemperature();
    checkTrue('read_temperature_single', t > -45 && t < 130);
    const [tr, rr] = await sensor.readRaw();
    checkTrue('read_raw_range', tr >= 0 && tr <= 65535 && rr >= 0 && rr <= 65535);
    checkTrue('dew_point_below_temperature', (await sensor.dewPoint()) <= t + 1);
    checkTrue('read_id_product_code', ((await sensor.readId()) & 0x083F) === 0x0807);
    sensor.setLowPower(true);
    const lp = await sensor.read();
    checkTrue('low_power_read', lp.humidity_pct >= 0 && lp.humidity_pct <= 100 && lp.temperature_c > -45);
    sensor.setLowPower(false);
    sensor.setSleepBetween(false);
    checkTrue('read_without_sleep', (await sensor.read()).humidity_pct >= 0);
    checkTrue('read_without_sleep_again', (await sensor.read()).humidity_pct >= 0);
    await sensor.sleep();
    await sensor.wake();
    sensor.setSleepBetween(true);
    sensor.setClockStretching(true);
    checkTrue('read_with_clock_stretching', (await sensor.read()).humidity_pct >= 0);
    sensor.setClockStretching(false);
    await sensor.softReset();
    checkTrue('read_after_reset', (await sensor.read()).humidity_pct >= 0);

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
