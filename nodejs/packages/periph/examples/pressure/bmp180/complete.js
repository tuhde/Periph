'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP180Full } = require('../../../packages/periph/src/chips/pressure/bmp180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x77', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP180Full(transport);                 // Create BMP180 driver, (transport, oss=0)
const cid = bmp.chipId();                            // Read chip ID, () → int
                                                      // returns 0x55 for BMP180
console.log('chip_id=' + cid.toString(16));
const oss = bmp.oversampling();                      // Read OSS, () → int 0–3
console.log('oss=' + oss);
bmp.setOversampling(BMP180Full.OSS_STANDARD);        // Set OSS, (oss 0–3) → None
                                                      // changes conversion time vs resolution trade-off
const t = bmp.temperature();                          // Read temperature, () → float C
const p = bmp.pressure();                            // Read pressure, () → float hPa
const alt = bmp.altitude();                        // Compute altitude, (sea_level_hpa=1013.25) → float m
                                                      // uses barometric formula to convert pressure to metres
const slp = bmp.seaLevelPressure(alt);              // Compute sea-level pressure, (altitude_m) → float hPa
bmp.reset();                                       // Soft reset chip, () → None
                                                      // re-reads calibration after reset
console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} hPa, alt=${alt.toFixed(1)} m, slp=${slp.toFixed(1)} hPa`);
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
