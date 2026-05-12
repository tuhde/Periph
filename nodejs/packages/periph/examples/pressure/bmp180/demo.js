'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP180Full } = require('../../../packages/periph/src/chips/pressure/bmp180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x77', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP180Full(transport, BMP180Full.OSS_ULP); // Create BMP180 driver, (transport, oss=0 ULP)

const t0 = bmp.temperature();                          // Read temperature, () → float C
const p0 = bmp.pressure();                            // Read pressure, () → float hPa
const altRef = bmp.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
console.log('Reference: ' + t0.toFixed(1) + ' C, ' + p0.toFixed(1) + ' hPa, alt=' + altRef.toFixed(1) + ' m');
let prevAlt = 0.0;

for (let n = 0; n < 60; n++) {
    const t = bmp.temperature();                       // Read temperature, () → float C
    const p = bmp.pressure();                        // Read pressure, () → float hPa
    const a = bmp.altitude();                       // Compute altitude, (sea_level_hpa=1013.25) → float m
    const da = (a - prevAlt) * 100;

    if (n > 0) {
        console.log(n + 's: ' + t.toFixed(1) + ' C, ' + p.toFixed(1) + ' hPa, alt=' + a.toFixed(1) + ' m (delta=' + da.toFixed(0) + ' cm)');
    } else {
        console.log(n + 's: ' + t.toFixed(1) + ' C, ' + p.toFixed(1) + ' hPa, alt=' + a.toFixed(1) + ' m');
    }
    prevAlt = a;
}

transport.close();
console.log('===DONE: 0 passed, 0 failed===');
