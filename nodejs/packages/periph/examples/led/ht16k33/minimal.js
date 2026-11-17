'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { HT16K33Minimal } = require('../../../src/chips/led/ht16k33');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x70', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const matrix = new HT16K33Minimal(connection);            // Create HT16K33 driver, (connection)
    await matrix.ready;

    for (let row = 0; row < HT16K33Minimal.ROWS; row++) {
        matrix.setPixel(0, row);                              // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → void
    }
    await matrix.show();                                      // Write the shadow RAM to the chip, () → void
    await sleep(1000);

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
