'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { MCP4728Full }   = require('../../packages/periph/src/chips/adc_dac/mcp4728');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const dac = new MCP4728Full(transport);

dac.set_voltage(0, 0.5);
checkTrue('set_voltage(ch0, 0.5) accepted', true);

dac.set_raw(1, 2048);
checkTrue('set_raw(ch1, 2048) accepted', true);

dac.set_all([0.0, 0.25, 0.5, 1.0]);
checkTrue('set_all accepted', true);

dac.set_voltage_eeprom(0, 0.5, 0, 1);
checkTrue('set_voltage_eeprom accepted', true);

dac.set_raw_eeprom(1, 2048, 0, 1);
checkTrue('set_raw_eeprom accepted', true);

dac.set_all_eeprom([0.0, 0.25, 0.5, 0.75], [0, 0, 0, 0], [1, 1, 1, 1]);
checkTrue('set_all_eeprom accepted', true);

dac.set_vref(0, 0, 0, 0);
checkTrue('set_vref accepted', true);

dac.set_gain(1, 1, 1, 1);
checkTrue('set_gain accepted', true);

dac.set_power_down(MCP4728Full.PD_NORMAL, MCP4728Full.PD_NORMAL,
                   MCP4728Full.PD_NORMAL, MCP4728Full.PD_NORMAL);
checkTrue('set_power_down normal accepted', true);

dac.set_power_down(MCP4728Full.PD_1K_GND, MCP4728Full.PD_100K_GND,
                   MCP4728Full.PD_500K_GND, MCP4728Full.PD_NORMAL);
checkTrue('set_power_down modes accepted', true);

const state = dac.read();
checkTrue('read returns object', typeof state === 'object');
checkTrue('read returns 4 channels', state.channel.length === 4);
checkTrue('ch0 code in range', state.channel[0].code >= 0 && state.channel[0].code <= 4095);
checkTrue('ch0 vref valid', state.channel[0].vref === 0 || state.channel[0].vref === 1);
checkTrue('ch0 gain valid', state.channel[0].gain === 1 || state.channel[0].gain === 2);
checkTrue('ch0 eeprom_code in range', state.channel[0].eeprom_code >= 0 && state.channel[0].eeprom_code <= 4095);
checkTrue('eeprom_ready in state', typeof state.eeprom_ready === 'boolean');

const ready = dac.is_eeprom_ready();
checkTrue('is_eeprom_ready returns bool', typeof ready === 'boolean');

dac.software_update();
checkTrue('software_update accepted', true);

dac.wake_up();
checkTrue('wake_up accepted', true);

dac.reset();
checkTrue('reset accepted', true);

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
