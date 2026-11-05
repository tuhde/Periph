'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { AD5243Minimal } = require('periph/src/chips/adc_dac/ad5243');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, AD5243Minimal.I2C_ADDRESS);
    const pot = new AD5243Minimal(connection);                                     // Create AD5243 driver, (connection, rAb=10000 Ω, rWiper=160 Ω)
    await pot.init();                                                              // Prepare driver, () → None

    for (const code of [0, 64, 128, 192, 255]) {
        await pot.setWiper(0, code);                                               // Set wiper position, (channel 0–1, code 0–255) → None
        console.log(code, await pot.getWiper(0));                                  // Read wiper position, (channel 0–1) → int 0–255
        console.log(await pot.getResistance(0));                                   // Read W–B resistance, (channel 0–1) → float Ω
        await sleep(500);
    }

    await pot.setResistance(1, 2200.0);                                            // Set W–B resistance, (channel 0–1, ohms Ω) → None
    await connection.close();
})();
