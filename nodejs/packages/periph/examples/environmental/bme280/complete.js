'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BME280Full } = require('../../../src/chips/environmental/bme280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bme = new BME280Full(connection);                 // Create BME280 driver, (connection, busType='i2c')

(async () => {
    const cid = await bme.chipId();                     // Read chip ID, () → number
                                                        // returns 0x60 for BME280
    await bme.configure(1, 1, 1, 0, 0, 0);             // Configure chip, (osrsT 0–5, osrsP 0–5, osrsH 0–5, mode 0/1/3, filter 0–4, tSb 0–7) → void
                                                        // writes ctrl_hum, config, ctrl_meas in correct order
    await bme.setOversampling(BME280Full.OSRS_X4, BME280Full.OSRS_X2, BME280Full.OSRS_X1);  // Set oversampling, (osrsT 0–5, osrsP 0–5, osrsH 0–5) → void
                                                        // humidity update requires ctrl_meas write to latch
    await bme.setMode(BME280Full.MODE_FORCED);          // Set power mode, (mode 0/1/3) → void
    await bme.setFilter(BME280Full.FILTER_4);           // Set IIR filter, (coeff 0–4) → void
                                                        // suppresses short-term pressure disturbances
    await bme.setStandby(BME280Full.T_SB_125_MS);       // Set standby time, (tSb 0–7) → void
                                                        // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
    const st = await bme.status();                      // Read status register, () → number
    const t = await bme.temperature();                  // Read temperature, () → number °C
    const p = await bme.pressure();                     // Read pressure, () → number hPa
    const h = await bme.humidity();                     // Read humidity, () → number %RH
    const alt = await bme.altitude();                   // Compute altitude, (seaLevelHpa=1013.25) → number m
                                                        // uses barometric formula to convert pressure to metres
    const slp = await bme.seaLevelPressure(alt);        // Compute sea-level pressure, (altitudeM) → number hPa
    const dp = await bme.dewPoint();                    // Compute dew point, () → number °C
                                                        // Magnus-Tetens approximation from current T and RH
    await bme.reset();                                  // Soft reset chip, () → void
                                                        // re-reads calibration and re-applies configuration
    console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} hPa, RH=${h.toFixed(1)} %RH, alt=${alt.toFixed(1)} m, dp=${dp.toFixed(1)} C`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
