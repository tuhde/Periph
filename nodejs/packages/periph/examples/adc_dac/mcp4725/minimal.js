'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MCP4725Minimal } = require('../../../src/chips/adc_dac/mcp4725');

const transport = new I2CTransport(1, 0x60);
const dac = new MCP4725Minimal(transport);

dac.set_voltage(0.5);