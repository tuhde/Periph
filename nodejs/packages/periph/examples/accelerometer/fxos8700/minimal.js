'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { FXOS8700Minimal } = require('periph/src/chips/accelerometer/fxos8700');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const connection = new I2CConnection(I2C_BUS, 0x1E);          // Open I²C bus, (busNumber, addr=0x1E) → I2CConnection
const compass = new FXOS8700Minimal(connection);              // Create FXOS8700 driver, (connection) → FXOS8700Minimal

(async () => {
    await compass.init();                                     // Wait for initialization, () → Promise<void>
    while (true) {
        const accel = await compass.readAccel();              // Read acceleration, () → Promise<{x:float, y:float, z:float}> g
        const mag = await compass.readMag();                  // Read magnetic field, () → Promise<{x:float, y:float, z:float}> uT
        console.log(`accel x=${accel.x.toFixed(4)} y=${accel.y.toFixed(4)} z=${accel.z.toFixed(4)} g`);
        console.log(`mag   x=${mag.x.toFixed(2)} y=${mag.y.toFixed(2)} z=${mag.z.toFixed(2)} uT`);
        await new Promise((r) => setTimeout(r, 1000));
    }
})();
