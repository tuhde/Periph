'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { LSM303Minimal } = require('periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);                 // Open I2C bus for the accelerometer, (bus, addr=0x19)
const magConnection = new I2CConnection(I2C_BUS, 0x1E);                   // Open I2C bus for the magnetometer, (bus, addr=0x1E)
const lsm = new LSM303Minimal(accelConnection, magConnection);            // Create LSM303 driver, (accelConnection, magConnection)
                                                                          // runs init: ±2 g / 100 Hz / high-resolution accel, ±130 µT / 15 Hz / continuous mag

(async () => {
    for (let i = 0; i < 10; i++) {
        const [x, y, z] = await lsm.readAcceleration();                   // Read 3-axis acceleration, () → [number, number, number] g
        const [mx, my, mz] = await lsm.readMagnetic();                    // Read 3-axis magnetic flux density, () → [number, number, number] µT
        console.log(`accel: ${x.toFixed(3)} ${y.toFixed(3)} ${z.toFixed(3)} g  mag: ${mx.toFixed(1)} ${my.toFixed(1)} ${mz.toFixed(1)} uT`);
        await new Promise(r => setTimeout(r, 100));
    }
    await accelConnection.close();
    await magConnection.close();
})().catch(err => { console.error(err); process.exit(1); });
