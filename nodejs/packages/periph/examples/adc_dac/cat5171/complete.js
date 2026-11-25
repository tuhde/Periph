'use strict';

// Complete example: exercises every method of CAT5171Full.

const { I2CConnection } = require('periph/src/connection/i2c');
const { CAT5171Full } = require('periph/src/chips/adc_dac/cat5171');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, CAT5171Full.I2C_ADDRESS);
    const pot = new CAT5171Full(connection);                                   // Create CAT5171 Full driver, (connection, rAb=100000 Ω, rWiper=50 Ω)
    await pot.init();                                                          // Prepare driver, () → None
                                                                               // no I²C traffic; the chip needs no initialisation

    await pot.setWiper(100);                                                   // Set wiper position, (code 0–255) → None
                                                                               // writes [instruction, code] with RS = 0, SD = 0
    console.log(await pot.getWiper());                                         // Read wiper position, () → int 0–255
                                                                               // bare one-byte read; no instruction byte is sent
    await pot.setResistance(50000.0);                                          // Set W–B resistance, (ohms Ω) → None
                                                                               // inverts R = D/256·RAB + RW to the nearest code
    console.log(await pot.getResistance());                                    // Read W–B resistance, () → float Ω
                                                                               // evaluates D/256·RAB + RW for the current code

    await pot.setRatio(0.25);                                                  // Set divider ratio, (ratio 0.0–1.0) → None
                                                                               // code = round(ratio × 256), clamped to 255
    console.log(await pot.getRatio());                                         // Read divider ratio, () → float
                                                                               // code / 256
    await pot.setResistanceWa(30000.0);                                        // Set W–A resistance, (ohms Ω) → None
                                                                               // rheostat between W and A with B open
    console.log(await pot.getResistanceWa());                                  // Read W–A resistance, () → float Ω
                                                                               // (256 − D)/256·RAB + RW
    console.log(await pot.increment(5));                                       // Step the wiper, (steps=1) → int 0–255
                                                                               // read, add, clamp, write; returns the new code

    await pot.midscale();                                                      // Move wiper to midscale, () → None
                                                                               // RS = 1 instruction; wiper goes to 0x80
    await pot.shutdown();                                                      // Shut down, () → None
                                                                               // SD = 1: A opens, wiper shorts to B, WCR kept
    console.log(pot.isShutdown());                                             // Query shutdown state, () → bool
                                                                               // driver-tracked; the chip cannot report it
    await sleep(1000);
    await pot.resume();                                                        // Leave shutdown, () → None
                                                                               // rewrites the kept code with SD = 0

    pot.setRab(50000.0, 120.0);                                                // Change resistance model, (rAb Ω, rWiper Ω) → None
                                                                               // affects conversions only; no I²C traffic
    await pot.reset();                                                         // Midscale reset, () → None
                                                                               // RS = 1, SD = 0; wiper returns to 0x80
    await connection.close();
})();
