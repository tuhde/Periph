'use strict';

// Minimal example: read red, green and blue counts once a second.

const { I2CConnection } = require('periph/src/connection/i2c');
const { ISL29125Minimal } = require('periph/src/chips/color/isl29125');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, ISL29125Minimal.I2C_ADDRESS);
    const sensor = new ISL29125Minimal(connection);                         // Create ISL29125 driver, (connection)
    await sensor.init();                                                    // Check DEVICE_ID, start RGB conversion, () → void

    for (let i = 0; i < 10; i++) {
        const c = await sensor.read();                                      // Read the three channels, () → {red, green, blue} counts
        console.log('R', c.red, 'G', c.green, 'B', c.blue);
        await sleep(1000);
    }

    await connection.close();
})();
