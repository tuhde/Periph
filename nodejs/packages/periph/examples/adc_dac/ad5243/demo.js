'use strict';

// Voltage-divider level setter: steps channel 0 as a divider (A at VDD, B at
// GND) through several ratios and prints the expected output voltage at
// VDD = 3.3 V; sets channel 1 as a 2.2 kΩ rheostat and reports the
// quantisation error; demonstrates shutdown/resume and reset.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AD5243Full } = require('periph/src/chips/adc_dac/ad5243');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const VDD = 3.3;

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, AD5243Full.I2C_ADDRESS);
    const pot = new AD5243Full(connection);                                        // Create AD5243 Full driver, (connection, rAb=10000 Ω, rWiper=160 Ω)
    await pot.init();                                                              // Prepare driver, () → None

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    for (const ratio of [0.0, 0.25, 0.5, 0.75, 0.999]) {
        await pot.setRatio(0, ratio);                                              // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
        const code = await pot.getWiper(0);                                        // Read wiper position, (channel 0–1) → int 0–255
        const actual = await pot.getRatio(0);                                      // Read divider ratio, (channel 0–1) → float
        console.log(`ratio ${ratio.toFixed(3)} -> code ${code} (${actual.toFixed(4)}) -> ${(actual * VDD).toFixed(3)} V`);
        await sleep(500);
    }

    // --- Use channel 1 as a 2.2 kΩ rheostat ---
    // The 256 steps quantise the resistance (~39 Ω per code on a 10 kΩ part), so
    // the readback differs slightly from the request.
    await pot.setResistance(1, 2200.0);                                            // Set W–B resistance, (channel 0–1, ohms Ω) → None
    const r = await pot.getResistance(1);                                          // Read W–B resistance, (channel 0–1) → float Ω
    console.log(`rheostat ${r.toFixed(1)} Ω, error ${(r - 2200.0).toFixed(1)} Ω`);

    // --- Shut channel 1 down and bring it back ---
    // Shutdown opens A and shorts the wiper to B (about 0 Ω) while the RDAC keeps
    // its value, so resume restores the 2.2 kΩ setting.
    await pot.shutdown(1);                                                         // Shut channel down, (channel 0–1) → None
    console.log('shutdown:', pot.isShutdown(1));                                   // Query shutdown state, (channel 0–1) → bool
    await sleep(1000);
    await pot.resume(1);                                                           // Leave shutdown, (channel 0–1) → None

    // --- Restore the power-up state ---
    // The chip has no reset pin, so reset() rewrites midscale to both channels.
    await pot.reset();                                                             // Restore power-up preset, () → None
    await connection.close();
})();
