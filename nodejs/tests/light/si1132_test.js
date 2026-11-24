'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Si1132Full } = require('../../packages/periph/src/chips/light/si1132');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x60', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new Si1132Full(connection);
    await sensor.init();

    const m = await sensor.read();
    checkTrue('read_uv_index_range', m.uv_index >= 0 && m.uv_index < 20);
    checkTrue('read_visible_nonneg', m.visible >= 0);
    checkTrue('read_infrared_nonneg', m.infrared >= 0);
    checkTrue('read_uv_index_single', (await sensor.readUvIndex()) >= 0);
    checkTrue('read_visible_single', (await sensor.readVisible()) >= 0);
    checkTrue('read_infrared_single', (await sensor.readInfrared()) >= 0);
    const raw = await sensor.readRaw();
    checkTrue('read_raw_keys', 'visible' in raw && 'infrared' in raw && 'aux' in raw);
    checkTrue('temperature_raw', (await sensor.readTemperatureRaw()) >= 0);
    checkTrue('vdd_raw', (await sensor.readVddRaw()) >= 0);
    await sensor.setGain(2, 2);
    checkTrue('gain_read', (await sensor.readVisible()) >= 0);
    await sensor.setGain(0, 0);
    await sensor.setHighRange(true, true);
    checkTrue('high_range_read', (await sensor.readVisible()) >= 0);
    await sensor.setHighRange(false, false);
    await sensor.setChannels(true, false, false);
    checkTrue('uv_only_read', (await sensor.read()).uv_index >= 0);
    await sensor.setChannels(true, true, true);
    checkTrue('calibration_length', (await sensor.readCalibration()).length === 12);
    await sensor.force();
    await sleep(50);
    checkTrue('measurement_ready', await sensor.measurementReady());
    checkTrue('response_no_error', (await sensor.readResponse()).error === 0);
    await sensor.pollInterrupt();
    await sensor.startAutonomous(0.1);
    await sleep(500);
    checkTrue('autonomous_ready', await sensor.measurementReady());
    await sensor.pause();
    await sensor.startAutonomous(0);
    await sensor.reset();
    checkTrue('read_after_reset', (await sensor.read()).visible >= 0);

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
