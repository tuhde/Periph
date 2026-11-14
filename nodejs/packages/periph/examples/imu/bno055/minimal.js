'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { BNO055Minimal } = require('../../../src/chips/imu/bno055');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x29', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const imu = new BNO055Minimal(connection);                       // Create BNO055 driver, (connection) → void

async function main() {
    while (true) {
        const [h, r, p] = await imu.euler();                     // Read fused Euler angles, () → [number, number, number] rad
        console.log('heading %s rad, roll %s, pitch %s', h.toFixed(2), r.toFixed(2), p.toFixed(2));
        await new Promise(res => setTimeout(res, 100));
    }
}

main().catch(err => { console.error(err); process.exit(1); });
