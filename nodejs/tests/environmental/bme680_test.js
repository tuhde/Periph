'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { BME680Minimal, BME680Full } = require('../../packages/periph/src/chips/environmental/bme680');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

let passed = 0, failed = 0;

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bme = new BME680Minimal(transport);

const t = bme.temperature();
if (t >= -40 && t <= 85) { console.log('PASS temperature_range'); passed++; }
else { console.log('FAIL temperature_range: got ' + t); failed++; }

const p = bme.pressure();
if (p >= 300 && p <= 1100) { console.log('PASS pressure_range'); passed++; }
else { console.log('FAIL pressure_range: got ' + p); failed++; }

const h = bme.humidity();
if (h >= 0 && h <= 100) { console.log('PASS humidity_range'); passed++; }
else { console.log('FAIL humidity_range: got ' + h); failed++; }

const bmeFull = new BME680Full(transport);
if (bmeFull._osrsT === 1 && bmeFull._osrsP === 1 && bmeFull._osrsH === 1) { console.log('PASS default_oversampling'); passed++; }
else { console.log('FAIL default_oversampling'); failed++; }

bmeFull.setOversampling(BME680Full.OSRS_X4, BME680Full.OSRS_X2, BME680Full.OSRS_X1);
if (bmeFull._osrsT === 3 && bmeFull._osrsP === 2 && bmeFull._osrsH === 1) { console.log('PASS set_oversampling'); passed++; }
else { console.log('FAIL set_oversampling'); failed++; }

const cid = bmeFull.chipId();
if (cid === 0x61) { console.log('PASS chip_id'); passed++; }
else { console.log('FAIL chip_id: expected 0x61, got 0x' + cid.toString(16)); failed++; }

const r = bmeFull.readAll();
if (r.temperature >= -40 && r.temperature <= 85 && r.pressure >= 300 && r.pressure <= 1100 && r.humidity >= 0 && r.humidity <= 100) { console.log('PASS read_all'); passed++; }
else { console.log('FAIL read_all: T=' + r.temperature + ' P=' + r.pressure + ' H=' + r.humidity); failed++; }

bmeFull.reset();
console.log('PASS reset'); passed++;

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);
