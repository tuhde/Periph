'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { BNO055Full, BNO055Mode, BNO055Source } = require('../../../src/chips/imu/bno055');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x29', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const imu = new BNO055Full(connection);                          // Create BNO055 driver, (connection) → void

async function main() {
    await imu.init();                                            // Wait for boot and NDOF, () → void
                                                                 // rejects if CHIP_ID is not 0xA0

    const [h, r, p] = await imu.euler();                         // Read fused Euler angles, () → [number, number, number] rad
                                                                 // burst read of 0x1A…0x1F, raw / 900
    const [w, x, y, z] = await imu.quaternion();                 // Read orientation quaternion, () → [number, number, number, number]
                                                                 // raw / 16384
    const lin = await imu.linearAcceleration();                  // Read gravity-free acceleration, () → [number, number, number] m/s²
                                                                 // raw / 100
    const grav = await imu.gravity();                            // Read gravity vector, () → [number, number, number] m/s²
                                                                 // raw / 100
    const acc = await imu.acceleration();                        // Read raw accelerometer, () → [number, number, number] m/s²
                                                                 // raw / 100
    const mag = await imu.magneticField();                       // Read raw magnetometer, () → [number, number, number] µT
                                                                 // raw / 16
    const gyr = await imu.angularRate();                         // Read raw gyroscope, () → [number, number, number] rad/s
                                                                 // raw / 900
    const t = await imu.temperature();                           // Read temperature, () → number °C
                                                                 // signed 8 bit, 1 LSB = 1 °C
    const [sys, gy, ac, mg] = await imu.calibrationStatus();     // Read calibration status, () → [number, number, number, number]
                                                                 // each 0 (uncalibrated) … 3 (calibrated)
    const done = await imu.isCalibrated();                       // Check full calibration, () → boolean
                                                                 // true when all four values are 3
    console.log(h, r, p, w, x, y, z, lin, grav, acc, mag, gyr, t, sys, gy, ac, mg, done);

    const mode = await imu.getMode();                            // Read operation mode, () → number
                                                                 // 0x0C = NDOF
    await imu.setMode(BNO055Mode.IMU);                           // Select operation mode, (mode) → void
                                                                 // via CONFIGMODE, 19 ms + 7 ms waits
    await imu.setMode(BNO055Mode.NDOF);                          // Select operation mode, (mode) → void
                                                                 // back to 9-DOF fusion

    const blob = await imu.getCalibration();                     // Read offsets and radii, () → Buffer
                                                                 // 22 bytes, 0x55…0x6A, read in CONFIGMODE
    await imu.setCalibration(blob);                              // Restore offsets and radii, (data) → void
                                                                 // 22 bytes, written in CONFIGMODE
    await imu.setAxisRemap(0x24, 0x00);                          // Remap axes, (config, sign) → void
                                                                 // 0x24/0x00 = identity placement
    await imu.setPowerMode('normal');                            // Select power mode, (mode) → void
                                                                 // 'normal', 'low_power' or 'suspend'
    await imu.setTemperatureSource('accelerometer');             // Select temperature sensor, (source) → void
                                                                 // 'accelerometer' or 'gyroscope'
    await imu.useExternalCrystal(false);                         // Select clock source, (on) → void
                                                                 // internal oscillator unless a crystal is fitted

    const result = await imu.selfTest();                         // Run self-test, () → {acc, mag, gyr, mcu}
                                                                 // booleans, true = passed
    const [status, error] = await imu.systemStatus();            // Read system status, () → [number, number]
                                                                 // SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
    const info = await imu.revisions();                          // Read revisions, () → object
                                                                 // chip/acc/mag/gyr IDs, SW revision, bootloader
    const uid = await imu.uniqueId();                            // Read unique ID, () → Buffer
                                                                 // 16 bytes from page 1
    console.log(mode, result, status, error, info, uid.toString('hex'));

    await imu.enableInterrupt(BNO055Source.ACC_BSX_DRDY);        // Enable interrupt source, (source) → void
                                                                 // INT_EN and INT_MSK on page 1
    const ints = await imu.pollInterrupt();                      // Poll interrupt status, () → number
                                                                 // INT_STA, cleared on read
    await imu.onInterrupt((s) => console.log('INT status 0x' + s.toString(16)));  // Subscribe to interrupts, (callback, intPin?) → void
                                                                 // uses an INT pin or a 100 ms polling timer
    await new Promise(res => setTimeout(res, 500));
    await imu.offInterrupt();                                    // Unsubscribe, () → void
                                                                 // stops the callback
    await imu.disableInterrupt(BNO055Source.ACC_BSX_DRDY);       // Disable interrupt source, (source) → void
                                                                 // clears the INT_EN and INT_MSK bits
    console.log(ints);

    await imu.reset();                                           // Reset the chip, () → void
                                                                 // RST_SYS, waits 650 ms, re-enters NDOF
}

main().catch(err => { console.error(err); process.exit(1); });
