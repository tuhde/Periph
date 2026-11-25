'use strict';

// Complete example: exercises every method of AD5243Full.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AD5243Full } = require('periph/src/chips/adc_dac/ad5243');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, AD5243Full.I2C_ADDRESS);
    const pot = new AD5243Full(connection);                                        // Create AD5243 Full driver, (connection, rAb=10000 Ω, rWiper=160 Ω)
    await pot.init();                                                              // Prepare driver, () → None
                                                                                   // no I²C traffic; the chip needs no initialisation

    await pot.setWiper(0, 100);                                                    // Set wiper position, (channel 0–1, code 0–255) → None
                                                                                   // writes [instruction, code]; the instruction byte selects the channel
    console.log(await pot.getWiper(0));                                            // Read wiper position, (channel 0–1) → int 0–255
                                                                                   // sends the instruction alone, then reads one byte
    await pot.setResistance(0, 5000.0);                                            // Set W–B resistance, (channel 0–1, ohms Ω) → None
                                                                                   // inverts R = D/256·RAB + 2·RW to the nearest code
    console.log(await pot.getResistance(0));                                       // Read W–B resistance, (channel 0–1) → float Ω
                                                                                   // evaluates D/256·RAB + 2·RW for the current code

    await pot.setBoth(32, 224);                                                    // Write both wipers, (code1 0–255, code2 0–255) → None
                                                                                   // two transactions, channel 0 then channel 1
    await pot.setRatio(0, 0.25);                                                   // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
                                                                                   // code = round(ratio × 256), clamped to 255
    console.log(await pot.getRatio(0));                                            // Read divider ratio, (channel 0–1) → float
                                                                                   // code / 256
    await pot.setResistanceWa(1, 3000.0);                                          // Set W–A resistance, (channel 0–1, ohms Ω) → None
                                                                                   // rheostat between W and A with B open
    console.log(await pot.getResistanceWa(1));                                     // Read W–A resistance, (channel 0–1) → float Ω
                                                                                   // (256 − D)/256·RAB + 2·RW
    console.log(await pot.increment(0, 5));                                        // Step the wiper, (channel 0–1, steps=1) → int 0–255
                                                                                   // read, add, clamp, write; returns the new code

    await pot.shutdown(1);                                                         // Shut channel down, (channel 0–1) → None
                                                                                   // SD = 1: A opens, wiper shorts to B, RDAC kept
    console.log(pot.isShutdown(1));                                                // Query shutdown state, (channel 0–1) → bool
                                                                                   // driver-tracked; the chip cannot report it
    await sleep(1000);
    await pot.resume(1);                                                           // Leave shutdown, (channel 0–1) → None
                                                                                   // rewrites the kept code with SD = 0

    pot.setRab(50000.0, 120.0);                                                    // Change resistance model, (rAb Ω, rWiper Ω) → None
                                                                                   // affects conversions only; no I²C traffic
    await pot.reset();                                                             // Restore power-up preset, () → None
                                                                                   // writes 0x80 to both channels with SD = 0
    await connection.close();
})();
