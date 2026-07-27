'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { MPU6050Minimal } = require('../../../src/chips/imu/mpu6050');

const connection = new I2CConnection(1, 0x68);
const imu = new MPU6050Minimal(connection);               // Create MPU6050 driver, (connection, addr=0x68) → None

setInterval(async () => {
    const [ax, ay, az] = await imu.accel();              // Read 3-axis acceleration, () → [float, float, float] m/s²
    const [gx, gy, gz] = await imu.gyro();               // Read 3-axis angular rate, () → [float, float, float] rad/s
    console.log('accel:', ax.toFixed(2), ay.toFixed(2), az.toFixed(2),
                ' gyro:', gx.toFixed(2), gy.toFixed(2), gz.toFixed(2));
}, 100);
