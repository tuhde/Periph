'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ADS1014Minimal, ADS1014Full } = require('../../packages/periph/src/chips/adc_dac/ads1014');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x48', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const adc = new ADS1014Minimal(connection);
    checkTrue('construct minimal', adc instanceof ADS1014Minimal);
    const raw = await adc.readRaw();
    checkTrue('raw in range', raw >= -2048 && raw <= 2047);
    checkTrue('voltage matches raw', Math.abs((await adc.readVoltage()) - 0.001 * raw) < 0.05);

    const full = new ADS1014Full(connection);
    await full.reset();
    checkTrue('reset config', ((await full.readConfig()) & 0x7FFF) === 0x0583);

    await full.setGain(ADS1014Full.PGA_0_256);
    checkTrue('gain roundtrip', full.getGain() === ADS1014Full.PGA_0_256 && full.fullScale() === 0.256);
    await full.readRaw();
    checkTrue('config gain bits', (((await full.readConfig()) >> 9) & 0x7) === ADS1014Full.PGA_0_256);
    await full.setGain(ADS1014Full.PGA_2_048);

    await full.setDataRate(ADS1014Full.DR_250);
    checkTrue('dr roundtrip', full.getDataRate() === ADS1014Full.DR_250 && full.dataRateSps() === 250);
    await full.readRaw();
    checkTrue('config dr bits', (((await full.readConfig()) >> 5) & 0x7) === ADS1014Full.DR_250);
    await full.setDataRate(ADS1014Full.DR_1600);

    await full.startContinuous();
    await sleep(50);
    const cont = await full.readContinuousRaw();
    checkTrue('continuous raw in range', cont >= -2048 && cont <= 2047);
    checkTrue('continuous mode bit', (await full.readConfig() & 0x0100) === 0);
    await full.stopContinuous();
    checkTrue('stop mode bit', ((await full.readConfig()) & 0x0100) !== 0);

    await full.setLowThresholdRaw(-100);
    await full.setHighThresholdRaw(300);
    checkTrue('threshold roundtrip', (await full.getThresholdsRaw()).join() === '-100,300');
    await full.setThresholds(-0.2, 0.5);
    checkTrue('threshold volts roundtrip', (await full.getThresholdsRaw()).join() === '-200,500');

    await full.configureComparator(ADS1014Full.COMP_WINDOW, ADS1014Full.ACTIVE_HIGH, true, ADS1014Full.QUEUE_2);
    checkTrue('comparator bits', ((await full.readConfig()) & 0x1F) === (0x10 | 0x08 | 0x04 | 0x01));
    await full.clearAlert();
    await full.disableComparator();
    checkTrue('comparator disabled', ((await full.readConfig()) & 0x3) === 3);

    await full.enableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);
    checkTrue('ready thresholds', (await full.getThresholdsRaw()).join() === '0,-2048');
    checkTrue('ready que 00', ((await full.readConfig()) & 0x3) === 0);
    await full.disableInterrupt(ADS1014Full.SOURCE_CONVERSION_READY);

    await full.reset();
    checkTrue('final reset', ((await full.readConfig()) & 0x7FFF) === 0x0583);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
