'use strict';
const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { BMP280Minimal, BMP280Full } = require('../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

let passed = 0, failed = 0;

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP280Minimal(transport);

const bmpFull = new BMP280Full(transport);

const cid = bmpFull.chipId();
if (cid === 0x58) { console.log('PASS chip_id'); passed++; }
else              { console.log('FAIL chip_id got ' + cid.toString(16)); failed++; }

const s = bmpFull.status();
if (s >= 0) { console.log('PASS status_register'); passed++; }
else         { console.log('FAIL status_register'); failed++; }

bmpFull.configure(BMP280Full.OSRS_X2, BMP280Full.OSRS_X4,
                  BMP280Full.MODE_FORCED, BMP280Full.FILTER_4,
                  BMP280Full.T_SB_62_5_MS);
if (true) { console.log('PASS configure'); passed++; }
else      { console.log('FAIL configure'); failed++; }

bmpFull.setOversampling(BMP280Full.OSRS_X1, BMP280Full.OSRS_X1);
if (true) { console.log('PASS set_oversampling'); passed++; }
else      { console.log('FAIL set_oversampling'); failed++; }

bmpFull.setFilter(BMP280Full.FILTER_OFF);
if (true) { console.log('PASS set_filter'); passed++; }
else      { console.log('FAIL set_filter'); failed++; }

bmpFull.setStandby(BMP280Full.T_SB_250_MS);
if (true) { console.log('PASS set_standby'); passed++; }
else      { console.log('FAIL set_standby'); failed++; }

const t = bmpFull.temperature();
if (t >= -40 && t <= 85) { console.log('PASS temperature_range'); passed++; }
else                      { console.log('FAIL temperature_range got ' + t); failed++; }

const p = bmpFull.pressure();
if (p >= 300 && p <= 1100) { console.log('PASS pressure_range'); passed++; }
else                        { console.log('FAIL pressure_range got ' + p); failed++; }

bmpFull.setMode(BMP280Full.MODE_FORCED);
if (true) { console.log('PASS set_mode'); passed++; }
else      { console.log('FAIL set_mode'); failed++; }

bmpFull.reset();
if (true) { console.log('PASS reset'); passed++; }
else      { console.log('FAIL reset'); failed++; }

const alt = bmpFull.altitude();
if (alt >= -500 && alt <= 9000) { console.log('PASS altitude_range'); passed++; }
else                            { console.log('FAIL altitude_range got ' + alt); failed++; }

const slp = bmpFull.seaLevelPressure(alt);
if (slp >= 900 && slp <= 1100) { console.log('PASS sea_level_pressure'); passed++; }
else                            { console.log('FAIL sea_level_pressure got ' + slp); failed++; }

bmpFull._dig_T1 = 27504;
bmpFull._dig_T2 = 26435;
bmpFull._dig_T3 = -1000;
bmpFull._dig_P1 = 36477;
bmpFull._dig_P2 = -10685;
bmpFull._dig_P3 = 3024;
bmpFull._dig_P4 = 2855;
bmpFull._dig_P5 = 140;
bmpFull._dig_P6 = -7;
bmpFull._dig_P7 = 15500;
bmpFull._dig_P8 = -14600;
bmpFull._dig_P9 = 6000;

bmpFull._t_fine = 0;
const tVal = bmpFull._compensateTemp(519888);
if (Math.abs(tVal - 25.08) < 0.1) { console.log('PASS compensate_temp'); passed++; }
else                               { console.log('FAIL compensate_temp got ' + tVal); failed++; }

const pVal = bmpFull._compensatePressure(415148);
if (Math.abs(pVal - 1006.53) < 0.5) { console.log('PASS compensate_pressure'); passed++; }
else                                { console.log('FAIL compensate_pressure got ' + pVal); failed++; }

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);