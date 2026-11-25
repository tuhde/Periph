'use strict';

// Minimal example: set the volume of two audio channels with DS1881Minimal.

const { I2CConnection } = require('periph/src/connection/i2c');
const { DS1881Minimal } = require('periph/src/chips/adc_dac/ds1881');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, DS1881Minimal.I2C_ADDRESS);
    const amp = new DS1881Minimal(connection);                                // Create DS1881 driver, (connection)
    await amp.init();                                                         // Apply defaults, () → None

    for (const db of [0, 10, 20, 40]) {
        await amp.setAttenuation(0, db);                                      // Set attenuation, (channel 0–1, db dB) → None
        await amp.setAttenuation(1, db);                                      // Set attenuation, (channel 0–1, db dB) → None
        console.log(db, await amp.getAttenuation(0));                         // Read attenuation, (channel 0–1) → float dB
        await sleep(500);
    }

    await amp.mute(0);                                                        // Mute channel, (channel 0–1) → None
    await connection.close();
})();
