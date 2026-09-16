'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { HMC5883LFull } = require('../../../src/chips/magnetometer/hmc5883l');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x1E', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const hmc5883l = new HMC5883LFull(connection);

async function main() {
    // --- Configure for electronic compass ---
    // 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
    await hmc5883l.configure(15, 8, 1);                            // Configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → None

    console.log('Electronic compass demo — hold sensor flat, rotate horizontally');
    console.log('Vertical mount warning: |Z| > 30 uT indicates tilt compensation needed');
    console.log('');

    // --- Sample and compute heading ---
    // User rotates the sensor horizontally; we compute heading from X/Y axes.
    // At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
    for (let n = 0; n < 10; n++) {
        while (!(await hmc5883l.dataReady())) {                     // Check data ready, () → bool
            await new Promise(r => setTimeout(r, 1));
        }
        const { x, y, z } = await hmc5883l.magneticField();        // Read magnetic field, () → { x: float T, y: float T, z: float T }

        // --- Compute heading from X and Y ---
        if (x !== null && y !== null) {
            const heading = Math.atan2(y, x) * 180 / Math.PI;
            const h = heading < 0 ? heading + 360 : heading;
            console.log('Heading: %.1f°', h);
        }

        // --- Vertical mount detection ---
        if (z !== null && Math.abs(z) > 30e-6) {
            console.log('[TILT WARNING] Z=%.1f uT — tilt compensation needed', z * 1e6);
        }

        if (n === 4) {
            console.log('>>> Now tilt sensor vertically <<<');
        }

        await new Promise(r => setTimeout(r, 500));
    }
}

main().catch(console.error);