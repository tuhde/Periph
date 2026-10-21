'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LIS3DHMinimal } = require('periph/src/chips/accelerometer/lis3dh');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x18', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new LIS3DHMinimal(connection);               // Create LIS3DH driver, (connection)

(async () => {
    for (let i = 0; i < 10; i++) {
        const { x, y, z } = await accel.read();             // Read 3-axis acceleration, () → {x, y, z} g
        console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} g`);
        await new Promise(r => setTimeout(r, 100));
    }
    await connection.close();
})();
