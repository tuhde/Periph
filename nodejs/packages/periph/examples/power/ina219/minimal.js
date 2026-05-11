'use strict';

const { I2CTransport } = require('../../../src/transport/i2c');
const { INA219Minimal } = require('../../../src/chips/power/ina219');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const ina = new INA219Minimal(transport);

setInterval(() => {
    const v = ina.voltage();      // Read bus voltage, () → float V
    const i = ina.current();      // Read load current, () → float A
    const p = ina.power();        // Read power, () → float W
    console.log(`${v.toFixed(3)} V  ${i.toFixed(4)} A  ${p.toFixed(4)} W`);
}, 1000);