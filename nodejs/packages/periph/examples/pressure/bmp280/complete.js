'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP280Full } = require('../../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP280Full(transport);                   // Create BMP280 driver, (transport, busType='i2c')
const cid = bmp.chipId();                              // Read chip ID, () → number
                                                        // returns 0x58 for BMP280
console.log('chip_id=' + cid.toString(16));
bmp.configure(BMP280Full.OSRS_X1, BMP280Full.OSRS_X1, BMP280Full.MODE_FORCED, BMP280Full.FILTER_OFF, BMP280Full.T_SB_0_5_MS);  // Configure chip, (osrsT 0–5, osrsP 0–5, mode 0/1/3, filter 0–4, tSb 0–7) → undefined
                                                        // writes ctrl_meas and config registers
bmp.setOversampling(BMP280Full.OSRS_X4, BMP280Full.OSRS_X2);  // Set oversampling, (osrsT 0–5, osrsP 0–5) → undefined
                                                        // changes conversion time vs resolution trade-off
bmp.setMode(BMP280Full.MODE_FORCED);                    // Set power mode, (mode 0/1/3) → undefined
bmp.setFilter(BMP280Full.FILTER_4);                     // Set IIR filter, (coeff 0–4) → undefined
                                                        // suppresses short-term pressure disturbances
bmp.setStandby(BMP280Full.T_SB_125_MS);                 // Set standby time, (tSb 0–7) → undefined
                                                        // only relevant in normal mode
const st = bmp.status();                               // Read status register, () → number
const t = bmp.temperature();                            // Read temperature, () → number °C
const p = bmp.pressure();                              // Read pressure, () → number hPa
const alt = bmp.altitude();                            // Compute altitude, (seaLevelHpa=1013.25) → number m
                                                        // uses barometric formula to convert pressure to metres
const slp = bmp.seaLevelPressure(alt);                 // Compute sea-level pressure, (altitudeM) → number hPa
bmp.reset();                                           // Soft reset chip, () → undefined
                                                        // re-reads calibration and re-applies configuration
console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} hPa, alt=${alt.toFixed(1)} m, slp=${slp.toFixed(1)} hPa`);
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
