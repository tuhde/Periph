'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MCP4725Minimal } = require('../../../src/chips/adc_dac/mcp4725');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const dac = new MCP4725Minimal(transport);

dac.set_voltage(0.5);                          // Set output as fraction of V_DD, (fraction=0.0–1.0) → None
dac.set_raw(2048);                             // Set raw 12-bit code, (code=0–4095) → None
console.log('MCP4725 minimal running');