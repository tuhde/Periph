'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { BME280Full } = require('../../../src/chips/environmental/bme280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bme = new BME280Full(transport);                 // Create BME280 driver, (transport, busType='i2c')
const cid = bme.chipId();                              // Read chip ID, () → number
                                                        // returns 0x60 for BME280
bme.configure(1, 1, 1, 0, 0, 0);                       // Configure chip, (osrsT 0–5, osrsP 0–5, osrsH 0–5, mode 0/1/3, filter 0–4, tSb 0–7) → void
                                                        // writes ctrl_hum, config, ctrl_meas in correct order
bme.setOversampling(BME280Full.OSRS_X4, BME280Full.OSRS_X2, BME280Full.OSRS_X1);  // Set oversampling, (osrsT 0–5, osrsP 0–5, osrsH 0–5) → void
                                                        // humidity update requires ctrl_meas write to latch
bme.setMode(BME280Full.MODE_FORCED);                   // Set power mode, (mode 0/1/3) → void
bme.setFilter(BME280Full.FILTER_4);                    // Set IIR filter, (coeff 0–4) → void
                                                        // suppresses short-term pressure disturbances
bme.setStandby(BME280Full.T_SB_125_MS);                // Set standby time, (tSb 0–7) → void
                                                        // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
const st = bme.status();                               // Read status register, () → number
const t = bme.temperature();                           // Read temperature, () → number °C
const p = bme.pressure();                              // Read pressure, () → number hPa
const h = bme.humidity();                              // Read humidity, () → number %RH
const alt = bme.altitude();                            // Compute altitude, (seaLevelHpa=1013.25) → number m
                                                        // uses barometric formula to convert pressure to metres
const slp = bme.seaLevelPressure(alt);                 // Compute sea-level pressure, (altitudeM) → number hPa
const dp = bme.dewPoint();                             // Compute dew point, () → number °C
                                                        // Magnus-Tetens approximation from current T and RH
bme.reset();                                           // Soft reset chip, () → void
                                                        // re-reads calibration and re-applies configuration
console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} hPa, RH=${h.toFixed(1)} %RH, alt=${alt.toFixed(1)} m, dp=${dp.toFixed(1)} C`);
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
