'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { BH1750Full } = require('../../packages/periph/src/chips/light/bh1750');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x23', 16);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const inRange = (v) => v >= 0 && v <= 120000;

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new BH1750Full(connection);
    await sensor.init();

    checkTrue('read_range', inRange(await sensor.read()));
    const raw = await sensor.readRaw();
    checkTrue('read_raw_range', raw >= 0 && raw <= 65535);
    checkTrue('measure_once_h', inRange(await sensor.measureOnce(BH1750Full.MODE_H)));
    checkTrue('measure_once_h2', inRange(await sensor.measureOnce(BH1750Full.MODE_H2)));
    checkTrue('measure_once_l', inRange(await sensor.measureOnce(BH1750Full.MODE_L)));
    checkTrue('lux_per_count_default', Math.abs(sensor.luxPerCount() - 1 / 1.2) < 1e-9);
    checkTrue('measurement_time_default', Math.abs(sensor.measurementTime() - 0.180) < 1e-9);
    await sensor.startContinuous(BH1750Full.MODE_H);
    await sleep(sensor.measurementTime() * 1000 + 20);
    checkTrue('read_continuous_range', inRange(await sensor.readContinuous()));
    await sensor.setMeasurementTime(138);
    checkTrue('mtreg_roundtrip', sensor.getMeasurementTimeRegister() === 138);
    checkTrue('measurement_time_scaled', Math.abs(sensor.measurementTime() - 0.360) < 1e-9);
    checkTrue('read_with_mtreg', inRange(await sensor.measureOnce(BH1750Full.MODE_H)));
    await sensor.setSensitivity(1.0);
    checkTrue('sensitivity_default', sensor.getMeasurementTimeRegister() === 69);
    await sensor.powerDown();
    await sensor.powerOn();
    await sensor.reset();
    checkTrue('read_after_reset', inRange(await sensor.measureOnce()));
    await sensor.powerDown();

    connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
