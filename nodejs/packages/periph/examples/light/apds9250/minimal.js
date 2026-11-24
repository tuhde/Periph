'use strict';

// Minimal example: read red, green, blue and IR counts once a second.

const { I2CConnection } = require('periph/src/connection/i2c');
const { APDS9250Minimal } = require('periph/src/chips/light/apds9250');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, APDS9250Minimal.I2C_ADDRESS);
    const sensor = new APDS9250Minimal(connection);                         // Create APDS-9250 driver, (connection)
    await sensor.init();                                                    // Check PART_ID, start colour mode, () → void

    for (let i = 0; i < 10; i++) {
        const c = await sensor.read();                                      // Read the four channels, () → {red, green, blue, ir} counts
        console.log('R', c.red, 'G', c.green, 'B', c.blue, 'IR', c.ir);
        await sleep(1000);
    }

    await connection.close();
})();
