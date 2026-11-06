'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ADS1114Minimal, ADS1114Full } = require('../../packages/periph/src/chips/adc_dac/ads1114');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x48', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new ADS1114Minimal(connection);
    const raw = await sensor.readRaw();
    const raw2 = await sensor.readRaw();
    checkTrue('read_raw_stable', Math.abs(raw - raw2) < 4000);
    const v = await sensor.readVoltage();
    checkTrue('read_voltage_matches_raw', Math.abs(v - raw * 0.0000625) < 0.25);

    const full = new ADS1114Full(connection);
    await full.setDataRate(ADS1114Full.DR_860);
    checkTrue('data_rate_roundtrip', full.getDataRate() === 7 && full.dataRateSps() === 860);
    await full.setGain(ADS1114Full.PGA_4_096);
    checkTrue('gain_roundtrip', full.getGain() === 1 && full.fullScale() === 4.096);
    await full.setGain(ADS1114Full.PGA_2_048);
    await full.startContinuous();
    await new Promise((r) => setTimeout(r, 50));
    const cont = await full.readContinuousRaw();
    checkTrue('continuous_matches_single_shot', Math.abs(cont - raw) < 8000);
    checkTrue('continuous_config_mode', ((await full.readConfig()) & 0x0100) === 0);
    await full.stopContinuous();
    checkTrue('single_shot_config_mode', ((await full.readConfig()) & 0x0100) !== 0);
    await full.setLowThresholdRaw(-1000);
    await full.setHighThresholdRaw(1000);
    const [lo, hi] = await full.getThresholdsRaw();
    checkTrue('threshold_roundtrip', lo === -1000 && hi === 1000);
    await full.configureComparator(ADS1114Full.COMP_WINDOW, ADS1114Full.ACTIVE_LOW, false, ADS1114Full.QUEUE_4);
    checkTrue('comparator_bits', ((await full.readConfig()) & 0x0017) === 0x0012);
    await full.disableComparator();
    checkTrue('comparator_disabled', ((await full.readConfig()) & 0x0003) === 0x0003);
    await full.reset();
    checkTrue('reset_config', ((await full.readConfig()) & 0x7FFF) === 0x0583);

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
