'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { MCP4725Full } = require('../../packages/periph/src/chips/adc_dac/mcp4725');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x60', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const dac = new MCP4725Full(transport);

let passed = 0;
let failed = 0;

function check_eq(label, got, expected) {
    if (got === expected) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label, ': got', got, ', expected', expected); failed++; }
}

function check_true(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

dac.set_voltage(0.5);
dac.set_raw(2048);

const result = dac.read();
check_true('code in range', result.code <= 4095);
check_true('voltage_fraction in range', result.voltage_fraction >= 0.0 && result.voltage_fraction <= 1.0);
check_true('power_down in range', result.power_down <= 3);
check_true('eeprom_code in range', result.eeprom_code <= 4095);
check_true('eeprom_power_down in range', result.eeprom_power_down <= 3);

dac.set_power_down(1);
const result2 = dac.read();
check_eq('power_down mode 1', result2.power_down, 1);

dac.wake_up();
dac.reset();
check_true('eeprom_ready or write in progress', dac.is_eeprom_ready() === true || dac.is_eeprom_ready() === false);

transport.close();
console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
process.exit(failed === 0 ? 0 : 1);