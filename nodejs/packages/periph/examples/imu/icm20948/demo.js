'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { ICM20948Full } = require('../../../src/chips/imu/icm20948');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

    // --- Configure for motion and heading monitoring ---
    // ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
    // 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The
    // magnetometer runs through the chip's own I²C master, so one connection
    // is enough.
    const imu = new ICM20948Full(connection);                    // Create ICM20948 driver, (connection) → void
    await imu.configureAccel(1);                                 // Configure accel range, (fullScale=0) → void
    await imu.configureGyro(1);                                  // Configure gyro range, (fullScale=0) → void
    await imu.configureFilters(3, 3);                            // Configure low-pass filters, (gyroDlpf=3, accelDlpf=3, gyroFilter=true, accelFilter=true) → void
    await imu.configureSampleRate(10, 10);                       // Configure sample rate, (gyroDiv=10, accelDiv=10) → void
    await imu.enableMag();                                       // Enable magnetometer, () → void

    console.log('ICM-20948 die temperature: %s C', (await imu.temperature()).toFixed(1));  // Read die temperature, () → number °C

    // --- Arm wake-on-motion and route it to the interrupt status ---
    // A 200 mg threshold flags a bump; pollInterrupt() reports it as
    // SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
    await imu.setWakeOnMotion(200);                              // Arm wake-on-motion, (thresholdMg, enabled=true) → void
    await imu.enableInterrupt(ICM20948Full.SOURCE_WAKE_ON_MOTION);  // Enable interrupt source, (source) → void

    // --- Stream accelerometer + gyro into the FIFO ---
    // Stream mode overwrites the oldest data; once per second the FIFO is
    // drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
    await imu.enableFifo(true, true);                            // Enable FIFO sources, (accel=true, gyro=true, temp=false) → void
    await imu.setFifoMode(false);                                // Select FIFO mode, (snapshot=false) → void

    console.log('roll     pitch    heading  |gyro|');

    const start = Date.now();
    let lastFifo = start;
    while (Date.now() - start < 60000) {
        const [[ax, ay, az], [gx, gy, gz]] = await imu.readAll();  // Read one coherent sample, () → [[number x3], [number x3], number] m/s², rad/s, °C
        const [mx, my, mz] = await imu.mag();                    // Read 3-axis magnetic field, () → [number, number, number] µT

        // --- Compute roll, pitch and a tilt-compensated heading ---
        // The magnetometer frame is rotated 180° about X relative to the
        // inertial frame, so (mx, -my, -mz) maps it into the accel frame
        // before the tilt compensation rotates it into the horizontal plane.
        const roll = Math.atan2(ay, az);
        const pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az));
        const bx = mx, by = -my, bz = -mz;
        const xh = bx * Math.cos(pitch) + bz * Math.sin(pitch);
        const yh = bx * Math.sin(roll) * Math.sin(pitch) + by * Math.cos(roll)
            - bz * Math.sin(roll) * Math.cos(pitch);
        const heading = (Math.atan2(-yh, xh) * 180 / Math.PI + 360) % 360;
        const gyroMag = Math.sqrt(gx * gx + gy * gy + gz * gz);

        console.log('%s      %s      %s      %s',
            (roll * 180 / Math.PI).toFixed(1), (pitch * 180 / Math.PI).toFixed(1),
            heading.toFixed(1), gyroMag.toFixed(3));

        const status = await imu.pollInterrupt();                // Read interrupt status, () → number
        if (status & ICM20948Full.SOURCE_WAKE_ON_MOTION) console.log('MOTION');

        if (Date.now() - lastFifo >= 1000) {
            lastFifo = Date.now();
            const count = await imu.fifoCount();                 // Read FIFO byte count, () → number
            const data = await imu.readFifo();                   // Read FIFO data, (count?) → Buffer
            console.log('FIFO: %d bytes (count %d), %d frames', data.length, count, Math.floor(data.length / 12));
        }
        await sleep(100);
    }
    connection.close();
}

main().catch(err => { console.error(err); process.exit(1); });
