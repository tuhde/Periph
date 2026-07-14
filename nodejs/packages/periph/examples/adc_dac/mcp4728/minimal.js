'use strict';

const { I2CTransport } = require('periph/src/transport/i2c');
const { MCP4728Minimal } = require('periph/src/chips/adc_dac/mcp4728');

const transport = new I2CTransport(1, 0x60);     // Create I2C transport, (bus=1, addr=0x60)
const dac = new MCP4728Minimal(transport);        // Create MCP4728 driver, (transport)

dac.set_voltage(0, 0.5);                           // Set channel A as fraction of V_DD, (channel=0–3, fraction=0.0–1.0) → None
dac.set_raw(1, 2048);                              // Set channel B raw 12-bit code, (channel=0–3, code=0–4095) → None
dac.set_all([0.0, 0.25, 0.5, 1.0]);                // Update all four channels simultaneously, (fractions[4]) → None
