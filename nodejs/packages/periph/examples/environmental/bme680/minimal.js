'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BME680Minimal } = require('../../../packages/periph/src/chips/environmental/bme680');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bme = new BME680Minimal(transport);                // Create BME680 driver, (transport)

for (let i = 0; i < 5; i++) {
    const t = bme.temperature();                         // Read temperature, () → number °C
    const p = bme.pressure();                           // Read pressure, () → number hPa
    const h = bme.humidity();                           // Read humidity, () → number %RH
    const g = bme.gasResistance();                      // Read gas resistance, () → number Ω
    console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa, ${h.toFixed(1)} %RH, ${g.toFixed(0)} Ohm`);
}
transport.close();
console.log('===DONE: 0 passed, 0 failed===');
