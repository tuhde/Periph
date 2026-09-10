'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS22DFMinimal } = require('../../../src/chips/pressure/lps22df');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lps = new LPS22DFMinimal(connection);                  // Create LPS22DF driver, (connection, busType='i2c')

(async () => {
    for (let i = 0; i < 5; i++) {
        const p = await lps.pressure();                     // Read pressure, () → number Pa
        const t = await lps.temperature();                  // Read temperature, () → number °C
        console.log(`${t.toFixed(1)} C, ${p.toFixed(0)} Pa`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();