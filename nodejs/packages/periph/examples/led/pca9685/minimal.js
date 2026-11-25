'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { PCA9685Minimal } = require('../../../src/chips/led/pca9685');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
    const pwm = new PCA9685Minimal(connection);               // Create PCA9685 driver at 1 kHz, (connection)
    await pwm.ready;

    for (let step = 0; step <= 10; step++) {
        await pwm.setDuty(0, step / 10);                      // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → void
        await sleep(200);
    }
    await pwm.setDuty(0, 0.0);

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
