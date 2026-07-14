'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { BME280Minimal, BME280Full } = require('../../packages/periph/src/chips/environmental/bme280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

let passed = 0, failed = 0;

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bme = new BME280Minimal(transport);

bme._digT1 = 27504;
bme._digT2 = 26435;
bme._digT3 = -1000;
bme._digP1 = 36477;
bme._digP2 = -10685;
bme._digP3 = 3024;
bme._digP4 = 2855;
bme._digP5 = 140;
bme._digP6 = -7;
bme._digP7 = 15500;
bme._digP8 = -14600;
bme._digP9 = 6000;

const t = bme._compensateTemp(519888);
if (Math.abs(t - 25.08) < 0.1) { console.log('PASS temperature_compensation'); passed++; }
else { console.log('FAIL temperature_compensation: expected 25.08, got ' + t); failed++; }

const p = bme._compensatePressure(415148);
if (Math.abs(p - 1006.53) < 0.5) { console.log('PASS pressure_compensation'); passed++; }
else { console.log('FAIL pressure_compensation: expected 1006.53, got ' + p); failed++; }

bme._digH1 = 75;
bme._digH2 = 362;
bme._digH3 = 0;
bme._digH4 = 341;
bme._digH5 = 50;
bme._digH6 = 30;
const h = bme._compensateHumidity(29000);
if (h >= 30.0 && h <= 70.0) { console.log('PASS humidity_compensation'); passed++; }
else { console.log('FAIL humidity_compensation: expected 30-70 %RH, got ' + h); failed++; }

const bmeFull = new BME280Full(transport);
if (bmeFull._osrsT === 1 && bmeFull._osrsP === 1 && bmeFull._osrsH === 1) { console.log('PASS default_oversampling'); passed++; }
else { console.log('FAIL default_oversampling'); failed++; }

bmeFull.setOversampling(BME280Full.OSRS_X4, BME280Full.OSRS_X2, BME280Full.OSRS_X1);
if (bmeFull._osrsT === 3 && bmeFull._osrsP === 2 && bmeFull._osrsH === 1) { console.log('PASS set_oversampling'); passed++; }
else { console.log('FAIL set_oversampling'); failed++; }

const alt = bmeFull.altitude();
if (alt >= -500 && alt <= 9000) { console.log('PASS altitude'); passed++; }
else { console.log('FAIL altitude: got ' + alt); failed++; }

const slp = bmeFull.seaLevelPressure(0);
if (slp >= 900 && slp <= 1100) { console.log('PASS sea_level_pressure'); passed++; }
else { console.log('FAIL sea_level_pressure: got ' + slp); failed++; }

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);
