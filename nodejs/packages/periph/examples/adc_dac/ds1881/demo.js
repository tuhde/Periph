'use strict';

// Demo: stereo volume control with mute and optional EEPROM store.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS1881Full } = require('periph/src/chips/adc_dac/ds1881');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const STORE = process.argv.includes('--store');

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DS1881Full.I2C_ADDRESS);
    const amp = new DS1881Full(connection);                                   // Create DS1881 Full driver, (connection)
    await amp.init();                                                         // Apply defaults, () → None

    // --- Fade the volume down and back up ---
    // Zero-crossing detection (on by default) moves the wiper only at a signal
    // zero crossing, so each 2 dB step is clickless; 100 ms per step is longer
    // than the 50 ms maximum zero-crossing window.
    const steps = [];
    for (let db = 0; db <= 40; db += 2) steps.push(db);
    for (let db = 38; db >= 0; db -= 2) steps.push(db);
    for (const db of steps) {
        await amp.setAttenuationBoth(db);                                     // Set both attenuations, (db0 dB, db1=db0 dB) → None
        const [p0, p1] = await amp.readAll();                                 // Read everything, () → [position0, position1, config]
        console.log(`${db} dB -> positions ${p0} ${p1}`);
        await sleep(100);
    }

    // --- Mute and unmute one channel ---
    // Muting channel 0 leaves channel 1 playing; restoring writes the position again.
    await amp.mute(0);                                                        // Mute channel, (channel 0–1) → None
    console.log('channel 0 muted:', await amp.getAttenuation(0));             // Read attenuation, (channel 0–1) → float dB
    await sleep(1000);
    await amp.setAttenuation(0, 0);                                           // Set attenuation, (channel 0–1, db dB) → None

    // --- Inspect the Option 2 attenuation table ---
    // Option 2 (DS1808 compatible) uses 1, 2 and 3 dB steps; positions keep their
    // numeric value when the option changes, so re-set them afterwards.
    await amp.setOption(2);                                                   // Select option, (option 1–2) → None
    for (const position of [0, 12, 13, 24, 25, 32, 33]) {
        console.log('position', position, '->', amp.positionToDb(position), 'dB'); // Table lookup, (position) → float dB
    }
    await amp.setOption(1);                                                   // Select option, (option 1–2) → None
    await amp.setAttenuationBoth(10);                                         // Set both attenuations, (db0 dB, db1=db0 dB) → None
    console.log(await amp.getConfig());                                       // Read configuration, () → {option, zeroCrossing, nonvolatile, raw}

    // --- Optionally persist the volume ---
    // Stored positions are restored at power-up. The EEPROM is rated for only
    // 50 000 writes, so this runs only when requested with --store.
    if (STORE) {
        console.log('WARNING: EEPROM endurance is 50 000 writes');
        await amp.setNonvolatile(true);                                       // Select EEPROM storage, (enabled bool) → None
        const start = Date.now();
        await amp.store();                                                    // Store positions, () → None
        console.log('store took', Date.now() - start, 'ms');
        await amp.setNonvolatile(false);                                      // Select EEPROM storage, (enabled bool) → None
    }
    await connection.close();
})();
