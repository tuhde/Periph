'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { AD5248Minimal } = require('../../../src/chips/adc_dac/ad5248');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x2C', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const pot = new AD5248Minimal(connection, 10000.0, 160.0);  // Create AD5248 driver, (connection, rAb=10000 Ω, rWiper=160 Ω) → AD5248Minimal

(async () => {
    await pot.setWiper(0, 64);                              // Set channel 0 wiper, (channel 0-1, code 0-255) → void
    await pot.setResistance(1, 2200.0);                     // Set channel 1 W-B resistance, (channel 0-1, ohms) → void

    setInterval(async () => {
        const code = await pot.getWiper(0);                 // Read channel 0 wiper, (channel 0-1) → int 0-255
        const ohms = await pot.getResistance(0);            // Read channel 0 W-B resistance, (channel 0-1) → float Ω
        console.log('ch0 code=%d  R_WB=%d ohm', code, ohms.toFixed(0));
    }, 1000);
})();
