'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP085Full } = require('../../../src/chips/pressure/bmp085');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x77', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bmp = new BMP085Full(connection);                 // Create BMP085 driver, (connection, oss=0)

(async () => {
    const cid = await bmp.chipId();                      // Read chip ID, () → int
                                                      // returns 0x55 for BMP085
    console.log('chip_id=' + cid.toString(16));
    const oss = bmp.oversampling();                      // Read OSS, () → int 0–3
    console.log('oss=' + oss);
    bmp.setOversampling(BMP085Full.OSS_STANDARD);        // Set OSS, (oss 0–3) → None
                                                      // changes conversion time vs resolution trade-off
    const t = await bmp.temperature();                   // Read temperature, () → float C
    const p = await bmp.pressure();                      // Read pressure, () → float Pa
    const alt = await bmp.altitude();                    // Compute altitude, (sea_level_pa=101325.0) → float m
                                                      // uses barometric formula to convert pressure to metres
    const slp = await bmp.seaLevelPressure(alt);         // Compute sea-level pressure, (altitude_m) → float Pa
    await bmp.reset();                                   // Soft reset chip, () → None
                                                      // re-reads calibration after reset
    console.log(`T=${t.toFixed(1)} C, P=${p.toFixed(1)} Pa, alt=${alt.toFixed(1)} m, slp=${slp.toFixed(1)} Pa`);
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();