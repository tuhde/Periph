'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPU9255Full } = require('../../packages/periph/src/chips/imu/mpu9255');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const magConnection = new I2CConnection(I2C_BUS, 0x0C);              // AK8963, same bus, reached via I²C bypass

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
    // --- Configure for motion-triggered wake logger ---
    // 64 mg threshold and 31.25 Hz wake-up rate balance sensitivity against spurious
    // wake-ups from vibration; once motion fires, the full 6-axis sensor suite
    // (gyro + mag at 100 Hz) is re-enabled to capture a 5-second tilt/heading burst.
    const imu = new MPU9255Full(connection, magConnection);          // Create MPU9255 driver, (connection, magConnection) → void
    await imu.configureWakeOnMotion(64, 31.25);                      // Configure wake-on-motion, (thresholdMg=64, odrHz=31.25) → void

    let lastHeartbeat = Date.now();

    while (true) {
        // --- Idle phase: motion poll at ~5 Hz, "sleeping…" heartbeat at ~1 Hz ---
        // configure_wake_on_motion already disabled the gyro and put the chip
        // in CYCLE=1 duty-cycled mode; polling motion_detected() reflects that
        // state without forcing any further register writes.
        while (!await imu.motionDetected()) {                        // Check motion detected, () → boolean
            const now = Date.now();
            if (now - lastHeartbeat >= 1000) {
                console.log('sleeping...');
                lastHeartbeat = now;
            }
            await sleep(200);
        }

        // --- Wake phase: re-arm the full 6-axis + mag stack ---
        // PWR_MGMT_1=0x01 clears CYCLE; configureGyro re-enables all three gyro axes.
        await imu.setSleep(false);                                   // Wake from sleep, (sleep=true) → void
        await imu.configureGyro(1);                                  // Configure gyro range, (fullScale=0) → void
        await imu.configureAccel(1);                                 // Configure accel range, (fullScale=0) → void
        await imu.enableMag(16, 6);                                  // Initialize magnetometer, (bits=16, mode=6) → void

        // --- Capture a 5-second tilt/heading burst at ~10 Hz ---
        // Roll/pitch from gravity (quasi-static) + heading from mag (no tilt comp).
        console.log('--- motion detected ---');
        const end = Date.now() + 5000;
        while (Date.now() < end) {
            while (!await imu.dataReady()) {                         // Check data ready flag, () → boolean
            }

            const [ax, ay, az] = await imu.accel();                  // Read 3-axis acceleration, () → [number, number, number] m/s²
            const [gx, gy, gz] = await imu.gyro();                   // Read 3-axis angular rate, () → [number, number, number] rad/s
            const [mx, my, mz] = await imu.mag();                    // Read 3-axis magnetic field, () → [number, number, number] µT

            const roll    = Math.atan2(ay, az) * 180.0 / Math.PI;
            const pitch   = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180.0 / Math.PI;
            const heading = Math.atan2(my, mx) * 180.0 / Math.PI;

            console.log('%s      %s      %s      |g|=%s',
                roll.toFixed(1), pitch.toFixed(1), heading.toFixed(1),
                Math.sqrt(gx * gx + gy * gy + gz * gz).toFixed(2));
            await sleep(100);
        }

        // --- Return to low-power wake-on-motion mode ---
        await imu.configureWakeOnMotion(64, 31.25);                  // Configure wake-on-motion, (thresholdMg=64, odrHz=31.25) → void
        lastHeartbeat = Date.now();
    }
}

main().catch(err => { console.error(err); process.exit(1); });