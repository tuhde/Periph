'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { QMI8658AFull } = require('../../../src/chips/imu/qmi8658a');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x6B', 16);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new QMI8658AFull(connection);                    // Create QMI8658A driver, (connection) → void

    // --- Identify the part and run the built-in checks ---
    // The revision, firmware version and USID identify the silicon; the
    // self-tests need the board lying still and the gyro calibration trims the
    // X/Y gains before real use.
    const info = await imu.deviceInfo();                         // Read device info, () → {revision, firmware, usid}
    console.log('revision 0x%s firmware %s usid %s', info.revision.toString(16), info.firmware.toString('hex'), info.usid.toString('hex'));
    const [accelOk] = await imu.selfTestAccel();                 // Run accel self-test, () → [boolean, [number, number, number]] m/s²
    console.log('accel self-test:', accelOk ? 'PASS' : 'FAIL');
    const [gyroOk] = await imu.selfTestGyro();                   // Run gyro self-test, () → [boolean, [number, number, number]] rad/s
    console.log('gyro self-test:', gyroOk ? 'PASS' : 'FAIL');
    const [calOk, codStatus] = await imu.calibrateGyro();        // Calibrate gyroscope, () → [boolean, number]
    console.log('gyro calibration:', calOk ? 'OK' : 'FAILED 0x' + codStatus.toString(16));

    // --- Configure ±4 g / ±512 dps at 112.1 Hz ---
    // ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
    // noise bandwidth near 15 Hz.
    await imu.configureAccel(1, 6);                              // Configure accelerometer, (fullScale=0, odr=5) → void
    await imu.configureGyro(5, 6);                               // Configure gyroscope, (fullScale=4, odr=5) → void
    await imu.configureFilters(true, 3, true, 3);                // Configure low-pass filters, (accelFilter=true, accelMode=3, gyroFilter=true, gyroMode=3) → void

    // --- Step counting and tap detection ---
    // The pedometer defaults suit ~50 Hz, so the window parameters are scaled
    // for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
    // detector at this rate.
    await imu.configurePedometer(112, 200, 100, 450, 45, 10, 0, 1);  // Configure pedometer, (sampleCount=50, …) → void
    await imu.enablePedometer(true);                             // Enable pedometer, (enabled=true) → void
    await imu.configureTap();                                    // Configure tap engine, (priority=0, …) → void
    await imu.enableTap(true);                                   // Enable tap engine, (enabled=true) → void

    // --- Stream into the FIFO ---
    // Stream mode with 32 samples and a watermark of 16 is drained once per
    // second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
    await imu.configureFifo(2, 1, 16);                           // Configure FIFO, (mode=2, size=3, watermark=0) → void
    await sleep(200);

    const start = Date.now();
    let lastFifo = start;
    while (Date.now() - start < 60000) {
        if (await imu.dataReady()) {                             // Check for new data, () → boolean
            const [[ax, ay, az], [gx, gy, gz], temp] = await imu.readAll();  // Read one coherent sample, () → [[number, number, number], [number, number, number], number] m/s², rad/s, °C
            const roll = Math.atan2(ay, az) * 180 / Math.PI;
            const pitch = Math.atan2(-ax, Math.sqrt(ay * ay + az * az)) * 180 / Math.PI;
            const mag = Math.sqrt(gx * gx + gy * gy + gz * gz);
            console.log('roll %s pitch %s |gyro| %s rad/s  %s C  steps %d', roll.toFixed(1), pitch.toFixed(1),
                mag.toFixed(3), temp.toFixed(1), await imu.stepCount());  // Read step count, () → number
        }

        // --- Report taps ---
        // pollInterrupt() flags a tap without the INT pin wired.
        const status = await imu.pollInterrupt();                // Poll interrupt status, () → number
        if (status & QMI8658AFull.SOURCE_TAP) {
            const [taps, axis] = await imu.tapStatus();          // Read tap event, () → [number, number, boolean]
            console.log('TAP %s on axis %s', taps === 2 ? 'double' : 'single', axis ? 'XYZ'[axis - 1] : '?');
        }

        // --- Drain the FIFO once per second ---
        if (Date.now() - lastFifo >= 1000) {
            lastFifo = Date.now();
            const n = await imu.fifoCount();                     // Read FIFO fill, () → number bytes
            const data = await imu.readFifo();                   // Drain the FIFO, (count?) → Buffer
            console.log('FIFO: %d bytes (%d), %d frames', n, data.length, Math.floor(data.length / 12));
        }
        await sleep(100);
    }
}

main().catch(err => { console.error(err); process.exit(1); });
