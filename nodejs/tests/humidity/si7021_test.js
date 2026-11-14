'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { Si7021Full } = require('../../packages/periph/src/chips/humidity/si7021');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x40', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const sensor = new Si7021Full(connection);

    await sensor.softReset();
    checkTrue('device type', (await sensor.deviceType()) === 0x15);
    checkTrue('serial number', (await sensor.serialNumber()) > 0n);
    checkTrue('user register after reset', ((await sensor.readUserRegister()) & 0xBF) === 0x3A);

    const [t, rh] = await sensor.read();
    checkTrue('temperature range', t > -40 && t < 125);
    checkTrue('humidity range', rh >= 0 && rh <= 100);
    checkTrue('readHumidity range', (await sensor.readHumidity()) >= 0);
    checkTrue('readTemperature range', (await sensor.readTemperature()) > -40);
    checkTrue('readTemperaturePrevious range', (await sensor.readTemperaturePrevious()) > -40);
    checkTrue('raw humidity range', (await sensor.readRawHumidity()) <= 65535);
    checkTrue('raw temperature range', (await sensor.readRawTemperature()) <= 65535);

    for (const [rb, tb] of [[12, 14], [8, 12], [10, 13], [11, 11]]) {
        await sensor.setResolution(rb, tb);
        const res = await sensor.getResolution();
        checkTrue('resolution ' + rb + '/' + tb, res[0] === rb && res[1] === tb);
        const [t2, rh2] = await sensor.read();
        checkTrue('read at ' + rb + '/' + tb, t2 > -40 && t2 < 125 && rh2 >= 0 && rh2 <= 100);
    }
    await sensor.setResolution(12, 14);
    const ct = await sensor.conversionTime();
    checkTrue('conversion time', ct > 0.005 && ct < 0.03);

    await sensor.setHeater(true);
    checkTrue('heater on', await sensor.isHeaterOn());
    await sensor.setHeater(false);
    checkTrue('heater off', !(await sensor.isHeaterOn()));
    checkTrue('vdd ok', !(await sensor.vddLow()));
    const fw = await sensor.firmwareRevision();
    checkTrue('firmware readable', fw >= 0 && fw <= 0xFF);

    const dp = await sensor.dewPoint();
    checkTrue('dew point plausible', dp > -60 && dp < 60);
    await sensor.softReset();
    checkTrue('read after software reset', (await sensor.read())[1] >= 0);

    connection.close();
    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
