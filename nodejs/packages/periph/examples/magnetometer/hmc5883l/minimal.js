'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HMC5883LMinimal } = require('../../../src/chips/magnetometer/hmc5883l');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1E', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const hmc5883l = new HMC5883LMinimal(connection);               // Create HMC5883L driver, (connection) → HMC5883LMinimal

setInterval(async () => {
    const { x, y, z } = await hmc5883l.magneticField();          // Read magnetic field, () → { x: float T, y: float T, z: float T }
    console.log('X=%.6f T  Y=%.6f T  Z=%.6f T', x, y, z);
}, 1000);