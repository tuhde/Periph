'use strict';

const { I2CConnection } = require('../../../src/connection/i2c');
const { INA219Minimal } = require('../../../src/chips/power/ina219');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x40', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const ina = new INA219Minimal(connection);

setInterval(async () => {
    const v = await ina.voltage();      // Read bus voltage, () → float V
    const i = await ina.current();      // Read load current, () → float A
    const p = await ina.power();        // Read power, () → float W
    console.log(`${v.toFixed(3)} V  ${i.toFixed(4)} A  ${p.toFixed(4)} W`);
}, 1000);
