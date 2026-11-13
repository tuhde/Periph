'use strict';

// Minimal example: take and release the downstream I²C bus.

const { I2CConnection } = require('periph/src/connection/i2c');
const { PCA9541Minimal } = require('periph/src/chips/other/pca9541');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, PCA9541Minimal.I2C_ADDRESS);
    const selector = new PCA9541Minimal(connection);                        // Create PCA9541 driver, (connection)
    await selector.init();                                                  // Run initialization sequence, () → void (reads CONTROL and ISTAT; writes nothing)

    console.log('has control', await selector.hasControl());                // Report control, () → bool
    console.log('acquire', await selector.acquire());                       // Take the downstream bus, (busInit=false, timeoutMs=20) → bool
    await sleep(1000);
    await selector.release();                                               // Give the bus to the other master, () → void
    console.log('has control', await selector.hasControl());

    await connection.close();
})();
