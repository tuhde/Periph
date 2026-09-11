'use strict';
const { I2CConnection } = require('../../../src/connection/i2c');
const { LPS33HWFull } = require('../../../src/chips/pressure/lps33hw');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x5C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);

// --- Altimeter preset: 10 Hz ODR with ODR/20 LPF ---
// 10 Hz gives a fresh pressure sample every 100 ms, and the on-chip low-pass
// filter at ODR/20 (~0.5 Hz cutoff) suppresses door-slam / ventilation noise.
const lps = new LPS33HWFull(connection);                 // Create LPS33HW driver, (connection)

(async () => {
    await lps.configure(LPS33HWFull.ODR_10_HZ, 1, 1, LPS33HWFull.LPFP_BW_ODR_20, 0, 0);  // Configure chip, (odr=10Hz, bdu=1, enLpfp=1, lpfpCfg=ODR/20, lcEn=0, sim=0) → undefined

    // --- Settle, then log altitude at 1 Hz for 10 s ---
    // The first AUTOZERO locks the local zero reference; later samples drift
    // relative to it, giving floor-to-floor relative altitude changes.
    await lps.setAutozero();                            // Enable AUTOZERO, () → undefined
    await new Promise((r) => setTimeout(r, 1000));

    const alts = [];
    for (let n = 0; n < 10; n++) {
        const sample = await lps.oneShot();             // Single-shot read, () → {pressure_Pa: number, temperature_C: number}
        const alt = 44330 * (1 - Math.pow(sample.pressure_Pa / 101325, 1 / 5.255));
        alts.push(alt);
        console.log(`${n}s: T=${sample.temperature_C.toFixed(2)} C, P=${sample.pressure_Pa.toFixed(1)} Pa, alt=${alt.toFixed(2)} m`);
        await new Promise((r) => setTimeout(r, 1000));
    }

    // --- Refresh the AUTOZERO every 10 s to track weather drift ---
    // Atmospheric pressure changes by ~1 hPa per 8 m of equivalent altitude,
    // so an unattended sensor will wander several metres over 10 minutes if
    // the reference isn't periodically re-zeroed.
    await lps.setAutozero();                            // Enable AUTOZERO, () → undefined

    console.log(`Altimeter: min=${Math.min(...alts).toFixed(2)} max=${Math.max(...alts).toFixed(2)} span=${(Math.max(...alts) - Math.min(...alts)).toFixed(2)} m`);

    await connection.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
