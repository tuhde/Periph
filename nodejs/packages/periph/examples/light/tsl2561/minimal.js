'use strict';

// Minimal example: read the illuminance once a second (1× gain, 402 ms, T/FN/CL coefficients).

const { I2CConnection } = require('periph/src/connection/i2c');
const { TSL2561Minimal } = require('periph/src/chips/light/tsl2561');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, TSL2561Minimal.I2C_ADDRESS);
    const sensor = new TSL2561Minimal(connection);                          // Create TSL2561 driver, (connection)
    await sensor.init();                                                    // Run initialization sequence, () → void (powers up, waits one integration)

    for (let i = 0; i < 10; i++) {
        console.log((await sensor.readLux()).toFixed(1), 'lx');             // Read illuminance, () → float lux
        await sleep(1000);
    }

    await connection.close();
})();
