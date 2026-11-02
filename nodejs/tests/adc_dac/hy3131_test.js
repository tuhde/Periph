'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { HY3131Full, HY3131Minimal } = require('../../packages/periph/src/chips/adc_dac/hy3131');

const SPI_BUS = parseInt(process.env.SPI_BUS || '0', 10);
const SPI_DEV = parseInt(process.env.SPI_DEV || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE0, maxSpeedHz: 1_000_000 });
    const adcMin = await HY3131Minimal.create(connection);
    const adc = await HY3131Full.create(connection);

    const raw = await adcMin.readRaw();
    checkTrue('Minimal.readRaw returns int', Number.isInteger(raw));
    checkTrue('Minimal.readRaw in 24-bit range', raw >= -8388608 && raw <= 8388607);
    checkTrue('Minimal.readVoltage returns number', typeof (await adcMin.readVoltage()) === 'number');
    checkTrue('Minimal.dataReady returns bool', typeof (await adcMin.dataReady()) === 'boolean');

    await adc.configureAd1({ osr: 4, chop: 2 });
    checkTrue('Full.readRaw after configureAd1', Number.isInteger(await adc.readRaw()));
    await adc.setAd1Input(10, 3, 2);
    await adc.setAd1Reference(1, 1);
    checkTrue('setAd1Input/Reference accepted', true);

    const tcode = await adc.readTemperatureCode();
    checkTrue('readTemperatureCode returns int', Number.isInteger(tcode));
    checkTrue('readTemperature returns number', typeof (await adc.readTemperature(tcode)) === 'number');

    await adc.enableAd2({ osr: 5, inputGain: 1 });
    const ad2 = await adc.readAd2Raw();
    checkTrue('Full.readAd2Raw in 19-bit range', ad2 >= -262144 && ad2 <= 262143);
    await adc.enableLpf(0);
    checkTrue('Full.readLpfRaw returns int', Number.isInteger(await adc.readLpfRaw()));
    await adc.enableRms();
    checkTrue('Full.readRms >= 0', (await adc.readRms()) >= 0);
    await adc.enablePeakHold(0);
    const pk = await adc.readPeak();
    checkTrue('Full.readPeak min <= max', pk.min <= pk.max);
    await adc.enableInterrupts(0x04);
    checkTrue('Full.readFlags returns int', Number.isInteger(await adc.readFlags()));
    await adc.reset();
    checkTrue('reset accepted', true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
