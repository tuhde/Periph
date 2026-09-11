'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { ADXL345Minimal } = require('periph/src/chips/accelerometer/adxl345');

(async () => {
    const bus  = parseInt(process.env.I2C_BUS || '1', 10);
    const addr = parseInt(process.env.I2C_ADDR || '0x53', 16);
    const connection = new I2CConnection(bus, addr);
    const accel = new ADXL345Minimal(connection);                                  // Create ADXL345 driver, (connection, bus_type='i2c')

    for (let i = 0; i < 10; i++) {
        const [x, y, z] = await accel.read();                                       // Read 3-axis acceleration, () → [float, float, float] g
        console.log(`x=${x.toFixed(3)} y=${y.toFixed(3)} z=${z.toFixed(3)} g`);
    }

    await connection.close();
})();