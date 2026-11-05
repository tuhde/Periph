'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AD5248Full } = require('../../../src/chips/adc_dac/ad5248');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x2C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const pot = new AD5248Full(connection, 10000.0, 160.0);     // Create AD5248 driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Full

(async () => {
    // --- Wiper position ---
    await pot.setWiper(0, 100);                             // Set wiper, (channel 0-1, code 0-255) → void
                                                            // writes [channel << 7, code]; code clamped to 0-255
    console.log(await pot.getWiper(0));                     // Read wiper, (channel 0-1) → int 0-255
                                                            // sends the instruction byte alone, then reads one byte
    await pot.setBoth(10, 20);                              // Set both wipers, (code1 0-255, code2 0-255) → void
                                                            // two transactions, one per channel

    // --- Resistance ---
    await pot.setResistance(1, 2200.0);                     // Set W-B resistance, (channel 0-1, ohms) → void
                                                            // nearest code of R = code/256*RAB + 2*RW, clamped
    console.log(await pot.getResistance(1));                // Read W-B resistance, (channel 0-1) → float Ω
                                                            // code/256*RAB + 2*RW

    // --- Ratio and increment ---
    await pot.setRatio(0, 0.5);                             // Set wiper ratio, (channel 0-1, ratio 0.0-1.0) → void
                                                            // code = round(ratio*256) clamped to 255; needs terminal A wired
    console.log(await pot.getRatio(0));                     // Read wiper ratio, (channel 0-1) → float 0.0-1.0
                                                            // code / 256
    console.log(await pot.increment(0, 5));                 // Step wiper, (channel 0-1, steps=1) → int 0-255
                                                            // read-modify-write, clamped to 0-255; negative steps decrement

    // --- Shutdown ---
    await pot.shutdown(1);                                  // Shut channel down, (channel 0-1) → void
                                                            // opens terminal A, shorts wiper to B, RDAC kept
    console.log(pot.isShutdown(1));                         // Query driver-tracked state, (channel 0-1) → bool
                                                            // the chip cannot be read back
    await pot.resume(1);                                    // Leave shutdown, (channel 0-1) → void
                                                            // rewrites the RDAC with SD = 0

    // --- Model and reset ---
    pot.setRab(10000.0, 160.0);                             // Set resistance model, (rAb Ω, rWiper Ω) → void
                                                            // no I²C traffic; affects conversions only
    await pot.reset();                                      // Reset to power-up preset, () → void
                                                            // writes midscale 0x80 to both channels with SD = 0
    await connection.close();
})();
