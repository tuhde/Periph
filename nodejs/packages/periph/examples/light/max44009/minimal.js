'use strict';

// Minimal example: read the ambient illuminance once a second.

const { I2CConnection } = require('periph/src/connection/i2c');
const { MAX44009Minimal } = require('periph/src/chips/light/max44009');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, MAX44009Minimal.I2C_ADDRESS);
    const sensor = new MAX44009Minimal(connection);                         // Create MAX44009 driver, (connection)
    await sensor.init();                                                    // Run initialization sequence, () → void (writes the reset state, waits 0.9 s)

    for (let i = 0; i < 10; i++) {
        console.log((await sensor.lux()).toFixed(2), 'lx');                 // Read illuminance, () → float lux
        await sleep(1000);
    }

    await connection.close();
})();
