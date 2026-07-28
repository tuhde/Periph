'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP180Minimal } = require('../../../src/chips/pressure/bmp180');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x77', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bmp = new BMP180Minimal(connection);              // Create BMP180 driver, (connection)

(async () => {
    for (let i = 0; i < 5; i++) {
        const t = await bmp.temperature();             // Read temperature, () → float C
        const p = await bmp.pressure();                // Read pressure, () → float hPa
        console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
