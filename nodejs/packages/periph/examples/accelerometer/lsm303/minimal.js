'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Minimal } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);
const magConnection = new I2CConnection(I2C_BUS, 0x1E);
const compass = new LSM303Minimal(accelConnection, magConnection);  // Create LSM303 driver, (accelConnection, magConnection)

(async () => {
    await compass.init();
    for (let i = 0; i < 10; i++) {
        const a = await compass.readAcceleration();          // Read 3-axis acceleration, () -> {x, y, z} g
        const m = await compass.readMagnetic();              // Read 3-axis magnetic field, () -> {x, y, z} uT
        console.log(`a=(${a.x.toFixed(3)}, ${a.y.toFixed(3)}, ${a.z.toFixed(3)}) g  m=(${m.x.toFixed(1)}, ${m.y.toFixed(1)}, ${m.z.toFixed(1)}) uT`);
        await new Promise(r => setTimeout(r, 100));
    }
    await accelConnection.close();
    await magConnection.close();
})();
