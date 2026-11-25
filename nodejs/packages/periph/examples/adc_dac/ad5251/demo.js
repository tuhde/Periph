'use strict';

// Voltage-divider level setter: steps channel 0 as a divider (A at VDD, B at
// GND) through several ratios and prints the expected output voltage at
// VDD = 3.3 V; sets channel 1 as a 20 kΩ rheostat and reports the
// quantisation error; steps the wiper with the quick commands, stores and restores it, reads and
// writes the user EEMEM, prints the stored RAB tolerance and resets from EEMEM.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AD5251Full } = require('periph/src/chips/adc_dac/ad5251');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const VDD = 3.3;

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, AD5251Full.I2C_ADDRESS);
    const pot = new AD5251Full(connection);                                        // Create AD5251 Full driver, (connection, rAb=50000 Ω, rWiper=75 Ω)
    await pot.init();                                                              // Prepare driver, () → None

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 64), so 0.999 lands on the top code 63.
    for (const ratio of [0.0, 0.25, 0.5, 0.75, 0.999]) {
        await pot.setRatio(0, ratio);                                              // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
        const code = await pot.getWiper(0);                                        // Read wiper position, (channel 0–1) → int 0–63
        const actual = await pot.getRatio(0);                                      // Read divider ratio, (channel 0–1) → float
        console.log(`ratio ${ratio.toFixed(3)} -> code ${code} (${actual.toFixed(4)}) -> ${(actual * VDD).toFixed(3)} V`);
        await sleep(500);
    }

    // --- Use channel 1 as a 20 kΩ rheostat ---
    // The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
    // the readback differs slightly from the request.
    await pot.setResistance(1, 20000.0);                                            // Set W–B resistance, (channel 0–1, ohms Ω) → None
    const r = await pot.getResistance(1);                                          // Read W–B resistance, (channel 0–1) → float Ω
    console.log(`rheostat ${r.toFixed(1)} Ω, error ${(r - 20000.0).toFixed(1)} Ω`);

    // --- Step and scale the wiper with the quick commands ---
    // increment/decrement move one code, double/halve change the level by 6 dB.
    await pot.setWiper(0, 16);                                                     // Set wiper position, (channel 0–1, code 0–63) → None
    await pot.increment(0);                                                        // Quick command, (channel=all 0–1) → None
    console.log('increment ->', await pot.getWiper(0));
    await pot.decrement(0);                                                        // Quick command, (channel=all 0–1) → None
    console.log('decrement ->', await pot.getWiper(0));
    await pot.double(0);                                                           // Quick command, (channel=all 0–1) → None
    console.log('double ->', await pot.getWiper(0));
    await pot.halve(0);                                                            // Quick command, (channel=all 0–1) → None
    console.log('halve ->', await pot.getWiper(0));

    // --- Make the settings persistent ---
    // store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
    // restore() brings the stored values back.
    await pot.store();                                                             // Store wipers in EEMEM, (channel=all 0–1) → None
    await pot.setBoth(1, 2);                                                       // Write both wipers, (code0 0–63, code1 0–63) → None
    await pot.restore();                                                           // Restore wipers from EEMEM, (channel=all 0–1) → None
    console.log('restored:', await pot.getWiper(0), await pot.getWiper(1));

    // --- User EEMEM bytes and the factory tolerance ---
    await pot.writeEemem(4, 0x40);                                                 // Write user EEMEM byte, (index 4–15, value 0–63) → None
    console.log('eemem 4', await pot.readEemem(4));                                // Read user EEMEM byte, (index 4–15) → int 0–63
    console.log('tolerance', (await pot.readTolerance(0)).toFixed(3), '%');        // Read stored RAB tolerance, (channel 0–1) → float %
    console.log('RAB', (await pot.rabActual(0)).toFixed(1), 'Ω');                  // Actual RAB of a channel, (channel 0–1) → float Ω

    // --- Restore the stored state ---
    // reset() restores both wipers from EEMEM and waits for the write cycle.
    await pot.reset();                                                             // Reset from EEMEM, () → None
    await connection.close();
})();
