'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPU9250Full } = require('../../packages/periph/src/chips/imu/mpu9250');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const magConnection = new I2CConnection(I2C_BUS, 0x0C);              // AK8963, same bus, reached via I²C bypass

async function main() {
    // --- Configure for tilt and heading estimation ---
    // ±4g / ±500dps trade sensitivity for headroom against sharper motion than
    // the ±2g / ±250dps defaults tolerate; 16-bit continuous magnetometer mode
    // keeps a fresh heading available on every poll.
    const imu = new MPU9250Full(connection, magConnection);          // Create MPU9250 driver, (connection, magConnection) → void
    await imu.configureAccel(1);                                    // Configure accel range, (fullScale=0) → void
    await imu.configureGyro(1);                                     // Configure gyro range, (fullScale=0) → void
    await imu.enableMag(16, 6);                                     // Initialize magnetometer, (bits=16, mode=6) → void

    console.log('roll     pitch    heading  |accel|  |gyro|');

    while (true) {
        // gate reads on dataReady so each sample reflects a fresh conversion
        while (!await imu.dataReady()) {                            // Check data ready flag, () → boolean
        }

        const [ax, ay, az] = await imu.accel();                     // Read 3-axis acceleration, () → [number, number, number] m/s²
        const [gx, gy, gz] = await imu.gyro();                      // Read 3-axis angular rate, () → [number, number, number] rad/s
        const [mx, my, mz] = await imu.mag();                       // Read 3-axis magnetic field, () → [number, number, number] µT

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        const roll  = Math.atan2(ay, az) * 180.0 / Math.PI;
        const pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180.0 / Math.PI;

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        const heading = Math.atan2(my, mx) * 180.0 / Math.PI;

        const accelMag = Math.sqrt(ax * ax + ay * ay + az * az);
        const gyroMag  = Math.sqrt(gx * gx + gy * gy + gz * gz);

        console.log('%s      %s      %s      %s    %s',
            roll.toFixed(1), pitch.toFixed(1), heading.toFixed(1),
            accelMag.toFixed(3), gyroMag.toFixed(3));
        await new Promise(r => setTimeout(r, 100));
    }
}

main().catch(err => { console.error(err); process.exit(1); });