'use strict';

// Complete example: exercises every method of DS1881Full.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS1881Full } = require('periph/src/chips/adc_dac/ds1881');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DS1881Full.I2C_ADDRESS);
    const amp = new DS1881Full(connection);                                   // Create DS1881 Full driver, (connection)
    await amp.init();                                                         // Apply defaults, () → None
                                                                              // waits 1 ms, rewrites the config only if it differs

    await amp.setAttenuation(0, 12);                                          // Set attenuation, (channel 0–1, db dB) → None
                                                                              // nearest position, one command byte
    console.log(await amp.getAttenuation(0));                                 // Read attenuation, (channel 0–1) → float dB
                                                                              // three-byte round-robin read, position in dB
    await amp.setPosition(1, 30);                                             // Set raw wiper position, (channel 0–1, position 0–63) → None
                                                                              // position 63 is mute in Option 1
    console.log(await amp.getPosition(1));                                    // Read wiper position, (channel 0–1) → int
                                                                              // low six bits of the read-back byte
    await amp.mute(1);                                                        // Mute channel, (channel 0–1) → None
                                                                              // writes the mute position (63)

    await amp.setBoth(5, 25);                                                 // Set both wipers, (position0 0–63, position1 0–63) → None
                                                                              // one transaction, both wipers update together
    await amp.setAttenuationBoth(20);                                         // Set both attenuations, (db0 dB, db1=db0 dB) → None
                                                                              // db1 defaults to db0 (stereo volume)
    console.log(await amp.readAll());                                         // Read everything, () → [position0, position1, config]
                                                                              // one three-byte round-robin read

    await amp.configure(2, true, false);                                      // Configure, (option=1 1–2, zeroCrossing=true, nonvolatile=false) → None
                                                                              // writes the config byte only if it changed
    console.log(await amp.getConfig());                                       // Read configuration, () → {option, zeroCrossing, nonvolatile, raw}
                                                                              // fresh read; refreshes the driver's table
    console.log(amp.positionToDb(13), amp.maxPosition());                     // Table lookup, (position) → float dB; Last non-mute position, () → int
                                                                              // Option 2: 13 → 14 dB, max position 32
    console.log(amp.dbToPosition(39));                                        // Nearest position, (db dB) → int
                                                                              // 70 dB and above map to mute
    await amp.setOption(1);                                                   // Select option, (option 1–2) → None
                                                                              // positions are not converted
    await amp.setZeroCrossing(false);                                         // Set zero-crossing, (enabled bool) → None
                                                                              // ZC bit of the configuration
    await amp.setZeroCrossing(true);                                          // Set zero-crossing, (enabled bool) → None
                                                                              // clickless updates again
    await amp.waitReady(0.05);                                                // Acknowledge-poll, (timeoutS=0.05 s) → None
                                                                              // resolves once the chip ACKs its address

    await amp.setNonvolatile(true);                                           // Select EEPROM storage, (enabled bool) → None
                                                                              // V/NV bit; every wiper write now wears the EEPROM (50 000 writes)
    await amp.store();                                                        // Store positions, () → None
                                                                              // rewrites both wipers and polls for completion
    await amp.setNonvolatile(false);                                          // Select EEPROM storage, (enabled bool) → None
                                                                              // back to volatile wipers
    await sleep(1000);
    await amp.reset();                                                        // Reset, () → None
                                                                              // volatile, zero-crossing, Option 1, both wipers muted
    await connection.close();
})();
