'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS22HBMinimal } = require('../../../src/chips/pressure/lps22hb');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const lps = new LPS22HBMinimal(connection);              // Create LPS22HB driver, (connection)

(async () => {
    for (let i = 0; i < 5; i++) {
        const t = await lps.temperature();               // Read temperature, () → number °C
        const p = await lps.pressure();                  // Read pressure, () → number Pa
        console.log(`${t.toFixed(2)} C, ${p.toFixed(1)} Pa`);
    }
    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
