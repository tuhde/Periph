'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { BNO055Full, BNO055Mode, BNO055Source } = require('../../../src/chips/imu/bno055');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x29', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const imu = new BNO055Full(connection);                          // Create BNO055 driver, (connection) → void
const sleep = (ms) => new Promise(res => setTimeout(res, ms));

async function main() {
    await imu.init();                                            // Wait for boot and NDOF, () → void

    // --- Identify the part ---
    const info = await imu.revisions();                          // Read revisions, () → object
    console.log('chip 0x%s, SW %d.%d, bootloader %d', info.chipId.toString(16), info.swRevision >> 8, info.swRevision & 0xFF, info.bootloader);
    console.log('unique id', (await imu.uniqueId()).toString('hex'));   // Read unique ID, () → Buffer

    // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
    for (;;) {
        const [h, r, p] = await imu.euler();                     // Read fused Euler angles, () → [number, number, number] rad
        const [w, x, y, z] = await imu.quaternion();             // Read orientation quaternion, () → [number, number, number, number]
        const lin = await imu.linearAcceleration();              // Read gravity-free acceleration, () → [number, number, number] m/s²
        const grav = await imu.gravity();                        // Read gravity vector, () → [number, number, number] m/s²
        const cal = await imu.calibrationStatus();               // Read calibration status, () → [number, number, number, number]
        console.log('euler %s %s %s  quat %s %s %s %s  lin %s  grav %s  cal %s',
            h.toFixed(2), r.toFixed(2), p.toFixed(2), w.toFixed(3), x.toFixed(3), y.toFixed(3), z.toFixed(3),
            lin.map(v => v.toFixed(2)).join(' '), grav.map(v => v.toFixed(2)).join(' '), cal.join(''));
        if (await imu.isCalibrated()) break;                     // Check full calibration, () → boolean
        await sleep(100);
    }

    // --- Save the calibration, relative mode for 5 s, restore it ---
    const blob = await imu.getCalibration();                     // Read offsets and radii, () → Buffer
    console.log('calibration', blob.toString('hex'));
    await imu.setMode(BNO055Mode.IMU);                           // Select operation mode, (mode) → void
    console.log('mode', await imu.getMode());                    // Read operation mode, () → number
    await sleep(5000);
    await imu.setMode(BNO055Mode.NDOF);                          // Select operation mode, (mode) → void
    await imu.setCalibration(blob);                              // Restore offsets and radii, (data) → void

    // --- Self-test, temperature and configuration ---
    console.log('self-test', await imu.selfTest());              // Run self-test, () → object
    console.log('temperature', await imu.temperature(), 'C');    // Read temperature, () → number °C
    await imu.setTemperatureSource('gyroscope');                 // Select temperature sensor, (source) → void
    await imu.setAxisRemap(0x24, 0x00);                          // Remap axes, (config, sign) → void
    await imu.setPowerMode('normal');                            // Select power mode, (mode) → void
    await imu.useExternalCrystal(false);                         // Select clock source, (on) → void

    // --- Raw sensors in AMG mode ---
    await imu.setMode(BNO055Mode.AMG);                           // Select operation mode, (mode) → void
    console.log('acc', await imu.acceleration());                // Read raw accelerometer, () → [number, number, number] m/s²
    console.log('mag', await imu.magneticField());               // Read raw magnetometer, () → [number, number, number] µT
    console.log('gyr', await imu.angularRate());                 // Read raw gyroscope, () → [number, number, number] rad/s

    // --- Interrupts: accelerometer/fusion data ready ---
    await imu.setMode(BNO055Mode.NDOF);                          // Select operation mode, (mode) → void
    await imu.enableInterrupt(BNO055Source.ACC_BSX_DRDY);        // Enable interrupt source, (source) → void
    await imu.onInterrupt((s) => console.log('INT 0x' + s.toString(16)));   // Subscribe to interrupts, (callback, intPin?) → void
    await sleep(500);
    await imu.offInterrupt();                                    // Unsubscribe, () → void
    await imu.disableInterrupt(BNO055Source.ACC_BSX_DRDY);       // Disable interrupt source, (source) → void
    console.log('interrupt status', await imu.pollInterrupt());  // Poll interrupt status, () → number
    console.log('system status', await imu.systemStatus());      // Read system status, () → [number, number]
    await imu.reset();                                           // Reset the chip, () → void
}

main().catch(err => { console.error(err); process.exit(1); });
