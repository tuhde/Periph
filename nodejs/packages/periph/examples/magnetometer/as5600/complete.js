'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AS5600Full } = require('../../../src/chips/magnetometer/as5600');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x36', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const as5600 = new AS5600Full(connection);                    // Create AS5600 driver, (connection) → AS5600Full

(async () => {
    // --- Status and magnet checks ---
    console.log(await as5600.isMagnetDetected());              // Check magnet present, () → bool
    console.log(await as5600.isMagnetTooStrong());             // Check magnet too strong, () → bool
    console.log(await as5600.isMagnetTooWeak());               // Check magnet too weak, () → bool
    console.log((await as5600.statusByte()).toString(16));    // Read raw status, () → int

    // --- Angle readings ---
    console.log(await as5600.angle());                         // Read absolute angle, () → float degrees
    console.log(await as5600.angleRaw());                      // Read scaled angle count, () → int 0-4095
    console.log(await as5600.rawAngle());                       // Read raw unscaled angle, () → int 0-4095
    console.log(await as5600.rawAngleDegrees());                // Read raw angle in degrees, () → float degrees

    // --- Diagnostics ---
    console.log(await as5600.agc());                            // Read AGC value, () → int
    console.log(await as5600.magnitude());                      // Read CORDIC magnitude, () → int

    // --- Position configuration (volatile) ---
    console.log(await as5600.zeroPosition());                   // Read ZPOS, () → int 0-4095
    console.log(await as5600.maxPosition());                    // Read MPOS, () → int 0-4095
    console.log(await as5600.maxAngle());                       // Read MANG, () → int 0-4095

    await as5600.setZeroPosition(0);                            // Set zero position, (pos 0-4095) → None
    await as5600.setMaxPosition(4095);                          // Set max position, (pos 0-4095) → None
    await as5600.setMaxAngle(2048);                             // Set max angle span, (span 0-4095) → None

    // --- Configure power mode and output ---
    await as5600.configure(0, 0, 0, 0, 0, 0, false);            // Configure chip, (pm 0-3, hyst 0-3, outs 0-2, pwmf 0-3, sf 0-3, fth 0-7, wd bool) → None

    // --- Burn count ---
    console.log(await as5600.burnCount());                      // Read burn count, () → int 0-3

    await connection.close();
})();
