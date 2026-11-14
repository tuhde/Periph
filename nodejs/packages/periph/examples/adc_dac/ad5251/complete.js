'use strict';

// Complete example: exercises every method of AD5251Full.

const { I2CConnection } = require('periph/src/connection/i2c');
const { AD5251Full } = require('periph/src/chips/adc_dac/ad5251');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, AD5251Full.I2C_ADDRESS);
    const pot = new AD5251Full(connection);                                        // Create AD5251 Full driver, (connection, rAb=50000 Ω, rWiper=75 Ω)
    await pot.init();                                                              // Prepare driver, () → None
                                                                                   // no I²C traffic; the chip needs no initialisation

    await pot.setWiper(0, 40);                                                    // Set wiper position, (channel 0–1, code 0–63) → None
                                                                                   // writes [instruction, code]; the instruction byte selects the channel
    console.log(await pot.getWiper(0));                                            // Read wiper position, (channel 0–1) → int 0–63
                                                                                   // sends the instruction alone, then reads one byte
    await pot.setResistance(0, 25000.0);                                            // Set W–B resistance, (channel 0–1, ohms Ω) → None
                                                                                   // inverts R = D/64·RAB + RW to the nearest code
    console.log(await pot.getResistance(0));                                       // Read W–B resistance, (channel 0–1) → float Ω
                                                                                   // evaluates D/64·RAB + RW for the current code

    await pot.setBoth(8, 56);                                                    // Write both wipers, (code0 0–63, code1 0–63) → None
                                                                                   // two transactions, channel 0 then channel 1
    await pot.setRatio(0, 0.25);                                                   // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → None
                                                                                   // code = round(ratio × 64), clamped to 63
    console.log(await pot.getRatio(0));                                            // Read divider ratio, (channel 0–1) → float
                                                                                   // code / 64
    await pot.setResistanceWa(1, 15000.0);                                          // Set W–A resistance, (channel 0–1, ohms Ω) → None
                                                                                   // rheostat between W and A with B open
    console.log(await pot.getResistanceWa(1));                                     // Read W–A resistance, (channel 0–1) → float Ω
                                                                                   // (64 − D)/64·RAB + RW
    await pot.increment(0);                                                        // Step up one code, (channel=all 0–1) → None
                                                                                   // quick command; omit the channel to address both
    await pot.decrement(0);                                                        // Step down one code, (channel=all 0–1) → None
                                                                                   // quick command; saturates at 0
    await pot.double(0);                                                           // Raise by 6 dB, (channel=all 0–1) → None
                                                                                   // quick command; the code doubles, saturating at 63
    await pot.halve(0);                                                            // Lower by 6 dB, (channel=all 0–1) → None
                                                                                   // quick command; the code is halved
    await pot.store();                                                             // Store wipers in EEMEM, (channel=all 0–1) → None
                                                                                   // quick command, then ACK polling (≈ 26 ms)
    await pot.setBoth(1, 2);
    await pot.restore();                                                           // Restore wipers from EEMEM, (channel=all 0–1) → None
                                                                                   // quick command followed by NOP
    console.log(await pot.getWiper(0), await pot.getWiper(1));
    await pot.writeEemem(4, 0x5A);                                                 // Write user EEMEM byte, (index 4–15, value 0–63) → None
                                                                                   // followed by ACK polling (≈ 26 ms)
    console.log(await pot.readEemem(4));                                           // Read user EEMEM byte, (index 4–15) → int 0–63
                                                                                   // pointer write, then a one-byte read
    console.log(await pot.readTolerance(0));                                       // Read stored RAB tolerance, (channel 0–1) → float %
                                                                                   // sign-magnitude integer byte + decimal byte / 256
    console.log(await pot.rabActual(0));                                           // Actual RAB of a channel, (channel 0–1) → float Ω
                                                                                   // rAb × (1 + tolerance / 100)
    await pot.nop();                                                               // Send NOP, () → None
                                                                                   // quick command 0x80; the chip returns to idle
    pot.setRab(50000.0, 120.0);                                                    // Change resistance model, (rAb Ω, rWiper=unchanged Ω) → None
                                                                                   // affects conversions only; no I²C traffic
    await sleep(1000);
    await pot.reset();                                                             // Reset from EEMEM, () → None
                                                                                   // quick command 0xB8, then ACK polling
    await connection.close();
})();
