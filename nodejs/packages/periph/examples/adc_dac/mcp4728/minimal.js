'use strict';

const { I2CConnection } = require('periph/src/connection/i2c');
const { MCP4728Minimal } = require('periph/src/chips/adc_dac/mcp4728');

const connection = new I2CConnection(1, 0x60);     // Create I2C connection, (bus=1, addr=0x60)
const dac = new MCP4728Minimal(connection);        // Create MCP4728 driver, (connection)

(async () => {
    await dac.set_voltage(0, 0.5);                 // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → None
    await dac.set_raw(1, 2048);                    // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → None
    await dac.set_all([0.0, 0.25, 0.5, 1.0]);      // Update all four channels simultaneously, (fractions[4]) → None
})();
