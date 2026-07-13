'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { BMP280Minimal, BMP280Full } = require('../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

let passed = 0, failed = 0;

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP280Minimal(transport);

bmp._digT1 = 27504;
bmp._digT2 = 26435;
bmp._digT3 = -1000;
bmp._digP1 = 36477;
bmp._digP2 = -10685;
bmp._digP3 = 3024;
bmp._digP4 = 2855;
bmp._digP5 = 140;
bmp._digP6 = -7;
bmp._digP7 = 15500;
bmp._digP8 = -14600;
bmp._digP9 = 6000;

const t = bmp._compensateTemp(519888);
if (Math.abs(t - 25.08) < 0.1) { console.log('PASS temperature_compensation'); passed++; }
else { console.log('FAIL temperature_compensation: expected 25.08, got ' + t); failed++; }

const p = bmp._compensatePressure(415148);
if (Math.abs(p - 1006.53) < 0.5) { console.log('PASS pressure_compensation'); passed++; }
else { console.log('FAIL pressure_compensation: expected 1006.53, got ' + p); failed++; }

const bmpFull = new BMP280Full(transport);
if (bmpFull._osrsT === 1 && bmpFull._osrsP === 1) { console.log('PASS default_oversampling'); passed++; }
else { console.log('FAIL default_oversampling'); failed++; }

bmpFull.setOversampling(BMP280Full.OSRS_X4, BMP280Full.OSRS_X2);
if (bmpFull._osrsT === 3 && bmpFull._osrsP === 2) { console.log('PASS set_oversampling'); passed++; }
else { console.log('FAIL set_oversampling'); failed++; }

const alt = bmpFull.altitude();
if (alt >= -500 && alt <= 9000) { console.log('PASS altitude'); passed++; }
else { console.log('FAIL altitude: got ' + alt); failed++; }

const slp = bmpFull.seaLevelPressure(0);
if (slp >= 900 && slp <= 1100) { console.log('PASS sea_level_pressure'); passed++; }
else { console.log('FAIL sea_level_pressure: got ' + slp); failed++; }

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);
