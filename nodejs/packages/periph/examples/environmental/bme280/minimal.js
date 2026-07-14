'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { BME280Minimal } = require('../../../src/chips/environmental/bme280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bme = new BME280Minimal(transport);              // Create BME280 driver, (transport, busType='i2c')

for (let i = 0; i < 5; i++) {
    const t = bme.temperature();                       // Read temperature, () → number °C
    const p = bme.pressure();                         // Read pressure, () → number hPa
    const h = bme.humidity();                         // Read humidity, () → number %RH
    console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa, ${h.toFixed(1)} %RH`);
}
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
