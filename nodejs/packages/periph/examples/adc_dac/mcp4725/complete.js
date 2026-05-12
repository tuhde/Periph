'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { MCP4725Full } = require('../../../src/chips/adc_dac/mcp4725');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const dac = new MCP4725Full(transport);

dac.set_voltage(0.75);                         // Set output as fraction of V_DD, (fraction=0.0–1.0) → None
                                                 // converts fraction to 12-bit code and issues Fast Write
dac.set_raw(3000);                             // Set raw 12-bit code, (code=0–4095) → None
                                                 // clamps to [0, 4095] and writes DAC register only
dac.set_voltage_eeprom(0.5);                  // Set output and persist to EEPROM, (fraction=0.0–1.0) → None
                                                 // writes both DAC register and EEPROM for power-cycle persistence
dac.set_raw_eeprom(2048);                      // Set raw code and persist to EEPROM, (code=0–4095) → None
                                                 // writes both DAC register and EEPROM for power-cycle persistence
const state = dac.read();                      // Read DAC and EEPROM registers, () → dict
                                                 // returns code, voltage_fraction, power_down, eeprom_code, eeprom_power_down, eeprom_ready
console.log('code:', state.code);
console.log('voltage_fraction:', state.voltage_fraction);
console.log('eeprom_ready:', state.eeprom_ready);
dac.set_power_down(MCP4725Full.PD_100K_GND); // Set power-down mode with code preserved, (mode=0–3) → None
                                                 // enters power-down; output stage disconnects with 100k to GND
dac.wake_up();                                 // Send General Call Wake-Up to clear power-down, () → None
                                                 // sends 0x00, 0x09 to address 0x00; clears PD bits in DAC register
dac.reset();                                   // Send General Call Reset and reload EEPROM, () → None
                                                 // sends 0x00, 0x06; triggers internal POR and reloads DAC from EEPROM
const ready = dac.is_eeprom_ready();          // Check if EEPROM write is complete, () → bool
                                                 // returns True when any pending EEPROM write has finished
console.log('eeprom_ready:', ready);
console.log('MCP4725 complete running');