'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS28DFWMinimal } = require('../../../src/chips/pressure/lps28dfw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lps = new LPS28DFWMinimal(connection);                // Create LPS28DFW driver, (connection)

(async () => {
    for (let i = 0; i < 5; i++) {
        const t = await lps.readTemperature();              // Read temperature, () → number °C
        const p = await lps.readPressure();                 // Read pressure, () → number hPa
        console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();