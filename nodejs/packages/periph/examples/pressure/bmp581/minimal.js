'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { BMP581Minimal } = require('../../../src/chips/pressure/bmp581');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x46', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const bmp = new BMP581Minimal(connection);              // Create BMP581 driver, (connection, busType='i2c')

(async () => {
    for (let i = 0; i < 5; i++) {
        const p = await bmp.pressure();                // Read pressure, () → number Pa
        const t = await bmp.temperature();             // Read temperature, () → number °C
        console.log(`${t.toFixed(2)} C, ${p.toFixed(1)} Pa`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();