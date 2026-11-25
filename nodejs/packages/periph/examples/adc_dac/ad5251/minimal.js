'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { AD5251Minimal } = require('periph/src/chips/adc_dac/ad5251');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, AD5251Minimal.I2C_ADDRESS);
    const pot = new AD5251Minimal(connection);                                     // Create AD5251 driver, (connection, rAb=50000 Ω, rWiper=75 Ω)
    await pot.init();                                                              // Prepare driver, () → None

    for (const code of [0, 16, 32, 48, 63]) {
        await pot.setWiper(0, code);                                               // Set wiper position, (channel 0–1, code 0–63) → None
        console.log(code, await pot.getWiper(0));                                  // Read wiper position, (channel 0–1) → int 0–63
        console.log(await pot.getResistance(0));                                   // Read W–B resistance, (channel 0–1) → float Ω
        await sleep(500);
    }

    await pot.setResistance(1, 20000.0);                                            // Set W–B resistance, (channel 0–1, ohms Ω) → None
    await connection.close();
})();
