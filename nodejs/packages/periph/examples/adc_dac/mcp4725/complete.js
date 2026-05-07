'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MCP4725Full } = require('../../../src/chips/adc_dac/mcp4725');

const transport = new I2CTransport(1, 0x60);
const dac = new MCP4725Full(transport);

dac.set_voltage(0.5);
dac.set_raw(2048);
dac.set_voltage_eeprom(0.75);
dac.set_raw_eeprom(3000);
const result = dac.read();
console.log(result.code);
console.log(result.voltage_fraction);
console.log(result.power_down);
console.log(result.eeprom_code);
console.log(result.eeprom_power_down);
console.log(result.eeprom_ready);
console.log(result.por);
dac.set_power_down(1);
dac.wake_up();
dac.reset();
dac.is_eeprom_ready();

transport.close();