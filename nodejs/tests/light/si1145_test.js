'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Si1145Full } = require('../../packages/periph/src/chips/light/si1145');

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
    const sensor = new Si1145Full(connection);
    await sensor.init();
    const id = await sensor.readId();
    checkTrue('part_id', id.partId >= 0x45 && id.partId <= 0x47);
    checkTrue('response_no_error', (await sensor.readResponse()).error === 0);
    const m = await sensor.read();
    checkTrue('read_proximity_range', m.proximity >= 0 && m.proximity <= 0xFFFF);
    checkTrue('read_uv_index_range', m.uv_index >= 0 && m.uv_index < 20);
    checkTrue('read_visible_positive', m.visible > 0);
    checkTrue('read_infrared_positive', m.infrared > 0);
    const p = await sensor.readProximity();
    checkTrue('read_proximity_single', p >= 0 && p <= 0xFFFF);
    checkTrue('read_uv_index_single', (await sensor.readUvIndex()) < 20);
    checkTrue('read_visible_single', (await sensor.readVisible()) > 0);
    checkTrue('read_infrared_single', (await sensor.readInfrared()) > 0);
    checkTrue('read_raw_keys', Object.keys(await sensor.readRaw()).sort().join() === 'aux,infrared,ps1,ps2,ps3,visible');
    checkTrue('read_temperature_raw', (await sensor.readTemperatureRaw()) > 0);
    checkTrue('read_vdd_raw', (await sensor.readVddRaw()) > 0);
    await sensor.setLedCurrent(1, 0);
    const dark = await sensor.readProximity();
    await sensor.setLedCurrent(1, 15);
    const lit = await sensor.readProximity();
    checkTrue('led_current_effect', lit >= dark);
    await sensor.setLedCurrent(1, 3);
    await sensor.setPsPhotodiode(1, true);
    await sensor.setGain(2, 2, 0);
    checkTrue('gain_read', (await sensor.readVisible()) > 0);
    await sensor.setGain();
    await sensor.setHighRange(true, true, false);
    checkTrue('high_range_read', (await sensor.readVisible()) >= 0);
    await sensor.setHighRange(false, false, false);
    await sensor.setAlignment();
    await sensor.setUvCoefficients(Si1145Full.UCOEF_DEFAULT);
    checkTrue('read_calibration_len', (await sensor.readCalibration()).length === 12);
    await sensor.setChannels(true, true, true, true);
    await sensor.pollInterrupt();
    await sensor.startAutonomous(0.05);
    await sleep(500);
    checkTrue('autonomous_irq', ((await sensor.pollInterrupt()) & 0x05) !== 0);
    await sensor.pause();
    await sensor.force();
    await sleep(100);
    checkTrue('force_irq', (await sensor.pollInterrupt()) !== 0);
    await sensor.enableInterrupt(Si1145Full.SOURCE_CMD);
    await sensor.disableInterrupt(Si1145Full.SOURCE_CMD);
    await sensor.reset();
    checkTrue('reset_no_error', (await sensor.readResponse()).error === 0);
    checkTrue('read_after_reset', (await sensor.read()).visible > 0);
    await connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
