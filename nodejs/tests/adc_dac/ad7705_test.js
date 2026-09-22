'use strict';

const spi = require('spi-device');
const { SPIConnection } = require('../../packages/periph/src/connection/spi');
const { AD7705Full, AD7705Minimal } = require('../../packages/periph/src/chips/adc_dac/ad7705');

const SPI_BUS  = parseInt(process.env.SPI_BUS  || '0', 10);
const SPI_DEV  = parseInt(process.env.SPI_DEV  || '0', 10);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new SPIConnection(SPI_BUS, SPI_DEV, { mode: spi.MODE3, maxSpeedHz: 1_000_000 });
    const adcMin = new AD7705Minimal(connection, 2.5, 2_457_600);
    const adc = new AD7705Full(connection, 2.5, 2_457_600);

    const raw = await adcMin.readRaw();
    checkTrue('Minimal.readRaw returns int', Number.isInteger(raw));
    checkTrue('Minimal.readRaw in [0, 65535]', raw >= 0 && raw <= 65535);

    const v = await adcMin.readVoltage();
    checkTrue('Minimal.readVoltage returns number', typeof v === 'number');
    checkTrue('Minimal.readVoltage in [-VREF, +VREF]', v >= -2.5 && v <= 2.5);

    const raw1 = await adc.readRawChannel(1);
    checkTrue('Full.readRaw(1) returns int', Number.isInteger(raw1));
    checkTrue('Full.readRaw(1) in [0, 65535]', raw1 >= 0 && raw1 <= 65535);

    const v1 = await adc.readVoltageChannel(1);
    checkTrue('Full.readVoltage(1) returns number', typeof v1 === 'number');

    const raw2 = await adc.readRawChannel(2);
    checkTrue('Full.readRaw(2) returns int', Number.isInteger(raw2));
    checkTrue('Full.readRaw(2) in [0, 65535]', raw2 >= 0 && raw2 <= 65535);

    const v2 = await adc.readVoltageChannel(2);
    checkTrue('Full.readVoltage(2) returns number', typeof v2 === 'number');

    await adc.configure(1, 2, true, false, 60);
    checkTrue('configure(1, gain=2) accepted', true);
    await adc.configure(2, 4, false, true, 60);
    checkTrue('configure(2, gain=4) accepted', true);
    await adc.configure(1, 128, true, true, 50);
    checkTrue('configure(1, gain=128) accepted', true);

    await adc.selfCalibrate(1);
    checkTrue('selfCalibrate(1) accepted', true);
    await adc.selfCalibrate(2);
    checkTrue('selfCalibrate(2) accepted', true);

    await adc.systemCalibrateZero(1);
    checkTrue('systemCalibrateZero(1) accepted', true);
    await adc.systemCalibrateFull(1);
    checkTrue('systemCalibrateFull(1) accepted', true);

    const off1 = await adc.getOffsetCalibration(1);
    checkTrue('getOffsetCalibration(1) returns int', Number.isInteger(off1));
    checkTrue('getOffsetCalibration(1) in [0, 2**24-1]', off1 >= 0 && off1 <= 0xFFFFFF);
    await adc.setOffsetCalibration(off1, 1);
    checkTrue('setOffsetCalibration(1) accepted', true);

    const gain1 = await adc.getGainCalibration(1);
    checkTrue('getGainCalibration(1) returns int', Number.isInteger(gain1));
    checkTrue('getGainCalibration(1) in [0, 2**24-1]', gain1 >= 0 && gain1 <= 0xFFFFFF);
    await adc.setGainCalibration(gain1, 1);
    checkTrue('setGainCalibration(1) accepted', true);

    const off2 = await adc.getOffsetCalibration(2);
    checkTrue('getOffsetCalibration(2) returns int', Number.isInteger(off2));
    const gain2 = await adc.getGainCalibration(2);
    checkTrue('getGainCalibration(2) returns int', Number.isInteger(gain2));

    await adc.standby();
    checkTrue('standby accepted', true);
    await adc.wakeup();
    checkTrue('wakeup accepted', true);

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
