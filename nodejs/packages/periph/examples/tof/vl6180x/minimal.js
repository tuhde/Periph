'use strict';

// Minimal example: print the distance in mm five times a second.

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL6180XMinimal } = require('periph/src/chips/tof/vl6180x');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL6180XMinimal.I2C_ADDRESS, null, null, 2);
    const sensor = new VL6180XMinimal(connection);                                 // Create VL6180X driver, (connection)
    await sensor.init();                                                           // Run the init sequence, () → undefined

    for (let i = 0; i < 50; i++) {
        const mm = await sensor.readRange();                                       // Single-shot range, () → number mm
        console.log(`${mm} mm`);
        await sleep(200);
    }

    await connection.close();
})();
