'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { AHT21Full }    = require('../../packages/periph/src/chips/environmental/aht21');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const aht = new AHT21Full(transport);

checkTrue('is_calibrated', aht.isCalibrated());
checkTrue('not busy at idle', !aht.isBusy());

const r = aht.read();
checkTrue('temperature range', r.temperature_c >= -40.0 && r.temperature_c <= 120.0);
checkTrue('humidity range', r.humidity_pct >= 0.0 && r.humidity_pct <= 100.0);

const tr = aht.readTemperature();
checkTrue('readTemperature range', tr >= -40.0 && tr <= 120.0);

const hr = aht.readHumidity();
checkTrue('readHumidity range', hr >= 0.0 && hr <= 100.0);

const rc = aht.readWithCrc();
checkTrue('crc_ok', rc.crc_ok);
checkTrue('crc temperature range', rc.temperature_c >= -40.0 && rc.temperature_c <= 120.0);
checkTrue('crc humidity range', rc.humidity_pct >= 0.0 && rc.humidity_pct <= 100.0);

aht.softReset();
const end = Date.now() + 50;
while (Date.now() < end) {}
checkTrue('calibrated after reset', aht.isCalibrated());

const r2 = aht.read();
checkTrue('read after reset: temperature range', r2.temperature_c >= -40.0 && r2.temperature_c <= 120.0);
checkTrue('read after reset: humidity range', r2.humidity_pct >= 0.0 && r2.humidity_pct <= 100.0);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
