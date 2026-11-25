'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { BMA150Minimal } = require('periph/src/chips/accelerometer/bma150');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new BMA150Minimal(connection);               // Create BMA150 driver, (connection)

(async () => {
    for (let i = 0; i < 10; i++) {
        const [x, y, z] = await accel.read();              // Read 3-axis acceleration, () → [number, number, number] g
        console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} g`);
    }
    await connection.close();
})();
