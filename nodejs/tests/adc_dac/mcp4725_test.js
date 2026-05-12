'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { MCP4725Full }   = require('../../packages/periph/src/chips/adc_dac/mcp4725');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const dac = new MCP4725Full(transport);

dac.set_voltage(0.5);
checkTrue('set_voltage(0.5) accepted', true);

dac.set_raw(2048);
checkTrue('set_raw(2048) accepted', true);

dac.set_voltage_eeprom(0.5);
checkTrue('set_voltage_eeprom(0.5) accepted', true);

dac.set_raw_eeprom(2048);
checkTrue('set_raw_eeprom(2048) accepted', true);

const state = dac.read();
checkTrue('read returns code', state.code <= 4095);
checkTrue('read returns eeprom_code', state.eeprom_code <= 4095);
checkTrue('read returns voltage_fraction', state.voltage_fraction >= 0.0 && state.voltage_fraction <= 1.0);

dac.set_power_down(MCP4725Full.PD_NORMAL);
checkTrue('set_power_down(NORMAL) accepted', true);

dac.set_power_down(MCP4725Full.PD_1K_GND);
checkTrue('set_power_down(1K) accepted', true);

dac.set_power_down(MCP4725Full.PD_100K_GND);
checkTrue('set_power_down(100K) accepted', true);

dac.set_power_down(MCP4725Full.PD_500K_GND);
checkTrue('set_power_down(500K) accepted', true);

dac.wake_up();
checkTrue('wake_up accepted', true);

dac.reset();
checkTrue('reset accepted', true);

const ready = dac.is_eeprom_ready();
checkTrue('is_eeprom_ready returns bool', typeof ready === 'boolean');

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);