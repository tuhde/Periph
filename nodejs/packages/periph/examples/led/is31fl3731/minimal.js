'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { IS31FL3731Minimal } = require('../../../src/chips/led/is31fl3731');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x74', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const matrix = new IS31FL3731Minimal(connection);         // Create IS31FL3731 driver, (connection)
    await matrix.ready;

    for (let col = 0; col < IS31FL3731Minimal.COLS; col++) {
        await matrix.setPixel(0, col, 128);                   // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → void
    }
    await sleep(1000);
    await matrix.fill(32);                                    // Set every LED's PWM brightness, (brightness 0–255) → void
    await sleep(1000);
    await matrix.clear();                                     // Switch all LEDs off, () → void

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
