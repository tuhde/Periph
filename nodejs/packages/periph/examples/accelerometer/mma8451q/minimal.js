'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { MMA8451QMinimal } = require('periph/src/chips/accelerometer/mma8451q');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new MMA8451QMinimal(connection);                // Create MMA8451Q driver, (connection)

(async () => {
    await accel.init();                                        // Run init sequence, () → None
                                                             // verifies WHO_AM_I, resets and sets ±2 g / 100 Hz / 14-bit
    for (let i = 0; i < 10; i++) {
        const [x, y, z] = await accel.read();                   // Read 3-axis acceleration, () → [number, number, number] g
        console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} g`);
    }
    await connection.close();
})();
