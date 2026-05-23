'use strict';
const { I2CTransport } = require('../../../packages/periph/src/transport/i2c');
const { BMP280Minimal } = require('../../../packages/periph/src/chips/pressure/bmp280');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x76', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const bmp = new BMP280Minimal(transport);             // Create BMP280 driver, (transport, addr=0x76)

for (let i = 0; i < 5; i++) {
    const t = bmp.temperature();                      // Read temperature, () → float C
    const p = bmp.pressure();                         // Read pressure, () → float hPa
    console.log(`${t.toFixed(1)} C, ${p.toFixed(1)} hPa`);
}
transport.close();
console.log('===DONE: 0 passed, 0 failed===');