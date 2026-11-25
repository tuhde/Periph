'use strict';

// Programmable level trimmer: sweeps the wiper in steps of 16 printing the
// W–B resistance, sets a 22 kΩ rheostat and reports the quantisation error,
// demonstrates shutdown/resume, midscale and reset, and steps the wiper as a
// voltage divider (A at VDD, B at GND) printing the expected output voltage
// at VDD = 3.3 V.

const { I2CConnection } = require('periph/src/connection/i2c');
const { CAT5171Full } = require('periph/src/chips/adc_dac/cat5171');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const VDD = 3.3;

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, CAT5171Full.I2C_ADDRESS);
    const pot = new CAT5171Full(connection);                                   // Create CAT5171 Full driver, (connection, rAb=100000 Ω, rWiper=50 Ω)
    await pot.init();                                                          // Prepare driver, () → None

    // --- Sweep the wiper across the full range ---
    // Zero scale is not 0 Ω (it is the wiper resistance) and code 255 sits one
    // LSB below the RAB end.
    for (let code = 0; code < 256; code += 16) {
        await pot.setWiper(code);                                              // Set wiper position, (code 0–255) → None
        const r = await pot.getResistance();                                   // Read W–B resistance, () → float Ω
        console.log(`code ${code} -> ${r.toFixed(1)} Ω`);
        await sleep(200);
    }

    // --- Set a 22 kΩ rheostat ---
    // The 256 steps quantise the resistance (~390 Ω per code on a 100 kΩ part),
    // so the readback differs slightly from the request.
    await pot.setResistance(22000.0);                                          // Set W–B resistance, (ohms Ω) → None
    const rheo = await pot.getResistance();                                    // Read W–B resistance, () → float Ω
    console.log(`rheostat ${rheo.toFixed(1)} Ω, error ${(rheo - 22000.0).toFixed(1)} Ω`);

    // --- Shutdown, resume, midscale and reset ---
    // Shutdown opens A and shorts the wiper to B while the WCR keeps its value,
    // so resume restores the setting; midscale and reset both return to 0x80.
    const kept = await pot.getWiper();                                         // Read wiper position, () → int 0–255
    await pot.shutdown();                                                      // Shut down, () → None
    console.log('shutdown:', pot.isShutdown(), 'kept', (await pot.getWiper()) === kept); // Query shutdown state, () → bool
    await sleep(1000);
    await pot.resume();                                                        // Leave shutdown, () → None
    await pot.midscale();                                                      // Move wiper to midscale, () → None
    await pot.reset();                                                         // Midscale reset, () → None

    // --- Use the wiper as a voltage divider ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 256), so 0.999 lands on the top code 255.
    for (const ratio of [0.0, 0.25, 0.5, 0.75, 0.999]) {
        await pot.setRatio(ratio);                                             // Set divider ratio, (ratio 0.0–1.0) → None
        const actual = await pot.getRatio();                                   // Read divider ratio, () → float
        console.log(`ratio ${ratio.toFixed(3)} -> ${actual.toFixed(4)} -> ${(actual * VDD).toFixed(3)} V`);
        await sleep(500);
    }
    await connection.close();
})();
