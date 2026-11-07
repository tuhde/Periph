'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { Mcp4xxxMinimal } = require('../../../src/chips/adc_dac/mcp4xxx');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x58', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const pot = new Mcp4xxxMinimal(connection, 'MCP4532', 10000.0, 75.0);  // Create MCP4XXX driver, (connection, part='MCP4532', rAb=10000 Ω, rWiper=75 Ω) → Mcp4xxxMinimal

(async () => {
    await pot.init();                                       // Bus check, () → void
    await pot.setWiper(32);                                 // Set wiper, (code 0-128, channel=0) → void
    await pot.setResistance(2200.0);                        // Set W-B resistance, (ohms, channel=0) → void

    setInterval(async () => {
        const code = await pot.getWiper();                  // Read wiper, (channel=0) → int 0-128
        const ohms = await pot.getResistance();             // Read W-B resistance, (channel=0) → float Ω
        console.log('code=%d  R_WB=%s ohm', code, ohms.toFixed(0));
    }, 1000);
})();
