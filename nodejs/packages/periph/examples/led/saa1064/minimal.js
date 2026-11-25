'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { SAA1064Minimal } = require('../../../src/chips/led/saa1064');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const display = new SAA1064Minimal(connection);           // Create SAA1064 driver, (connection)
    await display.ready;

    for (let n = 0; n < 100; n++) {
        await display.showNumber(n);                          // Show a right-aligned number, (value 0–9999) → void
        await sleep(100);
    }

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
