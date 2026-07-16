'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { PCF8591Minimal } = require('../../../src/chips/adc_dac/pcf8591');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x48', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const adc = new PCF8591Minimal(transport);

const ch0 = adc.read_channel(0);                            // Read single channel, (channel=0–3) → number
const allRaw = adc.read_all();                              // Read all four channels, () → number[]

console.log('ch0=' + ch0);
console.log('all=' + JSON.stringify(allRaw));
console.log('PCF8591 minimal running');
