'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { RRH62000Full } = require('../../packages/periph/src/chips/gas/rrh62000');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x69', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new RRH62000Full(connection);
    await sensor.init();
    checkTrue('firmware_version', (await sensor.firmwareVersion()).length === 2);
    checkTrue('algorithm_version', (await sensor.algorithmVersion()).length === 3);
    checkTrue('unique_id', (await sensor.uniqueId()).length === 6);
    const r = await sensor.read();
    checkTrue('read_pm', r.pm2_5_ug_m3 >= 0 && r.pm2_5_ug_m3 <= 1000);
    checkTrue('read_temperature', r.temperature_c >= -20 && r.temperature_c <= 60);
    checkTrue('read_humidity', r.humidity_rh >= 0 && r.humidity_rh <= 100);
    checkTrue('read_iaq', r.iaq >= 0 && r.iaq <= 5);
    checkTrue('read_eco2', r.eco2_ppm === 0 || (r.eco2_ppm >= 400 && r.eco2_ppm <= 5000));
    const a = await sensor.readAll();
    checkTrue('read_all_nc', a.nc_0_3 >= a.nc_0_5 && a.nc_0_5 >= a.nc_1);
    checkTrue('read_status_fan_ok', !(await sensor.readStatus()).fan);
    checkTrue('read_particles', Object.keys(await sensor.readParticles()).length === 11);
    checkTrue('data_ready', typeof (await sensor.dataReady()) === 'boolean');
    const avg = await sensor.getMovingAverage();
    await sensor.setMovingAverage(20);
    checkTrue('moving_average', (await sensor.getMovingAverage()) === 20);
    await sensor.setMovingAverage(avg);
    const fan = await sensor.getFanSpeed();
    await sensor.setFanSpeed(70);
    checkTrue('fan_speed', (await sensor.getFanSpeed()) === 70);
    await sensor.setFanSpeed(fan);
    const interval = await sensor.getCleaningInterval();
    await sensor.setCleaningInterval(86400);
    checkTrue('cleaning_interval', (await sensor.getCleaningInterval()) === 86400);
    await sensor.setCleaningInterval(interval);
    const t = await sensor.getCleaningTime();
    await sensor.setCleaningTime(12);
    checkTrue('cleaning_time', (await sensor.getCleaningTime()) === 12);
    await sensor.setCleaningTime(t);
    checkTrue('mox_resistance', (await sensor.readMoxResistance()) > 0);
    checkTrue('cleaning_done', typeof (await sensor.cleaningDone()) === 'boolean');
    await sensor.sleep();
    await sleep(3000);
    await sensor.wake();
    checkTrue('wake_read', 'iaq' in (await sensor.read()));
    await sensor.softReset();
    checkTrue('soft_reset_read', 'iaq' in (await sensor.read()));

    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
