'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MPU6050Minimal } = require('../../../src/chips/imu/mpu6050');

const transport = new I2CTransport(1, 0x68);
const imu = new MPU6050Minimal(transport);               // Create MPU6050 driver, (transport, addr=0x68) → None

setInterval(() => {
    const [ax, ay, az] = imu.accel();                    // Read 3-axis acceleration, () → [float, float, float] m/s²
    const [gx, gy, gz] = imu.gyro();                     // Read 3-axis angular rate, () → [float, float, float] rad/s
    console.log('accel:', ax.toFixed(2), ay.toFixed(2), az.toFixed(2),
                ' gyro:', gx.toFixed(2), gy.toFixed(2), gz.toFixed(2));
}, 100);
