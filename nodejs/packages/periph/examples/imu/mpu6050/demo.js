'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MPU6050Full } = require('../../../src/chips/imu/mpu6050');

const transport = new I2CTransport(1, 0x68);

// --- Configure for motion logging with moderate dynamic range ---
// ±4g captures typical tilting and handling forces without clipping;
// ±500 dps covers fast rotations while retaining sub-degree resolution.
const imu = new MPU6050Full(transport);                  // Create MPU6050 driver, (transport, addr=0x68) → None
imu.configureAccel(1);                                    // Configure accel range, (fullScale=0) → None
imu.configureGyro(1);                                     // Configure gyro range, (fullScale=0) → None

console.log('roll     pitch    |accel|    |gyro|');

setInterval(() => {
    // gate reads on dataReady so each sample reflects a fresh conversion
    while (!imu.dataReady()) {}                           // Check data ready flag, () → bool

    const [ax, ay, az] = imu.accel();                     // Read 3-axis acceleration, () → [float, float, float] m/s²
    const [gx, gy, gz] = imu.gyro();                      // Read 3-axis angular rate, () → [float, float, float] rad/s

    // --- Compute tilt angles from the accelerometer gravity vector ---
    // roll and pitch are reliable when the device is quasi-static;
    // gyro magnitude indicates how fast the board is being rotated.
    const roll  = Math.atan2(ay, az) * 180.0 / Math.PI;
    const pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180.0 / Math.PI;
    const accelMag = Math.sqrt(ax * ax + ay * ay + az * az);
    const gyroMag  = Math.sqrt(gx * gx + gy * gy + gz * gz);

    console.log(roll.toFixed(1).padEnd(9) + pitch.toFixed(1).padEnd(9) +
                accelMag.toFixed(3).padEnd(11) + gyroMag.toFixed(3));
}, 100);
