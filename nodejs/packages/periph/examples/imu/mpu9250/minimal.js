'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPU9250Minimal } = require('../../packages/periph/src/chips/imu/mpu9250');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const imu = new MPU9250Minimal(connection);                           // Create MPU9250 driver, (connection) → void

async function main() {
    while (true) {
        const [ax, ay, az] = await imu.accel();                     // Read 3-axis acceleration, () → [number, number, number] m/s²
        const [gx, gy, gz] = await imu.gyro();                      // Read 3-axis angular rate, () → [number, number, number] rad/s
        console.log('accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f', ax, ay, az, gx, gy, gz);
        await new Promise(r => setTimeout(r, 100));
    }
}

main().catch(err => { console.error(err); process.exit(1); });