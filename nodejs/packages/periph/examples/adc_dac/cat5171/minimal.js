'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { CAT5171Minimal } = require('periph/src/chips/adc_dac/cat5171');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
    const bus = parseInt(process.env.I2C_BUS || '1', 10);
    const connection = new I2CConnection(bus, CAT5171Minimal.I2C_ADDRESS);
    const pot = new CAT5171Minimal(connection);                                // Create CAT5171 driver, (connection, rAb=100000 Ω, rWiper=50 Ω)
    await pot.init();                                                          // Prepare driver, () → None

    for (const code of [0, 64, 128, 192, 255]) {
        await pot.setWiper(code);                                              // Set wiper position, (code 0–255) → None
        console.log(code, await pot.getWiper());                               // Read wiper position, () → int 0–255
        console.log(await pot.getResistance());                                // Read W–B resistance, () → float Ω
        await sleep(500);
    }

    await pot.setResistance(22000.0);                                          // Set W–B resistance, (ohms Ω) → None
    await connection.close();
})();
