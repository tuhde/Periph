'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { VL53L1XMinimal } = require('periph/src/chips/tof/vl53l1x');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, VL53L1XMinimal.I2C_ADDRESS);
    const sensor = new VL53L1XMinimal(connection);                                 // Create VL53L1X driver, (connection)
    await sensor.init();                                                           // Wait for init sequence, () → None

    for (let i = 0; i < 50; i++) {
        const d = await sensor.distance();                                         // Measure distance, () → number mm
        if (await sensor.rangeValid()) {                                           // Check last measurement, () → boolean
            console.log(`${d} mm`);
        } else {
            console.log('out of range');
        }
        await sleep(200);
    }

    await connection.close();
})();
