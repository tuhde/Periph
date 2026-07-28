'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP180Full } = require('../../../src/chips/pressure/bmp180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x77', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bmp = new BMP180Full(connection, BMP180Full.OSS_ULP); // Create BMP180 driver, (connection, oss=0 ULP)

(async () => {
    const t0 = await bmp.temperature();                  // Read temperature, () → float C
    const p0 = await bmp.pressure();                     // Read pressure, () → float hPa
    const altRef = await bmp.altitude();                 // Compute altitude, (sea_level_hpa=1013.25) → float m
    console.log('Reference: ' + t0.toFixed(1) + ' C, ' + p0.toFixed(1) + ' hPa, alt=' + altRef.toFixed(1) + ' m');
    let prevAlt = 0.0;

    for (let n = 0; n < 60; n++) {
        const t = await bmp.temperature();               // Read temperature, () → float C
        const p = await bmp.pressure();                  // Read pressure, () → float hPa
        const a = await bmp.altitude();                  // Compute altitude, (sea_level_hpa=1013.25) → float m
        const da = (a - prevAlt) * 100;

        if (n > 0) {
            console.log(n + 's: ' + t.toFixed(1) + ' C, ' + p.toFixed(1) + ' hPa, alt=' + a.toFixed(1) + ' m (delta=' + da.toFixed(0) + ' cm)');
        } else {
            console.log(n + 's: ' + t.toFixed(1) + ' C, ' + p.toFixed(1) + ' hPa, alt=' + a.toFixed(1) + ' m');
        }
        prevAlt = a;
    }

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
