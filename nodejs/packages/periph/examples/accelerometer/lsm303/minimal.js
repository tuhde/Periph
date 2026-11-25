'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Minimal } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);   // Open I²C bus, (busNumber, addr=0x19) → I2CConnection
const magConnection = new I2CConnection(I2C_BUS, 0x1E);     // Open I²C bus, (busNumber, addr=0x1E) → I2CConnection
const compass = new LSM303Minimal(accelConnection, magConnection);  // Create LSM303 driver, (accelConnection, magConnection) → LSM303Minimal

(async () => {
    while (true) {
        const accel = await compass.readAcceleration();       // Read acceleration, () → Promise<{x:float, y:float, z:float}> g
        const mag = await compass.readMagnetic();             // Read magnetic field, () → Promise<{x:float, y:float, z:float}> uT
        console.log(`accel x=${accel.x.toFixed(4)} y=${accel.y.toFixed(4)} z=${accel.z.toFixed(4)} g`);
        console.log(`mag   x=${mag.x.toFixed(2)} y=${mag.y.toFixed(2)} z=${mag.z.toFixed(2)} uT`);
        await new Promise(r => setTimeout(r, 1000));
    }

    await accelConnection.close();
    await magConnection.close();
})();
